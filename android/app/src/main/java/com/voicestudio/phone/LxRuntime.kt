package com.voicestudio.phone

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.util.Base64
import android.util.Log
import android.view.Gravity
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.spec.X509EncodedKeySpec
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import javax.crypto.Cipher
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * LX Music 音源宿主（Kotlin 侧）。
 *
 * 一个隐藏 WebView 承载全部脚本；隔离由 assets/lx/bootstrap.js 里的
 * `new Function('globalThis','window','self', 脚本原文)` + `Object.create(window)` 完成，
 * 所以不需要每个脚本一个 WebView / iframe。
 *
 * 分工：
 *  - 网络：JS 的 `lx.request` → [AndroidBridge.http]（异步入线程池）→ 完成后 `__lx.deliver`
 *  - 回调：脚本 handler 的结果 → [AndroidBridge.reply] → [call] 的 onResult
 *  - 加解密：`utils.crypto` → 同步桥到 javax.crypto（避免在 JS 里手写大数运算）
 *
 * 所有对外回调都在主线程执行。
 */
class LxRuntime(private val context: Context) {

    data class Meta(
        val name: String = "",
        val description: String = "",
        val version: String = "",
        val file: String = "",
    )

    companion object {
        private const val TAG = "LxRuntime"
        private const val BASE_URL = "https://lx.local/"
        private const val HOST_PAGE = "lx/host.html"
        private const val BOOTSTRAP = "/lx/bootstrap.js"
        private const val SRC_PREFIX = "/src/"
        private const val DEFAULT_TIMEOUT_MS = 8000
        private const val CALL_TIMEOUT_MS = 15000L
    }

    private val main = Handler(Looper.getMainLooper())
    private val io = Executors.newFixedThreadPool(4) { r ->
        Thread(r, "lx-http").apply { isDaemon = true }
    }
    private val seq = AtomicLong(0)

    /** scriptId -> 源码；由 shouldInterceptRequest 按需喂给 JS。 */
    private val sources = ConcurrentHashMap<String, String>()

    /** callId -> 结果回调。 */
    private val pendingCalls = ConcurrentHashMap<Long, (Boolean, String) -> Unit>()

    /** scriptId -> 加载结果回调。 */
    private val pendingLoads = ConcurrentHashMap<String, (Boolean) -> Unit>()

    private var webView: WebView? = null
    private var attached = false
    private var readyCallback: ((Boolean) -> Unit)? = null
    private var ready = false

    val isReady: Boolean get() = ready

    /* ------------------------------------------------------------------ 生命周期 */

    fun start(onReady: (Boolean) -> Unit) {
        main.post {
            if (ready) { onReady(true); return@post }
            readyCallback = onReady
            try {
                createWebView()
            } catch (e: Throwable) {
                Log.e(TAG, "WebView 创建失败", e)
                readyCallback = null
                onReady(false)
            }
        }
    }

