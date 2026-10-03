package dk.jpedersen.ladetid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

val CPH: ZoneId = ZoneId.of("Europe/Copenhagen")
const val HOUR_MS = 3_600_000L

/**
 * En time med prisens dele, alle i kr/kWh uden moms.
 * [price] er den samlede pris efter brugerens valg (se [Pricing]).
 */
data class HourPrice(
    val start: Long,        // epoch ms for timens start
    val spot: Double,       // gennemsnitlig spotpris fra kilderne
    val net: Double,        // netselskabets tarif (transport lokalt)
    val energinet: Double,  // Energinets system og nettarif
    val tax: Double,        // elafgift
    val sources: Int,       // hvor mange kilder der indgår i spotprisen
    val forecast: Boolean,  // true hvis spotprisen er en prognose
    val price: Double = 0.0
)

class PriceData(
    val hours: List<HourPrice>,
    val okSources: List<String>,
    val failedSources: List<String>,
    val fetchedAt: Long,
    val area: String,
    val supplierId: String
)

class Supplier(val id: String, val name: String, val area: String)

/** Lægger prisens dele sammen efter det man har valgt i indstillinger. */
object Pricing {
    fun apply(ctx: Context, hours: List<HourPrice>): List<HourPrice> {
        val transport = Store.inclTransport(ctx)
        val tax = Store.inclTax(ctx)
        val vat = Store.inclVat(ctx)
        val extra = Store.extraOre(ctx) / 100.0
        return hours.map { h ->
            var p = h.spot + extra
            if (transport) p += h.net + h.energinet
            if (tax) p += h.tax
            if (vat) p *= 1.25
            h.copy(price = p)
        }
    }

    fun describe(ctx: Context): String {
        val parts = mutableListOf("spotpris")
        if (Store.extraOre(ctx) > 0) parts.add("elhandlers tillæg")
        if (Store.inclTransport(ctx)) parts.add("transport")
        if (Store.inclTax(ctx)) parts.add("elafgift")
        if (Store.inclVat(ctx)) parts.add("moms")
        return if (parts.size == 1) "kun spotpris" else
            parts.dropLast(1).joinToString(", ") + " og " + parts.last()
    }
}

/**
 * Henter spotpris fra to kilder og tager gennemsnittet time for time.
 *
 * Strømligning: https://stromligning.dk/api/prices (spotpris, tariffer for det valgte netselskab, elafgift)
 * elpriser.org: https://elpriser.org/api/forecast (spotpris og prognose ca. 9 døgn frem)
 */
object PriceRepository {

    private class Parts(val net: Double, val energinet: Double, val tax: Double)

    private class SourceHours(
        val spot: Map<Long, Double>,
        val parts: Map<Long, Parts>,
        val forecast: Set<Long>
    )

    fun fetch(area: String, supplierId: String): PriceData {
        val ok = mutableListOf<String>()
        val failed = mutableListOf<String>()

        val strom = try {
            fetchStromligning(area, supplierId).also { ok.add("Strømligning") }
        } catch (e: Exception) {
            failed.add("Strømligning"); null
        }
        val elpriser = try {
            fetchElpriser(area).also { ok.add("elpriser.org") }
        } catch (e: Exception) {
            failed.add("elpriser.org"); null
        }
        if (strom == null && elpriser == null) throw IOException("Ingen kilder svarede")

        val allHours = sortedSetOf<Long>()
        strom?.spot?.keys?.let { allHours.addAll(it) }
        elpriser?.spot?.keys?.let { allHours.addAll(it) }

        // Tariffer følger klokken. Hvor Strømligning ikke rækker, bruges seneste kendte
        // værdi for samme time på døgnet.
        val byHourOfDay = HashMap<Int, Parts>()
        strom?.parts?.toSortedMap()?.forEach { (h, p) -> byHourOfDay[Fmt.hour(h)] = p }
        val fallback = Parts(0.0, 0.115, 0.008)

        val result = allHours.map { h ->
            val values = listOfNotNull(strom?.spot?.get(h), elpriser?.spot?.get(h))
            val parts = strom?.parts?.get(h) ?: byHourOfDay[Fmt.hour(h)] ?: fallback
            val isForecast = (strom?.forecast?.contains(h) == true) ||
                (elpriser?.forecast?.contains(h) == true)
            HourPrice(h, values.average(), parts.net, parts.energinet, parts.tax, values.size, isForecast)
        }
        return PriceData(result, ok, failed, System.currentTimeMillis(), area, supplierId)
    }

