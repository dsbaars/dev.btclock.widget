package dev.btclock.widget

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import androidx.core.content.res.ResourcesCompat

/**
 * Resolve a font resource by name at runtime. Returns 0 (no resource)
 * when the file isn't present, which is the documented "use fallback"
 * signal for ResourcesCompat.getFont. Looking up by string lets the
 * project compile cleanly even before the font assets are dropped in.
 */
private fun fontIdByName(context: Context, name: String): Int =
    context.resources.getIdentifier(name, "font", context.packageName)

/**
 * Renders the BTClock acrylic-front PCB into a Bitmap.
 *
 * Visual order (matches `btclock_v4/tools/wasm/render_doc_screens.mjs`):
 *
 *   1. Black PCB soldermask
 *   2. Light-grey panel windows + gold ENIG silkscreen rings
 *   3. Inner panel content (digits / symbols / rotated split label)
 *   4. "BTClock" italic wordmark below the panel row
 *
 * The rotated split-label on panel 0 is the one new visual primitive
 * the dashboard's WASM doesn't expose to us as TS — implemented
 * directly here against the doc-render reference images.
 */
class FrameRenderer(
    private val context: Context,
    private val panelCount: Int = 7,
    private val inverted: Boolean = false,
    private val digitFont: DigitFont = DigitFont.Antonio,
) {
    private val palette = PanelPalette.forInverted(inverted)

    private fun loadFont(name: String, fallbackStyle: Int): Typeface {
        val id = fontIdByName(context, name)
        val resolved = if (id != 0) runCatching { ResourcesCompat.getFont(context, id) }.getOrNull() else null
        return resolved ?: Typeface.create(Typeface.SANS_SERIF, fallbackStyle)
    }

    /**
     * Typefaces match the firmware:
     *   digits / symbols → Antonio (default `fontFamily=0`)
     *   labels           → Oswald-bold (separate AppFonts slot for the
     *                      panel-0 rotated description, not affected
     *                      by the user's digit-face pick on the device)
     *   sat glyph        → SatoshiSymbol.ttf, U+E000
     */
    private val digitTypeface: Typeface by lazy { loadFont(digitFont.resourceName, Typeface.NORMAL) }
    private val labelTypeface: Typeface by lazy { loadFont("oswald_bold", Typeface.BOLD) }
    private val satTypeface: Typeface by lazy { loadFont("satoshi_symbol", Typeface.NORMAL) }

    private val pcbPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.pcb
            style = Paint.Style.FILL
        }

    private val panelFillPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.panelBg
            style = Paint.Style.FILL
        }

    private val ringPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeJoin = Paint.Join.ROUND
            strokeCap = Paint.Cap.ROUND
        }

    private val wordmarkPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
        }

    private val digitPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.panelInk
            textAlign = Paint.Align.CENTER
            typeface = digitTypeface
        }

    private val labelPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.panelInk
            textAlign = Paint.Align.CENTER
            typeface = labelTypeface
        }

    private val satPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.panelInk
            textAlign = Paint.Align.CENTER
            typeface = satTypeface
        }

    private val separatorPaint =
        Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = palette.panelInk
            style = Paint.Style.FILL
        }

    private val tmpBounds = Rect()

    /** Cap-glyph used to derive a shared baseline for digit-row panels. */
    private val REFERENCE_CAP = "8"

    /**
     * Render at [widthPx]×[heightPx]. Aspect-fit scaling: pxPerMm is
     * the smaller of the per-axis scales so the BTClock's native
     * 2.7:1 frame always fits fully inside the cell with PCB-black
     * letterbox bars on whichever axis has slack. Width-fit-with-
     * overflow was the original strategy ("a bit of overflow is
     * okay"), but on Nova / Niagara the row-style 5×1 cells can be
     * ~6:1 or wider, which made the vertical overflow clip the digit
     * panels themselves. Users on those launchers should resize the
     * widget to 4×1 (closer to the BTClock aspect) for a fuller fit —
     * see widget_description.
     */
    fun render(widthPx: Int, heightPx: Int, cells: List<PanelText>): Bitmap {
        require(cells.size == panelCount) {
            "cell count ${cells.size} != panelCount $panelCount"
        }

        val w = widthPx.coerceAtLeast(1)
        val h = heightPx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)

        // Fill background with the PCB colour first so any
        // letterbox / overflow region reads as "extension of the PCB"
        // rather than the widget cell's underlying surface.
        canvas.drawColor(palette.pcb)

        val pxPerMm =
            minOf(
                w.toDouble() / FrameGeometry.FRAME_W_MM,
                h.toDouble() / FrameGeometry.FRAME_H_MM,
            )
        val frameWPx = FrameGeometry.FRAME_W_MM * pxPerMm
        val frameHPx = FrameGeometry.FRAME_H_MM * pxPerMm
        val ox = (w - frameWPx) / 2.0
        val oy = (h - frameHPx) / 2.0

        canvas.save()
        canvas.translate(ox.toFloat(), oy.toFloat())

        drawPanels(canvas, pxPerMm, cells)
        drawWordmark(canvas, pxPerMm)

        canvas.restore()
        return bitmap
    }

    private fun drawPanels(canvas: Canvas, pxPerMm: Double, cells: List<PanelText>) {
        val gold =
            LinearGradient(
                0f,
                0f,
                0f,
                (FrameGeometry.PANEL_H_MM * pxPerMm).toFloat(),
                PanelPalette.GOLD_BRIGHT,
                PanelPalette.GOLD_DEEP,
                Shader.TileMode.CLAMP,
            )
        ringPaint.shader = gold

        val ringW = (FrameGeometry.RING_W_MM * pxPerMm).toFloat()
        val ringOffsetMm = FrameGeometry.RING_W_MM / 2 + FrameGeometry.RING_GAP_MM
        ringPaint.strokeWidth = ringW

        val panelRect = RectF()

        // Pre-compute one shared label text size across the whole
        // row. Per-label sizing made the trailing "sat / vB" cell on
        // the fee-rate screen render much larger than the panel-0
        // "FEE / RATE" cell — short words fit at a bigger size when
        // each panel optimises independently. Sharing the smallest
        // fit means every split-label cell paints with a uniform
        // typeface size, matching btclock_v4's single-pass label
        // rendering.
        val sharedLabelSize = computeSharedLabelSize(cells, pxPerMm)

        for (i in 0 until panelCount) {
            val origin = FrameGeometry.panelOriginMm(i, panelCount)
            val xPx = origin.xMm * pxPerMm
            val yPx = origin.yMm * pxPerMm
            val wPx = FrameGeometry.PANEL_W_MM * pxPerMm
            val hPx = FrameGeometry.PANEL_H_MM * pxPerMm

            panelRect.set(xPx.toFloat(), yPx.toFloat(), (xPx + wPx).toFloat(), (yPx + hPx).toFloat())
            val panelR = (FrameGeometry.PANEL_R_MM * pxPerMm).toFloat()
            canvas.drawRoundRect(panelRect, panelR, panelR, panelFillPaint)

            val ringPx = (ringOffsetMm * pxPerMm).toFloat()
            val ringRect =
                RectF(
                    panelRect.left - ringPx,
                    panelRect.top - ringPx,
                    panelRect.right + ringPx,
                    panelRect.bottom + ringPx,
                )
            val ringR = ((FrameGeometry.PANEL_R_MM + ringOffsetMm) * pxPerMm).toFloat()
            canvas.drawRoundRect(ringRect, ringR, ringR, ringPaint)

            val bezelPx = (FrameGeometry.PANEL_BEZEL_MM * pxPerMm).toFloat()
            val innerRect =
                RectF(
                    panelRect.left + bezelPx,
                    panelRect.top + bezelPx,
                    panelRect.right - bezelPx,
                    panelRect.bottom - bezelPx,
                )
            drawPanelContent(canvas, innerRect, cells[i], sharedLabelSize)
        }
    }

    /**
     * Walk every cell, find the largest text size that fits each
     * split-label half rect, take the min across all of them. Each
     * panel has identical inner dimensions so the half-rect is
     * derived once from the panel constants.
     */
    private fun computeSharedLabelSize(cells: List<PanelText>, pxPerMm: Double): Float {
        val innerWMm = FrameGeometry.PANEL_W_MM - 2 * FrameGeometry.PANEL_BEZEL_MM
        val innerHMm = FrameGeometry.PANEL_H_MM - 2 * FrameGeometry.PANEL_BEZEL_MM
        val innerWPx = (innerWMm * pxPerMm).toFloat()
        val innerHPx = (innerHMm * pxPerMm).toFloat()
        val halfRect = RectF(0f, 0f, innerWPx, innerHPx / 2f - innerHPx * 0.04f)
        var smallest = Float.POSITIVE_INFINITY
        for (cell in cells) {
            if (cell.style != PanelText.Style.RotatedSplitLabel) continue
            for (word in arrayOf(cell.labelTop, cell.labelBottom)) {
                if (word.isBlank()) continue
                val fit = measureHorizontalFitSize(word, halfRect)
                if (fit < smallest) smallest = fit
            }
        }
        return if (smallest.isFinite()) smallest else innerHPx * 0.30f
    }

    private fun drawPanelContent(canvas: Canvas, inner: RectF, content: PanelText, sharedLabelSize: Float) {
        when (content.style) {
            PanelText.Style.Empty -> { /* blank panel, nothing to draw */ }
            PanelText.Style.Digit -> drawUprightGlyph(canvas, inner, content.text, digitPaint)
            PanelText.Style.Symbol -> drawUprightGlyph(canvas, inner, content.text, digitPaint)
            PanelText.Style.SatGlyph -> drawUprightGlyph(canvas, inner, "", satPaint)
            PanelText.Style.RotatedSplitLabel ->
                drawRotatedSplitLabel(canvas, inner, content.labelTop, content.labelBottom, sharedLabelSize)
        }
    }

    /**
     * Single upright glyph (digit, decimal point, currency / unit
     * symbol, suffix letter).
     *
     * Sizing + placement match the firmware's doc renders
     * (`market_cap.png`, `bitcoin_supply.png`): every char in the row
     * sits on a **shared baseline**, so "$1.65T" reads with the
     * period sitting low (at the baseline) and the digits / cap
     * glyphs centred vertically in the panel. The earlier per-glyph
     * visual-centring approach put the period in the middle of the
     * panel, which looked floating.
     *
     * Implementation:
     *
     *   1. Pick text size based purely on panel HEIGHT, not glyph
     *      bounds — keeps every panel in the same row at the same
     *      size regardless of which char it carries.
     *   2. Shrink only when the glyph is too WIDE (e.g. "M", "T", "%").
     *   3. Position baseline at `inner.centerY() + capHeight/2`,
     *      where capHeight is measured off a representative cap glyph
     *      ("8"). Caps centre on the panel; dots and commas drop to
     *      the baseline; descenders end up below centre. Matches the
     *      firmware's natural-baseline look.
     */
    private fun drawUprightGlyph(canvas: Canvas, inner: RectF, text: String, paint: Paint) {
        if (text.isBlank()) return

        // Base size: ~50 % of the panel inner height. Antonio caps at
        // this size occupy roughly 35 % of the panel — visually
        // matches the doc renders without crowding the gold ring.
        var size = inner.height() * 0.50f
        paint.textSize = size

        // Width-only shrink fallback for letters wider than digits.
        val maxWidth = inner.width() * 0.78f
        val measuredWidth = paint.measureText(text)
        if (measuredWidth > maxWidth) {
            size *= maxWidth / measuredWidth
            paint.textSize = size
        }

        // Baseline derived from a reference cap so every panel in a
        // row lines up. We use "8" — every face we ship (Antonio,
        // Oswald, sat-glyph fallback) defines it as a full-cap-height
        // numeral, so the resulting baseline is consistent.
        paint.getTextBounds(REFERENCE_CAP, 0, REFERENCE_CAP.length, tmpBounds)
        val capHeight = -tmpBounds.top.toFloat() // bounds.top is negative for caps
        val baseline = inner.centerY() + capHeight / 2f
        canvas.drawText(text, inner.centerX(), baseline, paint)
    }

    /**
     * Two horizontal words separated by a thin black line. The panel-0
     * description on every BTClock screen.
     *
     * Originally drawn rotated -90° to match the firmware doc render
     * (which uses verticalDesc=true with stb_truetype-rotated text);
     * we switched to upright horizontal text per user preference —
     * easier to read in a narrow phone-widget panel where each word
     * fits naturally across the panel width without rotation.
     *
     * Layout:
     *   ┌─────────┐
     *   │  BLOCK  │  ← top word, horizontal, fills upper half
     *   ├─────────┤  ← thin separator at panel mid-height
     *   │ HEIGHT  │  ← bottom word, horizontal, fills lower half
     *   └─────────┘
     */
    private fun drawRotatedSplitLabel(
        canvas: Canvas,
        inner: RectF,
        top: String,
        bottom: String,
        sharedSize: Float,
    ) {
        if (top.isBlank() && bottom.isBlank()) return

        val midY = inner.centerY()
        // Tiny gap above and below the separator so the words don't
        // crowd it.
        val gap = inner.height() * 0.04f
        val sepThickness = (inner.height() * 0.025f).coerceAtLeast(2f)

        val topRect = RectF(inner.left, inner.top, inner.right, midY - gap)
        val botRect = RectF(inner.left, midY + gap, inner.right, inner.bottom)

        // Use the row-wide text size pre-computed in
        // computeSharedLabelSize. Every split-label cell on the same
        // screen prints at this single size so e.g. fee-rate's
        // "FEE / RATE" (panel 0) and "sat / vB" (panel 6) read as one
        // visual family rather than two arbitrarily-sized labels.
        labelPaint.textSize = sharedSize

        // Push each word toward the separator instead of centring in
        // its half — earlier centred-in-half placement left a wide
        // gap between word and separator that read as awkward whitespace.
        if (top.isNotBlank()) drawHorizontalWord(canvas, topRect, top, biasTowardBottom = true)
        if (bottom.isNotBlank()) drawHorizontalWord(canvas, botRect, bottom, biasTowardBottom = false)

        // Separator line — thin filled rect, ~70 % of the panel
        // width, centred.
        val sepHalf = inner.width() * 0.35f
        canvas.drawRect(
            inner.centerX() - sepHalf,
            midY - sepThickness / 2f,
            inner.centerX() + sepHalf,
            midY + sepThickness / 2f,
            separatorPaint,
        )
    }

    /** Largest text size at which [word] fits horizontally inside [rect]. */
    private fun measureHorizontalFitSize(word: String, rect: RectF): Float {
        if (word.isEmpty()) return Float.POSITIVE_INFINITY
        val padX = rect.width() * 0.10f
        // Tight vertical pad — text needs to fit, but we don't want
        // generous empty space around it because the bias-toward-
        // separator placement below counts on the text spanning most
        // of the half-rect's height.
        val padY = rect.height() * 0.06f
        return fitTextSize(
            labelPaint,
            word,
            maxW = rect.width() - 2 * padX,
            maxH = rect.height() - 2 * padY,
        )
    }

    /**
     * Draw [word] inside [rect], pulling it toward the separator-side
     * edge of the rect rather than centring vertically.
     *
     *   biasTowardBottom = true   → word's bottom sits just above the
     *                                rect's bottom edge (TOP half: word
     *                                hugs the separator below it)
     *   biasTowardBottom = false  → word's top sits just below the
     *                                rect's top edge (BOTTOM half: word
     *                                hugs the separator above it)
     *
     * The bias gap is fixed at ~6 % of the rect height which, on a
     * 7-panel BTClock at typical phone-widget sizes, leaves about
     * 1 mm of clear space between the word and the separator.
     */
    private fun drawHorizontalWord(
        canvas: Canvas,
        rect: RectF,
        word: String,
        biasTowardBottom: Boolean,
    ) {
        labelPaint.getTextBounds(word, 0, word.length, tmpBounds)
        val visualCentre = (tmpBounds.top + tmpBounds.bottom) / 2f
        val visualHeight = (tmpBounds.bottom - tmpBounds.top).toFloat()
        val edgeGap = rect.height() * 0.06f
        val centerY =
            if (biasTowardBottom) {
                rect.bottom - edgeGap - visualHeight / 2f
            } else {
                rect.top + edgeGap + visualHeight / 2f
            }
        val baseline = centerY - visualCentre
        canvas.drawText(word, rect.centerX(), baseline, labelPaint)
    }

    /**
     * Find the largest text size at which [text] fits inside [maxW]×[maxH]
     * with a small bisection. Mutates [paint] freely; returns the
     * found size in pixels.
     */
    private fun fitTextSize(paint: Paint, text: String, maxW: Float, maxH: Float): Float {
        if (text.isEmpty() || maxW <= 0 || maxH <= 0) return 1f
        var lo = 4f
        var hi = maxOf(8f, maxH)
        repeat(14) {
            val mid = (lo + hi) / 2f
            paint.textSize = mid
            val width = paint.measureText(text)
            paint.getTextBounds(text, 0, text.length, tmpBounds)
            val height = (tmpBounds.bottom - tmpBounds.top).toFloat()
            if (width <= maxW && height <= maxH) lo = mid else hi = mid
        }
        return lo
    }

    private fun drawWordmark(canvas: Canvas, pxPerMm: Double) {
        val frameW = (FrameGeometry.FRAME_W_MM * pxPerMm).toFloat()
        val sFontUnitsToMm = FrameGeometry.WORDMARK_FONT_SIZE_MM / Wordmark.UPM
        val sToPx = sFontUnitsToMm * pxPerMm
        val widthMm = Wordmark.ADVANCE * sFontUnitsToMm
        val widthPx = widthMm * pxPerMm

        val path = Wordmark.newPath()
        val m = Matrix()
        val baselinePx = (FrameGeometry.FRAME_H_MM - FrameGeometry.WORDMARK_BASELINE_FROM_BOTTOM_MM) * pxPerMm
        val xPx = (frameW - widthPx) / 2

        m.setScale(sToPx.toFloat(), -sToPx.toFloat())
        m.postTranslate(xPx.toFloat(), baselinePx.toFloat())
        path.transform(m)

        wordmarkPaint.shader =
            LinearGradient(
                0f,
                baselinePx.toFloat() - (FrameGeometry.WORDMARK_FONT_SIZE_MM * pxPerMm).toFloat(),
                0f,
                baselinePx.toFloat(),
                PanelPalette.GOLD_BRIGHT,
                PanelPalette.GOLD_DEEP,
                Shader.TileMode.CLAMP,
            )
        canvas.drawPath(path, wordmarkPaint)
    }
}
