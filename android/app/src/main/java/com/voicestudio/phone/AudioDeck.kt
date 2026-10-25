package com.voicestudio.phone

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Environment
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.concurrent.thread
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 本地音频素材：/storage/emulated/0/好东西/音频/<分类>/<音频文件>。
 *
 * 分类就是一级子目录；只有分类目录里的文件才算音频，根目录下散放的文件忽略。
 * 分类按目录创建时间升序，分类里的音频按文件名自然排序（01_ 02_ 前缀就能定顺序）。
 * 按产品决定：每次打开 App 扫一次，使用过程中不刷新。
 */
object AudioLibrary {
    data class Category(val name: String, val dir: File, val created: Long)
    data class Track(val name: String, val file: File) {
        val id: String get() = file.absolutePath
    }

    enum class Problem { NONE, PERMISSION, MISSING, EMPTY }

    val root: File by lazy { File(Environment.getExternalStorageDirectory(), "好东西/音频") }

    @Volatile var categories: List<Category> = emptyList()
        private set
    @Volatile var problem = Problem.PERMISSION
        private set
    @Volatile var scanned = false
        private set

    private val AUDIO_EXT = setOf(
        "mp3", "wav", "m4a", "aac", "flac", "ogg", "opus", "wma", "amr", "mka", "mp2", "3gp",
    )

    /** 读 /storage/emulated/0 下任意目录需要「所有文件访问」；没有它连目录都列不出来。 */
    fun allowed(): Boolean = Environment.isExternalStorageManager()

    @Synchronized
    fun scan(): List<Category> {
        scanned = true
        if (!allowed()) {
            categories = emptyList()
            problem = Problem.PERMISSION
            return categories
        }
        val base = root
        if (!base.isDirectory) {
            categories = emptyList()
            problem = Problem.MISSING
            return categories
        }
        val dirs = runCatching { base.listFiles()?.filter { it.isDirectory } }.getOrNull() ?: emptyList()
        val list = dirs.map { Category(it.name, it, created(it)) }
            .sortedWith(compareBy({ it.created }, { it.name }))
        categories = list
        problem = if (list.isEmpty()) Problem.EMPTY else Problem.NONE
        return list
    }

    /** 分类里的音频文件；解码不了的文件在点开时才会报错，这里只按扩展名过滤。 */
    fun tracks(category: Category): List<Track> {
        val files = runCatching { category.dir.listFiles() }.getOrNull() ?: return emptyList()
        return files.filter { it.isFile && isAudio(it.name) && !it.name.startsWith(".") }
            .sortedWith { a, b -> natural(a.name, b.name) }
            .map { Track(it.name.substringBeforeLast('.'), it) }
    }

    fun category(name: String?): Category? = categories.firstOrNull { it.name == name }

    private fun isAudio(name: String): Boolean {
        val dot = name.lastIndexOf('.')
        if (dot <= 0) return false
        return name.substring(dot + 1).lowercase() in AUDIO_EXT
    }

    private fun created(file: File): Long = runCatching {
        java.nio.file.Files.readAttributes(
            file.toPath(), java.nio.file.attribute.BasicFileAttributes::class.java,
        ).creationTime().toMillis()
    }.getOrDefault(file.lastModified())

    /** "短语 2" 排在 "短语 10" 前面：数字段按数值比，其它按字符比。 */
    private fun natural(a: String, b: String): Int {
        var i = 0
        var j = 0
        while (i < a.length && j < b.length) {
            val ca = a[i]
            val cb = b[j]
            if (ca.isDigit() && cb.isDigit()) {
                var x = 0L
                var y = 0L
                while (i < a.length && a[i].isDigit()) { x = x * 10 + (a[i] - '0'); i++ }
                while (j < b.length && b[j].isDigit()) { y = y * 10 + (b[j] - '0'); j++ }
                if (x != y) return x.compareTo(y)
            } else {
                if (ca.lowercaseChar() != cb.lowercaseChar()) return ca.lowercaseChar().compareTo(cb.lowercaseChar())
                i++
                j++
            }
        }
        return (a.length - i).compareTo(b.length - j)
    }
}

