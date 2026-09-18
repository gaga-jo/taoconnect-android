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

enum class TranslationPresentation {
    INLINE,
    CALLOUT
}

data class TranslationItem(
    val sourceBounds: Rect,
    val translatedText: String,
    val presentation: TranslationPresentation = TranslationPresentation.CALLOUT,
    val backgroundColor: Int = Color.rgb(31, 35, 41),
    val foregroundColor: Int = Color.WHITE,
    val priority: Int = 0
)

class TranslationOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : View(context, attrs) {

    private val backgroundPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = dp(0.8f)
    }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.rgb(255, 90, 31)
        style = Paint.Style.FILL
    }
    private val connectorPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.argb(150, 255, 90, 31)
        style = Paint.Style.STROKE
        strokeWidth = dp(1.2f)
    }
    private val textPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.NORMAL
        )
    }

    private var translations: List<TranslationItem> = emptyList()
    private var sourceWidth = 1
    private var sourceHeight = 1
    private var avoidanceAreas: List<Rect> = emptyList()

    fun setTranslations(
        items: List<TranslationItem>,
        capturedScreenWidth: Int,
        capturedScreenHeight: Int,
        areasToAvoid: List<Rect> = emptyList()
    ) {
        translations = items
            .distinctBy { item ->
                val bounds = item.sourceBounds
                "${item.translatedText.lowercase().trim()}@${bounds.centerX() / 24},${bounds.centerY() / 24}"
            }
            .sortedWith(
                compareByDescending<TranslationItem> { it.priority }
                    .thenBy { it.sourceBounds.top }
                    .thenBy { it.sourceBounds.left }
            )
            .take(MAX_LAYOUT_CANDIDATES)
        sourceWidth = max(1, capturedScreenWidth)
        sourceHeight = max(1, capturedScreenHeight)
        avoidanceAreas = areasToAvoid.map { area -> Rect(area) }
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
        val anchors = translations.associateWith { item ->
            item.sourceBounds.toScaledRect(scaleX, scaleY)
        }
        val occupied = avoidanceAreas.mapTo(mutableListOf()) { rect ->
            rect.toScaledRect(scaleX, scaleY)
        }

        var drawnCount = 0
        for (item in translations) {
            if (drawnCount >= MAX_VISIBLE_LABELS) break
            val anchor = anchors.getValue(item)
            val otherAnchors = anchors
                .filterKeys { candidate -> candidate !== item }
                .values
                .toList()

            val drawnInline = item.presentation == TranslationPresentation.INLINE &&
                drawInlineTranslation(
                    canvas = canvas,
                    item = item,
                    anchor = anchor,
                    otherAnchors = otherAnchors,
                    occupied = occupied
                )

            val drawn = drawnInline ||
                drawCalloutTranslation(
                    canvas = canvas,
                    item = item,
                    anchor = anchor,
                    allAnchors = anchors.values.toList(),
                    occupied = occupied
                )
            if (drawn) drawnCount += 1
        }
    }

    private fun drawInlineTranslation(
        canvas: Canvas,
        item: TranslationItem,
        anchor: RectF,
        otherAnchors: List<RectF>,
        occupied: MutableList<RectF>
    ): Boolean {
        val horizontalPadding = dp(6f)
        val verticalPadding = dp(3.5f)
        val outerMargin = dp(5f)
        val collisionGap = dp(2f)
        val radius = dp(5f)
        val maxLabelWidth = min(width * 0.72f, max(dp(132f), anchor.width() * 1.55f))

        textPaint.textSize = max(dp(11f), anchor.height() * 0.68f)
            .coerceIn(sp(10f), sp(14f))
        textPaint.color = item.foregroundColor
        textPaint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.BOLD
        )

        val desiredWidth = textPaint.measureText(item.translatedText) + 2 * horizontalPadding
        val labelWidth = max(anchor.width() + dp(4f), desiredWidth)
            .coerceAtMost(maxLabelWidth)
        val layoutWidth = max(1, (labelWidth - 2 * horizontalPadding).toInt())
        val textLayout = createTextLayout(
            text = item.translatedText,
            width = layoutWidth,
            maxLines = 2,
            alignment = Layout.Alignment.ALIGN_CENTER
        )
        val labelHeight = max(anchor.height() + dp(2f), textLayout.height + 2 * verticalPadding)
        val labelRect = centeredRect(anchor, labelWidth, labelHeight, outerMargin)

        if (labelRect.overlaps(occupied, collisionGap)) return false
        if (labelRect.overlaps(otherAnchors, collisionGap)) return false

        backgroundPaint.color = withAlpha(item.backgroundColor, INLINE_BACKGROUND_ALPHA)
        borderPaint.color = contrastBorderColor(item.backgroundColor)
        canvas.drawRoundRect(labelRect, radius, radius, backgroundPaint)
        canvas.drawRoundRect(labelRect, radius, radius, borderPaint)

        canvas.save()
        canvas.translate(
            labelRect.left + horizontalPadding,
            labelRect.top + (labelRect.height() - textLayout.height) / 2f
        )
        textLayout.draw(canvas)
        canvas.restore()

        occupied += RectF(labelRect)
        return true
    }

    private fun drawCalloutTranslation(
        canvas: Canvas,
        item: TranslationItem,
        anchor: RectF,
        allAnchors: List<RectF>,
        occupied: MutableList<RectF>
    ): Boolean {
        val horizontalPadding = dp(7f)
        val verticalPadding = dp(4f)
        val accentWidth = dp(3f)
        val accentGap = dp(5f)
        val outerMargin = dp(6f)
        val collisionGap = dp(5f)
        val radius = dp(6f)
        val minLabelWidth = dp(92f)
        val maxLabelWidth = min(dp(188f), width * 0.48f)

        textPaint.textSize = max(dp(11f), anchor.height() * 0.62f)
            .coerceIn(sp(10f), sp(12.5f))
        textPaint.color = Color.WHITE
        textPaint.typeface = android.graphics.Typeface.create(
            android.graphics.Typeface.DEFAULT,
            android.graphics.Typeface.NORMAL
        )

        val measuredTextWidth = textPaint.measureText(item.translatedText)
        val labelWidth = max(
            minLabelWidth,
            min(
                maxLabelWidth,
                max(
                    anchor.width(),
                    measuredTextWidth + 2 * horizontalPadding + accentWidth + accentGap
                )
            )
        )
        val layoutWidth = max(
            1,
            (labelWidth - 2 * horizontalPadding - accentWidth - accentGap).toInt()
        )
        val textLayout = createTextLayout(
            text = item.translatedText,
            width = layoutWidth,
            maxLines = 3,
            alignment = Layout.Alignment.ALIGN_NORMAL
        )
        val labelHeight = textLayout.height + 2 * verticalPadding
        val labelRect = findCalloutPosition(
            anchor = anchor,
            labelWidth = labelWidth,
            labelHeight = labelHeight,
            occupied = occupied,
            reservedAnchors = allAnchors,
            outerMargin = outerMargin,
            collisionGap = collisionGap
        ) ?: return false

        drawConnector(canvas, anchor, labelRect)
        backgroundPaint.color = CALLOUT_BACKGROUND_COLOR
        borderPaint.color = CALLOUT_BORDER_COLOR
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

        occupied += RectF(labelRect)
        return true
    }

    private fun createTextLayout(
        text: String,
        width: Int,
        maxLines: Int,
        alignment: Layout.Alignment
    ): StaticLayout = StaticLayout.Builder
        .obtain(text, 0, text.length, textPaint, width)
        .setAlignment(alignment)
        .setIncludePad(false)
        .setMaxLines(maxLines)
        .setEllipsize(TextUtils.TruncateAt.END)
        .build()

    private fun centeredRect(
        anchor: RectF,
        labelWidth: Float,
        labelHeight: Float,
        outerMargin: Float
    ): RectF {
        val maxLeft = max(outerMargin, width - labelWidth - outerMargin)
        val maxTop = max(outerMargin, height - labelHeight - outerMargin)
        val left = (anchor.centerX() - labelWidth / 2f).coerceIn(outerMargin, maxLeft)
        val top = (anchor.centerY() - labelHeight / 2f).coerceIn(outerMargin, maxTop)
        return RectF(left, top, left + labelWidth, top + labelHeight)
    }

    private fun findCalloutPosition(
        anchor: RectF,
        labelWidth: Float,
        labelHeight: Float,
        occupied: List<RectF>,
        reservedAnchors: List<RectF>,
        outerMargin: Float,
        collisionGap: Float
    ): RectF? {
        val maxLeft = max(outerMargin, width - labelWidth - outerMargin)
        val maxTop = max(outerMargin, height - labelHeight - outerMargin)
        val centeredLeft = (anchor.centerX() - labelWidth / 2f)
            .coerceIn(outerMargin, maxLeft)
        val leftAligned = anchor.left.coerceIn(outerMargin, maxLeft)
        val rightAligned = (anchor.right - labelWidth).coerceIn(outerMargin, maxLeft)
        val belowSource = (anchor.bottom + collisionGap).coerceIn(outerMargin, maxTop)
        val aboveSource = (anchor.top - labelHeight - collisionGap)
            .coerceIn(outerMargin, maxTop)
        val rightOfSource = (anchor.right + collisionGap).coerceIn(outerMargin, maxLeft)
        val leftOfSource = (anchor.left - labelWidth - collisionGap)
            .coerceIn(outerMargin, maxLeft)
        val centeredTop = (anchor.centerY() - labelHeight / 2f)
            .coerceIn(outerMargin, maxTop)

        val candidates = listOf(
            RectF(centeredLeft, belowSource, centeredLeft + labelWidth, belowSource + labelHeight),
            RectF(centeredLeft, aboveSource, centeredLeft + labelWidth, aboveSource + labelHeight),
            RectF(leftAligned, belowSource, leftAligned + labelWidth, belowSource + labelHeight),
            RectF(rightAligned, aboveSource, rightAligned + labelWidth, aboveSource + labelHeight),
            RectF(rightOfSource, centeredTop, rightOfSource + labelWidth, centeredTop + labelHeight),
            RectF(leftOfSource, centeredTop, leftOfSource + labelWidth, centeredTop + labelHeight)
        )

        return candidates.firstOrNull { candidate ->
            !candidate.overlaps(occupied, collisionGap) &&
                !candidate.overlaps(reservedAnchors, dp(1.5f))
        }
    }

    private fun drawConnector(canvas: Canvas, anchor: RectF, label: RectF) {
        val endX = anchor.centerX().coerceIn(label.left, label.right)
        val endY = anchor.centerY().coerceIn(label.top, label.bottom)
        canvas.drawLine(anchor.centerX(), anchor.centerY(), endX, endY, connectorPaint)
    }

    private fun Rect.toScaledRect(scaleX: Float, scaleY: Float): RectF = RectF(
        left * scaleX,
        top * scaleY,
        right * scaleX,
        bottom * scaleY
    )

    private fun RectF.overlaps(rectangles: List<RectF>, gap: Float): Boolean {
        val expanded = RectF(this).apply { inset(-gap, -gap) }
        return rectangles.any { rectangle -> RectF.intersects(expanded, rectangle) }
    }

    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun contrastBorderColor(background: Int): Int {
        val luminance = (
            Color.red(background) * 77 +
                Color.green(background) * 150 +
                Color.blue(background) * 29
            ) shr 8
        return if (luminance >= 150) {
            Color.argb(54, 0, 0, 0)
        } else {
            Color.argb(74, 255, 255, 255)
        }
    }

    private fun dp(value: Float): Float =
        value * resources.displayMetrics.density

    private fun sp(value: Float): Float =
        value * resources.displayMetrics.scaledDensity

    private companion object {
        const val MAX_VISIBLE_LABELS = 6
        const val MAX_LAYOUT_CANDIDATES = 8
        const val FADE_IN_DURATION_MS = 120L
        const val INLINE_BACKGROUND_ALPHA = 250
        val CALLOUT_BACKGROUND_COLOR: Int = Color.argb(250, 24, 28, 34)
        val CALLOUT_BORDER_COLOR: Int = Color.argb(76, 255, 255, 255)
    }
}
