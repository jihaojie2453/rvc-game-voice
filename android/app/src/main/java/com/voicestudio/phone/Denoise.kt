package com.voicestudio.phone

/**
 * RNNoise 降噪：48 kHz、480 采样（10 ms）一帧，有状态的流式算法。
 *
 * 只插在采集侧、写进 ring 之前 —— 那里每一票采样只到达一次。16 kHz 那一层是重叠窗口
 * （[Dsp.WindowResampler] 每次返回整窗），同一段音频会被反复喂进去，状态会错乱，
 * 所以绝不能放在 [Rvc.streamWindow] 前后。
 *
 * 代价：RNNoise 自己是"输出上一帧"的算法，加上帧化，链路最多多 10～20 ms 延迟；
 * 输出缓冲（`live_cushion_cs`）本来就在，实际听不出来。
 */
object Denoise {
    const val FRAME = 480

    /** 库没打进 APK（旧版本）、或者机型没有这个 ABI 时返回 false，整条路径旁路。 */
    val available: Boolean by lazy {
        runCatching {
            System.loadLibrary("rnnoise")
            nativeFrameSize() == FRAME
        }.getOrDefault(false)
    }

    @JvmStatic external fun nativeCreate(): Long

    /** 原地降噪一帧；返回该帧的浊音概率（0..1），当前只用于日志。 */
    @JvmStatic external fun nativeProcess(handle: Long, frame: FloatArray): Float

    @JvmStatic external fun nativeFrameSize(): Int

    @JvmStatic external fun nativeDestroy(handle: Long)
}

/**
 * 采集侧的帧化缓冲：攒够 480 个新采样就降噪一组，把结果交回去写 ring。
 *
 * [process] 在采集线程跑，[reset]/[close] 在会话线程跑（IDLE_RESET、MUTE_ENTER、收尾），
 * 所以三个方法都加锁：否则 `nativeDestroy` 可能正好落在 `nativeProcess` 中间，直接崩。
 * 锁的持有时间是一次 10 ms 帧的推理（几十微秒），reset 又极少发生，不值得做更细的同步。
 */
class Denoiser private constructor(private var handle: Long) {
    private val frame = FloatArray(Denoise.FRAME)
    private val inBuf = FloatArray(Denoise.FRAME)
    private var inLen = 0
    private var outBuf = FloatArray(Denoise.FRAME * 2)
    private var outLen = 0

    /**
     * 就地处理 `pcm` 的前 `n` 个采样，返回真正可用的采样数（≤ n）。
     * 不足一帧的尾巴留到下一次，所以最多滞后 10 ms —— 调用方写成
     * `ring.write(pcm, denoiser.process(pcm, n))` 就不会把没处理的采样送出去。
     */
    fun process(pcm: FloatArray, n: Int = pcm.size): Int = synchronized(this) {
        if (handle == 0L || n <= 0) return n
        var i = 0
        if (inLen > 0) {
            val take = minOf(Denoise.FRAME - inLen, n)
            System.arraycopy(pcm, 0, inBuf, inLen, take)
            inLen += take; i += take
            if (inLen == Denoise.FRAME) { inLen = 0; emit(inBuf, 0) }
        }
        while (i + Denoise.FRAME <= n) { emit(pcm, i); i += Denoise.FRAME }
        if (i < n) { inLen = n - i; System.arraycopy(pcm, i, inBuf, 0, inLen) }
        val k = minOf(n, outLen)
        if (k > 0) {
            System.arraycopy(outBuf, 0, pcm, 0, k)
            System.arraycopy(outBuf, k, outBuf, 0, outLen - k)
            outLen -= k
        }
        return k
    }

    /** 新会话、IDLE_RESET、MUTE_ENTER：旧状态会让声音发闷，重建一份干净的。 */
    @Synchronized
    fun reset() {
        if (handle == 0L) return
        Denoise.nativeDestroy(handle)
        handle = Denoise.nativeCreate()
        inLen = 0; outLen = 0
    }

    @Synchronized
    fun close() {
        if (handle == 0L) return
        Denoise.nativeDestroy(handle)
        handle = 0L
        inLen = 0; outLen = 0
    }

    private fun emit(src: FloatArray, at: Int) {
        System.arraycopy(src, at, frame, 0, Denoise.FRAME)
        Denoise.nativeProcess(handle, frame)
        if (outBuf.size < outLen + Denoise.FRAME) outBuf = outBuf.copyOf(outBuf.size * 2 + Denoise.FRAME)
        System.arraycopy(frame, 0, outBuf, outLen, Denoise.FRAME)
        outLen += Denoise.FRAME
    }

    companion object {
        /** 开关关闭、或原生库不可用时返回 null，调用方按原样透传。 */
        fun create(enabled: Boolean): Denoiser? {
            if (!enabled || !Denoise.available) return null
            return runCatching { Denoiser(Denoise.nativeCreate()) }.getOrNull()?.takeIf { it.handle != 0L }
        }
    }
}
