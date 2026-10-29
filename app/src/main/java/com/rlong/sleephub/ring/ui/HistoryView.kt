package com.rlong.sleephub.ring.ui

import android.app.Activity
import android.app.AlertDialog
import android.app.DatePickerDialog
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.os.SystemClock
import android.view.GestureDetector
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.ble.Ring
import com.rlong.sleephub.ring.data.HistoryPage
import com.rlong.sleephub.ring.data.NightCsv
import com.rlong.sleephub.ring.data.SleepNight
import com.rlong.sleephub.ring.data.TimeAxis
import com.rlong.sleephub.ring.data.Vitals
import com.rlong.sleephub.ring.data.VitalsCsv
import com.rlong.sleephub.ring.data.VitalsDay
import com.rlong.sleephub.ring.data.WindowMode
import com.rlong.sleephub.ring.data.Decimator
import com.rlong.sleephub.ring.data.Series
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.rlong.sleephub.ring.proto.Actigraphy
import com.rlong.sleephub.ring.proto.Cmd
import com.rlong.sleephub.ring.proto.Intervals
import com.rlong.sleephub.ring.proto.Metric
import com.rlong.sleephub.ring.proto.Sample
import com.rlong.sleephub.ring.data.Nearest
import com.rlong.sleephub.ring.proto.SleepSeg
import com.rlong.sleephub.ring.proto.Support
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs

val STAGE_COLORS = intArrayOf(Color.rgb(230, 60, 60), Color.rgb(102, 178, 255), Color.rgb(30, 60, 220), Color.rgb(0, 170, 80))
val STAGE_NAMES = arrayOf("Awake", "Light", "Deep", "REM")
val METRIC_COLORS = mapOf(Metric.HEART_RATE to Color.rgb(255, 80, 80), Metric.HRV to Color.GREEN, Metric.SPO2 to Color.rgb(0, 150, 255),
    Metric.STRESS to Color.rgb(230, 149, 0), Metric.TEMP to Color.MAGENTA, Metric.ENERGY to Color.rgb(240, 220, 60))
fun fmtDur(min: Int) = if (min >= 60) "${min / 60}h ${min % 60}m" else "${min}m"

fun statsLine(d: VitalsDay, m: Metric): String {
    val s = d.stats(m) ?: return "${m.label}: no data"
    val fmt = if (m == Metric.TEMP) "%.1f" else "%.0f"
    val extra = if (m == Metric.SPO2) "  (${s.n} readings)" else "  n=${s.n}"
    val one = d.oneShot(m).size.let { if (it > 0) ", $it one-shot" else "" }
    return "${m.label}: min ${String.format(Locale.US, fmt, s.min)} / avg ${String.format(Locale.US, fmt, s.avg)} / max ${String.format(Locale.US, fmt, s.max)} ${m.unit}$extra$one"
}

/** Shared scrub state: one cursor time for every chart on the page. */
class Scrub {
    companion object { @Volatile var active = false }
    var t: Long? = null; private set
    val charts = ArrayList<View>()
    /** Shared pinch zoom (History page only); null = fixed axis. */
    var zoom: com.rlong.sleephub.ring.data.ZoomWindow? = null
    var onMove: ((chart: View, ev: MotionEvent, t: Long) -> Unit)? = null
    var onEnd: (() -> Unit)? = null
    fun set(chart: View, ev: MotionEvent, time: Long) { t = time; active = true; charts.forEach { it.invalidate() }; onMove?.invoke(chart, ev, time) }
    fun clear() { active = false; if (t == null) return; t = null; charts.forEach { it.invalidate() }; onEnd?.invoke() }
}

/**
 * The press-to-scrub gesture shared by History charts and the per-metric index charts: a quick vertical flick goes to
 * the ScrollView; holding or a horizontal drag locks into scrubbing. [keyAt] maps a finger x to the scrub key.
 */
class ScrubTouch(private val v: View, private val scrub: Scrub, private val keyAt: (Float) -> Long) {
    private val slop = ViewConfiguration.get(v.context).scaledTouchSlop
    private var downX = 0f; private var downY = 0f; private var downAt = 0L; private var locked = false
    fun onTouch(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = ev.x; downY = ev.y; downAt = SystemClock.uptimeMillis(); locked = false; scrub.set(v, ev, keyAt(ev.x)); return true }
            MotionEvent.ACTION_MOVE -> {
                if (!locked) {
                    val dx = abs(ev.x - downX); val dy = abs(ev.y - downY)
                    if (dx > slop || (SystemClock.uptimeMillis() - downAt > 180 && dy <= slop)) { locked = true; v.parent?.requestDisallowInterceptTouchEvent(true) }
                }
                scrub.set(v, ev, keyAt(ev.x)); return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> { locked = false; v.parent?.requestDisallowInterceptTouchEvent(false); scrub.clear(); if (ev.actionMasked == MotionEvent.ACTION_UP) v.performClick(); return true }
        }
        return false
    }
}

/** Base chart: shared axis, hour grid, sleep bands, cursor, and the press-to-scrub gesture. */
abstract class TimeChart(ctx: Context, private val baseAxis: TimeAxis, val scrub: Scrub) : View(ctx) {
    /** Visible axis: the shared zoom window when present, else the fixed page axis. */
    val axis: TimeAxis get() = scrub.zoom?.axis ?: baseAxis
    /** Y-axis labels sit in the left margin, outside the plot clip; drawn after the clip is removed. */
    private val labels = ArrayList<(Canvas) -> Unit>()
    protected fun label(f: (Canvas) -> Unit) { labels += f }
    var bands: List<Pair<Long, Long>> = emptyList()
    var title = ""; var titleColor = Color.LTGRAY
    protected val p = Paint(Paint.ANTI_ALIAS_FLAG)
    protected val top = 26f; protected val bottomPad = 20f
    protected val plotH get() = height - top - bottomPad
    protected fun x(t: Long) = axis.x(t, width)
    /** Visible pixel columns = decimation buckets. */
    protected fun cols() = axis.plotWidth(width).toInt().coerceAtLeast(1)

    // v0.3.12: geometry (Paths) is rebuilt only when the visible window, the view size or the data changes — not per frame
    // (scrub-cursor moves just redraw the cached Paths).
    private var cacheT0 = Long.MIN_VALUE; private var cacheT1 = Long.MIN_VALUE; private var cacheW = -1; private var cacheH = -1
    protected fun dataChanged() { cacheT0 = Long.MIN_VALUE; invalidate() }
    /** Rebuild cached Paths for the current [axis]/size. */
    protected open fun rebuild() {}

    init { scrub.charts += this }
    abstract fun drawContent(c: Canvas)

