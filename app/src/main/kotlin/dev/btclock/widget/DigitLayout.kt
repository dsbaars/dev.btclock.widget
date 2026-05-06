package dev.btclock.widget

import java.util.Locale
import kotlin.math.abs

/**
 * Per-screen layout: how each on-device screen splits its content
 * across the panel row.
 *
 * Direct study of the canonical doc renders in
 * `btclock_v4/docs/img/screens/`:
 *
 *   block_height       label, then digits right-justified across rest
 *   btc_price          label, blanks, $ sign, digits right-justified
 *   moscow_time        label, blanks, sat-glyph, digits right-justified
 *   block_fee_rate     label, blanks, digits, rotated "sat/vB" trailing label
 *   halving_countdown  label, then digits right-justified across rest
 *   market_cap         label, blanks, $, "1", ".", "9", "0", "T"  (char per panel)
 *   bitcoin_supply     label, then "1", "9", ".", "9", "5", "M"   (char per panel)
 *
 * Two reusable shapes:
 *
 *   - "main run":     a sequence of one-char-per-panel cells (digits,
 *                     currency symbols, decimal points, suffix
 *                     letters), right-justified into the available
 *                     slots between the label and any trailing label.
 *   - "trailing label": optional rotated split-label on the LAST
 *                       panel (currently only the fee-rate "sat/vB").
 */
enum class Screen { BlockHeight, Price, MoscowTime, FeeRate, Halving, MarketCap, Supply }

/** A single panel's content. */
data class PanelText(
    val text: String = "",
    val style: Style = Style.Empty,
    /** Top word for [Style.RotatedSplitLabel]. */
    val labelTop: String = "",
    /** Bottom word for [Style.RotatedSplitLabel]. */
    val labelBottom: String = "",
) {
    enum class Style {
        Empty,
        /** Big upright digit (or "."), Antonio. */
        Digit,
        /** Big upright currency / unit symbol, Antonio (matches firmware). */
        Symbol,
        /** Sat-glyph U+E000 from Satoshi Symbol font. */
        SatGlyph,
        /** Two stacked rotated words separated by a horizontal line, Oswald-bold. */
        RotatedSplitLabel,
    }

    companion object {
        val Empty = PanelText()
        fun digit(c: Char) = PanelText(text = c.toString(), style = Style.Digit)
        fun digit(s: String) = PanelText(text = s, style = Style.Digit)
        fun symbol(s: String) = PanelText(text = s, style = Style.Symbol)
        val SatGlyph = PanelText(text = "", style = Style.SatGlyph)
        fun label(top: String, bottom: String) =
            PanelText(style = Style.RotatedSplitLabel, labelTop = top, labelBottom = bottom)
    }
}

object DigitLayout {

    /** Build the per-panel array for [screen] with [panelCount] panels. */
    fun layoutFor(screen: Screen, panelCount: Int, snapshot: BackendSnapshot): List<PanelText> {
        val n = panelCount
        return when (screen) {
            Screen.BlockHeight -> buildLayout(
                n = n,
                label = PanelText.label("BLOCK", "HEIGHT"),
                main = digitsOf(snapshot.blockHeight),
            )

            Screen.Price -> buildLayout(
                n = n,
                label = PanelText.label("BTC", snapshot.currency),
                main = priceMain(snapshot.price, snapshot.currency, slotsAvailable = n - 1),
            )

            Screen.MoscowTime -> buildLayout(
                n = n,
                label = PanelText.label("MSCW", "TIME"),
                main = moscowMain(snapshot.price),
            )

            Screen.FeeRate -> buildLayout(
                n = n,
                label = PanelText.label("FEE", "RATE"),
                main = feeMain(snapshot.medianFee),
                trailingLabel = PanelText.label("sat", "vB"),
            )

            Screen.Halving -> buildLayout(
                n = n,
                label = PanelText.label("HAL", "VING"),
                main = digitsOf(blocksUntilHalving(snapshot.blockHeight)),
            )

            Screen.MarketCap -> buildLayout(
                n = n,
                label = PanelText.label(snapshot.currency, "MCAP"),
                main = marketCapMain(snapshot.blockHeight, snapshot.price, snapshot.currency, slotsAvailable = n - 1),
            )

            Screen.Supply -> buildLayout(
                n = n,
                label = PanelText.label("BTC", "SUPPLY"),
                main = supplyMain(snapshot.blockHeight),
            )
        }
    }

