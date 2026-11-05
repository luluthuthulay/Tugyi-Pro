package io.nekohasekai.sagernet.tugyi

import android.content.Context
import io.nekohasekai.sagernet.GroupType
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.GroupManager
import io.nekohasekai.sagernet.database.ProxyGroup
import io.nekohasekai.sagernet.database.SagerDatabase
import io.nekohasekai.sagernet.database.SubscriptionBean
import io.nekohasekai.sagernet.group.GroupUpdater
import io.nekohasekai.sagernet.ktx.app
import java.security.MessageDigest

/**
 * Tugyi Pro engine glue.
 *
 * Design choice, kept deliberately small: each country is one ordinary
 * NekoBox SUBSCRIPTION group whose link is the GitHub engine's live file
 * for that country (output/sg.txt, jp.txt, us.txt). The engine already
 * ranked the nodes and removed dead ones, so profile order in the group
 * IS the ranking: #1 is best, #2 is the first failover, and so on.
 * No NekoBox tunnel code is touched; only groups are created/updated.
 *
 * Auto-pull safety: a new list is never applied while a tunnel is live.
 * If a fetch finds a changed list while connected, it is stored as
 * pending and applied on the next country tap (before connecting) or
 * when the tunnel is stopped. A failed fetch never clears a saved list.
 */
object TugyiEngine {

    private const val PREFS = "tugyi_pro"
    private const val KEY_HASH_PREFIX = "hash_"
    private const val KEY_PENDING_PREFIX = "pending_"

    fun groupName(country: TugyiCountry): String = "Tugyi ${country.code}"

    fun prefs(context: Context = app) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun hashOf(text: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    fun lastHash(country: TugyiCountry): String? =
        prefs().getString(KEY_HASH_PREFIX + country.code, null)

    fun saveHash(country: TugyiCountry, hash: String) {
        prefs().edit().putString(KEY_HASH_PREFIX + country.code, hash).apply()
    }

    fun setPending(country: TugyiCountry, pending: Boolean) {
        prefs().edit().putBoolean(KEY_PENDING_PREFIX + country.code, pending).apply()
    }

    fun isPending(country: TugyiCountry): Boolean =
        prefs().getBoolean(KEY_PENDING_PREFIX + country.code, false)

    /** Find the country's group, or create it (empty) if it does not exist. */
    suspend fun ensureGroup(country: TugyiCountry): ProxyGroup {
        val existing = SagerDatabase.groupDao.allGroups()
            .firstOrNull { it.name == groupName(country) }
        if (existing != null) return existing
        val group = ProxyGroup(type = GroupType.SUBSCRIPTION)
        group.name = groupName(country)
        val subscription = SubscriptionBean()
        subscription.link = TugyiCountry.liveUrl(country)
        // Tugyi manages its own polling; do not let NekoBox auto-update
        // this group on its own schedule while a tunnel may be live.
        subscription.autoUpdate = false
        group.subscription = subscription
        return GroupManager.createGroup(group)
    }

    /** Profiles in engine ranking order (userOrder). Empty if never pulled. */
    fun profileIds(country: TugyiCountry): List<Long> {
        val group = SagerDatabase.groupDao.allGroups()
            .firstOrNull { it.name == groupName(country) } ?: return emptyList()
        return SagerDatabase.proxyDao.getIdsByGroup(group.id)
    }

    /**
     * Apply the latest GitHub list to this country's group, but only when
     * no tunnel is live. Returns true if the group was (re)loaded.
     * Callers must check isConnected first; this function re-checks anyway.
     */
    suspend fun applyUpdateIfIdle(country: TugyiCountry): Boolean {
        if (DataStore.serviceState.connected) return false
        val group = ensureGroup(country)
        val ok = GroupUpdater.executeUpdate(group, false)
        if (ok) {
            // executeUpdate re-reads the subscription link, so a fresh
            // fetch hash is recorded by TugyiAutoPull, not here.
            setPending(country, false)
        }
        return ok
    }

    /** Select a profile (by ranking index) as the next tunnel profile. */
    fun selectProfile(country: TugyiCountry, index: Int): Long? {
        val group = SagerDatabase.groupDao.allGroups()
            .firstOrNull { it.name == groupName(country) } ?: return null
        val ids = SagerDatabase.proxyDao.getIdsByGroup(group.id)
        val id = ids.getOrNull(index) ?: return null
        return selectProfileById(group.id, id)
    }

    /** Select a profile by its id (used for ping-ranked order). */
    fun selectProfileById(groupId: Long, id: Long): Long {
        DataStore.selectedGroup = groupId
        DataStore.selectedProxy = id
        DataStore.currentProfile = id
        return id
    }

    /** The group id for a country, or null if the group does not exist. */
    fun groupId(country: TugyiCountry): Long? =
        SagerDatabase.groupDao.allGroups()
            .firstOrNull { it.name == groupName(country) }?.id
}
