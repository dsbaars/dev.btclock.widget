package dev.btclock.widget

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build

/**
 * AlarmManager wrapper that ticks the widget at a configurable
 * sub-15-minute cadence — finer than WorkManager's periodic floor.
 *
 * Why AlarmManager and not WorkManager:
 *
 *   - WorkManager periodic minimum is 15 min (battery story). The
 *     screen rotation needs minute-level cadence to feel like the
 *     hardware BTClock; 15 min would defeat the purpose.
 *   - AlarmManager.set() is "inexact" per Android docs (OS may defer
 *     by a few seconds for battery), which is fine for cosmetic
 *     rotation. We deliberately avoid setExact() so we don't need
 *     SCHEDULE_EXACT_ALARM (Android 12+) — visible drift of ±5–30s
 *     is acceptable and saves a permission.
 *   - We RE-register from the broadcast receiver after each tick
 *     instead of using setRepeating() — repeating alarms are inexact
 *     and may stretch out under Doze, while one-shot re-registration
 *     keeps the cadence honest while the user has the home screen open.
 *
 * The actual screen advance + redraw lives in [RotationTickReceiver].
 */
object RotationScheduler {
    /** Action ID matched by [RotationTickReceiver]. */
    const val ACTION = "dev.btclock.widget.ROTATE_TICK"

    /** Stable PendingIntent request code so cancel() finds the same alarm. */
    private const val REQ_CODE = 0x42C10C

    /**
     * Schedule the next rotation tick [intervalMinutes] from now.
     * intervalMinutes ≤ 0 cancels any pending alarm and returns —
     * the user disabled rotation in Settings.
     */
    fun schedule(context: Context, intervalMinutes: Int) {
        if (intervalMinutes <= 0) {
            cancel(context)
            return
        }
        val pi = pendingIntent(context, mutable = false) ?: return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        val triggerAt = System.currentTimeMillis() + intervalMinutes * 60_000L
        // RTC (not RTC_WAKEUP): no need to wake the device for a
        // cosmetic redraw. If the screen is off, ticks deliver when
        // the user wakes the phone — no battery cost from a dark
        // home screen.
        am.set(AlarmManager.RTC, triggerAt, pi)
    }

    /** Cancel any pending tick. Idempotent. */
    fun cancel(context: Context) {
        val pi = pendingIntent(context, mutable = false, createIfMissing = false) ?: return
        val am = context.getSystemService(AlarmManager::class.java) ?: return
        am.cancel(pi)
        pi.cancel()
    }

    /**
     * PendingIntent for the tick broadcast. Mutability follows the
     * Android 12+ requirement to declare FLAG_IMMUTABLE; the alarm
     * system never mutates our extras so immutable is correct.
     */
    private fun pendingIntent(
        context: Context,
        @Suppress("SameParameterValue") mutable: Boolean,
        createIfMissing: Boolean = true,
    ): PendingIntent? {
        val intent = Intent(ACTION).setPackage(context.packageName)
        var flags = if (createIfMissing) PendingIntent.FLAG_UPDATE_CURRENT else PendingIntent.FLAG_NO_CREATE
        flags = flags or
            if (mutable) {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0
            } else {
                PendingIntent.FLAG_IMMUTABLE
            }
        return PendingIntent.getBroadcast(context, REQ_CODE, intent, flags)
    }
}
