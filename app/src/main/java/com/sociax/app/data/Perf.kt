package com.sociax.app.data

import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log

/**
 * Start-up and login timing (plan v3.5 section 25: "measure cold-start, login completion time ..."). Nothing is sent anywhere:
 * the numbers go to Logcat and (Step 52) are kept IN MEMORY ONLY (newest 30 per name, gone when the app closes) so Settings > Data can
 * show a report you can copy. Nothing is written to disk and nothing is sent anywhere. Logcat:  adb logcat -s SociaxPerf
 * `marks` are measured from the moment the app process started (so they include the cold start); `start` / `end` time a span.
 */
object Perf {
    private const val TAG = "SociaxPerf"
    private val marks = HashSet<String>()
    private val spans = HashMap<String, Long>()
    private val sinceStart = LinkedHashMap<String, Long>()          // Step 52: cold-start marks, in the order they happened
    private val samples = LinkedHashMap<String, ArrayList<Long>>()  // Step 52: newest timings per name, in ms
    private const val KEEP = 30

    private fun sinceProcessStart(): Long = SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()

    /** Logs "<name> = N ms since process start", once per name. */
    fun mark(name: String) {
        if (marks.add(name)) {
            val t = sinceProcessStart()
            Log.i(TAG, "$name = $t ms since process start")
            synchronized(this) { sinceStart[name] = t }
        }
    }

    /** Step 52: remember one timing (milliseconds) of a named operation, for the report in Settings > Data. */
    fun record(name: String, ms: Long) {
        Log.i(TAG, "$name took $ms ms")
        synchronized(this) {
            val list = samples.getOrPut(name) { ArrayList() }
            list.add(ms)
            if (list.size > KEEP) list.removeAt(0)
        }
    }

    /** Step 52: plain-text report to copy and send to the developer. Numbers only: no account, name, file name or address. */
    fun report(): String = synchronized(this) {
        val sb = StringBuilder()
        sb.append("Sociax performance report (this phone, since the app started)\n")
        sb.append("device: ").append(Build.MANUFACTURER).append(' ').append(Build.MODEL).append(", Android ").append(Build.VERSION.SDK_INT).append('\n')
        for ((name, t) in sinceStart) sb.append(name).append(": ").append(t).append(" ms since start\n")
        for ((name, list) in samples) {
            val sorted = list.sorted()
            sb.append(name).append(": n=").append(sorted.size).append(", min ").append(sorted.first())
                .append(", median ").append(sorted[sorted.size / 2]).append(", max ").append(sorted.last()).append(" ms\n")
        }
        sb.toString().trimEnd()
    }

    /** True when nothing was measured yet. */
    fun isEmpty(): Boolean = synchronized(this) { sinceStart.isEmpty() && samples.isEmpty() }

    fun start(name: String) { spans[name] = SystemClock.elapsedRealtime() }

    fun end(name: String) {
        val t = spans.remove(name) ?: return
        record(name, SystemClock.elapsedRealtime() - t)
    }
}
