package dk.jpedersen.ladetid

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalDateTime

/**
 * Vind og sol pr. dag i Danmark og Tyskland.
 * wind: gennemsnitlig vindhastighed i 120 m højde (m/s), som er vindmøllehøjde.
 * sun: dagens solindstråling i forhold til en skyfri dag i samme måned (0 til 1).
 */
data class DayWeather(
    val date: LocalDate,
    val dkWind: Double,
    val deWind: Double,
    val seWind: Double,
    val dkSun: Double,
    val deSun: Double
)

/**
 * Henter vejrprognoser fra Open-Meteo (https://open-meteo.com), som bygger på bl.a.
 * DMI's, DWD's og ECMWF's vejrmodeller. Gratis til ikke kommerciel brug, CC BY 4.0.
 */
object WeatherRepository {

    private class Point(val lat: Double, val lon: Double)

    // Steder valgt fordi de ligger, hvor meget af vind og solstrømmen produceres
    private val DK_WIND = Point(56.0, 8.1)    // Vestjylland ud mod Nordsøen
    private val DK_SUN = Point(55.8, 10.5)    // Midt i Danmark
    private val DE_WIND = Point(54.2, 8.8)    // Nordfrisland ved Nordsøen
    private val DE_SUN = Point(50.0, 10.0)    // Midt i Tyskland
    private val SE_WIND = Point(57.0, 13.5)   // Sydsverige, hvor mange svenske vindmøller står

    // Omtrentlig solindstråling på en skyfri dag (MJ pr. m²) for januar til december
    private val CLEAR_SKY = doubleArrayOf(5.0, 9.0, 15.0, 21.0, 26.0, 28.0, 27.0, 23.0, 17.0, 11.0, 6.0, 4.0)

    fun fetch(): List<DayWeather> {
        val dkWind = daily(DK_WIND).first
        val deWind = daily(DE_WIND).first
        val seWind = try { daily(SE_WIND).first } catch (e: Exception) { emptyMap() }
        val dkSun = daily(DK_SUN).second
        val deSun = daily(DE_SUN).second
        val dates = dkWind.keys.sorted()
        return dates.mapNotNull { d ->
            val clear = CLEAR_SKY[d.monthValue - 1]
            DayWeather(
                d,
                dkWind[d] ?: return@mapNotNull null,
                deWind[d] ?: Double.NaN,
                seWind[d] ?: Double.NaN,
                (dkSun[d] ?: Double.NaN) / clear,
                (deSun[d] ?: Double.NaN) / clear
            )
        }
    }

    /** Returnerer (gennemsnitlig vind pr. dag, samlet solindstråling i MJ pr. m² pr. dag). */
    private fun daily(p: Point): Pair<Map<LocalDate, Double>, Map<LocalDate, Double>> {
        val url = "https://api.open-meteo.com/v1/forecast?latitude=${p.lat}&longitude=${p.lon}" +
            "&hourly=wind_speed_120m,shortwave_radiation&wind_speed_unit=ms" +
            "&timezone=Europe%2FCopenhagen&forecast_days=10"
        val hourly = JSONObject(httpGet(url)).getJSONObject("hourly")
        val times = hourly.getJSONArray("time")
        val wind = hourly.getJSONArray("wind_speed_120m")
        val rad = hourly.getJSONArray("shortwave_radiation")

        val windSum = HashMap<LocalDate, Double>()
        val windN = HashMap<LocalDate, Int>()
        val radSum = HashMap<LocalDate, Double>()
        for (i in 0 until times.length()) {
            val d = LocalDateTime.parse(times.getString(i)).toLocalDate()
            val w = wind.optDouble(i, Double.NaN)
            if (!w.isNaN()) {
                windSum[d] = (windSum[d] ?: 0.0) + w
                windN[d] = (windN[d] ?: 0) + 1
            }
            val r = rad.optDouble(i, Double.NaN)
            // W pr. m² i en time svarer til 0,0036 MJ pr. m²
            if (!r.isNaN()) radSum[d] = (radSum[d] ?: 0.0) + r * 0.0036
        }
        val windAvg = windSum.filter { (windN[it.key] ?: 0) >= 20 }.mapValues { it.value / windN.getValue(it.key) }
        return windAvg to radSum
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 25_000
        c.setRequestProperty("User-Agent", "Ladetid/1.2 (privat Android app)")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }

