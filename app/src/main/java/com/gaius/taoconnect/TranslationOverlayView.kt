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
import android.text.TextUtils
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
        color = Color.argb(224, 24, 28, 34)
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(82, 255, 255, 255)
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 90, 31)
        style = Paint.Style.FILL
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.NORMAL
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
            .distinctBy { it.translatedText.lowercase().trim() }
            .take(MAX_VISIBLE_LABELS)
        sourceWidth = max(1, capturedScreenWidth)
        sourceHeight = max(1, capturedScreenHeight)
        invalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        alpha = 0f
        animate()
            .alpha(1f)
            .setDuration(FADE_IN_DURATION_MS)
            .start()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (translations.isEmpty() || width == 0 || height == 0) return

        val scaleX = width.toFloat() / sourceWidth.toFloat()
        val scaleY = height.toFloat() / sourceHeight.toFloat()
        val horizontalPadding = dp(8f)
        val verticalPadding = dp(5f)
        val accentWidth = dp(3f)
        val accentGap = dp(6f)
        val outerMargin = dp(7f)
        val collisionGap = dp(4f)
        val radius = dp(7f)
        val minLabelWidth = dp(74f)
        val maxLabelWidth = min(dp(218f), width * 0.52f)
        val occupied = mutableListOf<RectF>()

        translations.forEach { item ->
            val source = item.sourceBounds
            val anchor = RectF(
                source.left * scaleX,
                source.top * scaleY,
                source.right * scaleX,
                source.bottom * scaleY
            )

            val sourceTextHeight = max(dp(11f), source.height() * scaleY)
            textPaint.textSize = (sourceTextHeight * 0.72f)
                .coerceIn(sp(10.5f), sp(14f))

            val measuredTextWidth = textPaint.measureText(item.translatedText)
            val labelWidth = max(
                minLabelWidth,
                min(
                    maxLabelWidth,
                    max(
                        anchor.width() + 2 * horizontalPadding,
                        measuredTextWidth + 2 * horizontalPadding + accentWidth + accentGap
                    )
                )
            )
            val layoutWidth = max(
                1,
                (labelWidth - 2 * horizontalPadding - accentWidth - accentGap).toInt()
            )
            val textLayout = StaticLayout.Builder
                .obtain(item.translatedText, 0, item.translatedText.length, textPaint, layoutWidth)
                .setAlignment(Layout.Alignment.ALIGN_NORMAL)
                .setIncludePad(false)
                .setMaxLines(3)
                .setEllipsize(TextUtils.TruncateAt.END)
                .build()

            val labelHeight = textLayout.height + 2 * verticalPadding
            val labelRect = findFreePosition(
                anchor = anchor,
                labelWidth = labelWidth,
                labelHeight = labelHeight,
                occupied = occupied,
                outerMargin = outerMargin,
                collisionGap = collisionGap
            ) ?: return@forEach

            occupied += RectF(labelRect)
            canvas.drawRoundRect(labelRect, radius, radius, backgroundPaint)
            canvas.drawRoundRect(labelRect, radius, radius, borderPaint)

            val accentRect = RectF(
                labelRect.left,
                labelRect.top + radius,
                labelRect.left + accentWidth,
                labelRect.bottom - radius
            )
            canvas.drawRoundRect(accentRect, accentWidth, accentWidth, accentPaint)

            canvas.save()
            canvas.translate(
                labelRect.left + horizontalPadding + accentWidth + accentGap,
                labelRect.top + verticalPadding
            )
            textLayout.draw(canvas)
            canvas.restore()
        }
    }

    private fun findFreePosition(
        anchor: RectF,
        labelWidth: Float,
        labelHeight: Float,
        occupied: List<RectF>,
        outerMargin: Float,
        collisionGap: Float
    ): RectF? {
        val maxLeft = max(outerMargin, width - labelWidth - outerMargin)
        val maxTop = max(outerMargin, height - labelHeight - outerMargin)
        val preferredLeft = anchor.left.coerceIn(outerMargin, maxLeft)
        val rightAligned = (anchor.right - labelWidth).coerceIn(outerMargin, maxLeft)
        val leftOfSource = (anchor.left - labelWidth - collisionGap)
            .coerceIn(outerMargin, maxLeft)
        val rightOfSource = (anchor.right + collisionGap)
            .coerceIn(outerMargin, maxLeft)
        val preferredTop = anchor.top.coerceIn(outerMargin, maxTop)
        val belowSource = (anchor.bottom + collisionGap).coerceIn(outerMargin, maxTop)
        val aboveSource = (anchor.top - labelHeight - collisionGap)
            .coerceIn(outerMargin, maxTop)

        val candidates = listOf(
            RectF(preferredLeft, preferredTop, preferredLeft + labelWidth, preferredTop + labelHeight),
            RectF(rightAligned, preferredTop, rightAligned + labelWidth, preferredTop + labelHeight),
            RectF(preferredLeft, belowSource, preferredLeft + labelWidth, belowSource + labelHeight),
            RectF(preferredLeft, aboveSource, preferredLeft + labelWidth, aboveSource + labelHeight),
            RectF(rightOfSource, preferredTop, rightOfSource + labelWidth, preferredTop + labelHeight),
            RectF(leftOfSource, preferredTop, leftOfSource + labelWidth, preferredTop + labelHeight)
        )

        candidates.firstOrNull { !it.overlaps(occupied, collisionGap) }?.let { return it }

        val scanLeft = if (anchor.centerX() < width / 2f) outerMargin else maxLeft
        var scanTop = outerMargin
        val scanStep = max(dp(8f), labelHeight / 3f)
        while (scanTop <= maxTop) {
            val candidate = RectF(
                scanLeft,
                scanTop,
                scanLeft + labelWidth,
                scanTop + labelHeight
            )
            if (!candidate.overlaps(occupied, collisionGap)) return candidate
            scanTop += scanStep
        }

        return null
    }

    private fun RectF.overlaps(occupied: List<RectF>, gap: Float): Boolean {
        val expanded = RectF(this).apply { inset(-gap, -gap) }
        return occupied.any { RectF.intersects(expanded, it) }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        value * resources.displayMetrics.scaledDensity

    private companion object {
        const val MAX_VISIBLE_LABELS = 12
        const val FADE_IN_DURATION_MS = 160L
    }
}