    /**
     * Compose label (panel 0) + right-justified main run + optional
     * trailing rotated label (last panel) into a fixed-size cell list.
     * Empty slots fall in the middle as needed.
     */
    private fun buildLayout(
        n: Int,
        label: PanelText,
        main: List<PanelText>,
        trailingLabel: PanelText? = null,
    ): List<PanelText> {
        val cells = MutableList(n) { PanelText.Empty }
        cells[0] = label

        val lastMainIdx = if (trailingLabel != null) {
            cells[n - 1] = trailingLabel
            n - 2
        } else {
            n - 1
        }
        val mainSlots = lastMainIdx - 0  // slots 1..lastMainIdx inclusive
        val trimmed = if (main.size > mainSlots) main.takeLast(mainSlots) else main
        val firstMainIdx = lastMainIdx - trimmed.size + 1
        for ((i, cell) in trimmed.withIndex()) {
            cells[firstMainIdx + i] = cell
        }
        return cells
    }

    private fun digitsOf(value: Long?): List<PanelText> {
        if (value == null || value < 0) return emptyList()
        return value.toString().map { PanelText.digit(it) }
    }

    /** "$95432" → [$, 9, 5, 4, 3, 2]. Falls back to plain digits when the currency has no glyph. */
    private fun priceMain(price: Double?, currency: String, slotsAvailable: Int): List<PanelText> {
        if (price == null) return emptyList()
        val intPart = price.toLong().coerceAtLeast(0).toString()
        val symbol = Currencies.symbol(currency)
        val fitsWithSymbol = (intPart.length + (if (symbol != null) 1 else 0)) <= slotsAvailable
        if (!fitsWithSymbol) {
            // Collapse to "$X.YZk" style. Slot budget is tight so just
            // drop the symbol and show "${k}k".
            val k = (price / 1000.0).toLong().toString()
            return ("${k}k").map { PanelText.digit(it) }
        }
        val out = ArrayList<PanelText>(intPart.length + 1)
        if (symbol != null) out += PanelText.symbol(symbol)
        intPart.forEach { out += PanelText.digit(it) }
        return out
    }

    /**
     * Sats per fiat unit, with the firmware's sat-glyph prefix. Result
     * for "$95432 USD" is "ϟ 1048" (1e8/95432 ≈ 1048 sats/$).
     */
    private fun moscowMain(fiatPrice: Double?): List<PanelText> {
        if (fiatPrice == null || fiatPrice <= 0) return emptyList()
        val sats = (100_000_000.0 / fiatPrice).toLong().coerceAtLeast(0)
        val out = ArrayList<PanelText>()
        out += PanelText.SatGlyph
        sats.toString().forEach { out += PanelText.digit(it) }
        return out
    }

    private fun feeMain(fee: Double?): List<PanelText> {
        if (fee == null) return emptyList()
        // Display with one decimal place so users see precision they'd
        // otherwise miss between integer transitions (e.g. 12.4 → 12.6
        // both render as "12" in the integer layout). Maps to the
        // firmware's `blockFeeDec=true` render
        // (block_fee_rate_decimal.png) with the decimal point sharing
        // a panel cell. The Go ws-node's /api/lastfee returns the
        // raw float; widening to one decimal here does NOT need a
        // separate v2-WS feerate2 subscription on the widget side
        // because we already have the precision in the REST snapshot.
        val clamped = fee.coerceAtLeast(0.0)
        val formatted = String.format(Locale.ROOT, "%.1f", clamped)
        return formatted.map { PanelText.digit(it) }
    }

    /** Blocks until next halving (210k epoch). */
    private fun blocksUntilHalving(height: Long?): Long? {
        if (height == null) return null
        val nextHalving = ((height / 210_000) + 1) * 210_000
        return (nextHalving - height).coerceAtLeast(0)
    }

