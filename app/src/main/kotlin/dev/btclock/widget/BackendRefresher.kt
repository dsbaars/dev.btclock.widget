package dev.btclock.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Single owner of the "fetch backend → write Glance state → redraw"
 * pipeline. Used by:
 *
 *   - [RefreshWorker]          (periodic 15-min safety net)
 *   - [RotationTickReceiver]   (every rotation tick — typically 1 min)
 *   - [AdvanceScreenAction]    (every user tap)
 *
 * Why factor this out: the Worker, the AlarmManager-driven receiver,
 * and the Glance ActionCallback all need the same sequence. Inlining
 * meant the tap and tick paths advanced the rotation index using
 * stale Glance state — block height could be out-of-date by up to
 * 15 minutes (the WorkManager periodic floor) regardless of how
 * often the user cycled screens.
 *
 * Thread/coroutine model: [refreshNow] is `suspend`, expected to be
 * called from a coroutine. It does a single HTTP round-trip
 * (~hundreds of ms on btclock.dev), no further blocking work.
 *
 * If the fetch fails, the previous state stays — we deliberately do
 * not clear it. A one-off network blip shouldn't blank the screen
 * mid-rotation; the next tick will retry.
 */
object BackendRefresher {
    /**
     * Fetch the snapshot and write it to every pinned widget instance.
     * The optional [onlyId] parameter limits the write to a single
     * widget (used by the tap action — only the widget the user
     * touched should be refreshed; other instances stay on their
     * own clocks).
     */
    suspend fun refreshNow(context: Context, onlyId: GlanceId? = null) {
        val cfg = Prefs.read(context)
        val api = BackendApi(cfg.backendUrl)
        val snapshot =
            try {
                api.fetchSnapshot(cfg.currency)
            } finally {
                api.close()
            }

        val manager = GlanceAppWidgetManager(context)
        val ids = if (onlyId != null) listOf(onlyId) else manager.getGlanceIds(BTClockWidget::class.java)
        for (id in ids) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply {
                    snapshot.blockHeight?.let { set(WidgetStateKeys.BlockHeight, it) }
                    val newCcy = snapshot.currency
                    val storedCcy = this[WidgetStateKeys.PriceCurrency]
                    if (snapshot.price != null) {
                        set(WidgetStateKeys.PriceCents, (snapshot.price * 100).toLong())
                        set(WidgetStateKeys.PriceCurrency, newCcy)
                    } else if (storedCcy != null && storedCcy != newCcy) {
                        // User switched to a currency the upstream node
                        // doesn't expose — clear the stale value so we
                        // don't render it under the new symbol.
                        // Transient fetch errors with the same currency
                        // fall through and leave the previous good
                        // price in place, matching the class-level
                        // contract for blockHeight and medianFee.
                        remove(WidgetStateKeys.PriceCents)
                        set(WidgetStateKeys.PriceCurrency, newCcy)
                    }
                    snapshot.medianFee?.let { set(WidgetStateKeys.MedianFeeMilli, (it * 1000).toLong()) }
                    set(WidgetStateKeys.LastFetchEpochMs, System.currentTimeMillis())
                    remove(WidgetStateKeys.LastError)
                }
            }
        }
    }
}