/** 48 kHz 单声道 PCM，长度可能小于 [data] 的容量（省掉最后一次拷贝）。 */
class Pcm(val data: ShortArray, val size: Int)

internal class ShortSink {
    private var data = ShortArray(1 shl 16)
    private var size = 0

    fun add(x: FloatArray) {
        if (x.isEmpty()) return
        ensure(x.size)
        for (i in x.indices) {
            data[size + i] = (x[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
        }
        size += x.size
    }

    private fun ensure(n: Int) {
        if (size + n <= data.size) return
        data = data.copyOf(max(data.size * 2, size + n))
    }

    fun result(): Pcm = Pcm(data, size)
}

/**
 * 流式重采样（Kaiser 窗 sinc），内存只跟输出走，不跟整段源音频走。
 * 每个输出点都按实际用到的权重归一化，所以首尾（权重落在数组外）不会衰减。
 */
internal class SincResampler(private val src: Int, private val dst: Int, zeros: Int = 24) {
    private val step = src.toDouble() / dst
    private val cutoff = min(1.0, dst.toDouble() / src) * 0.95
    private val width = zeros / cutoff
    private val table = kernel(cutoff, width)
    private val tableSteps = (table.size - 1) / width

    private var buf = FloatArray(1 shl 15)
    private var head = 0
    private var size = 0
    private var start = 0           // buf[head] 对应的绝对输入下标
    private var next = 0.0          // 下一个输出点对应的绝对输入位置
    private var done = false

    fun feed(x: FloatArray): FloatArray {
        append(x)
        return produce(false)
    }

    fun finish(): FloatArray {
        if (done) return FloatArray(0)
        done = true
        return produce(true)
    }

    private fun append(x: FloatArray) {
        if (x.isEmpty()) return
        if (head + size + x.size > buf.size) compact(x.size)
        System.arraycopy(x, 0, buf, head + size, x.size)
        size += x.size
    }

    private fun compact(extra: Int) {
        if (head > 0) {
            System.arraycopy(buf, head, buf, 0, size)
            head = 0
        }
        if (size + extra > buf.size) buf = buf.copyOf(max(buf.size * 2, size + extra))
    }

    private fun produce(flush: Boolean): FloatArray {
        val end = start + size
        val capacity = (((end - 1 - next) / step).toInt() + 4).coerceIn(4, 1 shl 20)
        val out = FloatArray(capacity)
        var n = 0
        while (n < out.size) {
            if (flush) {
                if (next > end - 1) break
            } else {
                if (next + width > end - 1) break
            }
            var acc = 0.0
            var weights = 0.0
            val lo = floor(next - width).toInt()
            val hi = ceil(next + width).toInt()
            for (j in lo..hi) {
                val pos = abs(next - j) * tableSteps
                val k = pos.toInt()
                val w = if (k + 1 < table.size) table[k] + (table[k + 1] - table[k]) * (pos - k)
                else if (k < table.size) table[k] else 0.0
                if (w == 0.0) continue
                weights += w
                val idx = j - start
                if (idx >= 0 && idx < size) acc += buf[head + idx.toInt()] * w
            }
            out[n++] = if (weights > 1e-9) (acc / weights).toFloat() else 0f
            next += step
        }
        // 丢掉已经用不到的输入
        val keep = floor(next - width).toInt() - 2
        val drop = (keep - start).coerceIn(0, size)
        if (drop > 0) {
            head += drop
            size -= drop
            start += drop
        }
        return if (n == out.size) out else out.copyOf(n)
    }

    /**
     * [cutoff] 是「占 Nyquist 的比例」（0.5 = 半个 Nyquist，1.0 = Nyquist），和 Rvc.kt 的 Dsp.kernel 同一约定：
     * sinc 用 sin(π·cutoff·x)/(π·cutoff·x)，再乘 cutoff 归一化。
     * 这里写成 sin(2π·cutoff·x) 就相当于把通带放宽一倍，44.1k→48k 的镜像会整段漏过去（听感即"损音"）。
     */
    private fun kernel(cutoff: Double, width: Double): DoubleArray {
        val steps = 400.0
        val count = (width * steps).toInt() + 2
        val table = DoubleArray(count)
        val beta = 8.6
        val normal = besselI0(beta)
        for (i in 0 until count) {
            val x = i / steps
            val arg = PI * cutoff * x
            val sinc = if (abs(arg) < 1e-12) 1.0 else sin(arg) / arg
            val r = x / width
            val window = if (r >= 1.0) 0.0 else besselI0(beta * sqrt(1 - r * r)) / normal
            table[i] = cutoff * sinc * window
        }
        return table
    }

    private fun besselI0(x: Double): Double {
        var sum = 1.0
        var term = 1.0
        val y = x * x / 4
        var k = 1
        while (k < 60) {
            term *= y / (k * k)
            sum += term
            if (term < 1e-13 * sum) break
            k++
        }
        return sum
    }
}

/** 任意格式（MediaCodec 能解的）→ 48 kHz 单声道 16 bit。 */
internal object AudioFileDecoder {
    const val RATE = 48000

    fun load(file: File): Pcm {
        val extractor = MediaExtractor()
        try {
            extractor.setDataSource(file.absolutePath)
            var track = -1
            var format: MediaFormat? = null
            for (i in 0 until extractor.trackCount) {
                val f = extractor.getTrackFormat(i)
                val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                if (mime.startsWith("audio/")) {
                    track = i
                    format = f
                    break
                }
            }
            val inputFormat = format ?: throw IllegalArgumentException("没有音频轨道")
            require(track >= 0) { "没有音频轨道" }
            extractor.selectTrack(track)
            val mime = inputFormat.getString(MediaFormat.KEY_MIME)
                ?: throw IllegalArgumentException("没有音频轨道")
            val codec = MediaCodec.createDecoderByType(mime)
            val sink = ShortSink()
            try {
                codec.configure(inputFormat, null, null, 0)
                codec.start()
                var rate = inputFormat.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100).coerceAtLeast(8000)
                var channels = inputFormat.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var resampler: SincResampler? = if (rate == RATE) null else SincResampler(rate, RATE)
                val info = MediaCodec.BufferInfo()
                var inputDone = false
                var outputDone = false
                while (!outputDone) {
                    if (!inputDone) {
                        val index = codec.dequeueInputBuffer(10_000)
                        if (index >= 0) {
                            val buffer = codec.getInputBuffer(index)!!
                            val size = extractor.readSampleData(buffer, 0)
                            if (size < 0) {
                                codec.queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                inputDone = true
                            } else {
                                codec.queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
                                extractor.advance()
                            }
                        }
                    }
                    val index = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        index == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val changed = codec.outputFormat
                            rate = changed.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100).coerceAtLeast(8000)
                            channels = changed.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                            encoding = if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING))
                                changed.getInteger(MediaFormat.KEY_PCM_ENCODING) else AudioFormat.ENCODING_PCM_16BIT
                            resampler = if (rate == RATE) null else SincResampler(rate, RATE)
                        }
                        index == MediaCodec.INFO_OUTPUT_BUFFERS_CHANGED -> Unit
                        index >= 0 -> {
                            if (info.size > 0) {
                                val buffer = codec.getOutputBuffer(index)!!
                                buffer.position(info.offset)
                                buffer.limit(info.offset + info.size)
                                val mono = toMono(buffer, channels, encoding)
                                if (mono.isNotEmpty()) {
                                    val r = resampler
                                    sink.add(if (r == null) mono else r.feed(mono))
                                }
                            }
                            codec.releaseOutputBuffer(index, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                resampler?.let { sink.add(it.finish()) }
            } finally {
                runCatching { codec.stop() }
                codec.release()
            }
            return sink.result()
        } finally {
            extractor.release()
        }
    }

