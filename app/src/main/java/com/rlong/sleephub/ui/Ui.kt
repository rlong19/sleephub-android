package com.rlong.sleephub.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rlong.sleephub.zeo.ZStage
import java.io.File

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.vbox(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } }
fun Context.hbox(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) } }
fun Context.text(s: CharSequence, size: Float = 15f, color: Int = Color.WHITE) = TextView(this).apply { text = s; textSize = size; setTextColor(color); setPadding(dp(4), dp(2), dp(4), dp(2)) }
fun Context.header(s: String) = text(s, 18f, Color.CYAN).apply { setPadding(dp(4), dp(10), dp(4), dp(2)) }
fun Context.button(s: String, f: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { f() } }
fun Context.scroll(v: View) = ScrollView(this).apply { addView(v) }
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
fun Context.demoBadge(s: String) = text(s, 15f, Color.WHITE).apply { setBackgroundColor(Color.rgb(200, 0, 0)); gravity = Gravity.CENTER }

/** Zeo hypnogram: Wake top … Deep bottom, Zeo colours. */
class HypnoView(ctx: Context) : View(ctx) {
    var stages: List<ZStage> = emptyList(); set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val col = mapOf(ZStage.WAKE to Color.rgb(230, 70, 50), ZStage.REM to Color.rgb(120, 200, 80), ZStage.LIGHT to Color.rgb(140, 160, 170), ZStage.DEEP to Color.rgb(20, 90, 160), ZStage.UNDEFINED to Color.rgb(170, 170, 170))
    private val lvl = mapOf(ZStage.WAKE to 4f, ZStage.REM to 3f, ZStage.LIGHT to 2f, ZStage.DEEP to 1f, ZStage.UNDEFINED to 4f)
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(25, 25, 25))
        if (stages.isEmpty()) { p.color = Color.GRAY; p.textSize = 34f; c.drawText("No hypnogram yet", 20f, 50f, p); return }
        val w = width.toFloat() / stages.size; val h = height.toFloat()
        stages.forEachIndexed { i, s -> val l = lvl[s] ?: return@forEachIndexed; p.color = col[s]!!; c.drawRect(i * w, h - h * l / 4f, (i + 1) * w + 0.5f, h, p) }
    }
}

/** Generic line chart of (time,value) points. */
class LineView(ctx: Context) : View(ctx) {
    var pts: List<Pair<Long, Double>> = emptyList(); set(v) { field = v; invalidate() }
    var color = Color.CYAN
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(25, 25, 25))
        if (pts.size < 2) { p.color = Color.GRAY; p.textSize = 30f; c.drawText("waiting for data", 20f, 40f, p); return }
        val t0 = pts.first().first; val t1 = pts.last().first.coerceAtLeast(t0 + 1)
        val mn = pts.minOf { it.second }; val mx = pts.maxOf { it.second }.coerceAtLeast(mn + 1)
        p.color = color
        for (i in 1 until pts.size) {
            val x0 = width * (pts[i - 1].first - t0).toFloat() / (t1 - t0); val x1 = width * (pts[i].first - t0).toFloat() / (t1 - t0)
            val y0 = (height - height * (pts[i - 1].second - mn) / (mx - mn)).toFloat(); val y1 = (height - height * (pts[i].second - mn) / (mx - mn)).toFloat()
            c.drawLine(x0, y0, x1, y1, p)
        }
        p.color = Color.LTGRAY; p.textSize = 26f; c.drawText("%.0f–%.0f".format(mn, mx), 6f, 26f, p)
    }
}

fun Activity.shareFiles(files: List<File>, subject: String) {
    if (files.isEmpty()) { toast("Nothing to share"); return }
    val uris = ArrayList(files.map { ShareProvider.uri(this, it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.type = "*/*"; i.putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = android.content.ClipData.newRawUri(subject, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    startActivity(Intent.createChooser(i, subject))
}

/** Live SpO2 (fixed 80–100 %) + pulse (autoscaled) as two stacked plots sharing the time axis; lines break on gaps. */
class OxiGraphView(ctx: Context) : View(ctx) {
    var readings: List<com.rlong.sleephub.oxi.OxiReading> = emptyList(); set(v) { field = v; invalidate() }
    var windowMs = 5 * 60_000L; set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f }
    override fun onMeasure(w: Int, h: Int) = setMeasuredDimension(MeasureSpec.getSize(w), (resources.displayMetrics.density * 260).toInt())
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(20, 20, 20))
        val now = System.currentTimeMillis(); val t0 = now - windowMs
        val half = height / 2f; val pad = 8f
        val sp = com.rlong.sleephub.oxi.OxiSeries.segments(readings, now, windowMs, { it.spo2 })
        val pr = com.rlong.sleephub.oxi.OxiSeries.segments(readings, now, windowMs, { it.pulse })
        val (plo, phi) = com.rlong.sleephub.oxi.OxiSeries.pulseRange(pr)
        fun x(t: Long) = width * (t - t0).toFloat() / windowMs
        fun plot(segs: List<List<Pair<Long, Double>>>, lo: Double, hi: Double, top: Float, bot: Float, col: Int) {
            p.color = col; p.style = Paint.Style.STROKE
            fun y(v: Double) = (bot - (bot - top) * ((v.coerceIn(lo, hi) - lo) / (hi - lo))).toFloat()
            for (s in segs) {
                if (s.size == 1) c.drawCircle(x(s[0].first), y(s[0].second), 2.5f, p)
                for (i in 1 until s.size) c.drawLine(x(s[i - 1].first), y(s[i - 1].second), x(s[i].first), y(s[i].second), p)
            }
        }
        // grid: SpO2 90% and 95% guides
        p.style = Paint.Style.STROKE; p.strokeWidth = 1f; p.color = Color.rgb(60, 60, 60)
        listOf(90.0, 95.0).forEach { v -> val yy = (half - pad - (half - 2 * pad) * ((v - 80) / 20)).toFloat(); c.drawLine(0f, yy, width.toFloat(), yy, p) }
        c.drawLine(0f, half, width.toFloat(), half, p)
        p.strokeWidth = 3f
        plot(sp, 80.0, 100.0, pad + 30f, half - pad, Color.CYAN)
        plot(pr, plo, phi, half + pad + 30f, height - pad - 24f, Color.rgb(255, 120, 120))
        p.style = Paint.Style.FILL; p.textSize = 30f
        val ls = sp.lastOrNull()?.lastOrNull()?.takeIf { now - it.first < 4000 }; val lp = pr.lastOrNull()?.lastOrNull()?.takeIf { now - it.first < 4000 }
        p.color = Color.CYAN; c.drawText("SpO2 ${ls?.second?.toInt()?.let { "$it%" } ?: "–"}", 10f, 32f, p)
        p.color = Color.rgb(255, 120, 120); c.drawText("Pulse ${lp?.second?.toInt()?.let { "$it bpm" } ?: "–"}", 10f, half + 32f, p)
        p.color = Color.GRAY; p.textSize = 22f
        c.drawText("80–100%", width - 110f, 28f, p); c.drawText("%.0f–%.0f".format(plo, phi), width - 110f, half + 28f, p)
        c.drawText("-${windowMs / 60_000} min", 6f, height - 6f, p); c.drawText("now", width - 50f, height - 6f, p)
        if (sp.isEmpty() && pr.isEmpty()) { p.textSize = 28f; c.drawText("no readings yet (finger off or not connected)", 20f, half - 10f, p) }
    }
}
