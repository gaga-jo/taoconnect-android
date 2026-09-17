package com.gaius.taoconnect

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.text.Layout
import android.text.StaticLayout
import android.text.TextPaint
import android.util.AttributeSet
import android.view.View
import kotlin.math.max
import kotlin.math.min

data class TranslationItem(
    val sourceBounds: Rect,
    val translatedText: String
)

class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(238, 216, 61, 9)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )
    }

    private var translations: List<TranslationItem> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1

    fun setTranslations(
        items: List<TranslationItem>,
        capturedScreenWidth: Int,
        capturedScreenHeight: Int
    ) {
        translations = items
        sourceWidth = max(1, capturedScreenWidth)
        sourceHeight = max(1, capturedScreenHeight)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (translations.isEmpty()) return

        val scaleX = width.toFloat() / sourceWidth.toFloat()
        val scaleY = height.toFloat() / sourceHeight.toFloat()
        val padding = dp(6f)
        val radius = dp(8f)
        val minLabelWidth = dp(112f)

        translations.forEach { item ->
            val source = item.sourceBounds
            val left = (source.left * scaleX).coerceIn(0f, width.toFloat())
            val top = (source.top * scaleY).coerceIn(0f, height.toFloat())
            val sourceBoxWidth = max(dp(64f), source.width() * scaleX)
            val availableWidth = max(dp(80f), width - left - dp(8f))
            val labelWidth = min(
                availableWidth,
                max(minLabelWidth, sourceBoxWidth + 2 * padding)
            )

            val sourceTextHeight = max(dp(12f), source.height() * scaleY)
            textPaint.textSize = sourceTextHeight
                .coerceIn(dp(11f), dp(17f))

            val layoutWidth = max(1, (labelWidth - 2 * padding).toInt())
            val textLayout = StaticLayout.Builder
                .obtain(item.translatedText, 0, item.translatedText.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(4)
                .setEllipsize(android.text.TextUtils.TruncateAt.END)
                .build()

            val labelHeight = textLayout.height + 2 * padding
            val adjustedTop = min(top, max(0f, height - labelHeight - dp(4f)))
            val labelRect = RectF(
                left,
                adjustedTop,
                min(width.toFloat() - dp(4f), left + labelWidth),
                adjustedTop + labelHeight
            )

            canvas.drawRoundRect(labelRect, radius, radius, backgroundPaint)
            canvas.drawRoundRect(labelRect, radius, radius, borderPaint)

            canvas.save()
            canvas.translate(labelRect.left + padding, labelRect.top + padding)
            textLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density
}
