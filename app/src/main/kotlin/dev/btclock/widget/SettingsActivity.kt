package dev.btclock.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.LocalMinimumInteractiveComponentSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch

/** BTClock-brand gold (bright stop of the wordmark gradient). */
private val GoldColor = Color(0xFFF1C64A)

/**
 * The BTClock device, dashboard, and store all use Ubuntu — pulling the
 * settings screen into the same family keeps the brand consistent
 * everywhere the user sees BTClock branding.
 */
private val Ubuntu =
    FontFamily(
        Font(R.font.ubuntu_regular, FontWeight.Normal),
        Font(R.font.ubuntu_medium, FontWeight.Medium),
        Font(R.font.ubuntu_bold, FontWeight.Bold),
        Font(R.font.ubuntu_medium_italic, FontWeight.Medium, FontStyle.Italic),
    )

/**
 * Apply Ubuntu to every Material 3 text style without restating each
 * size / weight / line-height. Built off the M3 defaults so spacings
 * and line metrics stay correct.
 */
private val UbuntuTypography: Typography =
    Typography().run {
        val withUbuntu: (TextStyle) -> TextStyle = { it.copy(fontFamily = Ubuntu) }
        copy(
            displayLarge = withUbuntu(displayLarge),
            displayMedium = withUbuntu(displayMedium),
            displaySmall = withUbuntu(displaySmall),
            headlineLarge = withUbuntu(headlineLarge),
            headlineMedium = withUbuntu(headlineMedium),
            headlineSmall = withUbuntu(headlineSmall),
            titleLarge = withUbuntu(titleLarge),
            titleMedium = withUbuntu(titleMedium),
            titleSmall = withUbuntu(titleSmall),
            bodyLarge = withUbuntu(bodyLarge),
            bodyMedium = withUbuntu(bodyMedium),
            bodySmall = withUbuntu(bodySmall),
            labelLarge = withUbuntu(labelLarge),
            labelMedium = withUbuntu(labelMedium),
            labelSmall = withUbuntu(labelSmall),
        )
    }

/**
 * Tiny configuration screen — backend URL, default screen, inverted
 * palette toggle, save button.
 *
 * Dual role:
 *   - Launcher icon (acts as a regular activity)
 *   - Widget configuration activity (returns RESULT_OK with the
 *     EXTRA_APPWIDGET_ID so the launcher finishes pinning)
 */
class SettingsActivity : ComponentActivity() {
    private var appWidgetId: Int = AppWidgetManager.INVALID_APPWIDGET_ID

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Default to "cancelled" so a back-press doesn't accidentally
        // pin a widget the user dismissed.
        appWidgetId = intent?.extras?.getInt(
            AppWidgetManager.EXTRA_APPWIDGET_ID,
            AppWidgetManager.INVALID_APPWIDGET_ID,
        ) ?: AppWidgetManager.INVALID_APPWIDGET_ID
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(Activity.RESULT_CANCELED, Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId))
        }

        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(),
                typography = UbuntuTypography,
            ) {
                // Drop Material's 48dp minimum tap target so checkbox /
                // radio rows can sit at their visual size (~28dp) and
                // stack tightly. Touch hit area is preserved by the
                // surrounding Row.selectable() which still expands to
                // fill the row width.
                CompositionLocalProvider(
                    LocalMinimumInteractiveComponentSize provides Dp.Unspecified,
                ) {
                    Scaffold { padding ->
                        SettingsScreen(
                            modifier =
                                Modifier
                                    .padding(padding)
                                    .fillMaxSize()
                                    .padding(horizontal = 20.dp, vertical = 16.dp),
                            onSaved = ::finishWithSuccess,
                        )
                    }
                }
            }
        }
    }

    private fun finishWithSuccess() {
        if (appWidgetId != AppWidgetManager.INVALID_APPWIDGET_ID) {
            setResult(
                Activity.RESULT_OK,
                Intent().putExtra(AppWidgetManager.EXTRA_APPWIDGET_ID, appWidgetId),
            )
        }
        finish()
    }
}

