package com.tuytam.automacro.view

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.View
import java.util.Locale

data class BarEntry(val label: String, val value: Long, val color: Int, val icon: android.graphics.Bitmap? = null)

/**
 * Bieu do cot ngang don gian (vd so pet theo tung tier). Chieu dai moi cot ti
 * le voi gia tri lon nhat trong danh sach. Tu ve bang Canvas.
 */
class BarChartView(context: Context, attrs: AttributeSet? = null) : View(context, attrs) {
    var entries: List<BarEntry> = emptyList()
        set(value) { field = value; requestLayout(); invalidate() }
    var labelColor: Int = Color.WHITE
        set(value) { field = value; invalidate() }

    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val valuePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textAlign = Paint.Align.RIGHT }
    private val barRect = RectF()

    private fun dp(v: Float) = v * resources.displayMetrics.density

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val rowH = dp(24f)
        val gap = dp(6f)
        val h = if (entries.isEmpty()) 0f else rowH * entries.size + gap * (entries.size - 1)
        setMeasuredDimension(MeasureSpec.getSize(widthMeasureSpec), h.toInt())
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (entries.isEmpty() || width == 0) return
        val maxValue = (entries.maxOfOrNull { it.value } ?: 1L).coerceAtLeast(1L)
        val rowH = dp(24f)
        val gap = dp(6f)
        val labelW = dp(76f)
        val valueW = dp(58f)
        labelPaint.textSize = dp(12f); labelPaint.color = labelColor
        valuePaint.textSize = dp(12f); valuePaint.color = labelColor
        var y = 0f
        val barAreaW = (width - labelW - valueW).coerceAtLeast(dp(20f))
        for (e in entries) {
            val barTop = y + dp(3f)
            val barBottom = y + rowH - dp(3f)
            val barW = (barAreaW * (e.value.toFloat() / maxValue)).coerceAtLeast(dp(3f))
            barPaint.color = e.color
            barRect.set(labelW, barTop, labelW + barW, barBottom)
            canvas.drawRoundRect(barRect, dp(5f), dp(5f), barPaint)
            val textY = y + rowH / 2f - (labelPaint.descent() + labelPaint.ascent()) / 2f
            canvas.drawText(e.label, 0f, textY, labelPaint)
            canvas.drawText(formatCount(e.value), width.toFloat(), textY, valuePaint)
            y += rowH + gap
        }
    }

    private fun formatCount(v: Long): String = String.format(Locale.US, "%,d", v)
}
