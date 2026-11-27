package io.nekohasekai.sagernet.tugyi

import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

/**
 * Tugyi Pro on-demand engine wake-up (Hsu, 2026-11-27).
 *
 * Example Hsu gave: Thai is selected, every Thai server is down,
 * or the line is very slow - the engine must wake up IMMEDIATELY
 * and search for fresh nodes, not wait for its 6-hour run.
 *
 * How it works - TWO TIERS (Hsu, 2026-11-27 22:05):
 * - NORMAL: at most one wake-up per country per 30 minutes. A
 *   ping test that settles with 0 live nodes, or a best node
 *   slower than SLOW_MS (1000 ms), wakes the engine through
 *   maybeWakeEngine(). The 30-minute rule stops a bad country
 *   from spamming the engine on every open.
 * - EMERGENCY: when the app itself proves the whole country is
 *   down - the connected tunnel died and failover found nothing,
 *   or all 5 connect tries failed - wakeEngineEmergency() wakes
 *   the engine IMMEDIATELY, bypassing the 30-minute rule. The
 *   only guard is 5 minutes between emergency wakes for the same
 *   country, so a still-dead country cannot spam GitHub while
 *   its new list is also dead. The FIRST emergency wake never
 *   waits. Both tiers share one last-wake timestamp, so an
 *   emergency wake also counts toward the normal 30 minutes.
 * - After a wake-up the app polls AutoPull every 30 s for up to
 *   10 minutes; the moment a new list hash arrives the poll stops
 *   (AutoPull already applies it and re-tests it when idle).
 * - If the endpoint does not exist yet, or the call fails, nothing
 *   breaks: the failure is only logged, and the app keeps its old
 *   behaviour (failover, then "no live node"). A wake-up must never
 *   block a connect or crash the app.
 *
 * Still missing (NOT built here): the proxy endpoint itself,
 * POST /api/engine/refresh {country, reason, bestMs} -> 202, which
 * triggers workflow_dispatch on luluthuthulay/Tugyi-Pro's
 * engine.yml. Lovable edits are May's own direct work, not an
 * agent's, and the endpoint needs a GitHub token with
 * actions:write - that token is created and stored in Lovable,
 * never in the app or in this repo.
 */
object TugyiEngineRefresh {

    /** Best-ms above which the line counts as "very slow". */
    private const val SLOW_MS = 1000

    /** At most one NORMAL wake-up per country per 30 minutes. */
    private const val COOLDOWN_MS = 30 * 60 * 1000L

    /** Emergency wakes for the same country are at least 5 minutes
     * apart (anti-spam guard only - the first one never waits). */
    private const val EMERGENCY_COOLDOWN_MS = 5 * 60 * 1000L

    /** Poll for the new list every 30 s, for at most 10 minutes. */
    private const val POLL_INTERVAL_MS = 30 * 1000L
    private const val POLL_TIMEOUT_MS = 10 * 60 * 1000L

    /** One shared last-wake timestamp per country (both tiers). */
    private const val KEY_LAST_WAKE_PREFIX = "engine_wake_"

    private const val REFRESH_URL =
        "https://tugyi-pro-download-hub.lovable.app/api/engine/refresh"

    private val client = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null

    /** NORMAL wake-up, called after a country's ping test settles.
     * Fires only when the country is all-down or very slow, and at
     * most once per country per 30 minutes. */
    fun maybeWakeEngine(country: TugyiCountry, bestMs: Int?, liveCount: Int) {
        val bad = liveCount == 0 || (bestMs != null && bestMs > SLOW_MS)
        if (!bad) return
        val now = System.currentTimeMillis()
        val last = TugyiEngine.prefs()
            .getLong(KEY_LAST_WAKE_PREFIX + country.code, 0L)
        if (now - last < COOLDOWN_MS) {
            Logs.d("Tugyi engine wake ${country.code}: cooldown, skip")
            return
        }
        val reason = if (liveCount == 0) "all_down" else "slow"
        sendWake(country, reason, bestMs)
    }

    /** EMERGENCY wake-up, called when the app itself proved the
     * whole country is down: the connected tunnel died and
     * failover found nothing, or all 5 connect tries failed.
     * Bypasses the normal 30-minute cooldown - the first
     * emergency wake is immediate. Only guard: 5 minutes between
     * emergency wakes for the same country. */
    fun wakeEngineEmergency(country: TugyiCountry) {
        val now = System.currentTimeMillis()
        val last = TugyiEngine.prefs()
            .getLong(KEY_LAST_WAKE_PREFIX + country.code, 0L)
        if (now - last < EMERGENCY_COOLDOWN_MS) {
            Logs.d("Tugyi engine wake ${country.code}: emergency cooldown, skip")
            return
        }
        sendWake(country, "emergency_all_down", null)
    }

    /** The POST itself: wake the engine through the proxy, record
     * the shared last-wake time on success, and start polling for
     * the new list. A failure is only logged, never thrown. */
    private fun sendWake(country: TugyiCountry, reason: String, bestMs: Int?) {
        val now = System.currentTimeMillis()
        runOnDefaultDispatcher {
            try {
                val body = JSONObject()
                    .put("country", country.code.lowercase())
                    .put("reason", reason)
                    .put("bestMs", bestMs ?: -1)
                    .toString()
                    .toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(REFRESH_URL)
                    .header("User-Agent", "TugyiPro/1.0")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) {
                        Logs.d("Tugyi engine wake ${country.code}: " +
                            "HTTP ${response.code} (endpoint missing?)")
                        return@runOnDefaultDispatcher
                    }
                    TugyiEngine.prefs().edit()
                        .putLong(KEY_LAST_WAKE_PREFIX + country.code, now)
                        .apply()
                    Logs.d("Tugyi engine wake ${country.code}: " +
                        "accepted ($reason), polling for new list")
                    startPolling(country)
                }
            } catch (e: Exception) {
                // Silent: a failed wake-up must never affect the app.
                Logs.d("Tugyi engine wake ${country.code} failed: ${e.message}")
            }
        }
    }

    /** Poll AutoPull for the new list until it arrives or we time
     * out. Stops at once when the saved list hash changes (a new
     * list arrived and AutoPull already applies + re-tests it). */
    private fun startPolling(country: TugyiCountry) {
        pollJob?.cancel()
        pollJob = runOnDefaultDispatcher {
            val hashBefore = TugyiEngine.lastHash(country)
            val deadline = System.currentTimeMillis() + POLL_TIMEOUT_MS
            while (isActive && System.currentTimeMillis() < deadline) {
                delay(POLL_INTERVAL_MS)
                if (!isActive) break
                TugyiAutoPull.checkNow()
                if (TugyiEngine.lastHash(country) != hashBefore) {
                    Logs.d("Tugyi engine wake ${country.code}: new list arrived")
                    break
                }
            }
        }
    }
}
