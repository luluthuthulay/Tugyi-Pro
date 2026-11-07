package io.nekohasekai.sagernet.tugyi

import android.content.Context
import io.nekohasekai.sagernet.ktx.app
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Data usage counters (Hsu, 2026-11-07, v1.0.25): three small
 * numbers above "Location" - Today / This Month / Total, one
 * combined download+upload number each.
 *
 * Counting starts at install / first use: all three start at 0
 * and only grow from there, no backfilled history.
 *
 * Source of truth: the service's per-session totals
 * (SpeedDisplayData.txTotal/rxTotal - cumulative bytes for the
 * current tunnel session, bypass excluded). MainActivity
 * forwards them to the home once a second while the home is
 * visible. Each tick we add (newTotal - lastTotal) to the
 * counters. Because the totals are cumulative, the first tick
 * after coming back from the background catches up ALL the
 * traffic from while the app was away in one jump - nothing is
 * missed just because the home was not on screen. If the totals
 * jump backwards, the session restarted (new tunnel): the tail
 * of the old session after the last seen tick cannot be
 * recovered, so we just restart the baseline at 0 and count the
 * new session from there.
 *
 * Writes are batched: every 10th tick and on pause, so the
 * counters are not rewritten to flash every second. A process
 * kill loses at most the last few seconds of increments - and
 * even those are recovered by the catch-up delta on the next
 * tick, as long as the session is the same.
 */
object TugyiDataUsage {

    private const val PREFS = "tugyi_data_usage"
    private const val KEY_TOTAL = "total_bytes"
    private const val KEY_TODAY = "today_key"
    private const val KEY_TODAY_BYTES = "today_bytes"
    private const val KEY_MONTH = "month_key"
    private const val KEY_MONTH_BYTES = "month_bytes"
    private const val KEY_LAST_TX = "last_tx_total"
    private const val KEY_LAST_RX = "last_rx_total"
    private const val PERSIST_EVERY = 10

    private var totalBytes = 0L
    private var todayBytes = 0L
    private var monthBytes = 0L
    private var lastTxTotal = 0L
    private var lastRxTotal = 0L
    private var loaded = false
    private var ticksSincePersist = 0

    private fun prefs(context: Context = app) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    private fun ensureLoaded() {
        if (loaded) return
        val p = prefs()
        totalBytes = p.getLong(KEY_TOTAL, 0L)
        todayBytes = p.getLong(KEY_TODAY_BYTES, 0L)
        monthBytes = p.getLong(KEY_MONTH_BYTES, 0L)
        lastTxTotal = p.getLong(KEY_LAST_TX, 0L)
        lastRxTotal = p.getLong(KEY_LAST_RX, 0L)
        loaded = true
    }

    @Synchronized
    fun addSessionTotals(txTotal: Long, rxTotal: Long) {
        ensureLoaded()
        // Day / month rollover (device timezone): a new day
        // zeroes Today, a new month zeroes This Month. Total
        // never resets.
        val now = Date()
        val todayKey = SimpleDateFormat("yyyy-MM-dd", Locale.US).format(now)
        val monthKey = SimpleDateFormat("yyyy-MM", Locale.US).format(now)
        val p = prefs()
        if (p.getString(KEY_TODAY, "") != todayKey) {
            todayBytes = 0L
            p.edit().putString(KEY_TODAY, todayKey).apply()
        }
        if (p.getString(KEY_MONTH, "") != monthKey) {
            monthBytes = 0L
            p.edit().putString(KEY_MONTH, monthKey).apply()
        }
        // Session restarted (new tunnel): totals jump back
        // towards 0. Restart the baseline so the new session
        // counts from 0 instead of going negative.
        if (txTotal < lastTxTotal || rxTotal < lastRxTotal) {
            lastTxTotal = 0L
            lastRxTotal = 0L
        }
        val deltaTx = (txTotal - lastTxTotal).coerceAtLeast(0L)
        val deltaRx = (rxTotal - lastRxTotal).coerceAtLeast(0L)
        val delta = deltaTx + deltaRx
        if (delta > 0L) {
            totalBytes += delta
            todayBytes += delta
            monthBytes += delta
        }
        lastTxTotal = txTotal
        lastRxTotal = rxTotal
        ticksSincePersist++
        if (ticksSincePersist >= PERSIST_EVERY) persistLocked()
    }

    @Synchronized
    fun persist() {
        ensureLoaded()
        persistLocked()
    }

    private fun persistLocked() {
        ticksSincePersist = 0
        prefs().edit()
            .putLong(KEY_TOTAL, totalBytes)
            .putLong(KEY_TODAY_BYTES, todayBytes)
            .putLong(KEY_MONTH_BYTES, monthBytes)
            .putLong(KEY_LAST_TX, lastTxTotal)
            .putLong(KEY_LAST_RX, lastRxTotal)
            .apply()
    }

    @Synchronized
    fun totalBytes(): Long {
        ensureLoaded()
        return totalBytes
    }

    @Synchronized
    fun todayBytes(): Long {
        ensureLoaded()
        return todayBytes
    }

    @Synchronized
    fun monthBytes(): Long {
        ensureLoaded()
        return monthBytes
    }

    /** "1.5 MB", "31.3 GB", "456 GB" - one decimal, except
     * whole GB at 100+ so 456 GB does not read "456.0 GB". */
    fun format(bytes: Long): String {
        val gb = bytes / 1_073_741_824.0
        if (gb >= 1.0) {
            return if (gb >= 100.0) String.format(Locale.US, "%.0f GB", gb)
            else String.format(Locale.US, "%.1f GB", gb)
        }
        val mb = bytes / 1_048_576.0
        if (mb >= 1.0) return String.format(Locale.US, "%.1f MB", mb)
        val kb = bytes / 1024.0
        if (kb >= 1.0) return String.format(Locale.US, "%.0f KB", kb)
        return String.format(Locale.US, "%d B", bytes)
    }
}
