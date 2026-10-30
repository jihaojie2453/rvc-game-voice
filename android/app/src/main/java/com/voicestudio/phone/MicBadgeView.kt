package com.voicestudio.phone

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.View

/**
 * 闭麦悬浮按钮的样子：一整颗实心圆 + 白色话筒；闭麦时多一道斜杠。
 *
 * 颜色本身就是状态（红 = 闭麦 / 黄 = 正在开麦、还没生效 / 绿 = 已经送出去了），
 * 所以图案一律白色：emoji 不能染色，中文「麦」放到英文界面里也不合适，
 * 自己画图形就不用依赖字库。
 */
class MicBadgeView(context: Context) : View(context) {

    /** 底色：整个圆就是这个颜色，和主胶囊的红/绿同一个来源。 */
    var tint: Int = Color.RED
        set(value) {
            if (value != field) { field = value; invalidate() }
        }

    /** 闭麦时画斜杠。 */
    var slashed: Boolean = false
        set(value) {
            if (value != field) { field = value; invalidate() }
        }

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        color = Color.WHITE
    }
    private val capsule = RectF()
    private val cradle = RectF()

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) / 2f

        fill.color = tint
        canvas.drawCircle(cx, cy, r, fill)

        // 话筒本体
        val capW = r * 0.40f
        val capH = r * 0.80f
        val top = cy - r * 0.62f
        capsule.set(cx - capW / 2f, top, cx + capW / 2f, top + capH)
        fill.color = Color.WHITE
        canvas.drawRoundRect(capsule, capW / 2f, capW / 2f, fill)

        // 托架 + 立杆
        line.strokeWidth = r * 0.13f
        cradle.set(
            cx - r * 0.46f, top + capH * 0.28f,
            cx + r * 0.46f, top + capH * 1.28f,
        )
        canvas.drawArc(cradle, 20f, 140f, false, line)
        canvas.drawLine(cx, top + capH * 1.02f, cx, top + capH * 1.32f, line)

        if (slashed) {
            line.strokeWidth = r * 0.16f
            canvas.drawLine(cx - r * 0.60f, cy - r * 0.60f, cx + r * 0.60f, cy + r * 0.60f, line)
        }
    }
}
