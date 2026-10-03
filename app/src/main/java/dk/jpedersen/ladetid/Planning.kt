package dk.jpedersen.ladetid

import android.Manifest
import android.app.AlarmManager
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.Instant
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Det billigste sammenhængende ladevindue på en given dag. */
data class ChargeWindow(
    val day: LocalDate,
    val start: Long,
    val hours: Int,
    val avg: Double,
    val forecast: Boolean
) {
    val end: Long get() = start + hours * HOUR_MS
}

object Planner {
    /**
     * Finder for hver af de næste [days] dage (i dag medregnet) det sammenhængende vindue
     * på [chargeHours] timer med den laveste gennemsnitspris. Vinduet skal starte på dagen,
     * men må gerne slutte efter midnat.
     */
    fun plan(hours: List<HourPrice>, chargeHours: Int, now: Long, days: Int = 5): List<ChargeWindow> {
        val byStart = hours.associateBy { it.start }
        val currentHour = now - Math.floorMod(now, HOUR_MS)
        val today = Instant.ofEpochMilli(now).atZone(CPH).toLocalDate()
        val result = mutableListOf<ChargeWindow>()

        for (d in 0 until days) {
            val date = today.plusDays(d.toLong())
            var best: ChargeWindow? = null
            for (h in hours) {
                if (h.start < currentHour) continue
                if (dateOf(h.start) != date) continue
                val window = (0 until chargeHours).map { byStart[h.start + it * HOUR_MS] }
                if (window.any { it == null }) continue
                val avg = window.sumOf { it!!.price } / chargeHours
                if (best == null || avg < best.avg) {
                    best = ChargeWindow(date, h.start, chargeHours, avg, window.any { it!!.forecast })
                }
            }
            best?.let { result.add(it) }
        }
        return result
    }

    fun dateOf(epochMs: Long): LocalDate = Instant.ofEpochMilli(epochMs).atZone(CPH).toLocalDate()
}

/** Danske tekster til tider og priser. */
object Fmt {
    private val DA = Locale("da", "DK")
    private val dayFmt = DateTimeFormatter.ofPattern("EEEE d. MMM", DA)
    private val shortDay = DateTimeFormatter.ofPattern("EEE", DA)

    fun day(date: LocalDate): String {
        val today = LocalDate.now(CPH)
        return when (date) {
            today -> "I dag"
            today.plusDays(1) -> "I morgen"
            else -> dayFmt.format(date).trimEnd('.').replaceFirstChar { it.uppercase() }
        }
    }

    fun shortDay(date: LocalDate): String = shortDay.format(date).trimEnd('.')

    fun hour(epochMs: Long): Int = Instant.ofEpochMilli(epochMs).atZone(CPH).hour

    fun clock(epochMs: Long): String = "%02d".format(hour(epochMs))

    fun span(w: ChargeWindow): String = "kl. ${clock(w.start)} til ${clock(w.end)}"

    fun kr(v: Double): String = String.format(DA, "%.2f kr", v)

    fun time(epochMs: Long): String =
        DateTimeFormatter.ofPattern("HH:mm", DA).format(Instant.ofEpochMilli(epochMs).atZone(CPH))
}

/** Henter nye priser, gemmer dem og planlægger påmindelser. */
object Refresher {
    private const val PERIODIC = "ladetid-opdatering"
    private const val ONCE = "ladetid-nu"

    fun refresh(ctx: Context): PriceData {
        val data = PriceRepository.fetch(Store.area(ctx), Store.supplierId(ctx))
        Store.save(ctx, data)
        replan(ctx, data)
        return data
    }

    fun replan(ctx: Context, data: PriceData) {
        val windows = Planner.plan(Pricing.apply(ctx, data.hours), Store.chargeHours(ctx), System.currentTimeMillis())
        Scheduler.schedule(ctx, windows)
    }

    private fun constraints() =
        Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()