@Composable
private fun SettingsScreen(modifier: Modifier = Modifier, onSaved: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    var url by remember { mutableStateOf("") }
    var screen by remember { mutableStateOf(Screen.BlockHeight) }
    var inverted by remember { mutableStateOf(false) }
    var currency by remember { mutableStateOf("USD") }
    var rotationMinutes by remember { mutableStateOf(1) }
    // Plain HashSet under remember{} — Compose can't observe internal
    // mutations, so we re-assign via toMutableSet() helpers below.
    var rotationScreens by remember { mutableStateOf<Set<Screen>>(Screen.entries.toSet()) }
    var digitFont by remember { mutableStateOf(DigitFont.Antonio) }

    LaunchedEffect(Unit) {
        val cfg = Prefs.read(context)
        url = cfg.backendUrl
        screen = cfg.screen
        inverted = cfg.inverted
        currency = cfg.currency
        rotationMinutes = cfg.rotationMinutes
        rotationScreens = cfg.rotationScreens.toSet()
        digitFont = cfg.digitFont
    }

    val uriHandler = LocalUriHandler.current

    Column(modifier = modifier.verticalScroll(rememberScrollState())) {
        Text(
            text = context.getString(R.string.app_name),
            color = GoldColor,
            fontFamily = Ubuntu,
            fontWeight = FontWeight.Medium,
            fontStyle = FontStyle.Italic,
            fontSize = 34.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(14.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(context.getString(R.string.backend_label)) },
            placeholder = { Text(context.getString(R.string.backend_hint)) },
            singleLine = true,
            textStyle = MaterialTheme.typography.bodyLarge,
            modifier = Modifier.fillMaxWidth(),
        )

        Section(title = context.getString(R.string.screen_label)) {
            ScreenChoice(R.string.screen_block_height, Screen.BlockHeight, screen) { screen = it }
            ScreenChoice(R.string.screen_price, Screen.Price, screen) { screen = it }
            ScreenChoice(R.string.screen_moscow_time, Screen.MoscowTime, screen) { screen = it }
            ScreenChoice(R.string.screen_fee_rate, Screen.FeeRate, screen) { screen = it }
            ScreenChoice(R.string.screen_halving, Screen.Halving, screen) { screen = it }
            ScreenChoice(R.string.screen_market_cap, Screen.MarketCap, screen) { screen = it }
            ScreenChoice(R.string.screen_supply, Screen.Supply, screen) { screen = it }
        }

        Section(
            title = context.getString(R.string.rotation_label),
            hint = context.getString(R.string.rotation_hint),
        ) {
            OutlinedTextField(
                value = if (rotationMinutes <= 0) "" else rotationMinutes.toString(),
                onValueChange = { input ->
                    rotationMinutes = input
                        .filter { it.isDigit() }
                        .take(3)
                        .toIntOrNull()
                        ?.coerceIn(0, 240)
                        ?: 0
                },
                label = { Text(context.getString(R.string.rotation_minutes_label)) },
                placeholder = { Text("0 (off)") },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.fillMaxWidth(),
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = context.getString(R.string.rotation_screens_label),
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            ROTATION_OPTIONS.forEach { (sc, labelRes) ->
                RotationToggle(
                    labelRes = labelRes,
                    included = sc in rotationScreens,
                    onChange = { on ->
                        rotationScreens = if (on) rotationScreens + sc else rotationScreens - sc
                    },
                )
            }
        }

        Section(title = context.getString(R.string.currency_label)) {
            CurrencyPicker(selected = currency, onSelect = { currency = it })
        }

        Section(title = context.getString(R.string.font_label)) {
            DigitFont.entries.forEach { font ->
                val labelRes =
                    when (font) {
                        DigitFont.Antonio -> R.string.font_antonio
                        DigitFont.Oswald -> R.string.font_oswald
                        DigitFont.Ubuntu -> R.string.font_ubuntu
                    }
                TightRow(
                    selected = digitFont == font,
                    onClick = { digitFont = font },
                    label = context.getString(labelRes),
                )
            }
        }

        Spacer(Modifier.height(14.dp))
        TightRow(
            checked = inverted,
            onCheckedChange = { inverted = it },
            label = "Inverted (white on black)",
        )

        Spacer(Modifier.height(20.dp))
        Button(
            onClick = {
                scope.launch {
                    // Preserve user's selection ORDER from Screen.entries
                    // so the rotation cycle reads naturally instead of in
                    // hash-bucket order.
                    val orderedScreens = Screen.entries.filter { it in rotationScreens }
                    Prefs.write(
                        context,
                        WidgetConfig(
                            backendUrl = url.trim(),
                            screen = screen,
                            inverted = inverted,
                            currency = currency,
                            rotationMinutes = rotationMinutes,
                            rotationScreens = orderedScreens,
                            digitFont = digitFont,
                        ),
                    )
                    RefreshWorker.runOnce(context)
                    // Reschedule the rotation alarm to pick up the new
                    // cadence — old PendingIntent is replaced via
                    // FLAG_UPDATE_CURRENT, and rotationMinutes <= 0
                    // cancels cleanly.
                    RotationScheduler.schedule(context, rotationMinutes)
                    onSaved()
                }
            },
            colors =
                ButtonDefaults.buttonColors(
                    containerColor = GoldColor,
                    contentColor = Color.Black,
                ),
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                context.getString(R.string.save),
                style = MaterialTheme.typography.labelLarge.copy(fontWeight = FontWeight.Bold),
            )
        }

        Spacer(Modifier.height(10.dp))
        Text(
            text = "v${BuildConfig.VERSION_NAME} · ${BuildConfig.GIT_COMMIT}",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelSmall,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(6.dp))
        Text(
            text = "Get the real BTClock at btclock.store",
            color = GoldColor,
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            textDecoration = TextDecoration.Underline,
            modifier =
                Modifier
                    .fillMaxWidth()
                    .clickable { uriHandler.openUri("https://btclock.store") }
                    .padding(vertical = 6.dp),
        )
    }
}