    override fun onDraw(c: Canvas) {
        val ax = axis
        if (ax.t0 != cacheT0 || ax.t1 != cacheT1 || width != cacheW || height != cacheH) { cacheT0 = ax.t0; cacheT1 = ax.t1; cacheW = width; cacheH = height; rebuild() }
        c.drawColor(Color.rgb(20, 20, 20))
        p.style = Paint.Style.FILL; p.color = Color.argb(60, 90, 90, 255)
        bands.forEach { (a, b) -> if (b >= ax.t0 && a <= ax.t1) c.drawRect(x(a), top, x(b), top + plotH, p) }
        val z = scrub.zoom
        c.save(); c.clipRect(axis.left(width), 0f, width.toFloat(), height.toFloat())
        if (z != null && z.zoomed) drawZoomGrid(c) else drawHourGrid(c)
        drawContent(c)
        c.restore()
        labels.forEach { it(c) }; labels.clear()
        p.style = Paint.Style.FILL; p.color = titleColor; p.textSize = 22f; c.drawText(title, 6f, 21f, p)
        scrub.t?.let { t -> p.color = Color.WHITE; p.strokeWidth = 2f; val xx = x(t); c.drawLine(xx, 0f, xx, height.toFloat(), p) }
    }

    /**
     * Polyline Path of a decimated series: each run is one connected line; decimated columns add a vertical min→max
     * stroke so spikes/dips stay visible. Single isolated raw points go to [singles] as dots of radius [dotR] (or a 1-px tick).
     */
    protected fun pathOf(res: Decimator.Result, y: (Float) -> Float, path: Path, singles: Path? = null, dotR: Float = 1.5f) {
        path.reset(); singles?.reset()
        for (run in res.runs) {
            if (run.size == 1) { val q = run[0]; val xx = x(q.t)
                if (singles != null && q.lo == q.hi) singles.addCircle(xx, y(q.lo), dotR, Path.Direction.CW)
                else { path.moveTo(xx, y(q.lo)); path.lineTo(xx + 0.5f, y(q.hi)) }
                continue }
            for (i in run.indices) { val q = run[i]; val xx = x(q.t)
                if (i == 0) path.moveTo(xx, y(q.lo)) else path.lineTo(xx, y(q.lo))
                if (q.hi != q.lo) path.lineTo(xx, y(q.hi)) }
        }
    }
    /** Dots for every raw point of [res] (only used when zoomed in far enough that points < pixels). */
    protected fun dotsOf(res: Decimator.Result, y: (Float) -> Float, path: Path, r: Float) {
        path.reset(); if (!res.raw) return
        res.runs.forEach { run -> run.forEach { q -> path.addCircle(x(q.t), y(q.lo), r, Path.Direction.CW) } }
    }

    private fun drawZoomGrid(c: Canvas) {
        val span = axis.t1 - axis.t0
        val step = listOf(1_000L, 5_000L, 10_000L, 15_000L, 30_000L, 60_000L, 120_000L, 300_000L, 600_000L, 900_000L, 1_800_000L, 3_600_000L).firstOrNull { span / it <= 6 } ?: 3_600_000L
        val off = java.util.TimeZone.getDefault().getOffset(axis.t0).toLong()
        var t = ((axis.t0 + off) / step + 1) * step - off
        val f = if (step < 60_000L) FMT_HMS else FMT_HM
        p.textSize = 16f; p.strokeWidth = 1f
        while (t <= axis.t1) { val xx = x(t); p.color = Color.rgb(60, 60, 60); c.drawLine(xx, top, xx, top + plotH, p)
            p.color = Color.GRAY; c.drawText(f.format(Date(t)), xx + 2, height - 3f, p); t += step }
    }
    private fun drawHourGrid(c: Canvas) {
        val cal = Calendar.getInstance().apply { timeInMillis = axis.t0; set(Calendar.MINUTE, 0); set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0); if (timeInMillis < axis.t0) add(Calendar.HOUR_OF_DAY, 1) }
        p.textSize = 18f
        while (cal.timeInMillis <= axis.t1) { val xx = x(cal.timeInMillis); val h = cal.get(Calendar.HOUR_OF_DAY)
            p.color = if (h % 3 == 0) Color.rgb(70, 70, 70) else Color.rgb(40, 40, 40); c.drawLine(xx, top, xx, top + plotH, p)
            if (h % 3 == 0) { p.color = Color.GRAY; c.drawText(String.format(Locale.US, "%02d", h), xx - 10, height - 3f, p) }
            cal.add(Calendar.HOUR_OF_DAY, 1) }
    }
    private companion object { val FMT_HMS = SimpleDateFormat("HH:mm:ss", Locale.US); val FMT_HM = SimpleDateFormat("HH:mm", Locale.US) }

    private val gesture = ScrubTouch(this, scrub) { x -> axis.timeAt(x, width) }
    // Multi-touch (pinch zoom + two-finger pan) and double-tap reset; only active when the page has a shared zoom.
    private var multi = false; private var lastFocusX = 0f
    private val scaler = android.view.ScaleGestureDetector(ctx, object : android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: android.view.ScaleGestureDetector): Boolean {
            val z = scrub.zoom ?: return false
            val sx = if (d.currentSpanX > 0 && d.previousSpanX > 0 && kotlin.math.abs(d.currentSpanX - d.previousSpanX) > kotlin.math.abs(d.currentSpanY - d.previousSpanY)) d.currentSpanX / d.previousSpanX else d.scaleFactor
            z.zoom(sx.toDouble(), axis.timeAt(d.focusX, width)); scrub.charts.forEach { it.invalidate() }; return true
        }
    })
    private val taps = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDoubleTap(e: MotionEvent): Boolean { val z = scrub.zoom ?: return false; z.reset(); scrub.clear(); scrub.charts.forEach { it.invalidate() }; return true }
    })
    override fun onTouchEvent(ev: MotionEvent): Boolean {
        val z = scrub.zoom ?: return gesture.onTouch(ev) || super.onTouchEvent(ev)
        taps.onTouchEvent(ev); scaler.onTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> { multi = true; parent?.requestDisallowInterceptTouchEvent(true); scrub.clear(); Scrub.active = true /* holds off page rebuilds during the gesture */; lastFocusX = focusX(ev); return true }
            MotionEvent.ACTION_MOVE -> if (multi) {
                if (ev.pointerCount >= 2) { val fx = focusX(ev); val w = axis.plotWidth(width).coerceAtLeast(1f)
                    z.pan(-((fx - lastFocusX) / w * (axis.t1 - axis.t0)).toLong()); lastFocusX = fx; scrub.charts.forEach { it.invalidate() } }
                return true }
            MotionEvent.ACTION_POINTER_UP -> { lastFocusX = focusX(ev, ev.actionIndex); return true }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if (multi) { multi = false; parent?.requestDisallowInterceptTouchEvent(false); scrub.clear(); return true }
        }
        if (multi) return true
        return gesture.onTouch(ev) || super.onTouchEvent(ev)
    }
    private fun focusX(ev: MotionEvent, skip: Int = -1): Float { var sum = 0f; var n = 0
        for (i in 0 until ev.pointerCount) if (i != skip) { sum += ev.getX(i); n++ }; return if (n > 0) sum / n else ev.x }
    override fun performClick(): Boolean = super.performClick()
}

