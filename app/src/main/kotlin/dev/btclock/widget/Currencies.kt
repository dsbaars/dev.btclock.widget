package dev.btclock.widget

/**
 * Currency rendering helpers — the symbol that goes on panel 0 of the
 * price screen, and the canonical list shown in the Settings picker.
 *
 * The firmware has a much longer table (lnbits + Yadio extras = 162
 * codes); we expose a sane subset by default and fall back to the ISO
 * code when no symbol is mapped, so a user pointed at a node with
 * extra currencies still gets something readable.
 */
object Currencies {
    /** Default offering in the Settings picker. Order matters — first entry wins as the fallback. */
    val OFFERED: List<String> =
        listOf(
            "USD",
            "EUR",
            "GBP",
            "JPY",
            "CAD",
            "AUD",
            "CHF",
            "CNY",
            "INR",
            "BRL",
            "MXN",
            "RUB",
            "ZAR",
            "SEK",
            "NOK",
            "DKK",
            "PLN",
            "TRY",
        )

    /**
     * Single-glyph symbols. Keep these to one printable char each so
     * the panel-0 cell can fit the symbol at the same point size as
     * the digits in the other cells. Anything multi-char (e.g. "kr",
     * "Fr") falls through to the ISO-code path below where the whole
     * code is stacked.
     */
    private val SYMBOLS: Map<String, String> =
        mapOf(
            "USD" to "$",
            "EUR" to "€",
            "GBP" to "£",
            "JPY" to "¥",
            "CNY" to "¥",
            "AUD" to "$",
            "CAD" to "$",
            "MXN" to "$",
            "BRL" to "R", // "R$" is two chars; pick the leading R for the cell, ISO fallback shows full code
            "INR" to "₹",
            "RUB" to "₽",
            "TRY" to "₺",
            "ZAR" to "R",
            "CHF" to "₣", // historic glyph; CHF code is also fine via fallback
        )

    /**
     * Symbol if we have a single-glyph one; otherwise null. Callers
     * fall back to stacking the ISO code (e.g. "S/E/K") on the panel
     * when no symbol is registered.
     */
    fun symbol(code: String): String? = SYMBOLS[code.uppercase()]
}
