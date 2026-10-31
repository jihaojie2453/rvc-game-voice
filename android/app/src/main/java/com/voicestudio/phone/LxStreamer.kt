package com.voicestudio.phone

import android.media.AudioFormat
import android.media.MediaCodec
import android.media.MediaDataSource
import android.media.MediaExtractor
import android.media.MediaFormat
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlin.math.max
import kotlin.math.min

/**
 * HTTP 边下边解：网络字节流 → MediaExtractor/MediaCodec → 48 kHz 单声道 → 追加进可增长的 [Pcm]。
 *
 * 两个要点：
 *  1. 用 [MediaDataSource]（API 23+）让 MediaExtractor 直接吃「还在增长」的流，不用等整首下完；
 *  2. 下载线程顺手把字节写进缓存文件，边听边缓存，下次直接命中本地文件。
 *
 * MediaExtractor 对「暂时读到末尾但还有后续数据」的流会返回 -1，这里不当作结束，
 * 而是等下载线程补数据再继续读；只有下载真的结束才送 EOS。
 */
internal object LxStreamer {

    private const val TAG = "LxStreamer"
    private const val RATE = 48000
    private const val WAIT_STEP_MS = 15L

    /** 把下载的字节同时写进缓存：成功才落地，中途放弃就删掉半成品。 */
    class CacheTee(private val target: File) {
        // 和整首下载用的 .part 区分开，避免回退时两边抢同一个临时文件
        private val part = File(target.absolutePath + ".streaming")
        private var os: FileOutputStream? = null
        private var bytes = 0L

        @Synchronized
        fun write(b: ByteArray, off: Int, n: Int) {
            try {
                if (os == null) {
                    target.parentFile?.mkdirs()
                    os = FileOutputStream(part)
                }
                os?.write(b, off, n)
                bytes += n
            } catch (t: Throwable) {
                Log.w(TAG, "写缓存失败", t)
            }
        }

        @Synchronized
        fun finish(ok: Boolean) {
            runCatching { os?.close() }
            os = null
            if (ok && part.isFile && part.length() > 4096) {
                runCatching { target.delete() }
                if (!part.renameTo(target)) {
                    runCatching { part.copyTo(target, overwrite = true) }
                    part.delete()
                }
            } else {
                part.delete()
            }
        }
    }

    /** MediaExtractor 的数据源：环形缓冲 + 下载线程，非 seekable。 */
    class HttpMediaSource(private val url: String, private val tee: CacheTee? = null) : MediaDataSource() {
        private val cap = 1 shl 21                    // 2 MB 环形缓冲
        private val ring = ByteArray(cap)
        private val lock = java.lang.Object()
        private var writePos = 0L
        private var readFloor = 0L
        @Volatile private var finished = false
        @Volatile private var failure: Throwable? = null
        @Volatile private var closed = false
        private var conn: HttpURLConnection? = null

        val isFinished: Boolean get() = finished
        val error: Throwable? get() = failure

        private val pump = thread(name = "lx-http-pump", isDaemon = true) { pumpLoop() }

        private fun pumpLoop() {
            try {
                // 注意：在 apply{} 里 url 会被解析成 HttpURLConnection.url（URL 类型），
                // 所以这里先在外面把 Referer 算好。
                val referer = runCatching {
                    val u = URL(url)
                    "${u.protocol}://${u.host}/"
                }.getOrNull()
                val c = (URL(url).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 15000
                    readTimeout = 20000
                    instanceFollowRedirects = true
                    useCaches = false
                    setRequestProperty("User-Agent", "Mozilla/5.0 (Linux; Android 13) AppleWebKit/537.36")
                    setRequestProperty("Accept", "*/*")
                    if (referer != null) setRequestProperty("Referer", referer)
                }
                conn = c
                val code = c.responseCode
                if (code !in 200..299) throw IOException("HTTP $code")
                c.inputStream.use { input ->
                    val chunk = ByteArray(64 * 1024)
                    while (!closed) {
                        val n = input.read(chunk)
                        if (n < 0) break
                        tee?.write(chunk, 0, n)
                        var off = 0
                        while (off < n && !closed) off += put(chunk, off, n - off)
                    }
                }
            } catch (t: Throwable) {
                if (!closed) failure = t
            } finally {
                synchronized(lock) {
                    finished = true
                    lock.notifyAll()
                }
            }
        }

        private fun put(buf: ByteArray, off: Int, len: Int): Int {
            synchronized(lock) {
                while (!closed && writePos - readFloor >= cap) {
                    runCatching { lock.wait(200) }
                }
                if (closed) return len
                val room = (cap - (writePos - readFloor)).toInt()
                val n = min(len, room)
                var idx = (writePos % cap).toInt()
                var done = 0
                while (done < n) {
                    val run = min(n - done, cap - idx)
                    System.arraycopy(buf, off + done, ring, idx, run)
                    done += run
                    idx = 0
                }
                writePos += n
                lock.notifyAll()
                return n
            }
        }

        override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
            if (size <= 0) return 0
            synchronized(lock) {
                while (position >= writePos) {
                    if (finished) return -1
                    runCatching { lock.wait(200) }
                }
                val avail = min((writePos - position).toInt(), size)
                var idx = (position % cap).toInt()
                var done = 0
                while (done < avail) {
                    val run = min(avail - done, cap - idx)
                    System.arraycopy(ring, idx, buffer, offset + done, run)
                    done += run
                    idx = 0
                }
                if (position + done > readFloor) readFloor = position + done
                return done
            }
        }

        /** 长度未知：让 MediaExtractor 按流式处理，不要求 seek。 */
        override fun getSize(): Long = -1L

