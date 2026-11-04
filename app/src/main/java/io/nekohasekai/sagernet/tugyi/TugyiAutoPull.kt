package io.nekohasekai.sagernet.tugyi

import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Tugyi Pro auto-pull.
 *
 * - On app open, and while the app is open, check the three small GitHub
 *   live files (output/sg.txt, jp.txt, us.txt).
 * - Download only if the content hash changed, to save mobile data.
 * - Silent: no dialog, no toast on success or on failure.
 * - Never affects a live tunnel: if connected, a changed list is only
 *   marked pending. It is applied on the next country tap, before the
 *   new connection starts, or when the tunnel stops.
 * - If a fetch fails, the last saved live list stays in place.
 *
 * The poll interval below is only the phone's check interval. Real
 * freshness is limited by the GitHub engine's publish interval
 * (for example every 6 hours); polling faster does not make GitHub
 * publish faster.
 */
object TugyiAutoPull {

    /** Check every 15 minutes while the app is open. */
    private const val POLL_INTERVAL_MS = 15 * 60 * 1000L

    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(20, TimeUnit.SECONDS)
        .build()

    private var pollJob: Job? = null

    /** One silent check of all three countries. Safe to call repeatedly. */
    fun checkNow() {
        runOnDefaultDispatcher {
            for (country in TugyiCountry.all()) {
                try {
                    val text = fetch(country) ?: continue
                    val hash = TugyiEngine.hashOf(text)
                    if (hash == TugyiEngine.lastHash(country)) continue
                    // Changed list found. Record it, then apply only if idle.
                    TugyiEngine.saveHash(country, hash)
                    if (DataStore.serviceState.connected) {
                        TugyiEngine.setPending(country, true)
                    } else {
                        TugyiEngine.applyUpdateIfIdle(country)
                    }
                } catch (e: Exception) {
                    // Silent fallback: keep the last saved live list.
                    Logs.d("Tugyi auto-pull ${country.code} failed: ${e.message}")
                }
            }
        }
    }

    /** Start the while-open poll loop. Idempotent; call from onResume. */
    fun startWhileOpen() {
        if (pollJob?.isActive == true) return
        pollJob = runOnDefaultDispatcher {
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                checkNow()
            }
        }
    }

    fun stopWhileOpen() {
        pollJob?.cancel()
        pollJob = null
    }

    private fun fetch(country: TugyiCountry): String? {
        val request = Request.Builder()
            .url(TugyiCountry.liveUrl(country))
            .header("User-Agent", "TugyiPro/1.0")
            .header("Cache-Control", "no-cache")
            .build()
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            return response.body?.string()?.takeIf { it.isNotBlank() }
        }
    }
}
