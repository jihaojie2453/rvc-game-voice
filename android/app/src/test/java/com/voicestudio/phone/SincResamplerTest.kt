package com.voicestudio.phone

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/**
 * 重采样的两条硬指标：音高不变、镜像被滤掉。
 *
 * 44.1k → 48k 的时候，1 kHz 的镜像落在 43.1 kHz；如果插值滤波器通带放宽到 Nyquist 之外
 * （sin(2π·cutoff·x) 那种写法），这个镜像会原样通过并在 48k 里折回 4.9 kHz —— 听感就是
 * 又毛又刺的"损音"。所以这里直接量 4.9 kHz 的幅度。
 */
class SincResamplerTest {

    private fun sine(rate: Int, freq: Double, n: Int): FloatArray =
        FloatArray(n) { (0.5 * sin(2 * PI * freq * it / rate)).toFloat() }

    private fun resample(x: FloatArray, src: Int, dst: Int): FloatArray {
        val r = SincResampler(src, dst)
        val out = ArrayList<Float>(x.size * dst / src + 64)
        var at = 0
        while (at < x.size) {
            val n = minOf(1024, x.size - at)
            for (v in r.feed(x.copyOfRange(at, at + n))) out.add(v)
            at += n
        }
        for (v in r.finish()) out.add(v)
        return out.toFloatArray()
    }

    /** 单频点 DFT 幅度；采样段取整数个周期，避免泄漏影响读数。 */
    private fun magnitude(x: FloatArray, rate: Int, freq: Double): Double {
        var re = 0.0
        var im = 0.0
        val w = 2 * PI * freq / rate
        for (i in x.indices) {
            re += x[i] * cos(w * i)
            im -= x[i] * sin(w * i)
        }
        return 2 * hypot(re, im) / x.size
    }

    /** 取中间 0.8 秒（38400 个 48k 采样），两端各留够滤波器暖机；1k/4.9k/15k/18.9k 都正好整数个周期。 */
    private fun body(y: FloatArray): FloatArray {
        val size = 38400
        assertTrue("输出太短：${y.size}", y.size > size + 8000)
        val start = (y.size - size) / 2
        return y.copyOfRange(start, start + size)
    }

    @Test
    fun upsamplingKeepsTheToneAndRejectsTheImage() {
        val src = 44100
        val dst = 48000
        val y = resample(sine(src, 1000.0, src), src, dst)
        assertEquals(dst.toDouble(), y.size.toDouble(), 64.0)

        val part = body(y)
        val tone = magnitude(part, dst, 1000.0)
        assertTrue("1 kHz 幅度应接近 0.5，实际 $tone", tone in 0.45..0.52)

        val image = magnitude(part, dst, 4900.0)
        assertTrue("4.9 kHz 镜像过高：$image（相对 $tone）", image < tone * 0.01)
    }

    @Test
    fun upsamplingPreservesAFullBandSource() {
        val src = 44100
        val dst = 48000
        val y = resample(sine(src, 15000.0, src), src, dst)
        val part = body(y)
        val tone = magnitude(part, dst, 15000.0)
        // 15 kHz 在 0.95·Nyquist 的通带内，应该基本无损
        assertTrue("15 kHz 幅度 $tone", tone in 0.40..0.52)
        // 它的镜像 44.1 - 15 = 29.1 kHz 折回 48 - 29.1 = 18.9 kHz
        val image = magnitude(part, dst, 18900.0)
        assertTrue("18.9 kHz 镜像过高：$image（相对 $tone）", image < tone * 0.02)
    }
}
