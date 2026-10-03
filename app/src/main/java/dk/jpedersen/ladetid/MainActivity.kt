package dk.jpedersen.ladetid

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.Html
import android.text.method.LinkMovementMethod
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.Switch
import android.widget.TextView
import androidx.core.content.ContextCompat

class MainActivity : Activity() {

    private lateinit var subtitle: TextView
    private lateinit var heroWhen: TextView
    private lateinit var heroDetail: TextView
    private lateinit var chart: PriceChartView
    private lateinit var dayList: LinearLayout
    private lateinit var hoursValue: TextView
    private lateinit var areaDk1: TextView
    private lateinit var areaDk2: TextView
    private lateinit var notifySwitch: Switch
    private lateinit var sources: TextView
    private lateinit var chartNote: TextView
    private lateinit var supplierValue: TextView
    private lateinit var extraValue: TextView
    private lateinit var planKwhValue: TextView
    private lateinit var planKwValue: TextView
    private lateinit var planDay: TextView
    private lateinit var planHour: TextView
    private lateinit var planResult: LinearLayout

    private val kwOptions = floatArrayOf(2.3f, 3.7f, 7.4f, 11f, 22f)

    @Volatile private var loading = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        subtitle = findViewById(R.id.subtitle)
        heroWhen = findViewById(R.id.heroWhen)
        heroDetail = findViewById(R.id.heroDetail)
        chart = findViewById(R.id.chart)
        dayList = findViewById(R.id.dayList)
        hoursValue = findViewById(R.id.hoursValue)
        areaDk1 = findViewById(R.id.areaDk1)
        areaDk2 = findViewById(R.id.areaDk2)
        notifySwitch = findViewById(R.id.notifySwitch)
        sources = findViewById(R.id.sources)
        chartNote = findViewById(R.id.chartNote)
        supplierValue = findViewById(R.id.supplierValue)
        extraValue = findViewById(R.id.extraValue)
        planKwhValue = findViewById(R.id.planKwhValue)
        planKwValue = findViewById(R.id.planKwValue)
        planDay = findViewById(R.id.planDay)
        planHour = findViewById(R.id.planHour)
        planResult = findViewById(R.id.planResult)
        ensureDeadline()

        Scheduler.ensureChannel(this)
        askNotificationPermission()
        Refresher.startPeriodic(this)

        findViewById<View>(R.id.refresh).setOnClickListener { load() }
        findViewById<View>(R.id.hoursMinus).setOnClickListener { changeHours(-1) }
        findViewById<View>(R.id.hoursPlus).setOnClickListener { changeHours(+1) }
        areaDk1.setOnClickListener { changeArea("DK1") }
        areaDk2.setOnClickListener { changeArea("DK2") }
        findViewById<View>(R.id.supplierRow).setOnClickListener { pickSupplier() }
        findViewById<View>(R.id.extraMinus).setOnClickListener { changeExtra(-1) }
        findViewById<View>(R.id.extraPlus).setOnClickListener { changeExtra(+1) }
        bindSwitch(R.id.transportSwitch, Store.inclTransport(this)) { Store.setInclTransport(this, it) }
        bindSwitch(R.id.taxSwitch, Store.inclTax(this)) { Store.setInclTax(this, it) }
        bindSwitch(R.id.vatSwitch, Store.inclVat(this)) { Store.setInclVat(this, it) }
        findViewById<View>(R.id.planKwhMinus).setOnClickListener { changeKwh(-5) }
        findViewById<View>(R.id.planKwhPlus).setOnClickListener { changeKwh(+5) }
        findViewById<View>(R.id.planKwMinus).setOnClickListener { changeKw(-1) }
        findViewById<View>(R.id.planKwPlus).setOnClickListener { changeKw(+1) }
        planDay.setOnClickListener { pickPlanDay() }
        planHour.setOnClickListener { pickPlanHour() }
        bindSwitch(R.id.planNotifySwitch, Store.planNotify(this)) {
            Store.setPlanNotify(this, it)
            if (it) askNotificationPermission()
        }
        notifySwitch.isChecked = Store.notify(this)
        notifySwitch.setOnCheckedChangeListener { _, on ->
            Store.setNotify(this, on)
            if (on) askNotificationPermission()
            Store.load(this)?.let { Refresher.replan(this, it) }
        }

