package dev.btclock.widget

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

/**
 * User-settable widget configuration. Persisted via DataStore so the
 * Worker can read the chosen backend and screen without touching
 * SharedPreferences (which has questionable thread-safety on the
 * widget cold-start path).
 */
private val Context.dataStore by preferencesDataStore(name = "btclock_widget")

object PrefKeys {
    val BackendUrl = stringPreferencesKey("backend_url")
    val Screen = stringPreferencesKey("screen")
    val Inverted = stringPreferencesKey("inverted")
    val Currency = stringPreferencesKey("currency")

    /** Rotation cadence in minutes — 0 disables rotation, ≥1 enables. */
    val RotationMinutes = intPreferencesKey("rotation_minutes")

    /** Comma-separated screen names included in the rotation cycle. */
    val RotationScreens = stringPreferencesKey("rotation_screens")

    /** Digit typeface: "Antonio" (default, condensed) or "Oswald" (condensed bold). */
    val DigitFont = stringPreferencesKey("digit_font")
}

/**
 * Selectable digit typefaces. Both are vendored from
 * btclock_v4/components/fonts/assets so the widget renders match the
 * firmware's two most-used picks. Add a new entry here + the
 * corresponding res/font/<lowercase>.ttf to expose another face.
 */
enum class DigitFont(
    val resourceName: String,
    /**
     * Bold companion used for the rotated split-label (panel 0 on every
     * screen). Pairs with the digit face so a Ubuntu digit pick reads
     * "BLOCK / HEIGHT" in Ubuntu Bold instead of mixing with Oswald.
     */
    val labelResourceName: String,
    /**
     * Per-face label trim. Oswald Bold is our reference at 1.0 — its
     * narrow proportions are what the cap-height/width fitter targets.
     * Antonio Bold and Ubuntu Bold render visually heavier at the same
     * fitter output (denser strokes / different cap-to-em ratio), so we
     * shrink the fitted size to keep the label sitting at the same
     * optical weight across faces.
     */
    val labelScale: Float = 1.0f,
) {
    Antonio(resourceName = "antonio_regular", labelResourceName = "antonio_bold", labelScale = 0.88f),
    Oswald(resourceName = "oswald_regular", labelResourceName = "oswald_bold"),
    Ubuntu(resourceName = "ubuntu_medium", labelResourceName = "ubuntu_bold", labelScale = 0.88f),
}

data class WidgetConfig(
    val backendUrl: String,
    /**
     * The "manual" screen — shown when rotation is disabled, and the
     * fallback target when the rotation set is empty.
     */
    val screen: Screen,
    val inverted: Boolean,
    /**
     * ISO currency code to render on the price screen. The Worker
     * fetches the full price map and picks this entry; a missing
     * currency renders blanks rather than zeros so a stale-but-empty
     * widget reads as "no data".
     */
    val currency: String,
    /**
     * Auto-rotation cadence in minutes. 0 disables rotation entirely
     * (the manual [screen] is shown). ≥1 schedules an AlarmManager
     * tick that advances [rotationScreens] on every interval.
     */
    val rotationMinutes: Int,
    /**
     * Ordered set of screens to cycle through when rotation is on.
     * Defaults to all screens; user trims this from Settings to e.g.
     * just "block height + price + fee rate".
     */
    val rotationScreens: List<Screen>,
    /** Big-digit typeface (Antonio default, Oswald alternative). */
    val digitFont: DigitFont,
)

object Prefs {
    /**
     * Default backend — the public BTClock relay. Most users won't
     * run their own ws-node, and `ws.btclock.dev` is the same host
     * the firmware itself talks to. Override via Settings to point
     * at a self-hosted ws-node (or `http://10.0.2.2:8080` from the
     * Android emulator → host loopback).
     */
    private const val DEFAULT_URL = "https://ws.btclock.dev"
    private const val DEFAULT_CURRENCY = "USD"
    private const val DEFAULT_ROTATION_MINUTES = 1
    private val DEFAULT_ROTATION_SCREENS: List<Screen> = Screen.entries.toList()
    private val DEFAULT_FONT = DigitFont.Antonio

    suspend fun read(context: Context): WidgetConfig {
        val prefs: Preferences = context.dataStore.data.first()
        return prefs.toConfig()
    }

    fun observe(context: Context): Flow<WidgetConfig> =
        context.dataStore.data.map { it.toConfig() }

    suspend fun write(context: Context, config: WidgetConfig) {
        context.dataStore.edit { prefs ->
            prefs[PrefKeys.BackendUrl] = config.backendUrl
            prefs[PrefKeys.Screen] = config.screen.name
            prefs[PrefKeys.Inverted] = if (config.inverted) "true" else "false"
            prefs[PrefKeys.Currency] = config.currency.uppercase()
            prefs[PrefKeys.RotationMinutes] = config.rotationMinutes.coerceAtLeast(0)
            prefs[PrefKeys.RotationScreens] = config.rotationScreens.joinToString(",") { it.name }
            prefs[PrefKeys.DigitFont] = config.digitFont.name
        }
    }

    private fun Preferences.toConfig() =
        WidgetConfig(
            backendUrl = this[PrefKeys.BackendUrl]?.takeIf { it.isNotBlank() } ?: DEFAULT_URL,
            screen = parseScreen(this[PrefKeys.Screen]),
            inverted = this[PrefKeys.Inverted] == "true",
            currency = this[PrefKeys.Currency]?.takeIf { it.isNotBlank() }?.uppercase() ?: DEFAULT_CURRENCY,
            rotationMinutes = this[PrefKeys.RotationMinutes] ?: DEFAULT_ROTATION_MINUTES,
            rotationScreens = parseScreens(this[PrefKeys.RotationScreens]),
            digitFont = parseFont(this[PrefKeys.DigitFont]),
        )

    private fun parseFont(name: String?): DigitFont =
        runCatching { DigitFont.valueOf(name ?: "") }.getOrDefault(DEFAULT_FONT)

    private fun parseScreen(name: String?): Screen =
        runCatching { Screen.valueOf(name ?: "") }.getOrDefault(Screen.BlockHeight)

    /** Comma-separated screen names → ordered list, with safe fallback to the full set. */
    private fun parseScreens(value: String?): List<Screen> {
        if (value.isNullOrBlank()) return DEFAULT_ROTATION_SCREENS
        val parsed =
            value
                .split(",")
                .mapNotNull { name -> runCatching { Screen.valueOf(name.trim()) }.getOrNull() }
        return if (parsed.isEmpty()) DEFAULT_ROTATION_SCREENS else parsed
    }
}
