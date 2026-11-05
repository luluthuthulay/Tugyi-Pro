package io.nekohasekai.sagernet.tugyi

import io.nekohasekai.sagernet.bg.proto.UrlTest
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.ktx.Logs
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

/**
 * Tugyi Pro in-app ping check (Hsu, 2026-11-05 13:48).
 *
 * Rule: once a list is inside the app, the app itself checks it in the
 * background - n/a nodes are dropped, ms nodes are kept, sorted by
 * lowest ping first. Tap SG/JP/US connects to the lowest-ping node;
 * if it breaks, the fragment tries the next lowest-ping node.
 *
 * How it works, using NekoBox's own UrlTest (the same test the
 * Configuration screen uses, sing-box URL test, 5s timeout):
 * - Only when NO tunnel is live, so testing never disturbs a connection.
 * - 5 nodes at a time, same concurrency NekoBox uses for URL tests.
 * - ping > 0 ms = live, ping <= 0 or exception = n/a (dropped).
 * - The ranked list is saved in SharedPreferences ("tugyi_pro",
 *   key "ping_<CODE>" = comma-separated profile ids, lowest ping
 *   first) so a tap can connect immediately without waiting for a
 *   fresh test. A test is re-run at most every 5 minutes, or after a
 *   new list is pulled.
 * - If there is no ping ranking yet (first run, or test never finished),
 *   the tap falls back to the engine's order (#1, #2, ...).
 * - profile.ping / status are also written to the database, so the
 *   values stay consistent with NekoBox's own delay display.
 */
object TugyiPing {

    private const val KEY_PING_PREFIX = "ping_"
    private const val KEY_PING_TIME_PREFIX = "ping_time_"

    /** Re-test at most every 5 minutes per country. */
    private const val MIN_RETEST_MS = 5 * 60 * 1000L

    private val testing = mutableSetOf<String>()

    /** Ranked profile ids for a country: lowest ping first, n/a dropped. */
    fun rankedIds(country: TugyiCountry): List<Long> {
        val saved = TugyiEngine.prefs()
            .getString(KEY_PING_PREFIX + country.code, null)
            ?.split(",")
            ?.mapNotNull { it.toLongOrNull() }
            .orEmpty()
        if (saved.isEmpty()) return emptyList()
        // Drop ids that no longer exist in the group (list rotated).
        val existing = TugyiEngine.profileIds(country).toSet()
        return saved.filter { it in existing }
    }

    private fun lastTestAt(country: TugyiCountry): Long =
        TugyiEngine.prefs().getLong(KEY_PING_TIME_PREFIX + country.code, 0L)

    private fun saveRanking(country: TugyiCountry, ranked: List<Long>) {
        TugyiEngine.prefs().edit()
            .putString(KEY_PING_PREFIX + country.code, ranked.joinToString(","))
            .putLong(KEY_PING_TIME_PREFIX + country.code, System.currentTimeMillis())
            .apply()
    }

    /** Fire-and-forget background test for one country. Idle only. */
    fun testCountryAsync(country: TugyiCountry, force: Boolean = false) {
        if (DataStore.serviceState.connected) return
        if (!force && System.currentTimeMillis() - lastTestAt(country) < MIN_RETEST_MS) return
        synchronized(testing) {
            if (!testing.add(country.code)) return
        }
        runOnDefaultDispatcher {
            try {
                testCountry(country)
            } finally {
                synchronized(testing) { testing.remove(country.code) }
            }
        }
    }

    /** Fire-and-forget background test for all three countries. */
    fun testAllAsync(force: Boolean = false) {
        for (country in TugyiCountry.all()) testCountryAsync(country, force)
    }

    /** The actual test. Must be called from a background dispatcher. */
    suspend fun testCountry(country: TugyiCountry) {
        if (DataStore.serviceState.connected) return
        val group = SagerDatabase.groupDao.allGroups()
            .firstOrNull { it.name == TugyiEngine.groupName(country) } ?: return
        val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
        if (profiles.isEmpty()) return
        val results = coroutineScope {
            val semaphore = Semaphore(5)
            profiles.map { profile ->
                async {
                    semaphore.withPermit {
                        try {
                            val ms = UrlTest().doTest(profile)
                            profile.status = if (ms > 0) 1 else 3
                            profile.ping = if (ms > 0) ms else 0
                        } catch (e: Exception) {
                            profile.status = 3
                            profile.ping = 0
                            Logs.d("Tugyi ping ${country.code} failed: ${e.message}")
                        }
                        profile
                    }
                }
            }.awaitAll()
        }
        // Persist ping values, then save the ranking: ms only, lowest first.
        try {
            ProfileManager.updateProfile(results)
        } catch (e: Exception) {
            Logs.d("Tugyi ping save failed: ${e.message}")
        }
        val ranked = results
            .filter { it.ping > 0 }
            .sortedBy { it.ping }
            .map { it.id }
        saveRanking(country, ranked)
        Logs.d("Tugyi ping ${country.code}: ${ranked.size}/${results.size} live")
    }
}