    fun startPeriodic(ctx: Context) {
        val req = PeriodicWorkRequestBuilder<RefreshWorker>(3, TimeUnit.HOURS)
            .setConstraints(constraints())
            .build()
        WorkManager.getInstance(ctx)
            .enqueueUniquePeriodicWork(PERIODIC, ExistingPeriodicWorkPolicy.KEEP, req)
    }

    fun runOnce(ctx: Context) {
        val req = OneTimeWorkRequestBuilder<RefreshWorker>().setConstraints(constraints()).build()
        WorkManager.getInstance(ctx).enqueueUniqueWork(ONCE, ExistingWorkPolicy.REPLACE, req)
    }
}

class RefreshWorker(ctx: Context, params: WorkerParameters) : Worker(ctx, params) {
    override fun doWork(): Result = try {
        Refresher.refresh(applicationContext)
        Result.success()
    } catch (e: Exception) {
        if (runAttemptCount < 3) Result.retry() else Result.failure()
    }
}

/** Sætter en alarm en time før hver dags bedste ladetid. */
object Scheduler {
    const val CHANNEL = "ladetid"
    private const val SLOTS = 7

    fun ensureChannel(ctx: Context) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        if (nm.getNotificationChannel(CHANNEL) == null) {
            val ch = NotificationChannel(CHANNEL, "Ladetid", NotificationManager.IMPORTANCE_HIGH)
            ch.description = "Besked en time før det er billigst at lade"
            nm.createNotificationChannel(ch)
        }
    }

    fun schedule(ctx: Context, windows: List<ChargeWindow>) {
        val am = ctx.getSystemService(AlarmManager::class.java)
        for (i in 0 until SLOTS) {
            PendingIntent.getBroadcast(
                ctx, 100 + i, Intent(ctx, AlarmReceiver::class.java),
                PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
            )?.let { am.cancel(it); it.cancel() }
        }
        if (!Store.notify(ctx)) return

        val now = System.currentTimeMillis()
        windows.take(SLOTS).forEachIndexed { i, w ->
            val at = w.start - HOUR_MS
            val key = w.day.toString()
            if (at <= now || Store.wasNotified(ctx, key)) return@forEachIndexed
            val intent = Intent(ctx, AlarmReceiver::class.java)
                .putExtra("day", key)
                .putExtra("title", "Om en time er det bedst at lade")
                .putExtra("text", "${Fmt.day(w.day)} ${Fmt.span(w)}, ca. ${Fmt.kr(w.avg)} pr. kWh i snit")
            val pi = PendingIntent.getBroadcast(
                ctx, 100 + i, intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            // Ikke sekundpræcis, men højst få minutter forsinket, også når telefonen sover
            am.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pi)
        }
    }
}

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        val day = intent.getStringExtra("day") ?: return
        if (Store.wasNotified(ctx, day)) return
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS)
            != PackageManager.PERMISSION_GRANTED
        ) return

        Scheduler.ensureChannel(ctx)
        val open = PendingIntent.getActivity(
            ctx, 0, Intent(ctx, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(ctx, Scheduler.CHANNEL)
            .setSmallIcon(R.drawable.ic_bolt)
            .setColor(ContextCompat.getColor(ctx, R.color.accent))
            .setContentTitle(intent.getStringExtra("title"))
            .setContentText(intent.getStringExtra("text"))
            .setStyle(NotificationCompat.BigTextStyle().bigText(intent.getStringExtra("text")))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(open)
            .setAutoCancel(true)
            .build()
        NotificationManagerCompat.from(ctx).notify(day.hashCode(), n)
        Store.markNotified(ctx, day)
    }
}

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        // Alarmer forsvinder ved genstart, så de lægges ind igen
        Store.load(ctx)?.let { Refresher.replan(ctx, it) }
        Refresher.startPeriodic(ctx)
        Refresher.runOnce(ctx)
    }
}
