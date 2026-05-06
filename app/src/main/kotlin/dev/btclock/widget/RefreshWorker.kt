package dev.btclock.widget

import android.content.Context
import androidx.glance.appwidget.GlanceAppWidgetManager
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import java.util.concurrent.TimeUnit

/**
 * Periodic backend fetch + widget refresh.
 *
 * WorkManager's minimum periodic interval is 15 minutes — Android's
 * battery story can't sustain anything tighter without a foreground
 * service or FCM push (which would mean running infrastructure on the
 * server side, out of scope for the debug widget). 15 min is fine for
 * the BTClock screens: block height changes ~10 min on average, fees
 * shift on minutes, USD price drifts cents per minute. Users who want
 * second-level live data should run the dashboard on a tablet next to
 * the device.
 */
class RefreshWorker(
    appContext: Context,
    params: WorkerParameters,
) : CoroutineWorker(appContext, params) {
    override suspend fun doWork(): Result {
        // Delegate the fetch + state-write to the shared refresher so
        // the periodic Worker, the rotation tick receiver, and the
        // tap action all stay in sync on the wire format and key
        // schema. Then redraw every pinned widget.
        BackendRefresher.refreshNow(applicationContext)
        val manager = GlanceAppWidgetManager(applicationContext)
        for (id in manager.getGlanceIds(BTClockWidget::class.java)) {
            BTClockWidget().update(applicationContext, id)
        }
        return Result.success()
    }

    companion object {
        private const val UNIQUE_NAME = "btclock-widget-refresh"

        /**
         * Enqueue a 15-min periodic refresh with REPLACE policy so the
         * cadence resets cleanly on widget add. Network is required;
         * a queued tick that finds no connection retries on the next
         * available window rather than burning a slot.
         */
        fun schedule(context: Context) {
            val constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()

            val request =
                PeriodicWorkRequestBuilder<RefreshWorker>(15, TimeUnit.MINUTES)
                    .setConstraints(constraints)
                    .build()

            WorkManager.getInstance(context).enqueueUniquePeriodicWork(
                UNIQUE_NAME,
                ExistingPeriodicWorkPolicy.UPDATE,
                request,
            )
        }

        /** One-shot refresh for the "just added" / "settings just saved" path. */
        fun runOnce(context: Context) {
            val request =
                androidx.work
                    .OneTimeWorkRequestBuilder<RefreshWorker>()
                    .setConstraints(
                        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build(),
                    ).build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