    /** "$1.90T" → [$, 1, ., 9, 0, T]. */
    private fun marketCapMain(
        height: Long?,
        price: Double?,
        currency: String,
        slotsAvailable: Int,
    ): List<PanelText> {
        if (height == null || price == null) return emptyList()
        val supply = bitcoinSupplyAt(height)
        val cap = supply * price
        val (mantissa, suffix) = humanScale(cap)
        val mantissaStr = formatMantissaForSlots(
            mantissa,
            slotsForMain = slotsAvailable - (if (Currencies.symbol(currency) != null) 2 else 1),
        )
        val out = ArrayList<PanelText>()
        Currencies.symbol(currency)?.let { out += PanelText.symbol(it) }
        mantissaStr.forEach { out += if (it == '.') PanelText(text = ".", style = PanelText.Style.Digit) else PanelText.digit(it) }
        out += PanelText.symbol(suffix)
        return out
    }

    /** "19.95M" → [1, 9, ., 9, 5, M]. */
    private fun supplyMain(height: Long?): List<PanelText> {
        if (height == null) return emptyList()
        val supply = bitcoinSupplyAt(height)
        val (mantissa, suffix) = humanScale(supply)
        val mantissaStr = formatMantissaForSlots(mantissa, slotsForMain = 5)
        val out = ArrayList<PanelText>()
        mantissaStr.forEach { out += PanelText.digit(it) }
        out += PanelText.symbol(suffix)
        return out
    }

    /**
     * Scale to (mantissa, suffix) where suffix is one of "T" (1e12),
     * "B" (1e9), "M" (1e6), "K" (1e3), or "" (raw). Mirrors the
     * firmware's `formatHumanReadable` output.
     */
    private fun humanScale(value: Double): Pair<Double, String> {
        val abs = abs(value)
        return when {
            abs >= 1e12 -> value / 1e12 to "T"
            abs >= 1e9 -> value / 1e9 to "B"
            abs >= 1e6 -> value / 1e6 to "M"
            abs >= 1e3 -> value / 1e3 to "K"
            else -> value to ""
        }
    }

    /**
     * Format mantissa as "1", "1.9", "19.95", "199.5" etc. so the
     * total digit count fits the available slots. Always emits at
     * most one decimal point.
     *
     * Locale.ROOT is critical here — without it the "%.Nf" specifier
     * picks the system locale's decimal separator, so on a Dutch /
     * German / French phone the market-cap screen renders "$1,65T"
     * instead of "$1.65T". Bitcoin numbers are not locale text;
     * Locale.ROOT is the documented "neutral, programmatic"
     * formatter that always uses the period.
     */
    private fun formatMantissaForSlots(mantissa: Double, slotsForMain: Int): String {
        if (slotsForMain <= 0) return ""
        val intPart = mantissa.toLong().toString()
        if (intPart.length >= slotsForMain) return intPart.take(slotsForMain)
        // Room for "." plus some fractional digits.
        val fracSlots = slotsForMain - intPart.length - 1
        if (fracSlots <= 0) return intPart
        val multiplier = Math.pow(10.0, fracSlots.toDouble())
        val rounded = Math.round(mantissa * multiplier) / multiplier
        return String.format(Locale.ROOT, "%.${fracSlots}f", rounded)
    }

    /**
     * Cumulative BTC issued at [height]. Sums halving epochs from
     * genesis: 50 BTC × 210k blocks, then 25, 12.5, … . Same recurrence
     * the firmware uses to render the supply screen.
     */
    fun bitcoinSupplyAt(height: Long): Double {
        if (height < 0) return 0.0
        var supply = 0.0
        var subsidy = 50.0
        var remaining = height + 1 // include block 0
        var epoch = 0
        while (remaining > 0 && epoch < 64 && subsidy > 0.0) {
            val blocksThisEpoch = minOf(remaining, 210_000L)
            supply += blocksThisEpoch * subsidy
            remaining -= blocksThisEpoch
            subsidy /= 2.0
            epoch++
        }
        return supply
    }
}
