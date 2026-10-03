package dk.jpedersen.ladetid

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.View
import kotlin.math.max
import kotlin.math.min

/** Søjlediagram over timepriser. Billige timer er grønne, dyre er røde. */
class PriceChartView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null
) : View(context, attrs) {

    private var hours: List<HourPrice> = emptyList()
    private var windowHours: Set<Long> = emptySet()
    private var selected = -1

    private val dp = resources.displayMetrics.density
    private val barPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val gridPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#E3DED3"); strokeWidth = 1f * dp
    }
    private val labelPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.parseColor("#6B756F"); textSize = 10.5f * dp
    }
    private val markPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E2A24") }
    private val tipBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.parseColor("#1E2A24") }
    private val tipText = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = Color.WHITE; textSize = 12f * dp; isFakeBoldText = true
    }

    private val cheap = Color.parseColor("#2E9B62")
    private val mid = Color.parseColor("#E0A43A")
    private val dear = Color.parseColor("#C2512F")

    fun setData(hours: List<HourPrice>, windows: List<ChargeWindow>) {
        this.hours = hours
        windowHours = windows.flatMap { w -> (0 until w.hours).map { w.start + it * HOUR_MS } }.toSet()
        selected = -1
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (hours.isEmpty()) {
            canvas.drawText("Ingen data endnu", 0f, height / 2f, labelPaint)
            return
        }

        val left = 30f * dp
        val top = 26f * dp
        val bottom = height - 30f * dp
        val chartW = width - left
        val prices = hours.map { it.price }
        val maxP = max(prices.max() * 1.08, 0.5)
        val minP = min(prices.min(), 0.0)
        fun y(v: Double) = (bottom - (v - minP) / (maxP - minP) * (bottom - top)).toFloat()

        // Hjælpelinjer
        val step = niceStep(maxP)
        var g = 0.0
        while (g <= maxP) {
            val yy = y(g)
            canvas.drawLine(left, yy, width.toFloat(), yy, gridPaint)
            canvas.drawText(String.format("%.1f", g).replace('.', ','), 0f, yy + 4 * dp, labelPaint)
            g += step
        }

        // Rangordning til farver
        val sorted = prices.sorted()
        val slot = chartW / hours.size
        val gap = if (slot > 4 * dp) 1.2f * dp else 0.5f * dp

        hours.forEachIndexed { i, h ->
            val rank = sorted.indexOf(h.price).toFloat() / max(1, sorted.size - 1)
            barPaint.color = blend(rank)
            barPaint.alpha = if (h.forecast) 120 else 255
            val x0 = left + i * slot + gap / 2
            val x1 = left + (i + 1) * slot - gap / 2
            val y0 = y(max(h.price, 0.0))
            val y1 = y(min(h.price, 0.0))
            canvas.drawRect(x0, min(y0, y1), x1, max(y0, y1), barPaint)

            // Markering af de valgte ladetimer
            if (h.start in windowHours) {
                canvas.drawRoundRect(RectF(x0, bottom + 3 * dp, x1, bottom + 7 * dp), 2 * dp, 2 * dp, markPaint)
            }

            // Dagsskel ved midnat
            if (Fmt.hour(h.start) == 0 && i > 0) {
                val x = left + i * slot
                canvas.drawLine(x, top - 8 * dp, x, bottom, gridPaint)
            }
            if (Fmt.hour(h.start) == 12) {
                val label = Fmt.shortDay(Planner.dateOf(h.start))
                val tw = labelPaint.measureText(label)
                canvas.drawText(label, left + i * slot - tw / 2, bottom + 22 * dp, labelPaint)
            }
        }

        // Valgt søjle
        if (selected in hours.indices) {
            val h = hours[selected]
            val x = left + selected * slot + slot / 2
            canvas.drawLine(x, top - 4 * dp, x, bottom, markPaint)
            val txt = "${Fmt.shortDay(Planner.dateOf(h.start))} kl. ${Fmt.clock(h.start)}: ${Fmt.kr(h.price)}" +
                if (h.forecast) " (prognose)" else ""
            val tw = tipText.measureText(txt)
            val bx = (x - tw / 2 - 8 * dp).coerceIn(0f, width - tw - 16 * dp)
            canvas.drawRoundRect(RectF(bx, 0f, bx + tw + 16 * dp, 20 * dp), 10 * dp, 10 * dp, tipBg)
            canvas.drawText(txt, bx + 8 * dp, 14.5f * dp, tipText)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (hours.isEmpty()) return false
        when (event.action) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                val left = 30f * dp
                val slot = (width - left) / hours.size
                selected = ((event.x - left) / slot).toInt().coerceIn(0, hours.size - 1)
                invalidate()
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                parent?.requestDisallowInterceptTouchEvent(false)
                performClick()
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    override fun performClick(): Boolean = super.performClick()

    private fun niceStep(maxP: Double): Double = when {
        maxP <= 1.2 -> 0.25
        maxP <= 3.0 -> 0.5
        maxP <= 6.0 -> 1.0
        else -> 2.0
    }

    private fun blend(t: Float): Int =
        if (t < 0.5f) mix(cheap, mid, t * 2) else mix(mid, dear, (t - 0.5f) * 2)

    private fun mix(a: Int, b: Int, t: Float): Int = Color.rgb(
        (Color.red(a) + (Color.red(b) - Color.red(a)) * t).toInt(),
        (Color.green(a) + (Color.green(b) - Color.green(a)) * t).toInt(),
        (Color.blue(a) + (Color.blue(b) - Color.blue(a)) * t).toInt()
    )
}