class HypnoChart(ctx: Context, axis: TimeAxis, scrub: Scrub) : TimeChart(ctx, axis, scrub) {
    var segs: List<SleepSeg> = emptyList(); set(v) { field = v.sortedBy { it.start }; dataChanged() }
    private val rows = intArrayOf(0, 3, 1, 2)   // top→bottom: awake, REM, light, deep
    // one Path per stage (+ unscored), built per window/size
    private val stagePaths = Array(5) { Path() }
    override fun rebuild() {
        stagePaths.forEach { it.reset() }
        val rh = plotH / 4; val ax = axis
        segs.forEach { s -> if (s.end < ax.t0 || s.start > ax.t1) return@forEach
            val x0 = x(s.start); val x1 = maxOf(x(s.end), x0 + 1)
            if (s.stage == 4) { stagePaths[4].addRect(x0, top + 1, x1, top + plotH - 1, Path.Direction.CW); return@forEach }
            val st = s.stage.coerceIn(0, 3); val r = rows.indexOf(st)
            stagePaths[st].addRect(x0, top + rh * r + 1, x1, top + rh * (r + 1) - 1, Path.Direction.CW) }
    }
    override fun drawContent(c: Canvas) {
        val rh = plotH / 4
        if (segs.isEmpty()) return
        p.style = Paint.Style.FILL
        p.color = Color.argb(110, 200, 200, 200); c.drawPath(stagePaths[4], p)
        for (st in 0..3) { p.color = STAGE_COLORS[st]; c.drawPath(stagePaths[st], p) }
        label { cc -> p.textSize = 16f; p.color = Color.GRAY
        listOf("W", "R", "L", "D").forEachIndexed { i, l -> cc.drawText(l, axis.left(width) - 18, top + rh * i + rh / 2 + 6, p) } }
    }
}

class MoveChart(ctx: Context, axis: TimeAxis, scrub: Scrub) : TimeChart(ctx, axis, scrub) {
    var data: List<Pair<Long, Int>> = emptyList(); set(v) { field = v; mag = Series.of(v, { it.first }, { Actigraphy.magnitude(it.second) }); dataChanged() }
    private var mag = Series.EMPTY
    var arousals: List<com.rlong.sleephub.ring.auto.Arousals.Event> = emptyList()
    private val bars = Path()
    override fun draw(c: Canvas) {
        super.draw(c)
        arousals.filter { it.time in axis.t0..axis.t1 }.forEach { a -> p.color = if (a.hrRise) Color.rgb(255, 60, 200) else Color.rgb(190, 120, 255); p.style = Paint.Style.FILL
            c.drawCircle(x(a.time), top + 5f, if (a.hrRise) 5f else 3.5f, p) }
    }
    override fun rebuild() {
        bars.reset(); if (mag.isEmpty) return
        val max = mag.max.coerceAtLeast(1f)
        val bw = maxOf(1f, axis.plotWidth(width) * 60_000f / (axis.t1 - axis.t0))
        // per pixel column keep the largest movement (max of the column), so bursts never disappear when zoomed out
        Decimator.decimate(mag.times, mag.vals, axis.t0, axis.t1, cols()).runs.forEach { run -> run.forEach { q ->
            if (q.hi > 0) { val xx = x(q.t); bars.addRect(xx, top + plotH - plotH * q.hi / max, xx + bw, top + plotH, Path.Direction.CW) } } }
    }
    override fun drawContent(c: Canvas) {
        if (data.isEmpty()) return
        p.style = Paint.Style.FILL; p.color = Color.rgb(255, 190, 60); c.drawPath(bars, p)
    }
}

class VitalChart(ctx: Context, axis: TimeAxis, scrub: Scrub) : TimeChart(ctx, axis, scrub) {
    var periodic: List<Sample> = emptyList(); set(v) { field = v; dirty = true; dataChanged() }
    var oneShot: List<Sample> = emptyList(); set(v) { field = v; dirty = true; dataChanged() }
    var appAuto: List<Sample> = emptyList(); set(v) { field = v; dirty = true; dataChanged() }
    var stream: List<Sample> = emptyList(); set(v) { field = v; dirty = true; dataChanged() }
    var marks: List<Pair<Long, Long>> = emptyList()
    var color = Color.CYAN; var pointsOnly = false
    // series + y-range computed once per data change (was: 4 list concatenations + minOf/maxOf on every frame)
    private var dirty = true
    private var per = Series.EMPTY; private var str = Series.EMPTY; private var lo = 0.0; private var hi = 1.0; private var empty = true
    private fun ensureData() {
        if (!dirty) return; dirty = false
        per = Series.of(periodic, { it.time }, { it.value }); str = Series.of(stream, { it.time }, { it.value })
        val all = periodic + oneShot + appAuto + stream; empty = all.isEmpty()
        if (!empty) { lo = all.minOf { it.value }; hi = all.maxOf { it.value }; if (hi - lo < 1) { lo -= 0.5; hi += 0.5 } }
    }
    private fun y(v: Double) = top + plotH - plotH * (0.05f + 0.9f * ((v - lo) / (hi - lo)).toFloat())
    private val perLine = Path(); private val perDots = Path(); private val strPath = Path(); private val strDots = Path()
    override fun rebuild() {
        ensureData(); perLine.reset(); perDots.reset(); strPath.reset(); strDots.reset(); if (empty) return
        val yf = { v: Float -> y(v.toDouble()) }
        val pr = Decimator.decimate(per.times, per.vals, axis.t0, axis.t1, cols(), 15 * 60_000L)
        if (pointsOnly) dotsOf(pr, yf, perDots, 5f)
        else { pathOf(pr, yf, perLine, perDots, 2.5f); if (per.size == 1) { perDots.reset(); perDots.addCircle(x(per.times[0]), yf(per.vals[0]), 4f, Path.Direction.CW) } }
        // stream: dots when sparse; per-column min→max ticks when dense
        val sr = Decimator.decimate(str.times, str.vals, axis.t0, axis.t1, cols())
        if (sr.raw) sr.runs.forEach { run -> run.forEach { q -> if (q.t in axis.t0..axis.t1) strDots.addCircle(x(q.t), yf(q.lo), 1.8f, Path.Direction.CW) } }
        else sr.runs.forEach { run -> run.forEach { q -> val xx = x(q.t); strPath.moveTo(xx, yf(q.lo) + 1f); strPath.lineTo(xx, yf(q.hi) - 1f) } }
    }
    override fun drawContent(c: Canvas) {
        ensureData()
        if (empty) return
        label { cc -> p.color = Color.GRAY; p.textSize = 16f; p.style = Paint.Style.FILL
        cc.drawText(String.format(Locale.US, "%.0f", hi), 4f, top + 14, p); cc.drawText(String.format(Locale.US, "%.0f", lo), 4f, top + plotH - 2, p) }
        p.color = color; p.strokeWidth = 3f
        p.style = Paint.Style.STROKE; c.drawPath(perLine, p)
        p.style = Paint.Style.FILL; c.drawPath(perDots, p)
        oneShot.forEach { s -> p.style = Paint.Style.FILL; p.color = color; c.drawCircle(x(s.time), y(s.value), 7f, p)
            p.style = Paint.Style.STROKE; p.color = Color.WHITE; p.strokeWidth = 2.5f; c.drawCircle(x(s.time), y(s.value), 8f, p) }
        p.color = color; p.style = Paint.Style.FILL; c.drawPath(strDots, p)
        p.style = Paint.Style.STROKE; p.strokeWidth = 2f; c.drawPath(strPath, p); p.style = Paint.Style.FILL
        p.color = Color.rgb(255, 140, 0)
        marks.forEach { (a, b) -> if (b >= axis.t0 && a <= axis.t1) c.drawRect(x(a), top, maxOf(x(b), x(a) + 3f), top + 6f, p) }
        appAuto.forEach { s -> p.style = Paint.Style.FILL; p.color = color; c.drawCircle(x(s.time), y(s.value), 6f, p)
            p.style = Paint.Style.STROKE; p.color = Color.YELLOW; p.strokeWidth = 2.5f; c.drawCircle(x(s.time), y(s.value), 8f, p) }
        p.style = Paint.Style.FILL
    }
}


