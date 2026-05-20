package dev.btclock.widget

/**
 * Geometry + palette for the BTClock acrylic-front PCB frame.
 *
 * Direct port of web/src/lib/wasm/frame.ts in the ws-nostr-publish-go
 * dashboard, which itself mirrors btclock_v4/tools/wasm/render_doc_screens.mjs.
 * Numbers are in millimetres. The renderer multiplies by a single
 * pxPerMm factor derived from the widget's actual pixel box at draw
 * time, so the look matches the dashboard at every widget size.
 *
 * Update both files together when the BTClock hardware changes.
 */
object FrameGeometry {
    const val FRAME_W_MM = 219.6
    const val FRAME_H_MM = 81.25

    const val PANEL_W_MM = 24.0
    const val PANEL_H_MM = 48.0
    const val PANEL_R_MM = 1.5
    const val PANEL_BEZEL_MM = 0.8

    const val SIDE_MARGIN_MM = 5.0

    /** Black gap between the panel cutout edge and the gold ring. */
    const val RING_GAP_MM = 0.4
    const val RING_W_MM = 0.6

    /** Wordmark sits 7.375 mm above the PCB bottom edge. */
    const val WORDMARK_BASELINE_FROM_BOTTOM_MM = 7.375
    const val WORDMARK_FONT_SIZE_MM = 7.5

    /**
     * Spread the leftover horizontal space evenly between the (n-1)
     * gaps after side margins and panel widths are accounted for.
     */
    fun panelGutterMm(n: Int): Double =
        (FRAME_W_MM - 2 * SIDE_MARGIN_MM - n * PANEL_W_MM) / (n - 1)

    data class PanelOriginMm(
        val xMm: Double,
        val yMm: Double,
    )

    fun panelOriginMm(i: Int, n: Int): PanelOriginMm {
        val gutter = panelGutterMm(n)
        return PanelOriginMm(
            xMm = SIDE_MARGIN_MM + i * (PANEL_W_MM + gutter),
            yMm = (FRAME_H_MM - PANEL_H_MM) / 2,
        )
    }
}

/** Two-tone palette for normal and inverted (white-on-black) panels. */
data class PanelPalette(
    val pcb: Int,
    val panelBg: Int,
    val panelInk: Int,
) {
    companion object {
        // ARGB ints, big-endian as Android Color expects.
        private const val PCB = 0xFF0A0A0A.toInt()
        private const val PANEL_BG = 0xFFDADBDE.toInt()
        private const val PANEL_INK = 0xFF101010.toInt()
        const val GOLD_BRIGHT: Int = 0xFFF1C64A.toInt()
        const val GOLD_DEEP: Int = 0xFFD9AA2C.toInt()

        val Normal = PanelPalette(pcb = PCB, panelBg = PANEL_BG, panelInk = PANEL_INK)
        val Inverted = PanelPalette(pcb = PCB, panelBg = PANEL_INK, panelInk = PANEL_BG)

        fun forInverted(inverted: Boolean): PanelPalette = if (inverted) Inverted else Normal
    }
}
