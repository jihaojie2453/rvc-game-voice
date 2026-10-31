package com.voicestudio.phone

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File

/**
 * 音源管理：把内置的 31 个 LX 音源脚本喂给 [LxRuntime]，收集它们
 * `send(EVENT_NAMES.inited, …)` 声明的平台/品质，建「平台 → 可用脚本」索引。
 *
 * 另外支持从文件导入新脚本（音源会死，必须能换）：把 .js 放进 files/lx_sources 目录即可。
 *
 * 注意：本注释里不要出现斜杠紧挨星号的写法，Kotlin 块注释可嵌套，会把整个文件吞掉。
 */
class LxSources(private val context: Context) {

    private companion object {
        const val TAG = "LxSources"
        const val INDEX = "lx/sources/index.json"
        const val DIR = "lx/sources/"
        const val USER_DIR = "lx_sources"
        const val DECLARE_RETRY = 4
        const val DECLARE_RETRY_MS = 400L
    }

    /** 一个脚本能为某个平台做什么。 */
    data class Variant(
        val scriptId: String,
        val scriptName: String,
        val origin: String,
        val qualitys: List<String>,
        val actions: List<String>,
        val searchHint: Boolean,
    ) {
        /**
         * 能否搜。声明里有 search/musicSearch，或源码里有搜索分支（见 assets 里 index.json 的 searchHint）。
         * 有些脚本（星海、裤佬）实现了却没声明，光看声明会漏。
         */
        val canSearch: Boolean
            get() = searchHint || actions.contains("search") || actions.contains("musicSearch")
    }

    data class Platform(val key: String, val name: String, val variants: List<Variant>)

    private class Item(val id: String, val meta: LxRuntime.Meta, val code: String)

    private val runtime = LxRuntime(context)
    private val byPlatform = LinkedHashMap<String, MutableList<Variant>>()
    private val platformNames = HashMap<String, String>()
    private val declaredOk = ArrayList<String>()

    @Volatile var ready = false
        private set
    @Volatile var scriptCount = 0
        private set
    @Volatile var failed = 0
        private set
    @Volatile var lastError: String? = null
        private set

    val isReady: Boolean get() = ready

    /* ------------------------------------------------------------------ 启动 */

    fun start(onReady: (Boolean) -> Unit) {
        if (ready) { onReady(true); return }
        runtime.start { webOk ->
            if (!webOk) {
                lastError = "webview-not-ready"
                Log.e(TAG, "WebView 宿主没就绪")
                onReady(false)
                return@start
            }
            Log.i(TAG, "WebView 宿主就绪，开始加载脚本")
            val items = collectScripts()
            if (items.isEmpty()) {
                lastError = "no-scripts"
                onReady(false)
                return@start
            }
            loadSequential(items, 0, onReady)
        }
    }

    fun stop() {
        runtime.stop()
        ready = false
    }

    private fun collectScripts(): List<Item> {
        val out = ArrayList<Item>()

        // 内置：assets/lx/sources/index.json + sNN.js
        runCatching {
            val indexText = context.assets.open(INDEX).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONObject(indexText).optJSONArray("sources") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id")
                val file = o.optString("file")
                if (id.isEmpty() || file.isEmpty()) continue
                val code = context.assets.open(DIR + file).use { it.readBytes().toString(Charsets.UTF_8) }
                out.add(
                    Item(
                        id = id,
                        meta = LxRuntime.Meta(
                            name = o.optString("name"),
                            description = o.optString("origin"),
                            version = o.optString("version"),
                            file = o.optString("origin"),
                        ),
                        code = code,
                    ),
                )
            }
        }.onFailure { Log.e(TAG, "读内置脚本失败", it); lastError = "assets:${it.message}" }

        // 用户导入：files/lx_sources/*.js
        runCatching {
            val dir = File(context.filesDir, USER_DIR)
            if (dir.isDirectory) {
                dir.listFiles { f -> f.isFile && f.name.endsWith(".js") }
                    ?.sortedBy { it.name }
                    ?.forEach { f ->
                        val code = f.readText()
                        val name = Regex("@name\\s+([^\\r\\n*]+)").find(code)?.groupValues?.get(1)?.trim()
                        out.add(
                            Item(
                                id = "u_" + f.name.removeSuffix(".js").replace(Regex("[^A-Za-z0-9_]"), "_").take(40),
                                meta = LxRuntime.Meta(name ?: f.name, "user", "", f.name),
                                code = code,
                            ),
                        )
                    }
            }
        }.onFailure { Log.w(TAG, "读用户脚本失败", it) }