/**
 * Shared scrub overlay (History and the per-metric screens): wraps [charts] with a popup that follows the finger
 * while [scrub] is active, showing [textAt] for the cursor time, and hides on lift.
 */
fun Activity.scrubFrame(charts: View, scrub: Scrub, onEnd: () -> Unit = {}, textAt: (chart: View, t: Long) -> String): FrameLayout {
    val popup = TextView(this).apply { textSize = 12f; setTextColor(Color.WHITE); setBackgroundColor(Color.argb(225, 35, 35, 45)); setPadding(dp(8), dp(6), dp(8), dp(6)); visibility = View.GONE; elevation = 8f }
    val frame = FrameLayout(this).apply { addView(charts, FrameLayout.LayoutParams(-1, -2)); addView(popup, FrameLayout.LayoutParams(-2, -2)) }
    scrub.onMove = { chart, ev, t ->
        popup.text = textAt(chart, t); popup.visibility = View.VISIBLE
        popup.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED)
        // chart position relative to the frame (charts may sit inside a nested column)
        var fx = ev.x; var fy = ev.y; var v: View? = chart
        while (v != null && v !== frame) { fx += v.left; fy += v.top; v = v.parent as? View }
        val pw = popup.measuredWidth; val ph = popup.measuredHeight
        popup.x = (if (fx + dp(24) + pw < frame.width) fx + dp(24) else fx - dp(24) - pw).coerceAtLeast(0f)
        popup.y = (fy - ph - dp(30)).let { if (it < 0) fy + dp(30) else it }.coerceAtMost((frame.height - ph).toFloat().coerceAtLeast(0f))
    }
    scrub.onEnd = { popup.visibility = View.GONE; onEnd() }
    return frame
}

/** Popup for the per-metric index charts: the selected point's real timestamp, value and auto/one-shot. */
fun samplePopupText(m: Metric, s: Sample): String {
    val v = if (m == Metric.TEMP || m == Metric.ENERGY) String.format(Locale.US, "%.1f", s.value) else String.format(Locale.US, "%.0f", s.value)
    return SimpleDateFormat("EEE MMM d HH:mm", Locale.US).format(Date(s.time)) + "\n${m.label}: $v${if (m.unit.isNotEmpty()) " ${m.unit}" else ""}" +
        Vitals.popupTag(s)
}

/** Popup text for one metric at [t]: nearest periodic (auto) or one-shot (manual) sample within the metric's tolerance. */
fun metricReadoutText(m: Metric, samples: List<Sample>, t: Long): String {
    val sorted = samples.sortedBy { it.time }
    val i = Nearest.index(LongArray(sorted.size) { sorted[it].time }, t, Nearest.tolerance(m))
    val head = SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(t))
    if (i < 0) return "$head\n${m.label}: —"
    val s = sorted[i]
    val v = if (m == Metric.TEMP || m == Metric.ENERGY) String.format(Locale.US, "%.1f", s.value) else String.format(Locale.US, "%.0f", s.value)
    return "$head\n${m.label}: $v${if (m.unit.isNotEmpty()) " ${m.unit}" else ""}" +
        Vitals.popupTag(s) +
        (if (abs(s.time - t) >= 60_000L) " @${SimpleDateFormat("HH:mm", Locale.US).format(Date(s.time))}" else "")
}

fun readoutText(r: HistoryPage.Readout): String {  // ENERGY shown as "Physical strength"
    val sb = StringBuilder(SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(r.time)))
    sb.append("\nStage: ").append(r.stage?.let { STAGE_NAMES[it.coerceIn(0, 3)] } ?: "—")
    sb.append("\nMovement: ").append(r.movement?.toString() ?: "—")
    for (m in listOf(Metric.HEART_RATE, Metric.HRV, Metric.SPO2, Metric.TEMP, Metric.STRESS, Metric.ENERGY)) {
        val s = r.values[m]
        sb.append("\n${m.label}: ").append(s?.let { (if (m == Metric.TEMP || m == Metric.ENERGY) String.format(Locale.US, "%.1f", it.value) else String.format(Locale.US, "%.0f", it.value)) +
            (if (m.unit.isNotEmpty()) " ${m.unit}" else "") + (if (it.source != "timing") Vitals.popupTag(it) else "") +
            (if (abs(it.time - r.time) >= 60_000L) " @${SimpleDateFormat("HH:mm", Locale.US).format(Date(it.time))}" else "") } ?: "—")
    }
    return sb.toString()
}

/**
 * v0.3.12: everything the History page shows, loaded once off the main thread (DB reads, oximeter 1 Hz series,
 * dips/ODI/arousals) and kept in memory. The page is rebuilt from this cache; it is reloaded only on the Refresh
 * button, a finished sync (ring/oximeter), opening a different page/mode, or a change of day — never on BLE events.
 */
