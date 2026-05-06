package dev.btclock.widget

import android.content.Context
import androidx.glance.GlanceId
import androidx.glance.action.ActionParameters
import androidx.glance.appwidget.action.ActionCallback
import androidx.glance.appwidget.state.updateAppWidgetState
import androidx.glance.state.PreferencesGlanceStateDefinition

/**
 * Glance action invoked when the user taps the widget body. Advances
 * the rotation index by one and triggers a re-render of THIS widget
 * instance only — leaves any other pinned widget instances on their
 * own current screens.
 *
 * Why this is its own ActionCallback (instead of e.g. firing a
 * broadcast to RotationTickReceiver):
 *
 *   - We only want to advance the widget the user actually tapped.
 *     RotationTickReceiver bumps every instance in lockstep — that's
 *     correct for the timer but wrong for tap-to-advance.
 *   - Glance ActionCallback hands us the GlanceId of the tapped
 *     widget for free, which the broadcast path can't.
 *   - It runs on a coroutine; we can read prefs and write Glance
 *     state without setting up a manual scope.
 *
 * Tap doesn't reset or otherwise interfere with the AlarmManager
 * tick — auto-rotation continues from the new index.
 */
class AdvanceScreenAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val cfg = Prefs.read(context)
        val cycleSize = cfg.rotationScreens.size
        if (cycleSize <= 1) return

        // Refresh from the backend before bumping the index so the
        // newly-shown screen reflects fresh data. Scoped to the
        // tapped widget only — other pinned instances keep their
        // current state until their own tap / tick. Failure is
        // non-fatal: a flaky network shouldn't block screen
        // navigation.
        runCatching { BackendRefresher.refreshNow(context, onlyId = glanceId) }

        updateAppWidgetState(context, PreferencesGlanceStateDefinition, glanceId) { prefs ->
            prefs.toMutablePreferences().apply {
                val curr = this[WidgetStateKeys.RotationIndex] ?: 0
                set(WidgetStateKeys.RotationIndex, (curr + 1) % cycleSize)
            }
        }
        BTClockWidget().update(context, glanceId)
    }
}