    fun save(ctx: Context, list: List<DayWeather>) {
        val arr = JSONArray()
        list.forEach {
            arr.put(JSONObject().apply {
                put("d", it.date.toString()); put("dw", it.dkWind); put("ew", nz(it.deWind)); put("sw", nz(it.seWind))
                put("ds", nz(it.dkSun)); put("es", nz(it.deSun))
            })
        }
        ctx.getSharedPreferences("ladetid", Context.MODE_PRIVATE).edit()
            .putString("weather", arr.toString()).apply()
    }

    fun load(ctx: Context): Map<LocalDate, DayWeather> {
        val raw = ctx.getSharedPreferences("ladetid", Context.MODE_PRIVATE).getString("weather", null)
            ?: return emptyMap()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).associate {
                val o = arr.getJSONObject(it)
                val d = LocalDate.parse(o.getString("d"))
                d to DayWeather(d, o.getDouble("dw"), o.getDouble("ew"), o.optDouble("sw", -1.0),
                    o.getDouble("ds"), o.getDouble("es"))
            }
        } catch (e: Exception) {
            emptyMap()
        }
    }

    // JSON kan ikke gemme NaN, så ukendte værdier gemmes som -1
    private fun nz(v: Double) = if (v.isNaN()) -1.0 else v
}

/** Laver en kort, forsigtig forklaring på dagens prisniveau ud fra vejret. */
object Explain {

    fun level(dayAvg: Double, periodAvg: Double): String = when {
        dayAvg < periodAvg * 0.9 -> "Lav pris"
        dayAvg > periodAvg * 1.1 -> "Høj pris"
        else -> "Middelpris"
    }

    fun reasons(w: DayWeather?, date: LocalDate): String {
        val parts = mutableListOf<String>()
        if (w != null) {
            val windy = listOf(
                "Danmark" to w.dkWind, "Nordtyskland" to w.deWind, "Sydsverige" to w.seWind
            ).filter { it.second >= 8 }
            if (windy.isNotEmpty()) {
                val word = if (windy.all { it.second >= 11 }) "Hård vind" else "Frisk vind"
                parts.add("$word i ${joinPlaces(windy.map { it.first })}")
            }
            val dkSunny = w.dkSun >= 0.6
            val deSunny = w.deSun >= 0.6
            when {
                dkSunny && deSunny -> parts.add("sol i Danmark og Tyskland midt på dagen")
                deSunny -> parts.add("sol i Tyskland midt på dagen")
                dkSunny -> parts.add("sol i Danmark midt på dagen")
            }
            if (parts.isEmpty()) {
                val calm = w.dkWind in 0.0..5.0 && (w.deWind.isNaN() || w.deWind <= 5.0) &&
                    (w.seWind < 0 || w.seWind <= 5.0)
                val cloudy = w.dkSun in 0.0..0.35 && (w.deSun.isNaN() || w.deSun <= 0.35)
                when {
                    calm && cloudy -> parts.add("Svag vind og overskyet i hele området")
                    calm -> parts.add("Svag vind i Danmark, Tyskland og Sverige")
                    cloudy -> parts.add("Overskyet og kun lidt vind")
                }
            }
        }
        if (date.dayOfWeek == DayOfWeek.SATURDAY || date.dayOfWeek == DayOfWeek.SUNDAY) {
            parts.add("weekend med lavere forbrug")
        }
        if (parts.isEmpty()) return ""
        val s = parts.joinToString(", ")
        return s.replaceFirstChar { it.uppercase() }
    }

    private fun joinPlaces(p: List<String>): String =
        if (p.size == 1) p[0] else p.dropLast(1).joinToString(", ") + " og " + p.last()
}