class HistoryData(
    val req: HistoryCache.Req, val stored: List<Long>, val now: Long, val partial: Long, val oldest: Long, val key: Long, val isPartial: Boolean,
    val page: HistoryPage, val sleepPeriods: List<Pair<Long, Long>>, val arousals: List<com.rlong.sleephub.ring.auto.Arousals.Event>,
    val perPeriod: List<List<com.rlong.sleephub.ring.auto.Dips.Dip>>, val spo2Dips: List<com.rlong.sleephub.ring.auto.Dips.Dip>,
    val zeoSegs: List<SleepSeg>, val oxi: List<com.rlong.sleephub.oxi.OxiReading>, val oxiTimes: LongArray, val odiCount: Int, val odiMarks: List<Pair<Long, Long>>,
    val spo2: Series, val pulse: Series, val spo2Line: String?, val pulseLine: String?,
)

object HistoryCache {
    data class Req(val current: Long?, val mode: WindowMode, val demo: Boolean, val zeoDemo: Boolean)
    private val scope = kotlinx.coroutines.MainScope()
    @Volatile private var data: HistoryData? = null
    /** Local day key the cache was loaded on (day change ⇒ reload). */
    @Volatile var dayKey = 0L; private set
    /** Remembered scroll position of the History page, restored after a refresh/rebuild of the same page. */
    var scrollY = 0; var scrollKey: Long? = null
    private var job: kotlinx.coroutines.Job? = null
    fun invalidate() { data = null }
    fun cached(r: Req): HistoryData? = data?.takeIf { it.req == r && dayKey == SleepNight.keyFor(System.currentTimeMillis()) }
    /** True when the calendar day changed since the cached page was loaded. */
    fun dayChanged() = data != null && dayKey != SleepNight.keyFor(System.currentTimeMillis())
    fun load(a: com.rlong.sleephub.App, r: Req, done: (HistoryData) -> Unit) {
        job?.cancel()
        job = scope.launch {
            val d = runCatching { withContext(Dispatchers.IO) { loadHistory(a, r) } }
            d.onSuccess { data = it; dayKey = SleepNight.keyFor(it.now); done(it) }
                .onFailure { if (it !is kotlinx.coroutines.CancellationException) android.util.Log.e("History", "load failed", it) }
        }
    }
}

/** Background (Dispatchers.IO) part of the History page: all DB reads and derived series. */
fun loadHistory(a: com.rlong.sleephub.App, r: HistoryCache.Req): HistoryData {
    val demo = r.demo; val mode = r.mode
    val stored = a.db.historyDays(demo)
    val now = System.currentTimeMillis()
    val HW = com.rlong.sleephub.ring.data.HistoryWindow
    val partial = HW.partialKey(now, mode)
    val today = partial
    val oldest = (stored.minOrNull() ?: today).coerceAtMost(SleepNight.shiftKey(today, -Cmd.HISTORY_MAX_DAY))
    val newest = (Vitals.METRICS.mapNotNull { a.db.latest(it, demo)?.time } + listOfNotNull(runCatching { a.zeoDb.newest(r.zeoDemo) }.getOrNull())).maxOrNull()
    val key = (r.current ?: HW.defaultKey(newest, now, mode)).coerceIn(oldest, today)
    val isPartial = key == partial
    val page = if (isPartial) a.db.historyPage(SleepNight.keyFor(now), mode, demo, HW.partialWindow(now)) else a.db.historyPage(key, mode, demo)
    // SpO2 dips (ODI-style) inside sleep periods: app-detected/manual + ring hypnogram bands
    val sleepPeriods = (a.db.appSleep(page.t0, page.t1) + page.vitals.sleepBands).sortedBy { it.first }
    val spo2All = page.vitals.of(Metric.SPO2).sortedBy { it.time }
    val hrPts = page.vitals.of(Metric.HEART_RATE).sortedBy { it.time }.map { it.time to it.value }
    // ring 1-min actigraphy + app 30-s live-step events (sleep mode only) feed the arousal proxy
    val moveMag = (page.movement.map { it.first to Actigraphy.magnitude(it.second) } +
        a.db.samples(Metric.STEPS, page.t0, page.t1, demo).filter { it.source == "app-30s" }.map { it.time to it.value.toInt().coerceAtLeast(1) }).sortedBy { it.first }
    val arousals = sleepPeriods.map { pp -> com.rlong.sleephub.ring.auto.Arousals.find(moveMag, hrPts, listOf(pp)) }.flatten().distinctBy { it.time }
    val perPeriod = sleepPeriods.map { (s, e) -> com.rlong.sleephub.ring.auto.Dips.find(spo2All.filter { it.time in s..e }.map { it.time to it.value }) }
    val spo2Dips = perPeriod.flatten().distinctBy { it.start }
    val zeoNights = runCatching { a.zeoDb.nights(page.t0, page.t1, r.zeoDemo) }.getOrDefault(emptyList())
    val zeoSegs = zeoNights.flatMap { it.segments }.filter { it.end > page.t0 && it.start < page.t1 }
    // v0.3.0: oximeter (EMAY SleepO2) 1 Hz SpO2/pulse — full per-second data kept in memory (never downsampled)
    val oxi = runCatching { a.oxiDb.range(page.t0, page.t1) }.getOrDefault(emptyList())
    val odi = com.rlong.sleephub.oxi.OxiSessions.split(oxi).map { com.rlong.sleephub.oxi.Odi.find(it) }.flatten()
    val oxiSp = oxi.filter { it.spo2 != null }; val oxiPr = oxi.filter { it.pulse != null }
    val spo2Line = if (oxiSp.isEmpty()) null else String.format(Locale.US, "Oximeter SpO2: min %d · avg %.1f%% · <90%% %d s · <88%% %d s · ODI3 events %d", oxiSp.minOf { it.spo2!! }, oxiSp.map { it.spo2!! }.average(),
        oxiSp.count { it.spo2!! < 90 }, oxiSp.count { it.spo2!! < 88 }, odi.size)
    val pulseLine = if (oxiPr.isEmpty()) null else String.format(Locale.US, "Oximeter pulse: min %d · avg %.0f · max %d bpm", oxiPr.minOf { it.pulse!! }, oxiPr.map { it.pulse!! }.average(), oxiPr.maxOf { it.pulse!! })
    // series keep every row; a null (invalid: SpO2 outside 1–100, pulse outside 25–250) becomes NaN = a gap in the line
    val spo2 = if (oxiSp.isEmpty()) Series.EMPTY else Series.of(oxi, { it.time }, { it.spo2 })
    val pulse = if (oxiPr.isEmpty()) Series.EMPTY else Series.of(oxi, { it.time }, { it.pulse })
    page.readout(page.t0)   // warm the readout caches off the main thread
    return HistoryData(r, stored, now, partial, oldest, key, isPartial, page, sleepPeriods, arousals, perPeriod, spo2Dips, zeoSegs, oxi,
        LongArray(oxi.size) { oxi[it].time }, odi.size, odi.map { it.start to it.end }, spo2, pulse, spo2Line, pulseLine)
}