    private fun toMono(buffer: ByteBuffer, channels: Int, encoding: Int): FloatArray {
        val ordered = buffer.order(ByteOrder.nativeOrder())
        if (encoding == AudioFormat.ENCODING_PCM_FLOAT) {
            val f = ordered.asFloatBuffer()
            val frames = f.remaining() / channels
            return FloatArray(frames) { i ->
                var sum = 0f
                for (c in 0 until channels) sum += f.get(i * channels + c)
                sum / channels
            }
        }
        val s = ordered.asShortBuffer()
        val frames = s.remaining() / channels
        return FloatArray(frames) { i ->
            var sum = 0f
            for (c in 0 until channels) sum += s.get(i * channels + c) / 32768f
            sum / channels
        }
    }

    /** 有的容器不带采样率/声道数，宁可回落到 44.1 kHz 单声道也不要抛。 */
    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}

/**
 * 发进麦克风的那一路本地音频。
 *
 * 它不经过 RVC、不经过静音阈值门限：音频线程每交付一块变声结果，就把这里的素材按块叠加进去
 * （[mix]）。所以「你在说话」和「正在放素材」是两条独立的通路，同时都成立。
 *
 * 混音只在这里做加法 + 现有的软限幅，不做闪避：音乐不会压低你的变声。
 */
