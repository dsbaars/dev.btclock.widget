package dev.btclock.widget

import android.appwidget.AppWidgetManager
import android.content.Context
import android.content.Intent
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/**
 * Glance ↔ AppWidgetProvider bridge.
 *
 * onUpdate / onEnabled / onDeleted are fanned through GlanceAppWidget;
 * we just need to schedule the WorkManager periodic refresh on first
 * add and tear it down when the user removes the last instance.
 */
class BTClockWidgetReceiver : GlanceAppWidgetReceiver() {

    override val glanceAppWidget: GlanceAppWidget = BTClockWidget()

    override fun onEnabled(context: Context) {
        super.onEnabled(context)
        // First instance pinned — start the refresh cadence and kick
        // off an immediate one-shot fetch so the user doesn't see
        // empty cells until the next 15-minute window.
        RefreshWorker.schedule(context)
        RefreshWorker.runOnce(context)
        // Auto-rotation between screens — read the configured cadence
        // and arm the first AlarmManager tick. set() is one-shot;
        // RotationTickReceiver re-arms after each tick.
        kotlinx.coroutines.runBlocking {
            val cfg = Prefs.read(context)
            RotationScheduler.schedule(context, cfg.rotationMinutes)
        }
    }

    override fun onDisabled(context: Context) {
        super.onDisabled(context)
        // Last instance removed — cancel the rotation alarm so the
        // OS doesn't keep waking us to redraw a non-existent widget.
        RotationScheduler.cancel(context)
    }

    override fun onUpdate(
        context: Context,
        appWidgetManager: AppWidgetManager,
        appWidgetIds: IntArray,
    ) {
        super.onUpdate(context, appWidgetManager, appWidgetIds)
        // OS asked us to refresh (e.g. boot, user added another
        // instance). Trigger an immediate Worker run so the new
        // instance picks up live data right away.
        RefreshWorker.runOnce(context)
    }

    override fun onReceive(context: Context, intent: Intent) {
        super.onReceive(context, intent)
        // Deep link could land here in the future (refresh-now action,
        // FCM nudge); kept as the canonical entry for that work.
    }
}
