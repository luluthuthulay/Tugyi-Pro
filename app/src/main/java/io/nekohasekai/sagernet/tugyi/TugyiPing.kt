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
 * Configuration screen uses, sing-box URL test, 3s timeout for
 * Tugyi since 2026-11-27, 5s NekoBox default elsewhere):
 * - YES, it can also test while a tunnel is live, without touching
 *   the tunnel (Hsu, 2026-11-05 13:54): UrlTest builds a SEPARATE
 *   sing-box instance with forTest=true, which has NO tun-in inbound,
 *   no FakeDNS and no Clash API (ConfigBuilder.kt), so it cannot
 *   stop, replace or reroute the live tunnel. It only measures the
 *   tested node. Two safeguards while connected: (1) the connected
 *   country's own list is NOT re-tested while its tunnel is live,
 *   so the failover order in use stays stable; (2) concurrency drops
 *   from 10 to 2, so the test does not load the phone while the
 *   tunnel is carrying traffic.
 * - 10 nodes at a time when idle (Hiddify/sing-box also test 10 at
 *   a time; NekoBox tests 5 by default), 2 at a time while connected.
 * - ping > 0 ms = live, ping <= 0 or exception = n/a (dropped).
 * - The ranked list is saved in SharedPreferences ("tugyi_pro",
 *   key "ping_<CODE>" = comma-separated profile ids, lowest ping
 *   first) so a tap can connect immediately without waiting for a
 *   fresh test. A test is re-run at most every 30 minutes, or after a
 *   new list is pulled.
 * - If there is no ping ranking yet (first run, or test never finished),
 *   the tap falls back to the engine's order (#1, #2, ...).
 * - profile.ping / status are also written to the database, so the
 *   values stay consistent with NekoBox's own delay display.
 * - Honest limit: a test run while a tunnel is live can show a
 *   slightly higher ms than an idle test, because the phone is busy
 *   carrying tunnel traffic. ms is still a live/dead signal (n/a =
 *   does not connect), but do not compare a connected-time ms
 *   against an idle-time ms as if they were equal.
 */
object TugyiPing {

    private const val KEY_PING_PREFIX = "ping_"
    private const val KEY_PING_TIME_PREFIX = "ping_time_"

    /** Re-test at most every 30 minutes per country (Hsu, 2026-11-05).
     * Nodes do not change that fast (GitHub engine publishes every
     * 6 hours), so testing more often only wastes battery and data.
     * A new list is still tested immediately (force = true), and the
     * app open forces a fresh test of the selected country every
     * time (Hsu, 2026-11-06: "App ဖွင့်တိုင်း စစ်ရမှာ"). */

    /** Ping timeout for the Tugyi check (Hsu, 2026-11-27: make the
     * app fast on first open, 10 at a time, 3s). A dead node used to
     * block its slot for the full 5s NekoBox default; at 3s a dead
     * node is dropped 2s sooner and a 10-node country finishes in
     * ~3s worst case instead of ~5s. Live nodes answer in well
     * under 1s either way, so the shorter timeout does not change
     * which live node wins. The Configuration screen keeps the
     * NekoBox 5s default (UrlTest() with no argument). */
    private const val TUGYI_PING_TIMEOUT_MS = 3000

    /** Switch tolerance (Hsu, 2026-11-27: took all the Pro advice -
     * Hiddify / sing-box urltest / Mihomo all use 50 ms). A
     * challenger must beat the incumbent best by MORE than 50 ms
     * to take the #1 spot, or the "best" flaps between two
     * near-equal nodes on every run. Judged on the current test's
     * own ping values, never on a stale ms. */
    private const val TOLERANCE_MS = 50

    private const val MIN_RETEST_MS = 30 * 60 * 1000L

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

    /** True when a ping test for this country finished recently
     * (within MIN_RETEST_MS), so the ranking is settled. Used by
     * the ping-gate (Hsu, 2026-11-06): only allow VPN on once the
     * check is stable. A test that found 0 live nodes still counts
     * as settled - the tap then says "no live node" instead of
     * waiting for a test that already ran. */
    fun isRankingFresh(country: TugyiCountry): Boolean =
        System.currentTimeMillis() - lastTestAt(country) < MIN_RETEST_MS