/**
 * Combined History tab: one page per day/night, all charts on one axis with press-and-drag scrubbing.
 * Shows the cached page immediately when valid, else "Loading…" while [loadHistory] runs on Dispatchers.IO.
 * [refresh] rebuilds the screen (the host activity's own rebuild); the Refresh button invalidates the cache first.
 */
fun Activity.historyView(current: Long?, mode: WindowMode, select: (Long) -> Unit, setMode: (WindowMode) -> Unit, refresh: () -> Unit = {}): View {
    val a = app()
    val req = HistoryCache.Req(current, mode, a.showingDemo, a.zeo.demo)
    HistoryCache.cached(req)?.let { return historyPageView(it, select, setMode, refresh) }
    val holder = FrameLayout(this)
    holder.addView(text("Loading history…", 14f, Color.LTGRAY).apply { setPadding(dp(12), dp(24), dp(12), dp(24)) })
    HistoryCache.load(a, req) { d -> if (!isFinishing && !isDestroyed && holder.parent != null) { holder.removeAllViews(); holder.addView(historyPageView(d, select, setMode, refresh), FrameLayout.LayoutParams(-1, -1)) } }
    return holder
}

private fun Activity.historyPageView(d: HistoryData, select: (Long) -> Unit, setMode: (WindowMode) -> Unit, refresh: () -> Unit): View {
    val a = app(); val demo = d.req.demo; val ring = a.ring; val mode = d.req.mode
    val stored = d.stored; val now = d.now; val partial = d.partial; val today = partial; val oldest = d.oldest; val key = d.key; val isPartial = d.isPartial
    val page = d.page
    val night = page.night
    val go = { dd: Int -> val k = SleepNight.shiftKey(key, dd); if (k in oldest..today) select(k) else toast(if (dd > 0) "Latest day" else "Oldest day") }
    val dateFmt = SimpleDateFormat("EEE, MMM d yyyy", Locale.US); val hm = SimpleDateFormat("HH:mm", Locale.US)
    val inProgress = now in page.t0 until page.t1
    val pageTitle = if (isPartial) "Today (partial)\n${SimpleDateFormat("EEE MMM d", Locale.US).format(Date(page.t0))} 00:00 – ${hm.format(Date(page.t1))}" else if (mode == WindowMode.NOON) "Night → ${dateFmt.format(Date(key))}${if (inProgress) " (in progress)" else ""}\n${SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(page.t0))} – ${SimpleDateFormat("EEE HH:mm", Locale.US).format(Date(page.t1))}"
        else "${dateFmt.format(Date(key))}\n00:00 – 24:00"

    val col = vbox()
    // header: arrows + date; horizontal swipe here changes day (charts use horizontal drags for scrubbing)
    val gd = GestureDetector(this, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(e: MotionEvent) = true
        override fun onFling(e1: MotionEvent?, e2: MotionEvent, vx: Float, vy: Float): Boolean {
            if (e1 == null) return false; val dx = e2.x - e1.x
            if (abs(dx) > 100 && abs(dx) > 1.5f * abs(e2.y - e1.y)) { go(if (dx < 0) 1 else -1); return true }; return false
        }
    })
    val header = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL; setBackgroundColor(Color.rgb(25, 35, 45))
        addView(button("◀") { go(-1) }.apply { isEnabled = key > oldest })
        addView(text(pageTitle, 15f, Color.CYAN).apply { gravity = Gravity.CENTER; setPadding(0, dp(10), 0, dp(10)) }, LinearLayout.LayoutParams(0, -2, 1f))
        addView(button("▶") { go(+1) }.apply { isEnabled = key < today })
    }
    header.setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    header.getChildAt(1).setOnTouchListener { _, ev -> gd.onTouchEvent(ev) }
    col.addView(header)
    col.addView(hbox(
        button("Days (${stored.size})…") {
            if (stored.isEmpty()) toast("No history stored yet") else
                AlertDialog.Builder(this).setTitle("Stored days").setItems((listOf("Today (partial) — midnight → now") + stored.map { k -> val n = a.db.night(k, demo)
                    "${dateFmt.format(Date(k))} — ${if (n.isEmpty) "no sleep" else fmtDur(n.asleepMinutes) + " asleep"}${if (n.naps.isNotEmpty()) " +${n.naps.size} nap" else ""}" }).toTypedArray()) { _, i -> select(if (i == 0) partial else stored[i - 1]) }.show()
        },
        button("Date…") {
            val c = Calendar.getInstance().apply { timeInMillis = key }
            DatePickerDialog(this, { _, y, m, dd -> select(SleepNight.keyFor(Calendar.getInstance().apply { set(y, m, dd, 12, 0, 0) }.timeInMillis).coerceIn(oldest, today)) },
                c.get(Calendar.YEAR), c.get(Calendar.MONTH), c.get(Calendar.DAY_OF_MONTH)).apply { datePicker.minDate = oldest; datePicker.maxDate = today + 86399_000L }.show()
        },
        button(if (mode == WindowMode.NOON) "Noon→noon ⇄" else "Midnight ⇄") { setMode(if (mode == WindowMode.NOON) WindowMode.MIDNIGHT else WindowMode.NOON) },
    ))
    // charts, in the required order, plus a popup overlay
    val axis = TimeAxis(page.t0, page.t1); val scrub = Scrub().apply { zoom = com.rlong.sleephub.ring.data.HistoryZoom.forPage(page.t0, page.t1) }
    val sleepPeriods = d.sleepPeriods; val arousals = d.arousals; val perPeriod = d.perPeriod; val spo2Dips = d.spo2Dips
    val zeoSegs = d.zeoSegs; val oxi = d.oxi
    val charts = vbox()
    fun lp(h: Int) = LinearLayout.LayoutParams(-1, dp(h)).apply { topMargin = dp(2) }
    // Combined SleepHub page: Zeo hypnogram, ring hypnogram, oximeter SpO2, actigraphy, HR, HRV, temp, oximeter pulse.
    // Only each chart's min/avg/max line is shown as text; a source with no data in the window gets a slim "no data" strip.
    fun slim(label: String, color: Int) = charts.addView(VitalChart(this, axis, scrub).apply { title = "$label — no data"; titleColor = Color.GRAY; this.color = color }, lp(30))
    if (zeoSegs.isEmpty()) slim("Zeo sleep stages", Color.GRAY)
    else charts.addView(HypnoChart(this, axis, scrub).apply { segs = zeoSegs; title = "Zeo sleep stages" + if (d.req.zeoDemo) " (DEMO)" else "" }, lp(110))
    if (page.segments.isEmpty()) slim("Ring sleep stages", Color.GRAY)
    else charts.addView(HypnoChart(this, axis, scrub).apply { segs = page.segments; title = "Ring sleep stages" }, lp(110))
    if (d.spo2Line == null) slim("Oximeter SpO2", Color.rgb(0, 200, 255)) else {
        charts.addView(text(d.spo2Line, 12f, Color.rgb(0, 200, 255)))
        charts.addView(OxiChart(this, axis, scrub).apply { series = d.spo2; refs = listOf(90, 88); marks = d.odiMarks; lo = 80; hi = 100
            color = Color.rgb(0, 200, 255); title = "Oximeter SpO2 (%, 1 Hz)"; titleColor = color; bands = page.vitals.sleepBands }, lp(90))
    }
    if (page.movement.isEmpty()) slim("Movement", Color.GRAY)
    else charts.addView(MoveChart(this, axis, scrub).apply { data = page.movement; this.arousals = arousals; bands = page.vitals.sleepBands; title = "Movement" }, lp(60))
    for (m in listOf(Metric.HEART_RATE, Metric.HRV, Metric.TEMP)) {
        val label = if (m == Metric.ENERGY) "Physical strength" else m.label
        if (page.vitals.stats(m) == null) { slim(label, METRIC_COLORS[m]!!); continue }
        charts.addView(text(statsLine(page.vitals, m).replace(m.label, label), 12f, METRIC_COLORS[m]!!))
        charts.addView(VitalChart(this, axis, scrub).apply { periodic = page.vitals.periodic(m); oneShot = page.vitals.oneShot(m); appAuto = page.vitals.appAuto(m); stream = page.vitals.stream(m)
            if (m == Metric.SPO2) marks = spo2Dips.map { it.start to it.end }
            if (m == Metric.HEART_RATE) marks = arousals.filter { it.hrRise }.map { it.time to it.time + 60_000L }; bands = page.vitals.sleepBands
            color = METRIC_COLORS[m]!!; pointsOnly = m == Metric.SPO2; title = label + if (m.unit.isNotEmpty()) " (${m.unit})" else ""; titleColor = color }, lp(90))
    }
    if (d.pulseLine == null) slim("Oximeter pulse", Color.rgb(255, 130, 130)) else {
        charts.addView(text(d.pulseLine, 12f, Color.rgb(255, 130, 130)))
        charts.addView(OxiChart(this, axis, scrub).apply { series = d.pulse; color = Color.rgb(255, 130, 130); title = "Oximeter pulse (bpm, 1 Hz)"; titleColor = color; bands = page.vitals.sleepBands }, lp(90))
    }
    var lastStage: Int? = null
    val hms = SimpleDateFormat("EEE HH:mm:ss", Locale.US)
    val frame = scrubFrame(charts, scrub, { lastStage = null }) { chart, t ->
        val r = page.readout(t)
        if (r.stage != lastStage && lastStage != null) chart.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
        lastStage = r.stage
        (Nearest.stage(zeoSegs, t)?.let { "Zeo: ${if (it == 4) "unscored" else STAGE_NAMES[it.coerceIn(0, 3)]}\n" } ?: if (zeoSegs.isNotEmpty()) "Zeo: —\n" else "") +
        readoutText(r).let { s -> if (scrub.zoom?.zoomed == true) hms.format(Date(t)) + s.substring(s.indexOf('\n').coerceAtLeast(0)) else s }.replace("\nStage: ", "\nRing stage: ") + oxiReadout(oxi, t, d.oxiTimes) + (arousals.firstOrNull { abs(it.time - t) <= 90_000L }?.let { "\nArousal burst (proxy: movement after ≥5 min still) @${hm.format(Date(it.time))} mag ${it.magnitude}" + if (it.hrRise) ", HR+" else "" } ?: "") +
            (spo2Dips.firstOrNull { t in (it.start - 30_000L)..(it.end + 30_000L) }?.let { dp -> String.format(Locale.US, "\nSpO2 dip %.0f→%.0f%% (%ds)", dp.baseline, dp.nadir, (dp.end - dp.start) / 1000) +
                sleepPeriods.zip(perPeriod).firstOrNull { (pp, _) -> dp.start in pp.first..pp.second }?.let { (pp, l) -> "\nDips ≥4%/≥10s this sleep (${hm.format(Date(pp.first))}–${hm.format(Date(pp.second))}): ${l.size} · screening only" }.orEmpty() } ?: "")
    }
    col.addView(text("Pinch to zoom (down to 60 s) · two fingers to pan · double-tap to reset · one finger to scrub", 11f, Color.GRAY))
    col.addView(frame)
    col.addView(text(Intervals.describe(ring.intervals, ring.tempState), 12f, Color.rgb(180, 220, 255)))

    // sleep summary (night ending on the morning of this date)
    if (night.isEmpty) col.addView(text("No sleep data for this night", 14f, Color.LTGRAY))
    else {
        val tot = night.totals
        col.addView(text("Sleep ${night.start?.let { hm.format(Date(it)) } ?: "—"} → ${night.end?.let { hm.format(Date(it)) } ?: "—"}   asleep ${fmtDur(night.asleepMinutes)}", 14f))
        val legend = LinearLayout(this)
        STAGE_NAMES.forEachIndexed { i, s -> legend.addView(text("■ $s ${fmtDur(tot[i])}", 13f, if (i == 2) Color.rgb(110, 140, 255) else STAGE_COLORS[i])) }
        col.addView(legend)
        night.sessions.filter { !it.isNap }.forEach { s -> col.addView(text(String.format(Locale.US, "Ring session %s–%s  score %.0f  efficiency %.0f%%", hm.format(Date(s.start)), hm.format(Date(s.end)), s.score, s.efficiency), 12f, Color.LTGRAY)) }
        col.addView(text(if (night.naps.isEmpty()) "Naps: none" else night.naps.joinToString("\n") { "Nap ${hm.format(Date(it.start))}–${hm.format(Date(it.end))} (${fmtDur(((it.end - it.start) / 60000).toInt())})" }, 12f, Color.rgb(200, 170, 255)))
    }


    col.addView(text(if (ring.fullSyncRunning) "Sync in progress… ${ring.status}" else ring.syncSummary.ifEmpty { "Not synced yet. Auto-sync runs after connecting (max once per hour)." }, 12f, Color.GRAY))
    col.addView(syncButtons(ring))
    col.addView(button(if (a.oxi.syncing) "Oximeter: ${a.oxi.syncStatus}" else "Sync oximeter history") {
        a.oxi.syncHistory { m -> runOnUiThread { toast(m) } }?.let { toast(it) } ?: toast("Oximeter history sync started") })
    val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date(key))
    col.addView(hbox(
        button("Sleep CSV: this night") { if (night.isEmpty) toast("No sleep data for this night") else export("sleep_$ymd.csv") { NightCsv.write(listOf(night), it) } },
        button("Sleep CSV: all nights") { Thread { val all = a.db.allNights(demo); runOnUiThread { if (all.isEmpty()) toast("No nights stored") else export("sleep_all_nights.csv") { NightCsv.write(all, it) } } }.start() },
    ))
    col.addView(hbox(
        button("Vitals CSV: this page") { export("vitals_$ymd.csv") { VitalsCsv.write(page.vitals.samples.values.flatten(), it) } },
        button("Vitals CSV: all days") { Thread { val all = a.db.allVitals(demo); runOnUiThread { if (all.isEmpty()) toast("No vitals stored") else export("vitals_all_days.csv") { VitalsCsv.write(all, it) } } }.start() },
    ))
    col.addView(text("Swipe left/right on the date header or use ◀ ▶ to change day.", 11f, Color.GRAY))
    // v0.3.12: History no longer auto-refreshes (data keeps being collected in the background); reload on demand.
    col.addView(text("Loaded ${SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(now))} · tap Refresh to show data collected since", 11f, Color.GRAY))
    col.addView(button("⟳ Refresh") { HistoryCache.invalidate(); refresh() })
    val sv = ScrollView(this).apply { addView(col) }
    if (HistoryCache.scrollKey == key) { val y = HistoryCache.scrollY; sv.post { sv.scrollTo(0, y) } } else { HistoryCache.scrollKey = key; HistoryCache.scrollY = 0 }
    sv.setOnScrollChangeListener { _, _, y, _, _ -> HistoryCache.scrollKey = key; HistoryCache.scrollY = y }
    return sv
}