object AudioDeck {
    const val RATE = 48000
    const val GAP_MS = 1500
    private const val GAP_SAMPLES = RATE * GAP_MS / 1000

    enum class State { IDLE, LOADING, PLAYING, PAUSED }
    enum class Failure { NONE, NO_SESSION, DECODE }

    @Volatile var state = State.IDLE
        private set
    @Volatile var track: AudioLibrary.Track? = null
        private set
    @Volatile var loop = false
    @Volatile var failure = Failure.NONE
        private set
    @Volatile var failureDetail: String? = null
        private set
    @Volatile var gainDb = -12f
        private set

    @Volatile private var gain = 10.0.pow(-12.0 / 20.0).toFloat()
    @Volatile private var pcm: Pcm? = null
    @Volatile private var position = 0
    @Volatile private var gapLeft = 0
    @Volatile private var generation = 0
    private var loader: Thread? = null

    /** 最近一块「变声 + 素材」相加后的峰值（削顶前）和已经削顶过的块数，进会话日志查问题用。 */
    @Volatile var lastPeak = 0f
        private set
    @Volatile var clippedBlocks = 0
        private set
    /** 调试用：非 null 时把解码结果写成 wav（只有 debug_hooks 存在时才设置）。 */
    @Volatile var dumpDir: File? = null

    val durationMs: Long get() = pcm?.let { it.size * 1000L / RATE } ?: 0L
    val positionMs: Long get() = position * 1000L / RATE

    fun setGain(db: Float) {
        gainDb = db
        gain = 10.0.pow(db / 20.0).toFloat()
    }

    /** 点一次发送一次：同一首正在发就忽略；换了就立刻打断重来。 */
    @Synchronized
    fun play(t: AudioLibrary.Track) {
        val same = track?.id == t.id
        if (same && state == State.PLAYING) return
        if (same && pcm != null) {
            position = 0
            gapLeft = 0
            failure = Failure.NONE
            failureDetail = null
            state = State.PLAYING
            android.util.Log.i("AudioDeck", "restart track=${t.name}")
            return
        }
        if (!VirtualMicSession.isActive) {
            failure = Failure.NO_SESSION
            failureDetail = null
            return
        }
        generation++
        val token = generation
        track = t
        pcm = null
        position = 0
        gapLeft = 0
        failure = Failure.NONE
        failureDetail = null
        state = State.LOADING
        android.util.Log.i("AudioDeck", "load track=${t.name} file=${t.file.name}")
        loader = thread(name = "audio-decode") {
            val result = runCatching { AudioFileDecoder.load(t.file) }
            synchronized(this) {
                if (token != generation) return@synchronized
                result.onSuccess { decoded ->
                    if (decoded.size <= 0) {
                        failure = Failure.DECODE
                        failureDetail = "empty"
                        state = State.IDLE
                    } else {
                        dumpDir?.let { dir -> runCatching { writeWav(decoded, t.name, dir) } }
                        pcm = decoded
                        position = 0
                        gapLeft = 0
                        state = State.PLAYING
                        android.util.Log.i("AudioDeck", "playing track=${t.name} ms=${decoded.size * 1000L / RATE}")
                    }
                }.onFailure {
                    failure = Failure.DECODE
                    failureDetail = it.message
                    state = State.IDLE
                    android.util.Log.i("AudioDeck", "decode-failed track=${t.name} err=${it.message}")
                }
            }
        }
    }

