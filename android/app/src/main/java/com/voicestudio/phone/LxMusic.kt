package com.voicestudio.phone

import android.content.Context
import android.util.Log
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 网络音乐：搜索 → 取 URL（31 个脚本串行故障转移）→ 下载进缓存 → 交给 [AudioDeck]。
 *
 * 交给 AudioDeck 之后，返音、虚拟麦注入、输出音量、循环、自动接下一首**全部自动继承**，
 * 因为对 AudioDeck 来说它只是「一个已经在本地的音频文件」。
 */
object LxMusic {

    private const val TAG = "LxMusic"
    private const val CACHE_DIR = "audio_cache"
    private const val CACHE_LIMIT = 2L * 1024 * 1024 * 1024      // 2 GB
    private const val MAX_TRIES_PER_QUALITY = 5
    private const val RECENT_MAX = 30
    private const val LIKED_MAX = 200
    private const val PREFS = "lx_music"
    private const val RATE = 48000

    enum class QualityMode { HIGHEST, STABLE, AUTO }

    /** 一首歌的身份 + 直接喂给脚本的 musicInfo。 */
    data class Song(
        val key: String,
        val name: String,
        val singer: String,
        val platform: String,
        val infoJson: String,
    ) {
        val display: String get() = if (singer.isBlank()) name else "$name - $singer"
        fun toJson(): JSONObject = JSONObject().apply {
            put("key", key); put("name", name); put("singer", singer)
            put("platform", platform); put("info", infoJson)
        }

        companion object {
            fun fromJson(o: JSONObject): Song? {
                val k = o.optString("key")
                if (k.isEmpty()) return null
                return Song(
                    key = k,
                    name = o.optString("name"),
                    singer = o.optString("singer"),
                    platform = o.optString("platform"),
                    infoJson = o.optString("info", "{}"),
                )
            }
        }
    }

    data class Resolved(val url: String, val quality: String, val scriptName: String)

    private var sources: LxSources? = null
    private var appContext: Context? = null
    private val io = Executors.newFixedThreadPool(2) { r -> Thread(r, "lx-music").apply { isDaemon = true } }
    private val busy = AtomicBoolean(false)

    @Volatile var qualityMode: QualityMode = QualityMode.AUTO
        private set

    @Volatile private var bestQuality = HashMap<String, String>()   // platform -> 上次成功的品质

    val isReady: Boolean get() = sources?.isReady == true

    /* ------------------------------------------------------------------ 初始化 */

    fun init(context: Context, onReady: (Boolean) -> Unit) {
        val ctx = context.applicationContext
        appContext = ctx
        loadPrefs(ctx)
        loadRecent()
        loadLiked()
        val s = sources ?: LxSources(ctx).also { sources = it }
        s.start { ok -> onReady(ok) }
    }

    fun release() {
        stopStream()
        sources?.stop()
        sources = null
    }

    fun setQualityMode(mode: QualityMode) {
        qualityMode = mode
        appContext?.let { ctx ->
            ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("quality_mode", mode.name).apply()
        }
    }

    fun platformKeys(): List<String> = sources?.platformKeys() ?: emptyList()
    fun platformName(key: String): String = sources?.platformName(key) ?: key
    fun scriptCount(): Int = sources?.scriptCount ?: 0
    fun failedCount(): Int = sources?.failed ?: 0
    fun lastError(): String? = sources?.lastError

    /* ------------------------------------------------------------------ 音质 */