private fun Activity.export(name: String, body: (Appendable) -> Unit) {
    val f = File(app().dir("exports"), name)
    f.bufferedWriter().use { body(it) }
    shareFiles(listOf(f), name)
}

/** v0.2.0 sync controls: full = every day the ring keeps (0–6), recent = v18 incremental sync (today from the last-synced page + yesterday if incomplete). */
fun Activity.syncButtons(ring: Ring): View = hbox(
    button("Sync full history") { when { ring.state != Ring.State.CONNECTED -> toast("Not connected"); ring.fullSyncRunning -> toast("A sync is already running")
        else -> AlertDialog.Builder(this).setTitle("Sync full history?")
            .setMessage("Re-downloads days 0–${com.rlong.sleephub.ring.proto.SyncPlan.MAX_DAY} of every table (all days the ring keeps). Takes a few minutes.")
            .setPositiveButton("Sync") { _, _ -> ring.syncFullWeek(); toast("Full sync started") }.setNegativeButton("Cancel", null).show() } },
    button("Sync recent") { when { ring.state != Ring.State.CONNECTED -> toast("Not connected"); ring.fullSyncRunning -> toast("A sync is already running")
        else -> { ring.syncAllHistory(trigger = "incremental"); toast("Syncing recent (incremental)") } } },
)

