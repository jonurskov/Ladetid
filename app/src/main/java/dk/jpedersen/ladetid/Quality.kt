package dk.jpedersen.ladetid

import android.content.Context
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.temporal.ChronoUnit

/**
 * Henter appens egen prognosekontrol fra GitHub (repoet jonurskov/Ladetid-data).
 * Der kører hver dag et program, som gemmer prognoserne, sammenligner dem med de
 * faktiske priser og beregner en rettelse. Appen henter resultatet her.
 */
object Quality {
    private const val BASE = "https://raw.githubusercontent.com/jonurskov/Ladetid-data/main/"
    const val EXCEL_URL = "https://github.com/jonurskov/Ladetid-data/raw/main/excel/seneste.xlsx"
    const val EXCEL_THIS_WEEK_URL = "https://github.com/jonurskov/Ladetid-data/raw/main/excel/denne-uge.xlsx"
    const val EXCEL_FOLDER_URL = "https://github.com/jonurskov/Ladetid-data/tree/main/excel"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("ladetid", Context.MODE_PRIVATE)

    /** Henter rapport og rettelse. Fejler det, bruges de gemte. */
    fun refresh(ctx: Context) {
        val rapport = httpGet(BASE + "data/rapport.json")
        JSONObject(rapport) // tjek at det er gyldigt
        val rettelse = httpGet(BASE + "data/rettelse.json")
        JSONObject(rettelse)
        prefs(ctx).edit().putString("qReport", rapport).putString("qFix", rettelse).apply()
    }

    fun report(ctx: Context): JSONObject? =
        prefs(ctx).getString("qReport", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    private fun fix(ctx: Context): JSONObject? =
        prefs(ctx).getString("qFix", null)?.let { runCatching { JSONObject(it) }.getOrNull() }

    /** Om rettelsen bruges for kort (1 til 4 dage) og lang (5 til 9 dage) sigt. */
    fun inUse(ctx: Context, area: String): Pair<Boolean, Boolean> {
        val brug = fix(ctx)?.optJSONObject("omraader")?.optJSONObject(area)?.optJSONObject("brug")
            ?: return false to false
        return brug.optBoolean("kort", false) to brug.optBoolean("lang", false)
    }

    /**
     * Retter spotprisen for prognosetimer, men kun hvis rettelsen har ramt bedre end den
     * rå prognose de sidste 4 uger. Kendte priser røres ikke.
     */
    fun apply(ctx: Context, data: PriceData): PriceData {
        val area = fix(ctx)?.optJSONObject("omraader")?.optJSONObject(data.area) ?: return data
        val brug = area.optJSONObject("brug") ?: return data
        val skaev = area.optJSONObject("skaevhed") ?: return data
        val today = LocalDate.now(CPH)
        val hours = data.hours.map { h ->
            if (!h.forecast) return@map h
            val days = ChronoUnit.DAYS.between(today, Planner.dateOf(h.start)).toInt()
            if (days !in 1..9) return@map h
            val use = if (days <= 4) brug.optBoolean("kort") else brug.optBoolean("lang")
            if (!use) return@map h
            val arr = skaev.optJSONArray(days.toString()) ?: return@map h
            val b = arr.optDouble(Fmt.hour(h.start), 0.0)
            if (b.isNaN() || b == 0.0) return@map h
            h.copy(
                spot = h.spot - b,
                spotLo = if (h.spotLo.isNaN()) h.spotLo else h.spotLo - b,
                spotHi = if (h.spotHi.isNaN()) h.spotHi else h.spotHi - b,
                fix = b
            )
        }
        return PriceData(hours, data.okSources, data.failedSources, data.fetchedAt, data.area, data.supplierId)
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", "Ladetid/1.6 (privat Android app)")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