        return out
    }

    private fun loadSequential(items: List<Item>, i: Int, onReady: (Boolean) -> Unit) {
        if (i >= items.size) {
            scriptCount = declaredOk.size
            ready = declaredOk.isNotEmpty()
            Log.i(TAG, "音源就绪：$scriptCount 个脚本，${byPlatform.size} 个平台，失败 $failed")
            onReady(ready)
            return
        }
        val item = items[i]
        runtime.load(item.id, item.meta, item.code) { ok ->
            if (!ok) {
                // 失败一定要问出原因，否则只看到「失败 31」没法查
                runtime.loadError(item.id) { err ->
                    Log.w(TAG, "加载失败 ${item.id} ${item.meta.name}: $err")
                    failed++
                    loadSequential(items, i + 1, onReady)
                }
            } else {
                readDeclared(item, 0) { declared ->
                    if (declared != null) {
                        absorb(item, declared)
                    } else {
                        Log.w(TAG, "没声明音源 ${item.id} ${item.meta.name}")
                        failed++
                    }
                    loadSequential(items, i + 1, onReady)
                }
            }
        }
    }

    /** 有的脚本联网初始化成功后才 send(inited)，所以读不到要重试几次。 */
    private fun readDeclared(item: Item, attempt: Int, cb: (String?) -> Unit) {
        runtime.declared(item.id) { json ->
            if (!json.isNullOrBlank()) {
                cb(json)
            } else if (attempt + 1 < DECLARE_RETRY) {
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    readDeclared(item, attempt + 1, cb)
                }, DECLARE_RETRY_MS)
            } else {
                cb(null)
            }
        }
    }

    private fun absorb(item: Item, declaredJson: String) {
        val root = runCatching { JSONObject(declaredJson) }.getOrNull() ?: return
        val sources = root.optJSONObject("sources") ?: return
        var any = false
        for (key in sources.keys()) {
            val o = sources.optJSONObject(key) ?: continue
            if (o.optString("type", "music") != "music") continue
            val q = o.optJSONArray("qualitys")
            val a = o.optJSONArray("actions")
            val qualitys = ArrayList<String>()
            if (q != null) for (i in 0 until q.length()) qualitys.add(q.optString(i))
            val actions = ArrayList<String>()
            if (a != null) for (i in 0 until a.length()) actions.add(a.optString(i))
            val variant = Variant(
                scriptId = item.id,
                scriptName = item.meta.name,
                origin = item.meta.file,
                qualitys = qualitys,
                actions = actions,
                searchHint = hintOf(item.id),
            )
            byPlatform.getOrPut(key) { ArrayList() }.add(variant)
            platformNames[key] = o.optString("name", key)
            any = true
        }
        if (any) declaredOk.add(item.id)
    }

    /** index.json 里预先算好的「源码里有搜索分支」标记。 */
    private val hints = HashMap<String, Boolean>()

    private fun hintOf(id: String): Boolean = hints[id] ?: false

    private fun loadHints() {
        runCatching {
            val text = context.assets.open(INDEX).use { it.readBytes().toString(Charsets.UTF_8) }
            val arr = JSONObject(text).optJSONArray("sources") ?: return@runCatching
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                hints[o.optString("id")] = o.optBoolean("searchHint", false)
            }
        }
    }

    /* ------------------------------------------------------------------ 查询 */

    /** 平台键（kw/wy/tx/kg/mg…），按可用脚本数从多到少。 */
    fun platformKeys(): List<String> =
        byPlatform.entries.sortedByDescending { it.value.size }.map { it.key }

    fun platformName(key: String): String = platformNames[key] ?: key

    fun variants(key: String): List<Variant> = byPlatform[key] ?: emptyList()

    /** 所有能被搜索的脚本（同一脚本只留一份）。 */
    fun searchVariants(): List<Variant> {
        val uniq = LinkedHashMap<String, Variant>()
        for (list in byPlatform.values) {
            for (v in list) if (v.canSearch) uniq.putIfAbsent(v.scriptId, v)
        }
        return uniq.values.toList()
    }

    /** 声明里带 search 的平台键（供搜索时按平台调度）。 */
    fun searchPlatforms(): List<String> =
        platformKeys().filter { key -> variants(key).any { it.canSearch } }

    fun call(scriptId: String, payload: String, onResult: (Boolean, String) -> Unit) =
        runtime.call(scriptId, payload, onResult)

    /* ------------------------------------------------------------------ 导入 */

    /** 把一个 .js 复制进 files/lx_sources 并尝试加载；成功声明才保留。 */
    fun importScript(file: File, onDone: (Boolean, String) -> Unit) {
        val dir = File(context.filesDir, USER_DIR).apply { mkdirs() }
        val dst = File(dir, file.name)
        if (dst.exists() && dst.absolutePath != file.absolutePath) {
            onDone(false, "同名脚本已存在")
            return
        }
        try {
            if (!dst.exists()) file.inputStream().use { input -> dst.outputStream().use { input.copyTo(it) } }
        } catch (e: Throwable) {
            onDone(false, e.message ?: "复制失败")
            return
        }
        if (!ready) {
            onDone(false, "音源还没就绪")
            return
        }
        val code = try { dst.readText() } catch (e: Throwable) { onDone(false, "读文件失败"); return }
        val name = Regex("@name\\s+([^\\r\\n*]+)").find(code)?.groupValues?.get(1)?.trim() ?: dst.name
        val id = "u_" + dst.name.removeSuffix(".js").replace(Regex("[^A-Za-z0-9_]"), "_").take(40)
        val meta = LxRuntime.Meta(name, "user", "", dst.name)
        runtime.load(id, meta, code) { ok ->
            if (!ok) {
                onDone(false, "脚本执行出错")
            } else {
                readDeclared(Item(id, meta, code), 0) { declared ->
                    if (declared == null) {
                        onDone(false, "没有声明音源")
                    } else {
                        absorb(Item(id, meta, code), declared)
                        onDone(true, name)
                    }
                }
            }
        }
    }

    init {
        loadHints()
    }
}