        override fun close() {
            closed = true
            synchronized(lock) { lock.notifyAll() }
            runCatching { conn?.disconnect() }
            conn = null
        }
    }

    /**
     * 开始边下边播。返回一个停止函数。
     * [onError] 在流式失败时回调（调用方据此回退到「整首下载再播」）。
     */
    fun start(url: String, sink: Pcm, tee: CacheTee?, onError: (String) -> Unit): () -> Unit {
        val stopped = AtomicBoolean(false)
        val src = HttpMediaSource(url, tee)
        var teeOk = true
        val th = thread(name = "lx-stream", isDaemon = true) {
            val ex = MediaExtractor()
            var codec: MediaCodec? = null
            var ok = false
            try {
                ex.setDataSource(src)
                var track = -1
                var format: MediaFormat? = null
                for (i in 0 until ex.trackCount) {
                    val f = ex.getTrackFormat(i)
                    val mime = f.getString(MediaFormat.KEY_MIME) ?: continue
                    if (mime.startsWith("audio/")) {
                        track = i
                        format = f
                        break
                    }
                }
                val fmt = format
                if (track < 0 || fmt == null) {
                    teeOk = false
                    onError("没有音频轨道")
                    return@thread
                }
                ex.selectTrack(track)
                val mime = fmt.getString(MediaFormat.KEY_MIME) ?: run {
                    teeOk = false
                    onError("轨道没有 mime")
                    return@thread
                }
                codec = MediaCodec.createDecoderByType(mime)
                codec.configure(fmt, null, null, 0)
                codec.start()

                var rate = fmt.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100).coerceAtLeast(8000)
                var channels = fmt.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                var encoding = AudioFormat.ENCODING_PCM_16BIT
                var resampler: SincResampler? = if (rate == RATE) null else SincResampler(rate, RATE)
                val info = MediaCodec.BufferInfo()
                val scratch = ByteBuffer.allocateDirect(256 * 1024)
                var inputDone = false
                var outputDone = false

                while (!outputDone && !stopped.get()) {
                    if (!inputDone) {
                        scratch.clear()
                        val size = ex.readSampleData(scratch, 0)
                        if (size < 0) {
                            if (src.isFinished) {
                                val idx = codec.dequeueInputBuffer(10_000)
                                if (idx >= 0) {
                                    codec.queueInputBuffer(idx, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                                    inputDone = true
                                }
                            } else {
                                Thread.sleep(WAIT_STEP_MS)  // 等下载线程补数据，别当结束
                            }
                        } else {
                            val idx = codec.dequeueInputBuffer(10_000)
                            if (idx >= 0) {
                                val ib = codec.getInputBuffer(idx) ?: continue
                                ib.clear()
                                scratch.position(0)
                                scratch.limit(size)
                                ib.put(scratch)
                                codec.queueInputBuffer(idx, 0, size, ex.sampleTime, 0)
                                ex.advance()
                            }
                        }
                    }
                    val idx = codec.dequeueOutputBuffer(info, 10_000)
                    when {
                        idx == MediaCodec.INFO_TRY_AGAIN_LATER -> Unit
                        idx == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                            val changed = codec.outputFormat
                            rate = changed.intOr(MediaFormat.KEY_SAMPLE_RATE, 44100).coerceAtLeast(8000)
                            channels = changed.intOr(MediaFormat.KEY_CHANNEL_COUNT, 1).coerceAtLeast(1)
                            encoding = if (changed.containsKey(MediaFormat.KEY_PCM_ENCODING)) {
                                changed.getInteger(MediaFormat.KEY_PCM_ENCODING)
                            } else {
                                AudioFormat.ENCODING_PCM_16BIT
                            }
                            resampler = if (rate == RATE) null else SincResampler(rate, RATE)
                        }
                        idx >= 0 -> {
                            if (info.size > 0) {
                                val b = codec.getOutputBuffer(idx)
                                if (b != null) {
                                    b.position(info.offset)
                                    b.limit(info.offset + info.size)
                                    val mono = toMono(b, channels, encoding)
                                    if (mono.isNotEmpty()) {
                                        val r = resampler
                                        append(sink, if (r == null) mono else r.feed(mono))
                                    }
                                }
                            }
                            codec.releaseOutputBuffer(idx, false)
                            if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) outputDone = true
                        }
                    }
                }
                resampler?.let { append(sink, it.finish()) }
                ok = outputDone && src.error == null
            } catch (t: Throwable) {
                if (!stopped.get()) {
                    teeOk = false
                    Log.w(TAG, "流式解码失败: $url", t)
                    onError(t.message ?: t.javaClass.simpleName)
                }
            } finally {
                runCatching { codec?.stop() }
                runCatching { codec?.release() }
                runCatching { ex.release() }
                src.close()
                synchronized(sink) { sink.ended = true }
                tee?.finish(ok && teeOk && !stopped.get())
            }
        }
        return {
            stopped.set(true)
            src.close()
            th.interrupt()
        }
    }

    /* ------------------------------------------------------------------ 工具 */

    private fun append(sink: Pcm, f: FloatArray) {
        if (f.isEmpty()) return
        synchronized(sink) {
            val need = sink.size + f.size
            var d = sink.data
            if (need > d.size) d = d.copyOf(max(d.size * 2, need))
            for (i in f.indices) {
                d[sink.size + i] = (f[i] * 32768f).toInt().coerceIn(-32768, 32767).toShort()
            }
            sink.data = d
            sink.size = need
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

    private fun MediaFormat.intOr(key: String, fallback: Int): Int =
        if (containsKey(key)) getInteger(key) else fallback
}