/**
 * Vandstand i de norske vandmagasiner fra NVE (Norges vassdrags- og energidirektorat),
 * https://www.nve.no, åbne data under NLOD. Opdateres en gang om ugen, så den hentes
 * højst en gang i døgnet. Område 2 er Sydnorge (NO2), som har kabel til Jylland.
 */
data class Hydro(val fill: Double, val median: Double, val week: Int)

object HydroRepository {
    private const val AREA = 2

    fun refreshIfOld(ctx: Context) {
        val prefs = ctx.getSharedPreferences("ladetid", Context.MODE_PRIVATE)
        val today = LocalDate.now(CPH).toString()
        if (prefs.getString("hydroDate", "") == today) return
        val now = JSONArray(httpGet("https://biapi.nve.no/magasinstatistikk/api/Magasinstatistikk/HentOffentligData"))
        var best: JSONObject? = null
        for (i in 0 until now.length()) {
            val o = now.getJSONObject(i)
            if (o.optString("omrType") != "EL" || o.optInt("omrnr") != AREA) continue
            if (best == null || key(o) > key(best)) best = o
        }
        val latest = best ?: return
        val week = latest.getInt("iso_uke")
        val stats = JSONArray(httpGet("https://biapi.nve.no/magasinstatistikk/api/Magasinstatistikk/HentOffentligDataMinMaxMedian"))
        var median = Double.NaN
        for (i in 0 until stats.length()) {
            val o = stats.getJSONObject(i)
            if (o.optString("omrType") == "EL" && o.optInt("omrnr") == AREA && o.optInt("iso_uke") == week) {
                median = frac(o.optDouble("medianFyllingsGrad", Double.NaN))
            }
        }
        prefs.edit()
            .putString("hydroDate", today)
            .putFloat("hydroFill", frac(latest.getDouble("fyllingsgrad")).toFloat())
            .putFloat("hydroMedian", if (median.isNaN()) -1f else median.toFloat())
            .putInt("hydroWeek", week)
            .apply()
    }

    // Tal kan komme som brøk (0,78) eller procent (78)
    private fun frac(v: Double) = if (v > 1.5) v / 100 else v

    private fun key(o: JSONObject) = o.optInt("iso_aar") * 100 + o.optInt("iso_uke")

    fun load(ctx: Context): Hydro? {
        val prefs = ctx.getSharedPreferences("ladetid", Context.MODE_PRIVATE)
        val fill = prefs.getFloat("hydroFill", -1f)
        if (fill < 0) return null
        return Hydro(fill.toDouble(), prefs.getFloat("hydroMedian", -1f).toDouble(), prefs.getInt("hydroWeek", 0))
    }

    fun describe(h: Hydro): String {
        val pct = Math.round(h.fill * 100)
        if (h.median < 0) return "Sydnorske vandmagasiner var $pct % fyldt i uge ${h.week}. Nye tal fra NVE hver onsdag."
        val med = Math.round(h.median * 100)
        val diff = pct - med
        val effect = when {
            diff <= -5 -> "Der er mindre vand end normalt, og det holder prisen oppe, især når det ikke blæser."
            diff >= 5 -> "Der er mere vand end normalt, og det hjælper med at holde prisen nede."
            else -> "Det er omkring normalt for årstiden."
        }
        return "Sydnorske vandmagasiner var $pct % fyldt i uge ${h.week} (normalt $med %). $effect " +
            "Nye tal fra NVE hver onsdag."
    }

    private fun httpGet(url: String): String {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 15_000
        c.readTimeout = 40_000
        c.setRequestProperty("Accept", "application/json")
        c.setRequestProperty("User-Agent", "Ladetid/1.3 (privat Android app)")
        try {
            if (c.responseCode !in 200..299) throw IOException("HTTP ${c.responseCode}")
            return c.inputStream.bufferedReader().use { it.readText() }
        } finally {
            c.disconnect()
        }
    }
}
