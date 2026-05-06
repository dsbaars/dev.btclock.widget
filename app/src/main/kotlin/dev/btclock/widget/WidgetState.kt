package dev.btclock.widget

import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey

/**
 * Glance app-widget state — the set of values the widget renders from.
 *
 * Glance gives every widget instance its own DataStore-backed state
 * accessible via `currentState<Preferences>()` inside provideGlance.
 * The Worker writes here through `updateAppWidgetState(...)`, then
 * triggers a recomposition with `BTClockWidget.update(...)`.
 *
 * Strings keep the schema independent from kotlinx.serialization —
 * Glance's state encoder doesn't natively know about our domain
 * types, and stuffing them as primitives keeps things small.
 */
object WidgetStateKeys {
    val BlockHeight = longPreferencesKey("block_height")

    /**
     * Currently-rendered price + the ISO code it's quoted in. Stored
     * as cents (×100) so we can keep using the long-typed
     * preference encoder without rounding issues at the widget's
     * one-decimal display precision.
     */
    val PriceCents = longPreferencesKey("price_cents")
    val PriceCurrency = stringPreferencesKey("price_currency")

    val MedianFeeMilli = longPreferencesKey("median_fee_milli")
    val LastFetchEpochMs = longPreferencesKey("last_fetch_ms")
    val LastError = stringPreferencesKey("last_error")

    /**
     * Position in the [WidgetConfig.rotationScreens] cycle. Bumped by
     * [RotationTickReceiver] on every alarm tick; the widget reads it
     * via `currentState<Preferences>()` and picks
     * `cfg.rotationScreens[index % size]`. Stored as Int so a long
     * uptime doesn't overflow it.
     */
    val RotationIndex = intPreferencesKey("rotation_index")
}

/** Decode the Glance state into a domain snapshot. */
fun Preferences.toBackendSnapshot(fallbackCurrency: String): BackendSnapshot {
    val height = this[WidgetStateKeys.BlockHeight].takeIf { it != null && it > 0L }
    val cents = this[WidgetStateKeys.PriceCents]
    val price = if (cents != null && cents > 0L) cents / 100.0 else null
    val ccy = this[WidgetStateKeys.PriceCurrency]?.takeIf { it.isNotBlank() } ?: fallbackCurrency
    val milli = this[WidgetStateKeys.MedianFeeMilli]
    val fee = if (milli != null && milli >= 0L) milli / 1000.0 else null
    return BackendSnapshot(blockHeight = height, price = price, currency = ccy, medianFee = fee)
}
