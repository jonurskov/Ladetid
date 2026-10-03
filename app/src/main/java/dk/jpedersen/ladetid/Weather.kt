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

    // Omtrentlig solindstråling på en skyfri dag (MJ pr. m²) for januar til december
    private val CLEAR_SKY = doubleArrayOf(5.0, 9.0, 15.0, 21.0, 26.0, 28.0, 27.0, 23.0, 17.0, 11.0, 6.0, 4.0)

    fun fetch(): List<DayWeather> {
        val dkWind = daily(DK_WIND).first
        val deWind = daily(DE_WIND).first
        val dkSun = daily(DK_SUN).second
        val deSun = daily(DE_SUN).second
        val dates = dkWind.keys.sorted()
        return dates.mapNotNull { d ->
            val clear = CLEAR_SKY[d.monthValue - 1]
            DayWeather(
                d,
                dkWind[d] ?: return@mapNotNull null,
                deWind[d] ?: Double.NaN,
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
                put("d", it.date.toString()); put("dw", it.dkWind); put("ew", nz(it.deWind))
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
                d to DayWeather(d, o.getDouble("dw"), o.getDouble("ew"), o.getDouble("ds"), o.getDouble("es"))
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
        dayAvg < periodAvg * 0.9 -> "Billig dag"
        dayAvg > periodAvg * 1.1 -> "Dyr dag"
        else -> "Middel"
    }

    fun reasons(w: DayWeather?, date: LocalDate): String {
        val parts = mutableListOf<String>()
        if (w != null) {
            val dkWindy = w.dkWind >= 8
            val deWindy = w.deWind >= 8
            val strong = (!dkWindy || w.dkWind >= 11) && (!deWindy || w.deWind >= 11)
            val windWord = if (strong) "Meget vind" else "God vind"
            when {
                dkWindy && deWindy -> parts.add("$windWord i Danmark og Nordtyskland")
                dkWindy -> parts.add("$windWord i Danmark")
                deWindy -> parts.add("$windWord i Nordtyskland")
            }
            val dkSunny = w.dkSun >= 0.6
            val deSunny = w.deSun >= 0.6
            when {
                dkSunny && deSunny -> parts.add("sol i Danmark og Tyskland midt på dagen")
                deSunny -> parts.add("sol i Tyskland midt på dagen")
                dkSunny -> parts.add("sol i Danmark midt på dagen")
            }
            if (parts.isEmpty()) {
                val calm = w.dkWind in 0.0..5.0 && (w.deWind.isNaN() || w.deWind <= 5.0)
                val cloudy = w.dkSun in 0.0..0.35 && (w.deSun.isNaN() || w.deSun <= 0.35)
                when {
                    calm && cloudy -> parts.add("Svag vind og overskyet i Danmark og Tyskland")
                    calm -> parts.add("Svag vind i Danmark og Tyskland")
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
}
