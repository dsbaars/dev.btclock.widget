package dev.btclock.widget

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.Image
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.LocalSize
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.SizeMode
import androidx.glance.appwidget.action.actionRunCallback
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.currentState
import androidx.glance.layout.Box
import androidx.glance.layout.ContentScale
import androidx.glance.layout.fillMaxSize
import androidx.glance.state.PreferencesGlanceStateDefinition
import androidx.glance.unit.ColorProvider

/**
 * Glance widget entrypoint. Glance is RemoteViews under the hood, so
 * the actual BTClock chrome can't be drawn with Glance composables —
 * RemoteViews knows nothing about Canvas, custom Paint shaders, or
 * Path drawing. We do the real work in [FrameRenderer], hand Glance
 * the resulting Bitmap via ImageProvider, and let RemoteViews stretch
 * it into the widget surface.
 *
 * Lifecycle:
 *   1. Widget added           → AppWidgetProvider triggers provideGlance
 *   2. provideGlance reads    → DataStore prefs + Glance state
 *   3. FrameRenderer runs     → Bitmap built at LocalSize.current pixels
 *   4. Glance hands RemoteViews to the launcher
 *   5. RefreshWorker polls    → updates state + calls update(); GOTO 2
 */
class BTClockWidget : GlanceAppWidget() {
    /**
     * Glance state schema. Sharing the same Preferences DataStore for
     * widget state lets multiple instances of the widget keep their
     * own per-id state while RefreshWorker writes to all of them.
     */
    override val stateDefinition = PreferencesGlanceStateDefinition

    /**
     * Single sizing class. The renderer adapts to whatever pixel box
     * arrives via LocalSize — letterboxing the BTClock aspect inside
     * the widget cell — so we don't need separate layouts per size
     * bucket. Saves us a lot of Glance complexity.
     */
    override val sizeMode = SizeMode.Exact

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            GlanceTheme {
                WidgetContent()
            }
        }
    }
}

@Composable
private fun WidgetContent() {
    val context = LocalContext.current
    val size = LocalSize.current
    val state = currentState<androidx.datastore.preferences.core.Preferences>()

    // Read prefs (screen choice + inverted + currency) inside a
    // produceState so a settings change triggers a recomposition
    // without us having to bind the DataStore flow into Glance
    // directly.
    val cfg by produceState(
        initialValue =
            WidgetConfig(
                backendUrl = "",
                screen = Screen.BlockHeight,
                inverted = false,
                currency = "USD",
                rotationMinutes = 0,
                rotationScreens = listOf(Screen.BlockHeight),
                digitFont = DigitFont.Antonio,
            ),
    ) {
        Prefs.observe(context).collect { value = it }
    }
    val snapshot = state.toBackendSnapshot(cfg.currency)

    // Pick the screen to render. Whenever the rotation set has at
    // least two screens we follow the index bumped by either the
    // AlarmManager tick (auto-rotate) or the tap action — that lets
    // tap-to-advance work even with auto-rotation disabled
    // (rotationMinutes == 0). When the cycle is empty or single we
    // fall back to the manual cfg.screen pick from Settings.
    val activeScreen =
        if (cfg.rotationScreens.size > 1) {
            val idx = (state[WidgetStateKeys.RotationIndex] ?: 0).coerceAtLeast(0)
            cfg.rotationScreens[idx % cfg.rotationScreens.size]
        } else {
            cfg.screen
        }

    val density = context.resources.displayMetrics.density
    val widthPx = (size.width.value * density).toInt().coerceAtLeast(1)
    val heightPx = (size.height.value * density).toInt().coerceAtLeast(1)

    val cells = DigitLayout.layoutFor(activeScreen, panelCount = 7, snapshot = snapshot)
    val renderer =
        FrameRenderer(
            context = context,
            panelCount = 7,
            inverted = cfg.inverted,
            digitFont = cfg.digitFont,
        )
    val bitmap = renderer.render(widthPx, heightPx, cells)

    Box(
        modifier =
            GlanceModifier
                .fillMaxSize()
                // Tap → advance to the next screen in the rotation cycle.
                // No-op when only 0 or 1 screens are selected. Settings
                // is reachable from the launcher icon (the widget is
                // configured automatically on first add via
                // android:configure in btclock_widget_info.xml).
                .clickable(actionRunCallback<AdvanceScreenAction>())
                .background(ColorProvider(Color.Black)),
    ) {
        Image(
            provider = ImageProvider(bitmap),
            contentDescription = "BTClock display",
            modifier = GlanceModifier.fillMaxSize(),
            contentScale = ContentScale.Fit,
        )
    }
}