/** Oximeter value at [t] (nearest within 3 s). [times] = the readings' time array, built once per page load. */
fun oxiReadout(l: List<com.rlong.sleephub.oxi.OxiReading>, t: Long, times: LongArray = LongArray(l.size) { l[it].time }): String {
    if (l.isEmpty()) return ""
    val i = Nearest.index(times, t, 3_000L)
    return if (i < 0) "\nOximeter: —" else l[i].let { "\nOximeter SpO2 ${it.spo2 ?: "–"}% · PR ${it.pulse ?: "–"}" }
}

/**
 * 1 Hz chart on the shared [TimeAxis]; gaps (null/invalid value, or >5 s without data) are never joined. Optional
 * horizontal reference lines and event marks. v0.3.12: draws min/max per pixel column when the window holds more
 * points than pixels, raw 1 Hz points (dots when ≤10 min) when zoomed in; the Path is built once per window/size.
 */
class OxiChart(ctx: Context, axis: TimeAxis, scrub: Scrub) : TimeChart(ctx, axis, scrub) {
    var series: Series = Series.EMPTY; set(v) { field = v; dataChanged() }
    var refs: List<Int> = emptyList(); var marks: List<Pair<Long, Long>> = emptyList()
    var color = Color.CYAN; var lo: Int? = null; var hi: Int? = null
    private val line = Path(); private val singles = Path(); private val dots = Path()
    private var l = 0.0; private var h = 1.0
    private fun y(v: Double) = top + plotH - plotH * (0.05f + 0.9f * ((v - l) / (h - l)).toFloat())
    override fun rebuild() {
        line.reset(); singles.reset(); dots.reset(); if (series.isEmpty) return
        l = minOf((lo ?: Int.MAX_VALUE).toDouble(), series.min.toDouble()); h = maxOf((hi ?: Int.MIN_VALUE).toDouble(), series.max.toDouble()).let { if (it - l < 1) l + 1 else it }
        val yf = { v: Float -> y(v.toDouble()) }
        val res = Decimator.decimate(series.times, series.vals, axis.t0, axis.t1, cols(), 5_000L)
        pathOf(res, yf, line, singles, 1.5f)
        if (axis.t1 - axis.t0 <= 10 * 60_000L) dotsOf(res, yf, dots, 3f)
    }
    override fun drawContent(c: Canvas) {
        if (series.isEmpty) return
        label { cc -> p.textSize = 16f; p.color = Color.GRAY; p.style = Paint.Style.FILL
        cc.drawText(String.format(Locale.US, "%.0f", h), 4f, top + 14, p); cc.drawText(String.format(Locale.US, "%.0f", l), 4f, top + plotH - 2, p)
        refs.forEach { r -> p.color = if (r <= 88) Color.rgb(230, 60, 60) else Color.rgb(230, 170, 0); cc.drawText("$r", axis.left(width) - 26f, y(r.toDouble()) + 6f, p) } }
        refs.forEach { r -> p.color = if (r <= 88) Color.rgb(230, 60, 60) else Color.rgb(230, 170, 0); p.strokeWidth = 1.5f
            val yy = y(r.toDouble()); c.drawLine(axis.left(width), yy, width.toFloat(), yy, p) }
        p.color = color; p.strokeWidth = 1.5f
        p.style = Paint.Style.STROKE; c.drawPath(line, p)
        p.style = Paint.Style.FILL; c.drawPath(singles, p); c.drawPath(dots, p)
        p.color = Color.rgb(255, 140, 0)
        marks.forEach { (a, b) -> if (b >= axis.t0 && a <= axis.t1) c.drawRect(x(a), top, maxOf(x(b), x(a) + 3f), top + 6f, p) }
    }
}