    fun suppliers(): List<Supplier> {
        val raw = httpGet("https://stromligning.dk/api/suppliers").trim()
        val arr = if (raw.startsWith("[")) JSONArray(raw) else JSONObject(raw).getJSONArray("suppliers")
        return (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Supplier(o.getString("id"), o.optString("name", o.getString("id")), o.optString("priceArea", ""))
        }.filter { !it.name.contains("Udgået", ignoreCase = true) }
            .sortedBy { it.name.lowercase() }
    }

    private fun fetchStromligning(area: String, supplierId: String): SourceHours {
        var url = "https://stromligning.dk/api/prices?priceArea=$area&forecast=true"
        if (supplierId.isNotEmpty()) url += "&supplierId=$supplierId"
        val arr = JSONObject(httpGet(url)).getJSONArray("prices")

        val count = HashMap<Long, Int>()
        val spotSum = HashMap<Long, Double>()
        val netSum = HashMap<Long, Double>()
        val enSum = HashMap<Long, Double>()
        val taxSum = HashMap<Long, Double>()
        val forecast = HashSet<Long>()

        fun v(o: JSONObject?, key: String): Double =
            o?.optJSONObject(key)?.optDouble("value", 0.0) ?: 0.0

        for (i in 0 until arr.length()) {
            val p = arr.getJSONObject(i)
            val t = Instant.parse(p.getString("date")).toEpochMilli()
            val h = t - Math.floorMod(t, HOUR_MS)
            val d = p.optJSONObject("details")
            val tr = d?.optJSONObject("transmission")
            val spot = if (d?.optJSONObject("electricity") != null) v(d, "electricity")
            else p.getJSONObject("price").getDouble("value")

            fun add(m: HashMap<Long, Double>, x: Double) { m[h] = (m[h] ?: 0.0) + x }
            add(spotSum, spot)
            add(netSum, v(d, "distribution"))
            add(enSum, v(tr, "systemTariff") + v(tr, "netTariff"))
            add(taxSum, v(d, "electricityTax"))
            count[h] = (count[h] ?: 0) + 1
            if (p.optBoolean("forecast", false)) forecast.add(h)
        }
        if (count.isEmpty()) throw IOException("Tomt svar")

        // Kvartersværdier samles til timegennemsnit
        val spot = HashMap<Long, Double>()
        val parts = HashMap<Long, Parts>()
        for ((h, n) in count) {
            spot[h] = spotSum.getValue(h) / n
            parts[h] = Parts(netSum.getValue(h) / n, enSum.getValue(h) / n, taxSum.getValue(h) / n)
        }
        return SourceHours(spot, parts, forecast)
    }

