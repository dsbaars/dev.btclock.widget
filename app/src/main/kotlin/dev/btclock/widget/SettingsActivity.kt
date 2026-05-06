package dev.btclock.widget

import android.app.Activity
import android.appwidget.AppWidgetManager
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

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
            MaterialTheme(colorScheme = darkColorScheme()) {
                Scaffold { padding ->
                    SettingsScreen(
                        modifier = Modifier
                            .padding(padding)
                            .fillMaxSize()
                            .padding(16.dp),
                        onSaved = ::finishWithSuccess,
                    )
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

    Column(
        modifier = modifier.verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(text = context.getString(R.string.app_name), style = MaterialTheme.typography.titleLarge)

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text(context.getString(R.string.backend_label)) },
            placeholder = { Text(context.getString(R.string.backend_hint)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(text = context.getString(R.string.screen_label), style = MaterialTheme.typography.titleMedium)
        ScreenChoice(R.string.screen_block_height, Screen.BlockHeight, screen) { screen = it }
        ScreenChoice(R.string.screen_price, Screen.Price, screen) { screen = it }
        ScreenChoice(R.string.screen_moscow_time, Screen.MoscowTime, screen) { screen = it }
        ScreenChoice(R.string.screen_fee_rate, Screen.FeeRate, screen) { screen = it }
        ScreenChoice(R.string.screen_halving, Screen.Halving, screen) { screen = it }
        ScreenChoice(R.string.screen_market_cap, Screen.MarketCap, screen) { screen = it }
        ScreenChoice(R.string.screen_supply, Screen.Supply, screen) { screen = it }

        Text(
            text = context.getString(R.string.rotation_label),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = context.getString(R.string.rotation_hint),
            style = MaterialTheme.typography.bodySmall,
        )
        OutlinedTextField(
            value = if (rotationMinutes <= 0) "" else rotationMinutes.toString(),
            onValueChange = { input ->
                rotationMinutes = input.filter { it.isDigit() }
                    .take(3)
                    .toIntOrNull()
                    ?.coerceIn(0, 240)
                    ?: 0
            },
            label = { Text(context.getString(R.string.rotation_minutes_label)) },
            placeholder = { Text("0 (off)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Text(
            text = context.getString(R.string.rotation_screens_label),
            style = MaterialTheme.typography.bodyMedium,
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

        Text(text = context.getString(R.string.currency_label), style = MaterialTheme.typography.titleMedium)
        CurrencyPicker(selected = currency, onSelect = { currency = it })

        Text(
            text = context.getString(R.string.font_label),
            style = MaterialTheme.typography.titleMedium,
        )
        DigitFont.entries.forEach { font ->
            val labelRes = when (font) {
                DigitFont.Antonio -> R.string.font_antonio
                DigitFont.Oswald -> R.string.font_oswald
            }
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .selectable(selected = digitFont == font, onClick = { digitFont = font })
                    .padding(vertical = 4.dp),
            ) {
                RadioButton(selected = digitFont == font, onClick = { digitFont = font })
                Text(
                    text = context.getString(labelRes),
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }

        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(checked = inverted, onCheckedChange = { inverted = it })
            Spacer(Modifier.height(0.dp))
            Text(text = "Inverted (white on black)")
        }

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
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(context.getString(R.string.save))
        }
    }
}

/**
 * Screens offered in the rotation-cycle picker. The order here is the
 * UI display order; the saved cycle order follows
 * [Screen.entries] regardless of user toggle order.
 */
private val ROTATION_OPTIONS: List<Pair<Screen, Int>> = listOf(
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp),
    ) {
        Checkbox(checked = included, onCheckedChange = onChange)
        Text(
            text = context.getString(labelRes),
            modifier = Modifier.padding(start = 8.dp),
        )
    }
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
            modifier = Modifier
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
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .selectable(selected = selected == option, onClick = { onSelect(option) })
            .padding(vertical = 4.dp),
    ) {
        RadioButton(selected = selected == option, onClick = { onSelect(option) })
        Text(text = context.getString(labelRes), modifier = Modifier.padding(start = 8.dp))
    }
}