    private fun loadPrefs(ctx: Context) {
        val p = ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        qualityMode = runCatching { QualityMode.valueOf(p.getString("quality_mode", "AUTO")!!) }
            .getOrDefault(QualityMode.AUTO)
        bestQuality = HashMap()
        runCatching {
            val arr = JSONArray(p.getString("best_quality", "[]") ?: "[]")
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                bestQuality[o.optString("p")] = o.optString("q")
            }
        }
    }

    private fun rememberQuality(platform: String, quality: String) {
        if (bestQuality[platform] == quality) return
        bestQuality[platform] = quality
        val ctx = appContext ?: return
        val arr = JSONArray()
        for ((k, v) in bestQuality) arr.put(JSONObject().put("p", k).put("q", v))
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("best_quality", arr.toString()).apply()
    }

    /** 按档位给出「先试哪些品质」。 */
    private fun qualityOrder(platform: String): List<String> = when (qualityMode) {
        QualityMode.HIGHEST -> listOf("flac24bit", "flac", "hires", "320k", "128k")
        QualityMode.STABLE -> listOf("320k", "128k")
        QualityMode.AUTO -> {
            val best = bestQuality[platform]
            when (best) {
                null -> listOf("320k", "flac", "128k")
                "flac24bit", "flac", "hires" -> listOf(best, "320k", "128k")
                else -> listOf("320k", "128k")
            }
        }
    }

    /* ------------------------------------------------------------------ 搜索 */

    /**
     * 搜索。目前只有少数脚本实现了 search（非常刀 / 星海 / 裤佬），
     * 按平台逐个试，合并去重。搜到之后播放仍然由 31 个脚本共同负责。
     */
    fun search(keyword: String, onResult: (List<Song>) -> Unit) {
        val s = sources
        if (s == null || !s.isReady || keyword.isBlank()) {
            Log.i(TAG, "search skipped ready=${s?.isReady} kw='$keyword'")
            onResult(emptyList())
            return
        }
        val platforms = s.searchPlatforms()
        Log.i(TAG, "search '$keyword' platforms=$platforms")
        if (platforms.isEmpty()) { onResult(emptyList()); return }

        val out = LinkedHashMap<String, Song>()
        var pending = 0

        for (platform in platforms) {
            // 声明里写了 search 的优先，其次才是「源码里有搜索分支但没声明」的
            val variants = s.variants(platform)
                .filter { it.canSearch }
                .sortedByDescending { it.actions.contains("search") || it.actions.contains("musicSearch") }
                .take(2)
            for (v in variants) {
                pending++
                val payload = JSONObject().apply {
                    put("action", "search")
                    put("source", platform)
                    put("info", JSONObject().put("keyword", keyword).put("page", 1).put("limit", 20))
                }.toString()
                s.call(v.scriptId, payload) { ok, json ->
                    Log.i(TAG, "search ${v.scriptName}/$platform ok=$ok len=${json.length} head=${json.take(160)}")
                    if (ok) absorbSearch(json, platform, out)
                    if (--pending == 0) onResult(out.values.toList())
                }
            }
        }
        if (pending == 0) onResult(emptyList())
    }

    /**
     * 各家脚本的搜索返回形状不统一：有的直接给数组，有的裹在
     * {"list":[…]}/{"data":{"list":[…]}}/{"songs":[…]} 里，这里都认。
     */
    private fun absorbSearch(json: String, platform: String, out: MutableMap<String, Song>) {
        val t = json.trim()
        val arr = if (t.startsWith("[")) {
            runCatching { JSONArray(t) }.getOrNull()
        } else {
            val o = runCatching { JSONObject(t) }.getOrNull()
            o?.optJSONArray("list")
                ?: o?.optJSONObject("data")?.optJSONArray("list")
                ?: o?.optJSONArray("songs")
                ?: o?.optJSONObject("data")?.optJSONArray("songs")
        }
        if (arr == null) {
            Log.w(TAG, "search 返回形状不认识: ${t.take(120)}")
            return
        }
        for (i in 0 until arr.length()) {
            val item = arr.optJSONObject(i) ?: continue
            val song = songOf(item, platform) ?: continue
            out.putIfAbsent(song.key, song)
        }
    }

    private fun songOf(item: JSONObject, platform: String): Song? {
        val src = item.optString("source", platform).ifBlank { platform }
        val mid = when {
            item.optString("songmid").isNotBlank() -> item.optString("songmid")
            item.optString("hash").isNotBlank() -> item.optString("hash")
            item.optString("songId").isNotBlank() -> item.optString("songId")
            item.optString("id").isNotBlank() -> item.optString("id")
            else -> ""
        }
        val name = item.optString("name", item.optString("title", ""))
        val singer = item.optString("singer", item.optString("artist", ""))
        if (name.isBlank()) return null
        val key = src + "|" + (mid.ifBlank { name + "/" + singer })
        val info = JSONObject(item.toString()).apply {
            put("source", src)
            if (!has("songmid") && mid.isNotBlank()) put("songmid", mid)
        }
        return Song(key = key, name = name, singer = singer, platform = src, infoJson = info.toString())
    }

    /* ------------------------------------------------------------------ 首页推荐 */

    private val hotKeywords = listOf("热歌", "流行", "新歌", "经典", "粤语", "伤感")
    private var hotCache: List<Song> = emptyList()

    /**
     * 首页「热门推荐」。
     *
     * 实测（PC 侧 probe-search.js）：31 个脚本里只有「非常刀」的**酷我**搜索是活的——
     * 网易云接口已死、咪咕查不到、星海全线不可用、裤佬不认 search 这个 action。
     * 所以这里定向用声明了 search 的脚本 + kw 平台，拿热门词去搜，合并去重。
     */
    fun hot(force: Boolean = false, onResult: (List<Song>) -> Unit) {
        if (!force && hotCache.isNotEmpty()) { onResult(hotCache); return }
        val s = sources
        if (s == null || !s.isReady) {
            Log.i(TAG, "hot skipped ready=${s?.isReady}")
            onResult(emptyList())
            return
        }
        val v = s.variants("kw").firstOrNull { it.actions.contains("search") }
            ?: s.variants("kw").firstOrNull { it.canSearch }
        if (v == null) {
            Log.i(TAG, "hot no searchable variant on kw")
            onResult(emptyList())
            return
        }
        Log.i(TAG, "hot using ${v.scriptName} on kw, keywords=$hotKeywords")

        val merged = LinkedHashMap<String, Song>()
        var pending = hotKeywords.size
        for (kw in hotKeywords) {
            val payload = JSONObject().apply {
                put("action", "search")
                put("source", "kw")
                put("info", JSONObject().put("keyword", kw).put("page", 1).put("limit", 20))
            }.toString()
            s.call(v.scriptId, payload) { ok, json ->
                Log.i(TAG, "hot '$kw' ok=$ok len=${json.length} head=${json.take(140)}")
                if (ok) absorbSearch(json, "kw", merged)
                if (--pending == 0) finishHot(merged, onResult)
            }
        }
    }

    private fun finishHot(merged: Map<String, Song>, onResult: (List<Song>) -> Unit) {
        hotCache = merged.values.take(120)
        onResult(hotCache)
    }

    /* ------------------------------------------------------------------ 播放 */

    /**
     * 播一首网络歌曲。整个过程：解析 URL → 命中缓存直接播 / 未命中先下载 → AudioDeck.playRemote。
     * 播完后由 [AudioDeck.onEnded] 回调接列表里的下一首。
     */
    fun play(song: Song, queue: List<Song>, index: Int, oneShot: Boolean = false, onState: (String) -> Unit) {
        val s = sources
        if (s == null || !s.isReady) { onState("音源未就绪"); return }
        if (!VirtualMicSession.isActive) { onState("先启动虚拟麦克风"); return }
        stopStream()   // 换歌先掐掉上一条流
        if (!busy.compareAndSet(false, true)) { onState("正在换歌，稍等"); return }
        Log.i(TAG, "play '${song.name}' platform=${song.platform} mode=$qualityMode")

        onState("解析中…")
        resolve(s, song) { resolved ->
            if (resolved == null) {
                busy.set(false)
                onState("所有音源都没取到地址")
                return@resolve
            }
            val file = cacheFile(song, resolved.quality, resolved.url)
            if (file.isFile && file.length() > 0) {
                startPlay(song, file, queue, index, oneShot)
                busy.set(false)
                onState("播放")
                return@resolve
            }
            // 边下边播：立刻开始解码出声，同时把字节写进缓存，下次直接命中
            onState("缓冲中…")
            startStream(song, resolved.url, file, queue, index, oneShot, onState)
        }
    }

    /**
     * 流式播放。解码线程一边下一边解，声音从第一个 20 ms 就开始出。
     * 6 秒内一点数据都没解出来，或脚本给的地址压根不能流式解，就回退到整首下载。
     */
    private fun startStream(
        song: Song,
        url: String,
        file: File,
        queue: List<Song>,
        index: Int,
        oneShot: Boolean,
        onState: (String) -> Unit,
    ) {
        val sink = Pcm(ShortArray(RATE * 8), 0).apply { ended = false }
        var settled = false
        var stop: (() -> Unit)? = null
        val main = android.os.Handler(android.os.Looper.getMainLooper())

        fun fail(reason: String) {
            if (settled) return
            settled = true
            runCatching { stop?.invoke() }
            streamStop = null
            synchronized(sink) { sink.ended = true }
            onState("整首下载中…")
            io.execute {
                val ok = download(url, file)
                main.post {
                    busy.set(false)
                    if (ok && file.isFile && file.length() > 0) {
                        startPlay(song, file, queue, index, oneShot)
                        onState("播放")
                    } else {
                        onState("下载失败: $reason")
                    }
                }
            }
        }

        stop = LxStreamer.start(url, sink, LxStreamer.CacheTee(file)) { err ->
            main.post { fail(err) }
        }
        streamStop = stop

        // 6 秒还没解出任何采样，说明这条路走不通，回退
        main.postDelayed({
            if (!settled && sink.size <= 0) fail("stream-timeout")
        }, 6000)

        main.post {
            val next: (() -> Unit)? = if (oneShot) null else ({
                val ni = nextIndex(queue, index)
                if (ni != null) play(queue[ni], queue, ni) { /* 状态由界面轮询 */ }
            })
            AudioDeck.playStream(sink, song.display, next, oneShot)
            busy.set(false)
            onState("播放")
        }
    }

    /** 正在进行的那条流（换歌/停服务时要掐掉）。 */
    @Volatile private var streamStop: (() -> Unit)? = null

    fun stopStream() {
        runCatching { streamStop?.invoke() }
        streamStop = null
    }

    /** 网络曲目的「下一首」由这里决定——流式路径下 AudioDeck 的队列是空的。 */
    private fun nextIndex(queue: List<Song>, index: Int): Int? = when (AudioDeck.mode) {
        AudioDeck.Mode.SHUFFLE -> {
            if (queue.size <= 1) {
                null
            } else {
                var n = queue.indices.random()
                if (n == index) n = (n + 1) % queue.size
                n
            }
        }
        else -> if (index + 1 < queue.size) index + 1 else null
    }

    private fun startPlay(song: Song, file: File, queue: List<Song>, index: Int, oneShot: Boolean = false) {
        remember(song)
        val track = AudioLibrary.Track(song.display, file)
        val next: (() -> Unit)? = if (oneShot) null else ({
            val ni = nextIndex(queue, index)
            if (ni != null) play(queue[ni], queue, ni) { }
        })
        AudioDeck.playRemote(track, next, oneShot)
    }

    private fun resolve(s: LxSources, song: Song, cb: (Resolved?) -> Unit) {
        val variants = s.variants(song.platform)
        val qualities = qualityOrder(song.platform)
        Log.i(TAG, "resolve '${song.name}' platform=${song.platform} variants=${variants.size} qualities=$qualities mode=$qualityMode")
        if (variants.isEmpty()) { cb(null); return }
        val attempt = HashMap<String, Int>()      // scriptId -> tries
        var qi = 0
        var vi = 0
        val tried = HashSet<String>()

        fun step() {
            if (qi >= qualities.size) { cb(null); return }
            val q = qualities[qi]
            // 找下一个支持该品质、且没试过的脚本
            while (vi < variants.size) {
                val v = variants[vi]
                val supports = v.qualitys.isEmpty() || v.qualitys.contains(q)
                val key = v.scriptId + "|" + q
                val underLimit = (attempt[v.scriptId] ?: 0) < MAX_TRIES_PER_QUALITY
                if (!supports || !underLimit || tried.contains(key)) { vi++; continue }
                tried.add(key)
                attempt[v.scriptId] = (attempt[v.scriptId] ?: 0) + 1
                vi++
                val payload = JSONObject().apply {
                    put("action", "musicUrl")
                    put("source", song.platform)
                    put("info", JSONObject().put("type", q).put("musicInfo", JSONObject(song.infoJson)))
                }.toString()
                s.call(v.scriptId, payload) { ok, json ->
                    val url = if (ok) extractUrl(json) else null
                    Log.i(TAG, "resolve ${v.scriptName} q=$q ok=$ok url=${url?.take(70)} raw=${if (url == null) json.take(140) else ""}")
                    if (url != null) {
                        rememberQuality(song.platform, q)
                        cb(Resolved(url, q, v.scriptName))
                    } else {
                        step()
                    }
                }
                return
            }
            // 这一档没人可试，换下一档
            qi++
            vi = 0
            step()
        }
        step()
    }

    /** 脚本返回的可能是纯字符串（URL）或 JSON 裹着的 URL。 */
    private fun extractUrl(json: String): String? {
        val t = json.trim()
        if (t.startsWith("http", true)) return t.trim('"', ' ')
        val o = runCatching { JSONObject(t) }.getOrNull()
        if (o != null) {
            for (k in listOf("url", "data", "src", "playUrl")) {
                val v = o.optString(k)
                if (v.startsWith("http", true)) return v
            }
        }
        val m = Regex("https?://[^\"'\\s]+").find(t)
        return m?.value
    }

    /* ------------------------------------------------------------------ 缓存 */

    fun cacheDir(): File = File(appContext?.filesDir ?: File("."), CACHE_DIR).apply { mkdirs() }

    private fun cacheFile(song: Song, quality: String, url: String): File {
        val ext = extOf(url)
        return File(cacheDir(), sha1("${song.platform}|${song.key}|$quality") + ext)
    }

    /** 缓存文件后缀跟随真实 URL，MediaExtractor 主要靠嗅探，但保留原后缀更保险。 */
    private fun extOf(url: String): String {
        val path = url.substringBefore('?').substringBefore('#').substringAfterLast('/')
        val ext = path.substringAfterLast('.', "").lowercase()
        val ok = setOf("mp3", "flac", "m4a", "aac", "ogg", "wav", "ape")
        return if (ext in ok) ".$ext" else ".mp3"
    }

    private fun download(url: String, file: File?): Boolean {
        if (file == null) return false
        val tmp = File(file.absolutePath + ".part")
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(url).openConnection() as HttpURLConnection).apply {
                connectTimeout = 15000
                readTimeout = 20000
                instanceFollowRedirects = true
                setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                runCatching {
                    val u = URL(url)
                    setRequestProperty("Referer", "${u.protocol}://${u.host}/")
                }
            }
            val code = conn.responseCode
            if (code !in 200..299) {
                Log.w(TAG, "下载失败 HTTP $code: $url")
                return false
            }
            tmp.delete()
            conn.inputStream.use { input -> tmp.outputStream().use { out -> input.copyTo(out, 64 * 1024) } }
            if (tmp.length() < 4096) { tmp.delete(); return false }
            if (file.exists()) file.delete()
            if (!tmp.renameTo(file)) {
                tmp.copyTo(file, overwrite = true)
                tmp.delete()
            }
            trimCache()
            true
        } catch (e: Throwable) {
            Log.w(TAG, "下载异常: $url", e)
            tmp.delete()
            false
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** 2 GB 上限，按最后访问时间淘汰最旧的；最近 10 分钟动过的跳过（可能正在解码/播放）。 */
    fun trimCache() {
        val dir = cacheDir()
        val files = dir.listFiles { f -> f.isFile && !f.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= CACHE_LIMIT) return
        val guard = System.currentTimeMillis() - 10 * 60 * 1000
        files.filter { it.lastModified() < guard }
            .sortedBy { it.lastModified() }
            .forEach { f ->
                if (total <= CACHE_LIMIT) return@forEach
                total -= f.length()
                f.delete()
            }
    }

    fun cacheSize(): Long =
        cacheDir().listFiles { f -> f.isFile && !f.name.endsWith(".part") }?.sumOf { it.length() } ?: 0L

    fun clearCache() {
        cacheDir().listFiles()?.forEach { it.delete() }
    }

    /* ------------------------------------------------------------------ 最近播放 */

    @Volatile private var recent: List<Song> = emptyList()

    fun recent(): List<Song> = recent

    private fun remember(song: Song) {
        recent = (listOf(song) + recent.filterNot { it.key == song.key }).take(RECENT_MAX)
        val ctx = appContext ?: return
        val arr = JSONArray()
        recent.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("recent", arr.toString()).apply()
    }

    fun loadRecent() {
        val ctx = appContext ?: return
        runCatching {
            val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("recent", "[]") ?: "[]")
            val list = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { Song.fromJson(it)?.let(list::add) }
            }
            recent = list
        }
    }

    /* ------------------------------------------------------------------ 喜欢 */

    @Volatile private var liked: List<Song> = emptyList()

    fun liked(): List<Song> = liked

    fun isLiked(song: Song): Boolean = liked.any { it.key == song.key }

    /** 点一下点亮、再点取消。返回切换之后是不是点亮状态。 */
    @Synchronized
    fun toggleLike(song: Song): Boolean {
        val wasOn = liked.any { it.key == song.key }
        liked = if (wasOn) liked.filterNot { it.key == song.key }
                else (listOf(song) + liked).take(LIKED_MAX)
        val ctx = appContext ?: return !wasOn
        val arr = JSONArray()
        liked.forEach { arr.put(it.toJson()) }
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putString("liked", arr.toString()).apply()
        return !wasOn
    }

    fun loadLiked() {
        val ctx = appContext ?: return
        runCatching {
            val arr = JSONArray(ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString("liked", "[]") ?: "[]")
            val list = ArrayList<Song>()
            for (i in 0 until arr.length()) {
                arr.optJSONObject(i)?.let { Song.fromJson(it)?.let(list::add) }
            }
            liked = list
        }
    }

    private fun sha1(s: String): String {
        val d = MessageDigest.getInstance("SHA-1").digest(s.toByteArray())
        val sb = StringBuilder()
        for (b in d) sb.append("%02x".format(b))
        return sb.toString()
    }
}
