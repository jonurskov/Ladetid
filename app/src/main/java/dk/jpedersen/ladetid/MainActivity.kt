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

        // Næste bedste ladetid
        val next = windows.firstOrNull { it.end > now }
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

        // Graf fra nu og fem dage frem
        val lastDay = Planner.dateOf(now).plusDays(4)
        val shown = data.hours.filter { it.start >= currentHour && !Planner.dateOf(it.start).isAfter(lastDay) }
        chart.setData(shown, windows)

        // Liste dag for dag
        dayList.removeAllViews()
        val dayAvg = shown.groupBy { Planner.dateOf(it.start) }
        windows.forEach { w ->
            val avg = dayAvg[w.day]?.map { it.price }?.average()
            val saving = if (avg != null && avg > 0) ((1 - w.avg / avg) * 100).toInt() else null
            dayList.addView(dayRow(w, saving))
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
                "Data: <a href=\"https://stromligning.dk\">Strømligning</a> og " +
                "<a href=\"https://elpriser.org\">elpriser.org</a>",
            Html.FROM_HTML_MODE_LEGACY
        )
        sources.movementMethod = LinkMovementMethod.getInstance()
    }

    private fun supplierNote(data: PriceData): String = when {
        !Store.inclTransport(this) -> "Transport er ikke med."
        data.supplierId.isEmpty() -> "Vælg dit netselskab for at få nettariffen med."
        else -> "Nettarif fra ${Store.supplierName(this)}."
    }

    private fun dayRow(w: ChargeWindow, saving: Int?): View {
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
        val sub = Fmt.span(w) + if (w.forecast) " · prognose" else " · kendt pris"
        leftCol.addView(text(sub, 13f, R.color.muted))

        val rightCol = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.END
        }
        rightCol.addView(text(Fmt.kr(w.avg), 15f, R.color.accent, bold = true).apply { gravity = Gravity.END })
        if (saving != null && saving > 0) {
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
