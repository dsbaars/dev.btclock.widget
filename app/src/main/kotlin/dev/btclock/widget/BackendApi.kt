package dev.btclock.widget

import io.ktor.client.HttpClient
import io.ktor.client.engine.okhttp.OkHttp
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.get
import io.ktor.client.statement.bodyAsText
import io.ktor.serialization.kotlinx.json.json
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull

/**
 * Tiny REST client for the ws-node's snapshot endpoints. We hit the
 * same paths the dashboard uses on its /rest page, so anything
 * /healthz says is OK should be fetchable here.
 *
 * Endpoints (see internal/server/server.go):
 *   GET /api/lastblock     → JSON int   (e.g. 923936)
 *   GET /api/lastprice     → JSON map   (e.g. {"USD":"95432","EUR":"88210"})
 *   GET /api/lastfee       → JSON float (e.g. 12.5 sat/vB)
 *
 * Errors return null fields rather than throwing — a stale widget
 * with one missing value still renders the others, and the frame
 * keeps drawing the BTClock chrome.
 */
data class BackendSnapshot(
    val blockHeight: Long?,
    /**
     * Selected price (in [currency]). Multiple-currency support is
     * picked here — the API itself returns the full map; the Worker
     * resolves the user's chosen currency before storing in the
     * widget state. Null when the chosen currency is unknown to the
     * upstream node (in which case the widget renders blanks rather
     * than zeros).
     */
    val price: Double?,
    val currency: String,
    val medianFee: Double?,
)

class BackendApi(
    private val baseUrl: String,
) {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = true
        }
    private val client =
        HttpClient(OkHttp) {
            install(ContentNegotiation) { json(this@BackendApi.json) }
            install(HttpTimeout) {
                connectTimeoutMillis = 8_000
                requestTimeoutMillis = 12_000
                socketTimeoutMillis = 12_000
            }
        }

    /**
     * Fetch all three snapshot values, picking [currency] out of the
     * /api/lastprice map. ISO code matching is case-insensitive on
     * the wire — the upstream node always replies upper-cased, but
     * we normalise just in case.
     */
    suspend fun fetchSnapshot(currency: String): BackendSnapshot {
        val ccy = currency.uppercase()
        val height = runCatching { fetchLastBlock() }.getOrNull()
        val price = runCatching { fetchPrice(ccy) }.getOrNull()
        val fee = runCatching { fetchMedianFee() }.getOrNull()
        return BackendSnapshot(blockHeight = height, price = price, currency = ccy, medianFee = fee)
    }

    private suspend fun fetchLastBlock(): Long? {
        val text = client.get(joinUrl("/api/lastblock")).bodyAsText().trim()
        return text.toLongOrNull()
    }

    private suspend fun fetchPrice(currency: String): Double? {
        val text = client.get(joinUrl("/api/lastprice")).bodyAsText()
        return parsePriceMap(text)[currency.uppercase()]
    }

    private suspend fun fetchMedianFee(): Double? {
        val text = client.get(joinUrl("/api/lastfee")).bodyAsText().trim()
        return text.toDoubleOrNull()
    }

    /**
     * Parse /api/lastprice into a Map<currencyCode, value>. Tolerates
     * both stringified ("95432") and numeric (95432) values; the Go
     * server emits strings but a future change shouldn't break the
     * widget over a JSON-type tweak.
     */
    private fun parsePriceMap(body: String): Map<String, Double> {
        val element: JsonElement =
            runCatching { json.parseToJsonElement(body) }.getOrNull()
                ?: return emptyMap()
        if (element !is JsonObject) return emptyMap()
        val out = HashMap<String, Double>(element.size)
        for ((k, v) in element) {
            val prim = (v as? kotlinx.serialization.json.JsonPrimitive) ?: continue
            val d = prim.contentOrNull?.toDoubleOrNull() ?: continue
            out[k.uppercase()] = d
        }
        return out
    }

    private fun joinUrl(path: String): String {
        val base = baseUrl.trimEnd('/')
        val p = if (path.startsWith("/")) path else "/$path"
        return base + p
    }

    fun close() {
        runCatching { client.close() }
    }
}