    @Synchronized
    fun toggle() {
        state = when (state) {
            State.PLAYING -> State.PAUSED
            State.PAUSED -> State.PLAYING
            else -> state
        }
        android.util.Log.i("AudioDeck", "toggle -> $state at ${position * 1000L / RATE}ms")
    }

    @Synchronized
    fun pause() {
        if (state == State.PLAYING) {
            state = State.PAUSED
            android.util.Log.i("AudioDeck", "pause at ${position * 1000L / RATE}ms")
        }
    }

    /** 会话停掉时调用：没有注入点，留着播放状态只会误导界面。 */
    @Synchronized
    fun release() {
        generation++
        state = State.IDLE
        track = null
        pcm = null
        position = 0
        gapLeft = 0
        failure = Failure.NONE
        failureDetail = null
    }

    /**
     * 音频线程每交付一块回注 PCM 就调一次：把素材按块加进去。
     * 位置只按「真正送出去的块」前进，所以推理掉块或游戏没占麦时不会跳音。
     */
    fun mix(y: FloatArray) {
        val clip = pcm ?: return
        if (state != State.PLAYING) return
        val token = generation
        var i = 0
        var p = position
        var gap = gapLeft
        var next = State.PLAYING
        var peak = 0f
        var over = false
        val data = clip.data
        // 素材是 16 bit 短整型（±32768），必须归一化到 [-1,1] 再乘增益；
        // 漏掉 /32768 会让音乐以 3 万倍的幅度进混音，整段被软限幅压成方波（听起来就是"炸"）。
        val g = gain / 32768f
        while (i < y.size) {
            if (gap > 0) {
                val n = min(gap, y.size - i)
                i += n
                gap -= n
                continue
            }
            if (p >= clip.size) {
                if (loop) {
                    p = 0
                    gap = GAP_SAMPLES
                    android.util.Log.i("AudioDeck", "loop-gap ${GAP_MS}ms track=${track?.name}")
                    continue
                }
                next = State.IDLE
                p = 0
                android.util.Log.i("AudioDeck", "ended track=${track?.name}")
                break
            }
            val n = min(clip.size - p, y.size - i)
            for (k in 0 until n) {
                val sum = y[i + k] + data[p + k] * g
                val a = if (sum < 0) -sum else sum
                if (a > peak) peak = a
                if (a > 1f) over = true
                y[i + k] = LiveCore.softClip(sum)
            }
            i += n
            p += n
        }
        lastPeak = peak
        if (over) clippedBlocks++
        if (token != generation || state != State.PLAYING) return
        position = p
        gapLeft = gap
        if (next != State.PLAYING) {
            state = next
            position = 0
            gapLeft = 0
        }
    }

    /** 调试：解码后的 48k 单声道 PCM 存成 wav，用来在电脑上验频谱。 */
    private fun writeWav(p: Pcm, name: String, dir: File) {
        dir.mkdirs()
        val file = File(dir, name.replace('/', '_') + ".wav")
        java.io.RandomAccessFile(file, "rw").use { raf ->
            raf.setLength(0)
            fun le16(value: Int) {
                raf.write(value and 0xFF)
                raf.write((value shr 8) and 0xFF)
            }
            fun le32(value: Int) {
                le16(value and 0xFFFF)
                le16((value ushr 16) and 0xFFFF)
            }
            raf.writeBytes("RIFF"); le32(36 + p.size * 2)
            raf.writeBytes("WAVEfmt "); le32(16)
            le16(1); le16(1); le32(RATE); le32(RATE * 2); le16(2); le16(16)
            raf.writeBytes("data"); le32(p.size * 2)
            val buffer = ByteArray(8192)
            var at = 0
            while (at < p.size) {
                val n = min(4096, p.size - at)
                for (k in 0 until n) {
                    val v = p.data[at + k].toInt()
                    buffer[2 * k] = v.toByte()
                    buffer[2 * k + 1] = (v shr 8).toByte()
                }
                raf.write(buffer, 0, n * 2)
                at += n
            }
        }
    }
}
