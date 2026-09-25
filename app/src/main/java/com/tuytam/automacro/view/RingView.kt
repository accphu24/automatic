package com.tuytam.automacro.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View

/**
 * Vong tron the hien % (do ben gem, ti le hoan thanh quest, tien do HuntBot...).
 * Tu ve bang Canvas — khong can thu vien bieu do ngoai.
 */
class RingView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var percent: Int = 0
        set(value) { field = value.coerceIn(0, 100); invalidate() }
    var ringColor: Int = Color.BLUE
        set(value) { field = value; invalidate() }
    var trackColor: Int = Color.argb(40, 128, 128, 128)
        set(value) { field = value; invalidate() }
    var strokeWidthDp: Float = 5f
        set(value) { field = value; invalidate() }

    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND }
    private val rect = RectF()

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (width == 0 || height == 0) return
        val stroke = strokeWidthDp * resources.displayMetrics.density
        trackPaint.strokeWidth = stroke
        trackPaint.color = trackColor
        ringPaint.strokeWidth = stroke
        ringPaint.color = ringColor
        val inset = stroke / 2f
        rect.set(inset, inset, width - inset, height - inset)
        canvas.drawArc(rect, -90f, 360f, false, trackPaint)
        if (percent > 0) canvas.drawArc(rect, -90f, 360f * percent / 100f, false, ringPaint)
    }
}
