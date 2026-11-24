package com.vrgames.huazhong.render

import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * v0.4.0 (PICO 2026-11-23: 「运行时帧数偏低」): the frame rate as the device runs it, logged every [EVERY] seconds under
 * HuaPerf: frames per second, and the frame times' median, 95th percentile and worst, with what the scene draws then.
 */
object PerfLog {
    private const val EVERY = 5.0
    private val dts = FloatArray(2048)
    private var n = 0
    private var t = 0.0
    private val main = Handler(Looper.getMainLooper())

    fun frame(dt: Double, what: () -> String) {
        if (dt <= 0) return
        if (n < dts.size) dts[n] = (dt * 1000).toFloat()
        n++
        t += dt
        if (t < EVERY) return
        val k = minOf(n, dts.size)
        val s = dts.copyOf(k).also { it.sort() }
        val line = "fps %.1f, frame ms p50 %.1f p95 %.1f max %.1f (%d frames in %.1f s)".format(n / t, s[k / 2], s[(k * 95) / 100], s[k - 1], n, t)
        // What the scene draws is read on the main thread (the SDK's model components ask for it).
        main.post { Log.i(WaterView.PERF, "$line ${runCatching(what).getOrElse { "?" }}") }
        n = 0
        t = 0.0
    }
}
