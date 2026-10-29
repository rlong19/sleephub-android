package com.rlong.sleephub.ring.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.rlong.sleephub.app
import com.rlong.sleephub.ring.proto.Sample
import java.io.File

fun Context.dp(v: Int) = (v * resources.displayMetrics.density).toInt()
fun Context.vbox(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; v.forEach { addView(it) } }
fun Context.hbox(vararg v: View) = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; v.forEach { addView(it, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f)) } }
fun Context.text(s: CharSequence, size: Float = 15f, color: Int = Color.WHITE) = TextView(this).apply { text = s; textSize = size; setTextColor(color); setPadding(dp(4), dp(2), dp(4), dp(2)) }
fun Context.button(s: String, f: () -> Unit) = Button(this).apply { text = s; isAllCaps = false; setOnClickListener { f() } }
fun Context.scroll(v: View) = ScrollView(this).apply { addView(v) }
fun Context.toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()
fun Context.demoBanner() = text("DEMO DATA — simulated, not your ring (Settings → Demo mode)", 15f, Color.WHITE).apply {
    setBackgroundColor(Color.rgb(200, 0, 0)); gravity = android.view.Gravity.CENTER
    visibility = if (app().showingDemo) View.VISIBLE else View.GONE }

/** Simple sparkline / bar chart of samples. */
/** Pre-v11 per-metric chart: points in sequence by index (no time axis). With a [scrub], hold/drag selects the nearest index. */
class ChartView(ctx: Context, val scrub: Scrub? = null) : View(ctx) {
    var samples: List<Sample> = emptyList(); set(v) { field = v; invalidate() }
    var color = Color.CYAN
    private val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { strokeWidth = 3f; style = Paint.Style.STROKE }
    /** Nearest point index for finger x (same x mapping as onDraw). */
    fun indexAt(x: Float): Int = if (samples.size < 2) 0 else Math.round(x / (width.toFloat() / (samples.size - 1))).coerceIn(0, samples.size - 1)
    private val gesture = scrub?.let { s -> s.charts += this; ScrubTouch(this, s) { x -> indexAt(x).toLong() } }
    override fun onTouchEvent(ev: android.view.MotionEvent): Boolean = (samples.isNotEmpty() && gesture?.onTouch(ev) == true) || super.onTouchEvent(ev)
    override fun performClick(): Boolean = super.performClick()
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.rgb(30, 30, 30))
        if (samples.size < 2) { p.style = Paint.Style.FILL; p.color = Color.GRAY; p.textSize = 36f; c.drawText("No data", 20f, 50f, p); p.style = Paint.Style.STROKE; return }
        val min = samples.minOf { it.value }; val max = samples.maxOf { it.value }.coerceAtLeast(min + 1)
        val dx = width.toFloat() / (samples.size - 1)
        p.color = color
        for (i in 1 until samples.size) {
            val y1 = height - height * ((samples[i - 1].value - min) / (max - min)).toFloat()
            val y2 = height - height * ((samples[i].value - min) / (max - min)).toFloat()
            c.drawLine((i - 1) * dx, y1, i * dx, y2, p)
        }
        scrub?.t?.let { i -> val xx = i.toInt().coerceIn(0, samples.size - 1) * dx
            val y = height - height * ((samples[i.toInt().coerceIn(0, samples.size - 1)].value - min) / (max - min)).toFloat()
            p.color = Color.WHITE; p.strokeWidth = 2f; c.drawLine(xx, 0f, xx, height.toFloat(), p)
            p.style = Paint.Style.FILL; c.drawCircle(xx, y, 7f, p); p.style = Paint.Style.STROKE; p.strokeWidth = 3f }
    }
}

/** Sleep hypnogram bars. */
class SleepView(ctx: Context) : View(ctx) {
    var segs: List<com.rlong.sleephub.ring.proto.SleepSeg> = emptyList(); set(v) { field = v; invalidate() }
    private val p = Paint(Paint.ANTI_ALIAS_FLAG)
    private val cols = intArrayOf(Color.RED, Color.rgb(102, 178, 255), Color.rgb(0, 0, 204), Color.rgb(0, 153, 0))
    private val hts = floatArrayOf(4f, 2f, 1f, 3f)
    override fun onDraw(c: Canvas) {
        c.drawColor(Color.BLACK)
        if (segs.isEmpty()) return
        val t0 = segs.first().start; val t1 = segs.last().end.coerceAtLeast(t0 + 1)
        val w = width.toFloat(); val h = height.toFloat()
        for (s in segs) {
            val x0 = w * (s.start - t0).toFloat() / (t1 - t0); val x1 = w * (s.end - t0).toFloat() / (t1 - t0)
            val st = s.stage.coerceIn(0, 3); p.color = cols[st]
            c.drawRect(x0, h - h * hts[st] / 4f, x1, h, p)
        }
    }
}

fun Activity.shareFiles(files: List<File>, subject: String) {
    if (files.isEmpty()) { toast("Nothing to share"); return }
    val uris = ArrayList(files.map { com.rlong.sleephub.ui.ShareProvider.uri(this, it) })
    val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
    else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
    i.type = "*/*"; i.putExtra(Intent.EXTRA_SUBJECT, subject).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    i.clipData = android.content.ClipData.newRawUri(subject, uris[0]).apply { uris.drop(1).forEach { addItem(android.content.ClipData.Item(it)) } }
    startActivity(Intent.createChooser(i, subject))
}