    private fun fetchElpriser(area: String): SourceHours {
        val json = JSONObject(httpGet("https://elpriser.org/api/forecast?area=$area&mode=spot_ex"))
        val days = json.getJSONArray("days")
        val spot = HashMap<Long, Double>()
        val forecast = HashSet<Long>()
        for (d in 0 until days.length()) {
            val day = days.getJSONObject(d)
            val date = LocalDate.parse(day.getString("date"))
            val isForecast = day.optString("type") == "forecast"
            val prices = day.getJSONArray("prices")
            for (i in 0 until prices.length()) {
                val p = prices.getJSONObject(i)
                val h = date.atTime(p.getInt("hour"), 0).atZone(CPH).toInstant().toEpochMilli()
                spot[h] = p.getDouble("price")
                if (isForecast) forecast.add(h)
            }
        }
        if (spot.isEmpty()) throw IOException("Tomt svar")
        return SourceHours(spot, emptyMap(), forecast)
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Ladetid/1.1 (privat Android app)")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}

/** Gemmer seneste priser og indstillinger lokalt på telefonen. */
object Store {
    private const val FILE = "ladetid"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(FILE, Context.MODE_PRIVATE)
    private fun bool(ctx: Context, k: String, d: Boolean) = prefs(ctx).getBoolean(k, d)
    private fun setBool(ctx: Context, k: String, v: Boolean) = prefs(ctx).edit().putBoolean(k, v).apply()

    fun area(ctx: Context): String = prefs(ctx).getString("area", "DK1") ?: "DK1"
    fun setArea(ctx: Context, v: String) = prefs(ctx).edit().putString("area", v).apply()

    // Standard: Konstant (Aarhus området)
    fun supplierId(ctx: Context): String = prefs(ctx).getString("supplierId", "konstant_c") ?: ""
    fun supplierName(ctx: Context): String = prefs(ctx).getString("supplierName", "Konstant C 151") ?: ""
    fun setSupplier(ctx: Context, id: String, name: String) =
        prefs(ctx).edit().putString("supplierId", id).putString("supplierName", name).apply()

    fun chargeHours(ctx: Context): Int = prefs(ctx).getInt("chargeHours", 4)
    fun setChargeHours(ctx: Context, v: Int) = prefs(ctx).edit().putInt("chargeHours", v).apply()

    fun extraOre(ctx: Context): Int = prefs(ctx).getInt("extraOre", 0)
    fun setExtraOre(ctx: Context, v: Int) = prefs(ctx).edit().putInt("extraOre", v).apply()

    fun inclTransport(ctx: Context) = bool(ctx, "inclTransport", true)
    fun setInclTransport(ctx: Context, v: Boolean) = setBool(ctx, "inclTransport", v)
    fun inclTax(ctx: Context) = bool(ctx, "inclTax", true)
    fun setInclTax(ctx: Context, v: Boolean) = setBool(ctx, "inclTax", v)
    fun inclVat(ctx: Context) = bool(ctx, "inclVat", true)
    fun setInclVat(ctx: Context, v: Boolean) = setBool(ctx, "inclVat", v)

    fun notify(ctx: Context): Boolean = bool(ctx, "notify", true)
    fun setNotify(ctx: Context, v: Boolean) = setBool(ctx, "notify", v)

    fun wasNotified(ctx: Context, dayKey: String): Boolean =
        prefs(ctx).getStringSet("notified", emptySet())!!.contains(dayKey)

    fun markNotified(ctx: Context, dayKey: String) {
        val today = LocalDate.now(CPH)
        val keep = prefs(ctx).getStringSet("notified", emptySet())!!
            .filter { runCatching { !LocalDate.parse(it).isBefore(today.minusDays(2)) }.getOrDefault(false) }
            .toMutableSet()
        keep.add(dayKey)
        prefs(ctx).edit().putStringSet("notified", keep).apply()
    }

    fun save(ctx: Context, data: PriceData) {
        val arr = JSONArray()
        data.hours.forEach {
            arr.put(JSONObject().apply {
                put("s", it.start); put("sp", it.spot); put("ne", it.net); put("en", it.energinet)
                put("tx", it.tax); put("n", it.sources); put("f", it.forecast)
            })
        }
        val obj = JSONObject().apply {
            put("v", 2)
            put("hours", arr)
            put("ok", JSONArray(data.okSources))
            put("failed", JSONArray(data.failedSources))
            put("at", data.fetchedAt)
            put("area", data.area)
            put("supplier", data.supplierId)
        }
        prefs(ctx).edit().putString("cache", obj.toString()).apply()
    }

    fun load(ctx: Context): PriceData? {
        val raw = prefs(ctx).getString("cache", null) ?: return null
        return try {
            val obj = JSONObject(raw)
            if (obj.optInt("v", 1) < 2) return null
            val arr = obj.getJSONArray("hours")
            val hours = (0 until arr.length()).map {
                val o = arr.getJSONObject(it)
                HourPrice(o.getLong("s"), o.getDouble("sp"), o.getDouble("ne"), o.getDouble("en"),
                    o.getDouble("tx"), o.getInt("n"), o.getBoolean("f"))
            }
            fun strings(a: JSONArray) = (0 until a.length()).map { a.getString(it) }
            PriceData(hours, strings(obj.getJSONArray("ok")), strings(obj.getJSONArray("failed")),
                obj.getLong("at"), obj.optString("area", "DK1"), obj.optString("supplier", ""))
        } catch (e: Exception) {
            null
        }
    }
}
