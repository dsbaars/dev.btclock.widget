package dev.btclock.widget

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * Handles a rotation tick from [RotationScheduler]:
 *
 *   1. Read prefs (rotation enabled, which screens cycle, interval)
 *   2. Bump the per-instance rotation index in Glance widget state
 *      (so multiple pinned widgets stay in sync with each other)
 *   3. Trigger a re-render of every widget instance
 *   4. Re-register the next tick — `set()` is one-shot, so we have
 *      to schedule the next interval ourselves
 *
 * Manifest-registered (AndroidManifest.xml) so it survives process
 * cold-start; the alarm fires it directly without the app being open.
 */
class RotationTickReceiver : BroadcastReceiver() {

    /**
     * goAsync() lets a BroadcastReceiver keep its process alive past
     * onReceive() return so the Glance state write + redraw can
     * complete on a background thread. Without this Android may kill
     * the process before the IO is flushed and the widget would skip
     * a tick.
     */
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != RotationScheduler.ACTION) return
        val pendingResult = goAsync()
        scope.launch {
            try {
                tick(context.applicationContext)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private suspend fun tick(context: Context) {
        val cfg = Prefs.read(context)
        if (cfg.rotationMinutes <= 0 || cfg.rotationScreens.size <= 1) {
            // Rotation disabled or only one screen in the cycle —
            // don't bump the index, don't reschedule.
            RotationScheduler.cancel(context)
            return
        }

        // Refresh data from the backend FIRST so the freshly-rotated
        // screen reflects the latest block height / price / fee.
        // Without this the widget would only get new data on the
        // 15-min WorkManager tick — even when cycling through screens
        // every minute the user would see stale block heights.
        // Failure is non-fatal; we still bump the index so rotation
        // doesn't stall on a flaky network.
        runCatching { BackendRefresher.refreshNow(context) }

        val manager = GlanceAppWidgetManager(context)
        val ids = manager.getGlanceIds(BTClockWidget::class.java)
        for (id in ids) {
            updateAppWidgetState(context, PreferencesGlanceStateDefinition, id) { prefs ->
                prefs.toMutablePreferences().apply {
                    val curr = this[WidgetStateKeys.RotationIndex] ?: 0
                    set(WidgetStateKeys.RotationIndex, (curr + 1) % cfg.rotationScreens.size)
                }
            }
            BTClockWidget().update(context, id)
        }

        // Schedule the next tick. set() is one-shot by design (see
        // RotationScheduler comments) so we re-register from here.
        RotationScheduler.schedule(context, cfg.rotationMinutes)
    }

    companion object {
        // SupervisorJob so a failure in one tick (e.g. transient IO)
        // doesn't tear down future ticks.
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    }
}