    /** True while a ping test for this country is running right now.
     * Used by the ping-gate (Hsu, 2026-11-06): while the ping is
     * running, VPN must NOT connect - even when an old ranking is
     * still fresh, the new check has to finish first. */
    fun isTesting(country: TugyiCountry): Boolean =
        synchronized(testing) { country.code in testing }

    private fun saveRanking(country: TugyiCountry, ranked: List<Long>) {
        TugyiEngine.prefs().edit()
            .putString(KEY_PING_PREFIX + country.code, ranked.joinToString(","))
            .putLong(KEY_PING_TIME_PREFIX + country.code, System.currentTimeMillis())
            .apply()
    }

    /** The country whose tunnel is live right now, if any. */
    fun connectedCountry(): TugyiCountry? {
        if (!DataStore.serviceState.connected) return null
        val groupId = DataStore.selectedGroup
        return TugyiCountry.all().firstOrNull { TugyiEngine.groupId(it) == groupId }
    }

    /** Fire-and-forget background test for one country. */
    fun testCountryAsync(country: TugyiCountry, force: Boolean = false) {
        // While a tunnel is live, do NOT re-test that country's own
        // list (failover order in use must stay stable). Other
        // countries can be tested in the background, tunnel untouched.
        if (connectedCountry() == country) return
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

    /** Fire-and-forget background test for all countries. */
    fun testAllAsync(force: Boolean = false) {
        for (country in TugyiCountry.all()) testCountryAsync(country, force)
    }

    /** The actual test. Must be called from a background dispatcher. */
    suspend fun testCountry(country: TugyiCountry) {
        if (connectedCountry() == country) return
        val group = SagerDatabase.groupDao.allGroups()
            .firstOrNull { it.name == TugyiEngine.groupName(country) } ?: return
        val profiles = SagerDatabase.proxyDao.getByGroup(group.id)
        if (profiles.isEmpty()) return
        // 2 at a time while a tunnel is live, 10 when idle (Hsu,
        // 2026-11-06: all 10 nodes at once; Hiddify/sing-box test
        // 10 at a time, NekoBox 5 by default).
        val concurrency = if (DataStore.serviceState.connected) 2 else 10
        val results = coroutineScope {
            val semaphore = Semaphore(concurrency)
            profiles.map { profile ->
                async {
                    semaphore.withPermit {
                        try {
                            val ms = UrlTest(TUGYI_PING_TIMEOUT_MS).doTest(profile)
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
        val live = results
            .filter { it.ping > 0 }
            .sortedBy { it.ping }
        var ranked = live.map { it.id }
        // 50 ms tolerance (see TOLERANCE_MS): the incumbent best
        // (previous ranking's #1) keeps the spot unless the new
        // fastest beats it by MORE than 50 ms in THIS test.
        val previousBest = rankedIds(country).firstOrNull()
        if (previousBest != null && ranked.isNotEmpty() && ranked[0] != previousBest) {
            val pingById = live.associate { it.id to it.ping }
            val bestPing = pingById[ranked[0]]
            val incumbentPing = pingById[previousBest]
            if (bestPing != null && incumbentPing != null &&
                bestPing + TOLERANCE_MS > incumbentPing) {
                ranked = listOf(previousBest) + ranked.filter { it != previousBest }
            }
        }
        saveRanking(country, ranked)
        Logs.d("Tugyi ping ${country.code}: ${ranked.size}/${results.size} live")
        // All-down / very-slow: wake the engine NOW so it searches
        // for fresh nodes instead of waiting for the 6-hour run
        // (Hsu, 2026-11-27). Best ms is the ranking's #1 ping.
        val bestMs = live.firstOrNull()?.ping
        TugyiEngineRefresh.maybeWakeEngine(country, bestMs, ranked.size)
    }
}