        renderSettings()
        Store.load(this)?.let { render(it) }
        load()
    }

    override fun onResume() {
        super.onResume()
        // Opdater visningen hvis data er mere end en time gammelt
        val cached = Store.load(this)
        if (cached != null && System.currentTimeMillis() - cached.fetchedAt > HOUR_MS) load()
    }

    private fun load() {
        if (loading) return
        loading = true
        subtitle.text = "Henter priser…"
        Thread {
            val data = try {
                Refresher.refresh(applicationContext)
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                loading = false
                if (data != null) render(data)
                else {
                    val cached = Store.load(this)
                    if (cached != null) render(cached)
                    subtitle.text = "Kunne ikke hente nye priser. Tjek forbindelsen."
                }
            }
        }.start()
    }

    private fun changeHours(delta: Int) {
        val v = (Store.chargeHours(this) + delta).coerceIn(1, 10)
        Store.setChargeHours(this, v)
        renderSettings()
        Store.load(this)?.let {
            Refresher.replan(this, it)
            render(it)
        }
    }

    private fun bindSwitch(id: Int, value: Boolean, save: (Boolean) -> Unit) {
        val sw = findViewById<Switch>(id)
        sw.isChecked = value
        sw.setOnCheckedChangeListener { _, on ->
            save(on)
            redraw()
        }
    }

    /** Tegner igen med de gemte priser, fx når man ændrer hvad der er med i prisen. */
    private fun redraw() {
        renderSettings()
        Store.load(this)?.let {
            Refresher.replan(this, it)
            render(it)
        }
    }

    private fun changeExtra(delta: Int) {
        Store.setExtraOre(this, (Store.extraOre(this) + delta).coerceIn(0, 99))
        redraw()
    }

    /** Standard for ladeplanen: næste fredag kl. 07. */
    private fun ensureDeadline() {
        if (Store.planDeadline(this) > System.currentTimeMillis()) return
        var d = java.time.LocalDate.now(CPH)
        while (d.dayOfWeek != java.time.DayOfWeek.FRIDAY) d = d.plusDays(1)
        var t = d.atTime(7, 0).atZone(CPH).toInstant().toEpochMilli()
        if (t <= System.currentTimeMillis()) t += 7 * 24 * HOUR_MS
        Store.setPlanDeadline(this, t)
    }

    private fun changeKwh(delta: Int) {
        Store.setPlanKwh(this, (Store.planKwh(this) + delta).coerceIn(5, 150))
        redraw()
    }

    private fun changeKw(step: Int) {
        val cur = kwOptions.indexOfFirst { Math.abs(it - Store.planKw(this)) < 0.05f }.coerceAtLeast(0)
        Store.setPlanKw(this, kwOptions[(cur + step).coerceIn(0, kwOptions.size - 1)])
        redraw()
    }

    private fun pickPlanDay() {
        val today = java.time.LocalDate.now(CPH)
        val days = (0 until Planner.DAYS).map { today.plusDays(it.toLong()) }
        val names = days.map { Fmt.day(it) as CharSequence }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Færdig senest hvilken dag?")
            .setItems(names) { _, which ->
                val hour = Fmt.hour(Store.planDeadline(this))
                Store.setPlanDeadline(this, days[which].atTime(hour, 0).atZone(CPH).toInstant().toEpochMilli())
                redraw()
            }
            .show()
    }

    private fun pickPlanHour() {
        val names = (0..23).map { "kl. %02d".format(it) as CharSequence }.toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("Færdig senest hvilket klokkeslæt?")
            .setItems(names) { _, which ->
                val day = Planner.dateOf(Store.planDeadline(this))
                Store.setPlanDeadline(this, day.atTime(which, 0).atZone(CPH).toInstant().toEpochMilli())
                redraw()
            }
            .show()
    }

    private fun pickSupplier() {
        supplierValue.text = "Henter…"
        val area = Store.area(this)
        Thread {
            val list = try {
                PriceRepository.suppliers().filter { it.area.isEmpty() || it.area == area }
            } catch (e: Exception) {
                null
            }
            runOnUiThread {
                renderSettings()
                if (list.isNullOrEmpty()) {
                    subtitle.text = "Kunne ikke hente listen over netselskaber"
                    return@runOnUiThread
                }
                val names = (listOf("Intet netselskab (kun Energinet)") + list.map { it.name })
                    .map { it as CharSequence }.toTypedArray()
                AlertDialog.Builder(this)
                    .setTitle("Vælg netselskab")
                    .setItems(names) { _, which ->
                        if (which == 0) Store.setSupplier(this, "", "")
                        else Store.setSupplier(this, list[which - 1].id, list[which - 1].name)
                        renderSettings()
                        load()
                    }
                    .show()
            }
        }.start()
    }

    private fun changeArea(area: String) {
        if (area == Store.area(this)) return
        Store.setArea(this, area)
        // Netselskabet hører til et prisområde, så det nulstilles
        Store.setSupplier(this, "", "")
        renderSettings()
        load()
    }

    private fun renderSettings() {
        val h = Store.chargeHours(this)
        hoursValue.text = if (h == 1) "1 time" else "$h timer"
        val dk1 = Store.area(this) == "DK1"
        supplierValue.text = Store.supplierName(this).ifEmpty { "Vælg" }
        extraValue.text = "${Store.extraOre(this)} øre"
        planKwhValue.text = "${Store.planKwh(this)} kWh"
        planKwValue.text = String.format(java.util.Locale("da", "DK"), "%.1f kW", Store.planKw(this)).replace(",0 ", " ")
        val dl = Store.planDeadline(this)
        planDay.text = Fmt.day(Planner.dateOf(dl))
        planHour.text = "kl. ${Fmt.clock(dl)}"
        styleToggle(areaDk1, dk1)
        styleToggle(areaDk2, !dk1)
    }

    private fun styleToggle(v: TextView, on: Boolean) {
        if (on) {
            v.setBackgroundResource(R.drawable.hero_bg)
            v.setTextColor(Color.WHITE)
        } else {
            v.setBackgroundResource(R.drawable.chip_bg)
            v.setTextColor(ContextCompat.getColor(this, R.color.accent))
        }
    }

    private fun render(stored: PriceData) {
        val data = PriceData(Pricing.apply(this, stored.hours), stored.okSources, stored.failedSources,
            stored.fetchedAt, stored.area, stored.supplierId)
        chartNote.text = "kr/kWh med ${Pricing.describe(this)}. Lyse søjler er prognose, mørke er kendte priser. Tryk på grafen for at se en time."
        val now = System.currentTimeMillis()
        val currentHour = now - Math.floorMod(now, HOUR_MS)
        val windows = Planner.plan(data.hours, Store.chargeHours(this), now)

        val areaName = if (data.area == "DK1") "Vestdanmark" else "Østdanmark"
        subtitle.text = "$areaName · opdateret ${Fmt.time(data.fetchedAt)}"

        // Næste bedste ladetid (kun de sikre dage)
        val notifyUntil = Planner.dateOf(now).plusDays(Planner.NOTIFY_DAYS.toLong())
        val next = windows.firstOrNull { it.end > now && it.day.isBefore(notifyUntil) }
        if (next == null) {
            heroWhen.text = "Ingen data"
            heroDetail.text = ""
        } else if (next.start <= now) {
            heroWhen.text = "Nu, til kl. ${Fmt.clock(next.end)}"
            heroDetail.text = "Ca. ${Fmt.kr(next.avg)} pr. kWh i snit"
        } else {
            heroWhen.text = "${Fmt.day(next.day)} ${Fmt.span(next)}"
            val remind = if (Store.notify(this) && next.start - HOUR_MS > now)
                " · besked kl. ${Fmt.clock(next.start - HOUR_MS)}" else ""
            heroDetail.text = "Ca. ${Fmt.kr(next.avg)} pr. kWh i snit$remind"
        }

        // Ladeplan
        val deadline = Store.planDeadline(this)
        val plan = if (deadline > now) Planner.energyPlan(
            data.hours, Store.planKwh(this).toDouble(), Store.planKw(this).toDouble(), now, deadline
        ) else null
        renderPlan(plan, deadline)

        // Graf fra nu og 9 dage frem
        val today = Planner.dateOf(now)
        val lastDay = today.plusDays((Planner.DAYS - 1).toLong())
        val roughFrom = today.plusDays(Planner.SURE_DAYS.toLong()).atStartOfDay(CPH).toInstant().toEpochMilli()
        val shown = data.hours.filter { it.start >= currentHour && !Planner.dateOf(it.start).isAfter(lastDay) }
        chart.setData(shown, windows, plan?.hours ?: emptySet(), roughFrom)

        // Liste dag for dag
        dayList.removeAllViews()
        val byDay = data.hours.groupBy { Planner.dateOf(it.start) }
        val periodAvg = shown.map { it.price }.average()
        val weather = WeatherRepository.load(this)
        val byStart = data.hours.associateBy { it.start }
        windows.forEach { w ->
            val dayHours = byDay[w.day].orEmpty()
            val avg = dayHours.map { it.price }.average()
            val saving = if (avg > 0) ((1 - w.avg / avg) * 100).toInt() else null
            val inWin = (0 until w.hours).mapNotNull { byStart[w.start + it * HOUR_MS] }
            val lo = inWin.map { it.priceLo }.filter { !it.isNaN() }
            val hi = inWin.map { it.priceHi }.filter { !it.isNaN() }
            val range = if (lo.size == inWin.size && hi.size == inWin.size && inWin.isNotEmpty())
                lo.average() to hi.average() else null
            val status = when {
                !w.forecast -> "kendt pris"
                w.day.isBefore(today.plusDays(Planner.SURE_DAYS.toLong())) -> "prognose"
                else -> "groft skøn"
            }
            val why = listOf(Explain.level(avg, periodAvg), Explain.reasons(weather[w.day], w.day))
                .filter { it.isNotEmpty() }.joinToString(". ")
            dayList.addView(dayRow(w, saving, status, why, range))
        }
        if (windows.isEmpty()) {
            dayList.addView(text("Ingen priser at vise endnu.", 14f, R.color.muted))
        }

        // Kilder
        val okText = data.okSources.joinToString(" og ")
        val failText = if (data.failedSources.isNotEmpty())
            "<br>Svarede ikke: ${data.failedSources.joinToString(", ")}" else ""
        sources.text = Html.fromHtml(
            "Spotpris er gennemsnittet af $okText. " +
                supplierNote(data) + "$failText<br>" +
                "Vind og sol er hentet fra vejrudsigten og er et skøn over, hvad der påvirker prisen, " +
                "ikke en præcis forklaring.<br>" +
                "Data: <a href=\"https://stromligning.dk\">Strømligning</a>, " +
                "<a href=\"https://elpriser.org\">elpriser.org</a> og " +
                "<a href=\"https://open-meteo.com\">Open-Meteo</a> (vejr)",
            Html.FROM_HTML_MODE_LEGACY
        )
        sources.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun supplierNote(data: PriceData): String = when {
        !Store.inclTransport(this) -> "Transport er ikke med."
        data.supplierId.isEmpty() -> "Vælg dit netselskab for at få nettariffen med."
        else -> "Nettarif fra ${Store.supplierName(this)}."
    }

    private fun renderPlan(plan: EnergyPlan?, deadline: Long) {
        planResult.removeAllViews()
        val ink = R.color.ink
        val muted = R.color.muted
        if (plan == null) {
            planResult.addView(text("Vælg et tidspunkt længere fremme.", 14f, muted))
            return
        }
        if (plan.blocks.isEmpty()) {
            planResult.addView(text("Der er ingen priser før det tidspunkt endnu.", 14f, muted))
            return
        }
        planResult.addView(text("Lad i disse perioder:", 13f, muted))
        plan.blocks.forEach { b ->
            val line = "${Fmt.dayDate(Planner.dateOf(b.start)).replaceFirstChar { it.uppercase() }} " +
                "${Fmt.span(b.start, b.end)}"
            planResult.addView(text(line, 15f, ink, bold = true).apply { setPadding(0, dp(4), 0, 0) })
        }
        val kwh = Store.planKwh(this)
        val summary = "Ca. ${Fmt.kr0(plan.cost)} for ${kwh - plan.missingKwh.toInt()} kWh " +
            "(${Fmt.kr(plan.avgPrice)} pr. kWh i snit)"
        planResult.addView(text(summary, 14f, ink).apply { setPadding(0, dp(10), 0, 0) })
        val saved = plan.costNow - plan.cost
        if (saved >= 1) {
            planResult.addView(text("Det er ca. ${Fmt.kr0(saved)} billigere end at lade med det samme.", 13f, muted))
        }
        if (plan.missingKwh > 0.5) {
            planResult.addView(text("Der er ikke tid nok til hele mængden. Der mangler ca. " +
                "${plan.missingKwh.toInt()} kWh, selv hvis du lader i alle timer frem til " +
                "kl. ${Fmt.clock(deadline)}.", 13f, R.color.warn))
        }
        val note = when {
            plan.rough -> "Planen bruger dage, hvor prisen kun er et groft skøn. Den kan ændre sig meget, " +
                "så kig igen et par dage før."
            plan.forecast -> "Planen bygger delvist på prognoser og opdateres, når priserne bliver kendt."
            else -> "Planen bygger på kendte priser."
        }
        planResult.addView(text(note, 12.5f, muted).apply { setPadding(0, dp(6), 0, 0) })
    }

    private fun dayRow(
        w: ChargeWindow,
        saving: Int?,
        status: String,
        why: String,
        range: Pair<Double, Double>?
    ): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(10), 0, dp(10))
        }
        val leftCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
        }
        leftCol.addView(text(Fmt.day(w.day), 15f, R.color.ink, bold = true))
        leftCol.addView(text("${Fmt.span(w)} · $status", 13f, R.color.muted))
        if (why.isNotEmpty()) {
            leftCol.addView(text(why, 12.5f, R.color.muted).apply {
                setTypeface(Typeface.DEFAULT, Typeface.ITALIC)
                setPadding(0, dp(2), dp(8), 0)
            })
        }

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        rightCol.addView(text(Fmt.kr(w.avg), 15f, R.color.accent, bold = true).apply { gravity = Gravity.END })
        if (range != null) {
            val r = String.format(java.util.Locale("da", "DK"), "%.2f til %.2f", range.first, range.second)
            rightCol.addView(text("ca. $r", 12f, R.color.muted).apply { gravity = Gravity.END })
        } else if (saving != null && saving > 0) {
            rightCol.addView(text("$saving% under dagens snit", 12f, R.color.muted).apply { gravity = Gravity.END })
        }
        row.addView(leftCol)
        row.addView(rightCol)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            if (dayList.childCount > 0) addView(View(context).apply {
                setBackgroundColor(ContextCompat.getColor(context, R.color.line))
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(1))
            })
            addView(row)
        }
    }

    private fun text(s: String, size: Float, colorRes: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        textSize = size
        setTextColor(ContextCompat.getColor(context, colorRes))
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    private fun askNotificationPermission() {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 1)
        }
    }
}
