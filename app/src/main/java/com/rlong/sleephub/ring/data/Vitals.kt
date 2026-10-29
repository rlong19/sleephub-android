package com.rlong.sleephub.ring.data

import com.rlong.sleephub.ring.proto.Metric
import com.rlong.sleephub.ring.proto.Sample
import com.rlong.sleephub.ring.proto.Valid
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

object Vitals {
    val METRICS = listOf(Metric.HEART_RATE, Metric.HRV, Metric.SPO2, Metric.STRESS, Metric.TEMP, Metric.ENERGY)
    /** "timing" rows come from the 5-minute tables; "measured" (2/09-2/0B histories) and "live" are one-shot. */
    fun isPeriodic(s: Sample) = s.source == "timing"
    fun isAppAuto(s: Sample) = s.source == "app-auto"
    fun isStream(s: Sample) = s.source?.startsWith("app-stream") == true
    fun sourceLabel(s: Sample) = when { isPeriodic(s) -> "periodic"; isAppAuto(s) -> "app-auto"; isStream(s) -> "app-continuous"; else -> "one-shot" }
    /** Popup suffix shared by every chart. */
    fun popupTag(s: Sample) = when { isPeriodic(s) -> " • auto (ring)"; isAppAuto(s) -> " • auto (app)"; isStream(s) -> " • continuous (app)"; else -> " • one-shot (manual)" }
    /** Drop anything the validators reject (defence in depth; parser and DB already filter). */
    fun valid(l: List<Sample>) = l.filter { Valid.ok(it.metric, it.value) && it.value != 0.0 }
}

data class Stats(val n: Int, val min: Double, val avg: Double, val max: Double)

data class VitalsDay(val key: Long, val samples: Map<Metric, List<Sample>>, val sleepBands: List<Pair<Long, Long>>) {
    fun of(m: Metric) = Vitals.valid(samples[m].orEmpty())
    fun periodic(m: Metric) = of(m).filter(Vitals::isPeriodic)
    fun oneShot(m: Metric) = of(m).filter { !Vitals.isPeriodic(it) && !Vitals.isAppAuto(it) && !Vitals.isStream(it) }
    fun stream(m: Metric) = of(m).filter(Vitals::isStream)
    fun appAuto(m: Metric) = of(m).filter(Vitals::isAppAuto)
    fun stats(m: Metric): Stats? = of(m).takeIf { it.isNotEmpty() }?.let { l -> Stats(l.size, l.minOf { it.value }, l.sumOf { it.value } / l.size, l.maxOf { it.value }) }
    val isEmpty get() = Vitals.METRICS.all { of(it).isEmpty() }
}

object VitalsCsv {
    const val HEADER = "timestamp,metric,value,unit,source"
    fun write(samples: List<Sample>, w: Appendable, tz: TimeZone = TimeZone.getDefault()) {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).apply { timeZone = tz }
        w.append(HEADER).append("\r\n")
        Vitals.valid(samples).sortedWith(compareBy({ it.time }, { it.metric.ordinal })).forEach { s ->
            w.append("${f.format(Date(s.time))},${s.metric.name},${String.format(Locale.US, "%.1f", s.value)},${s.metric.unit},${Vitals.sourceLabel(s)}\r\n")
        }
    }
    fun toString(samples: List<Sample>, tz: TimeZone = TimeZone.getDefault()) = StringBuilder().also { write(samples, it, tz) }.toString()
}