    fun stop() {
        main.post {
            ready = false
            pendingCalls.clear()
            pendingLoads.clear()
            sources.clear()
            val wv = webView ?: return@post
            try { wv.stopLoading() } catch (_: Throwable) {}
            try { wv.loadUrl("about:blank") } catch (_: Throwable) {}
            try { wv.destroy() } catch (_: Throwable) {}
            if (attached) {
                try { (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).removeView(wv) } catch (_: Throwable) {}
                attached = false
            }
            webView = null
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun createWebView() {
        // Service 的 context 没有主题，部分 ROM 上 WebView 初始化会因此报错，套一层主题
        val themed = android.view.ContextThemeWrapper(context, android.R.style.Theme_DeviceDefault)
        val wv = WebView(themed)
        wv.setBackgroundColor(Color.TRANSPARENT)
        wv.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true          // "云端音源" 用 localStorage 存 token
            blockNetworkImage = true           // 只需要 JSON/文本
            javaScriptCanOpenWindowsAutomatically = false
            mediaPlaybackRequiresUserGesture = false
            cacheMode = android.webkit.WebSettings.LOAD_NO_CACHE
        }
        wv.addJavascriptInterface(AndroidBridge(), "AndroidBridge")
        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView?,
                request: WebResourceRequest?,
            ): WebResourceResponse? = intercept(request?.url?.toString())

            override fun onPageFinished(view: WebView?, url: String?) {
                // __lxReady 由 bootstrap.js 在末尾置位
                view?.evaluateJavascript("String(!!window.__lxReady)") { value ->
                    val ok = value != null && value.contains("true")
                    if (!ok) Log.e(TAG, "bootstrap 未就绪: $value")
                    Log.i(TAG, "page finished ready=$ok")
                    ready = ok
                    val cb = readyCallback
                    readyCallback = null
                    cb?.invoke(ok)
                }
            }
        }
        webView = wv
        attach(wv)
        val html = readAsset(HOST_PAGE) ?: "<html><body>missing host page</body></html>"
        wv.loadDataWithBaseURL(BASE_URL, html, "text/html", "utf-8", null)
    }

    /**
     * 把 WebView 挂到 1×1 的屏幕外悬浮窗上。
     *
     * 不挂也能跑 JS，但未 attach 的 WebView 在部分机型上会被当成不可见页面节流
     * （setTimeout 被压到很慢），而脚本里到处是 setTimeout 重试。挂上最省事。
     * 没有悬浮窗权限就退化为不 attach。
     */
    private fun attach(wv: WebView) {
        if (!Settings.canDrawOverlays(context)) return
        try {
            val lp = WindowManager.LayoutParams(
                1, 1,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
                PixelFormat.TRANSLUCENT,
            ).apply {
                gravity = Gravity.TOP or Gravity.START
                x = -8
                y = -8
            }
            (context.getSystemService(Context.WINDOW_SERVICE) as WindowManager).addView(wv, lp)
            attached = true
        } catch (e: Throwable) {
            Log.w(TAG, "挂载隐藏 WebView 失败，退化为不 attach", e)
        }
    }

    private fun intercept(url: String?): WebResourceResponse? {
        if (url == null) return null
        if (url.endsWith(BOOTSTRAP)) {
            val bytes = readAssetBytes("lx/bootstrap.js") ?: return null
            return WebResourceResponse("application/javascript", "utf-8", ByteArrayInputStream(bytes))
        }
        val i = url.indexOf(SRC_PREFIX)
        if (i >= 0) {
            // 注意去掉 .js：sources 是按脚本 id（s01）存的，JS 请求的是 /src/s01.js
            val id = url.substring(i + SRC_PREFIX.length)
                .substringBefore('?')
                .substringBefore('#')
                .removeSuffix(".js")
            val code = sources[id] ?: return WebResourceResponse(
                "text/plain", "utf-8", 404, "Not Found",
                mapOf("Cache-Control" to "no-store"), ByteArrayInputStream(ByteArray(0)),
            )
            return WebResourceResponse(
                "application/javascript", "utf-8", 200, "OK",
                mapOf("Cache-Control" to "no-store"),
                ByteArrayInputStream(code.toByteArray(Charsets.UTF_8)),
            )
        }
        return null
    }

    private fun readAssetBytes(path: String): ByteArray? = try {
        context.assets.open(path).use { it.readBytes() }
    } catch (e: Throwable) {
        Log.e(TAG, "读 asset 失败: $path", e)
        null
    }

    private fun readAsset(path: String): String? =
        readAssetBytes(path)?.toString(Charsets.UTF_8)

    /* ------------------------------------------------------------------ 加载脚本 */

    /**
     * 加载一个脚本。[code] 会挂在 /src/<id>.js 上由 JS fetch。
     * [onDone] 在主线程回调；true 表示脚本执行没抛（不代表声明了音源）。
     */
    fun load(id: String, meta: Meta, code: String, onDone: (Boolean) -> Unit) {
        main.post {
            val wv = webView
            if (wv == null || !ready) { onDone(false); return@post }
            sources[id] = code
            if (pendingLoads.containsKey(id)) {
                Log.w(TAG, "重复加载同一脚本，忽略: $id")
                onDone(false)
                return@post
            }
            pendingLoads[id] = onDone
            // 脚本要是卡住，别把整个加载流程拖死
            main.postDelayed({
                pendingLoads.remove(id)?.invoke(false)
            }, 15000L)
            val metaJson = JSONObject().apply {
                put("name", meta.name)
                put("description", meta.description)
                put("version", meta.version)
                put("file", meta.file)
            }.toString()
            val js = "__lx.load(${q(id)}, ${q(metaJson)}, ${q("/src/$id.js")})"
            wv.evaluateJavascript(js, null)
        }
    }

    /** 读取脚本 send(inited, …) 的原始 JSON；没有声明返回 null。 */
    fun declared(id: String, onResult: (String?) -> Unit) {
        main.post {
            val wv = webView
            if (wv == null || !ready) { onResult(null); return@post }
            wv.evaluateJavascript("__lx.declared(${q(id)})") { value ->
                onResult(unquoteJson(value))
            }
        }
    }

    /* ------------------------------------------------------------------ 调用脚本 */

    /**
     * 调脚本的 request 处理器。
     * [payload] 是 {"action":…,"source":…,"info":{…}} 的 JSON 字符串。
     * [onResult] 收到 (ok, 结果的 JSON 文本 / 错误消息)。
     */
    fun call(scriptId: String, payload: String, onResult: (Boolean, String) -> Unit) {
        main.post {
            val wv = webView
            if (wv == null || !ready) { onResult(false, "runtime-not-ready"); return@post }
            val callId = seq.incrementAndGet()
            pendingCalls[callId] = onResult
            Log.i(TAG, "call $scriptId id=$callId")
            // 脚本可能永远不回（自己挂了/没走 reply）；超时就当失败，
            // 否则上层「解析中」会一直卡住。
            main.postDelayed({
                pendingCalls.remove(callId)?.invoke(false, "timeout")
            }, CALL_TIMEOUT_MS)
            val js = "__lx.call(${q(scriptId)}, ${q(payload)}, '$callId')"
            wv.evaluateJavascript(js, null)
        }
    }

    /** 脚本执行失败时 JS 侧记下的原因。 */
    fun loadError(id: String, onResult: (String?) -> Unit) {
        main.post {
            val wv = webView
            if (wv == null || !ready) { onResult(null); return@post }
            wv.evaluateJavascript("__lx.loadError(${q(id)})") { v -> onResult(unquoteJson(v)) }
        }
    }

    fun stats(onResult: (String) -> Unit) {
        main.post {
            val wv = webView
            if (wv == null || !ready) { onResult("{}"); return@post }
            wv.evaluateJavascript("__lx.stats()") { v -> onResult(unquoteJson(v) ?: "{}") }
        }
    }

    /* ------------------------------------------------------------------ JS 桥 */

    inner class AndroidBridge {

        /* ---- 网络：异步，完成后 __lx.deliver ---- */

        @JavascriptInterface
        fun http(reqId: String, url: String, optionsJson: String) {
            Log.i(TAG, "http $reqId ${url.take(110)}")
            io.execute { doHttp(reqId, url, optionsJson) }
        }

        /* ---- 脚本调用结果 ---- */

        @JavascriptInterface
        fun reply(callId: String, ok: Boolean, json: String) {
            Log.i(TAG, "reply id=$callId ok=$ok len=${json.length}")
            val cb = pendingCalls.remove(callId.toLongOrNull() ?: -1L) ?: return
            main.post { cb(ok, json) }
        }

        @JavascriptInterface
        fun loaded(id: String, ok: Boolean) {
            Log.i(TAG, "loaded $id ok=$ok")
            val cb = pendingLoads.remove(id) ?: return
            main.post { cb(ok) }
        }

        /* ---- 加解密：同步（在 JS 线程上直接返回） ---- */

        @JavascriptInterface
        fun md5(text: String): String = md5Hex(text.toByteArray(Charsets.UTF_8))

        @JavascriptInterface
        fun md5Bytes(b64: String): String = md5Hex(decodeB64(b64))

        @JavascriptInterface
        fun randomBytes(n: Int): String {
            val size = if (n in 1..4096) n else 16
            val b = ByteArray(size)
            java.security.SecureRandom().nextBytes(b)
            return encodeB64(b)
        }

        @JavascriptInterface
        fun aesEncrypt(dataB64: String, mode: String, key: String, iv: String?): String {
            return try {
                val algo = mode.lowercase()
                val secret = key.toByteArray(Charsets.UTF_8)
                val cipher = Cipher.getInstance(
                    when {
                        algo.contains("ecb") -> "AES/ECB/PKCS5Padding"
                        else -> "AES/CBC/PKCS5Padding"
                    }
                )
                val keySpec = SecretKeySpec(secret, "AES")
                val ivText = if (iv.isNullOrBlank() || iv == "null" || iv == "undefined") null else iv
                if (ivText != null && !algo.contains("ecb")) {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec, IvParameterSpec(ivText.toByteArray(Charsets.UTF_8)))
                } else {
                    cipher.init(Cipher.ENCRYPT_MODE, keySpec)
                }
                encodeB64(cipher.doFinal(decodeB64(dataB64)))
            } catch (e: Throwable) {
                Log.w(TAG, "aesEncrypt 失败 mode=$mode", e)
                ""
            }
        }

        @JavascriptInterface
        fun rsaEncrypt(dataB64: String, keyPem: String): String {
            return try {
                val body = keyPem
                    .replace("-----BEGIN PUBLIC KEY-----", "")
                    .replace("-----END PUBLIC KEY-----", "")
                    .replace(Regex("\\s"), "")
                val spec = X509EncodedKeySpec(decodeB64(body))
                val pub = KeyFactory.getInstance("RSA").generatePublic(spec)
                val cipher = Cipher.getInstance("RSA/ECB/PKCS1Padding")
                cipher.init(Cipher.ENCRYPT_MODE, pub)
                encodeB64(cipher.doFinal(decodeB64(dataB64)))
            } catch (e: Throwable) {
                Log.w(TAG, "rsaEncrypt 失败", e)
                ""
            }
        }
    }

    /* ------------------------------------------------------------------ HTTP 实现 */

    private fun doHttp(reqId: String, url: String, optionsJson: String) {
        var conn: HttpURLConnection? = null
        try {
            val opt = runCatching { JSONObject(optionsJson) }.getOrNull() ?: JSONObject()
            val method = opt.optString("method", "GET").uppercase().ifBlank { "GET" }
            val timeout = opt.optInt("timeout", DEFAULT_TIMEOUT_MS).coerceIn(1000, 20000)
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                requestMethod = method
                connectTimeout = timeout
                readTimeout = timeout
                instanceFollowRedirects = true
                useCaches = false
            }
            opt.optJSONObject("headers")?.let { hs ->
                for (k in hs.keys()) conn.setRequestProperty(k, hs.optString(k, ""))
            }
            val bodyText = opt.optString("body", "")
            if (bodyText.isNotEmpty() && method != "GET" && method != "HEAD") {
                conn.doOutput = true
                if (conn.getRequestProperty("Content-Type") == null) {
                    conn.setRequestProperty("Content-Type", "application/json; charset=utf-8")
                }
                conn.outputStream.use { it.write(bodyText.toByteArray(Charsets.UTF_8)) }
            }

            val status = conn.responseCode
            val headers = JSONObject()
            conn.headerFields?.forEach { (k, v) ->
                if (!k.isNullOrEmpty() && v.isNotEmpty()) headers.put(k, v.first())
            }
            val stream: InputStream? = if (status in 200..299) conn.inputStream else conn.errorStream
            val bytes = stream?.use { it.readBytes() } ?: ByteArray(0)

            val ct = conn.contentType ?: ""
            val isJson = ct.contains("json", true)
            val isText = isJson || ct.startsWith("text/", true) ||
                ct.contains("javascript", true) || ct.contains("xml", true) || ct.isEmpty()

            val out = JSONObject().apply {
                put("status", status)
                put("statusMessage", conn.responseMessage ?: "")
                put("headers", headers)
                if (isText) {
                    val text = bytes.toString(Charsets.UTF_8)
                    if (isJson) {
                        val parsed = runCatching { JSONObject(text) }.getOrNull()
                            ?: runCatching { org.json.JSONArray(text) }.getOrNull()
                        if (parsed != null) put("body", parsed) else put("body", text)
                    } else {
                        put("body", text)
                    }
                } else {
                    put("b64", true)
                    put("body", encodeB64(bytes))
                }
            }
            deliver(reqId, out.toString())
        } catch (e: Throwable) {
            Log.w(TAG, "http 失败: $url", e)
            val err = JSONObject().apply {
                put("err", e.javaClass.simpleName + ": " + (e.message ?: ""))
            }
            deliver(reqId, err.toString())
        } finally {
            try { conn?.disconnect() } catch (_: Throwable) {}
        }
    }

    private fun deliver(reqId: String, json: String) {
        main.post {
            val wv = webView ?: return@post
            if (!ready) return@post
            Log.i(TAG, "deliver $reqId len=${json.length}")
            wv.evaluateJavascript("__lx.deliver(${q(reqId)}, ${q(json)})", null)
        }
    }

    /* ------------------------------------------------------------------ 小工具 */

    private fun md5Hex(data: ByteArray): String {
        val d = MessageDigest.getInstance("MD5").digest(data)
        val sb = StringBuilder(d.size * 2)
        for (b in d) sb.append("0123456789abcdef"[(b.toInt() shr 4) and 0xF])
            .append("0123456789abcdef"[b.toInt() and 0xF])
        return sb.toString()
    }

    private fun encodeB64(b: ByteArray): String = Base64.encodeToString(b, Base64.NO_WRAP)

    private fun decodeB64(s: String): ByteArray = Base64.decode(s, Base64.DEFAULT)

    /** 把 Kotlin 字符串变成 JS 字符串字面量（含外层引号）。 */
    private fun q(s: String): String = JSONObject.quote(s)

    /** evaluateJavascript 的回调给的是 JSON 文本；字符串值是带引号的 JSON。 */
    private fun unquoteJson(v: String?): String? {
        if (v == null) return null
        val t = v.trim()
        if (t.isEmpty() || t == "null" || t == "undefined") return null
        if (t.startsWith("\"") && t.endsWith("\"")) {
            return runCatching { JSONObject("{\"v\":$t}").getString("v") }.getOrNull()
        }
        return t
    }
}