/**
 * Section with a small uppercase gold title and tightly-packed content
 * beneath it. The outer column doesn't use spacedBy, so each Section
 * provides its own top spacing — that lets dense rows (checkboxes /
 * radios) sit flush against each other while sections stay clearly
 * separated.
 */
@Composable
private fun Section(
    title: String,
    hint: String? = null,
    content: @Composable () -> Unit,
) {
    Spacer(Modifier.height(18.dp))
    Text(
        text = title.uppercase(),
        style =
            MaterialTheme.typography.labelLarge.copy(
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.2.sp,
            ),
        color = GoldColor,
    )
    if (hint != null) {
        Text(
            text = hint,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(top = 2.dp, bottom = 6.dp),
        )
    } else {
        Spacer(Modifier.height(6.dp))
    }
    content()
}

/**
 * Tight checkbox / radio row. Pulls the indicator to ~24dp via
 * Modifier.size, uses a 6dp gap to the label, and lets the surrounding
 * .selectable() drive the tap target — combined with the
 * LocalMinimumInteractiveComponentSize override at the theme level,
 * rows end up ~28dp tall instead of the Material default 48dp.
 */
@Composable
private fun TightRow(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = selected, onClick = onClick)
                .padding(vertical = 2.dp),
    ) {
        RadioButton(
            selected = selected,
            onClick = onClick,
            modifier = Modifier.padding(end = 4.dp),
            colors = RadioButtonDefaults.colors(selectedColor = GoldColor),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Checkbox variant of [TightRow]. */
@Composable
private fun TightRow(
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    label: String,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier =
            Modifier
                .fillMaxWidth()
                .selectable(selected = checked, onClick = { onCheckedChange(!checked) })
                .padding(vertical = 2.dp),
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            modifier = Modifier.padding(end = 4.dp),
            colors = CheckboxDefaults.colors(checkedColor = GoldColor),
        )
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

/**
 * Screens offered in the rotation-cycle picker. The order here is the
 * UI display order; the saved cycle order follows
 * [Screen.entries] regardless of user toggle order.
 */
private val ROTATION_OPTIONS: List<Pair<Screen, Int>> =
    listOf(
        Screen.BlockHeight to R.string.screen_block_height,
        Screen.Price to R.string.screen_price,
        Screen.MoscowTime to R.string.screen_moscow_time,
        Screen.FeeRate to R.string.screen_fee_rate,
        Screen.Halving to R.string.screen_halving,
        Screen.MarketCap to R.string.screen_market_cap,
        Screen.Supply to R.string.screen_supply,
    )

@Composable
private fun RotationToggle(
    labelRes: Int,
    included: Boolean,
    onChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    TightRow(checked = included, onCheckedChange = onChange, label = context.getString(labelRes))
}

/**
 * Material 3 ExposedDropdownMenuBox. Lets the user type a non-listed
 * ISO code (e.g. "BTCLOCK_EXTRA_CURRENCIES" enabled on a self-hosted
 * node exposes 162 codes — too many for a curated list). The widget's
 * Currencies map handles symbols for ~14 of them; everything else
 * falls back to a stacked ISO-code label.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun CurrencyPicker(selected: String, onSelect: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    // ExposedDropdownMenuBox plus an internal DropdownMenu — gets us
    // anchored positioning from the box without depending on the
    // ExposedDropdownMenu BoxScope.* API which moved around between
    // Material 3 minor versions.
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { expanded = it }) {
        OutlinedTextField(
            value = selected,
            onValueChange = { onSelect(it.uppercase()) },
            singleLine = true,
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded) },
            modifier =
                Modifier
                    .fillMaxWidth()
                    .menuAnchor(),
        )
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            for (code in Currencies.OFFERED) {
                DropdownMenuItem(
                    text = { Text(code) },
                    onClick = {
                        onSelect(code)
                        expanded = false
                    },
                )
            }
        }
    }
}

@Composable
private fun ScreenChoice(
    labelRes: Int,
    option: Screen,
    selected: Screen,
    onSelect: (Screen) -> Unit,
) {
    val context = LocalContext.current
    TightRow(
        selected = selected == option,
        onClick = { onSelect(option) },
        label = context.getString(labelRes),
    )
}
