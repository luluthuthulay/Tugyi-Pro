package io.nekohasekai.sagernet.tugyi

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.SystemClock
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import java.util.zip.ZipInputStream

/**
 * In-app update check + download for Tugyi Pro (Hsu, 2026-11-08,
 * v1.0.29): the Phoenix-VPN update function, adapted for Tugyi.
 *
 * The browser is NEVER involved: some phones' browsers block GitHub
 * APK downloads, so the version check reads the GitHub "latest
 * release" redirect with the app's own HTTP client, the APK is
 * downloaded by the app itself, and the local file is handed to the
 * system installer. Both requests are bound to the underlying
 * physical network (the EndpointService rule) so a dead tunnel
 * can never strangle an update.
 *
 * Tugyi differences from Phoenix: releases are named by tag
 * (v1.0.x) while VERSION_NAME stays 1.4.2, so the check compares
 * tags (TUGYI_VERSION_TAG), and the download is the arm64-v8a
 * APK only (Hsu, 2026-11-05: send ONLY the arm64 APK).
 */
object TugyiUpdateService {

    /** The release tag of THIS build. checkUpdate (AboutFragment)
     * and the home update strip compare the latest Tugyi-Pro
     * release tag against it. Bump this to the new tag on EVERY
     * release push. */
    const val TUGYI_VERSION_TAG = "v1.0.31"

    private const val LATEST_URL =
        "https://github.com/luluthuthulay/Tugyi-Pro/releases/latest"
    private const val DOWNLOAD_BASE =
        "https://github.com/luluthuthulay/Tugyi-Pro/releases/download"

    /**
     * Lovable download proxy (Hsu, 2026-11-08): do NOT use the
     * Phoenix route (ipmh.lovable.app — the myokooo2004 account).
     * The Tugyi proxy will be built later, on the Lovable account
     * Hsu gave on 2026-10-31, and its URL goes here. While this
     * is blank the download goes straight to GitHub. A Lovable
     * download is accepted only when the APK bytes contain the
     * expected release tag — the Lovable route serves the CURRENT
     * version, so during the window between a GitHub publish and
     * the Lovable link update it would serve the OLD file.
     */
    private const val LOVABLE_DOWNLOAD_URL = "https://tugyi-pro-download-hub.lovable.app/api/public/apk/tugyi-pro"

    private const val TAG = "TugyiUpdate"

    data class ReleaseInfo(val tag: String, val version: String) {
        val apkUrl: String get() = "$DOWNLOAD_BASE/$tag/TugyiPro-1.4.2-arm64-v8a.apk"
        val apkName: String get() = "TugyiPro-1.4.2-arm64-v8a.apk"
        val proxyUrl: String get() = LOVABLE_DOWNLOAD_URL
    }

    private val checkClient = OkHttpClient.Builder()
        .connectTimeout(20, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    private val downloadClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    /** The latest release published on GitHub. Never throws. */
    suspend fun fetchLatest(context: Context): Result<ReleaseInfo> =
        withContext(Dispatchers.IO) {
            val bound = boundClient(context, checkClient)
            val r = attemptLatest(bound ?: checkClient)
            if (r.isSuccess || bound == null) r else attemptLatest(checkClient)
        }

    private fun attemptLatest(client: OkHttpClient): Result<ReleaseInfo> {
        return try {
            val req = Request.Builder()
                .url(LATEST_URL)
                .header("User-Agent", "TugyiPro (Android update check)")
                .header("Cache-Control", "no-cache")
                .get()
                .build()
            client.newCall(req).execute().use { resp ->
                val loc = resp.header("Location")
                    ?: return Result.failure(
                        IOException("HTTP ${resp.code}, no redirect")
                    )
                // Location: .../releases/tag/v1.0.29
                val tag = loc.substringAfter("/releases/tag/", "")
                    .substringBefore("?")
                    .trimEnd('/')
                if (tag.isEmpty()) {
                    return Result.failure(IOException("Bad redirect"))
                }
                val version = tag.removePrefix("v")
                if (version.isEmpty() || version == tag) {
                    return Result.failure(IOException("Bad tag: $tag"))
                }
                Result.success(ReleaseInfo(tag, version))
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    // ============================================================
    //  Kept downloaded APK (Phoenix v1.53 rule).
    //
    //  A completed update download is KEPT in filesDir/updates until
    //  its update actually installs, so an install error, a cancel,
    //  or an app close never forces a re-download: the next UPDATE
    //  tap installs the kept file directly. The record (version +
    //  name + size + sha256 of the completed download) lives in
    //  prefs, so it survives process death and app restarts.
    //  Deletion happens ONLY on successful install
    //  (TugyiUpdateCleanupReceiver, via MY_PACKAGE_REPLACED) —
    //  install failure/cancel sends no such broadcast, so the kept
    //  file stays for a no-redownload retry.
    // ============================================================

    data class KeptApk(
        val version: String,
        val name: String,
        val size: Long,
        val sha256: String
    )

    private const val KEPT_PREFS = "tugyi_update"
    private const val KEY_KEPT_VERSION = "kept_apk_version"
    private const val KEY_KEPT_NAME = "kept_apk_name"
    private const val KEY_KEPT_SIZE = "kept_apk_size"
    private const val KEY_KEPT_SHA = "kept_apk_sha256"

    /** Durable home for kept update APKs (never cache-purged). */
    fun updatesDir(context: Context): File = File(context.filesDir, "updates")

    fun keptRecord(context: Context): KeptApk? {
        val p = context.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE)
        val version = p.getString(KEY_KEPT_VERSION, null) ?: return null
        val name = p.getString(KEY_KEPT_NAME, null) ?: return null
        val size = p.getLong(KEY_KEPT_SIZE, -1L)
        val sha = p.getString(KEY_KEPT_SHA, null) ?: return null
        if (size < 0) return null
        return KeptApk(version, name, size, sha)
    }

    fun saveKeptRecord(context: Context, kept: KeptApk) {
        context.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE).edit()
            .putString(KEY_KEPT_VERSION, kept.version)
            .putString(KEY_KEPT_NAME, kept.name)
            .putLong(KEY_KEPT_SIZE, kept.size)
            .putString(KEY_KEPT_SHA, kept.sha256)
            .apply()
    }

    fun clearKeptRecord(context: Context) {
        context.getSharedPreferences(KEPT_PREFS, Context.MODE_PRIVATE).edit()
            .remove(KEY_KEPT_VERSION)
            .remove(KEY_KEPT_NAME)
            .remove(KEY_KEPT_SIZE)
            .remove(KEY_KEPT_SHA)
            .apply()
    }

    /**
     * Delete the recorded kept APK + its record + legacy
     * cache/updates leftovers. Never throws.
     */
    fun deleteKeptApk(context: Context) {
        try {
            val kept = keptRecord(context)
            if (kept != null) {
                val f = File(updatesDir(context), kept.name)
                if (f.exists()) f.delete()
            }
            clearKeptRecord(context)
            val legacy = File(context.cacheDir, "updates")
            if (legacy.isDirectory) {
                legacy.listFiles()?.forEach { if (it.isFile) it.delete() }
            }
        } catch (_: Exception) {
        }
    }

    /** SHA-256 hex of a file, or null on any error. */
    fun sha256(file: File): String? = try {
        val md = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buf = ByteArray(64 * 1024)
            while (true) {
                val n = input.read(buf)
                if (n < 0) break
                md.update(buf, 0, n)
            }
        }
        md.digest().joinToString("") { "%02x".format(it) }
    } catch (_: Exception) {
        null
    }

    /**
     * Download with Lovable-first + GitHub fallback (Phoenix v1.61
     * shape). While LOVABLE_DOWNLOAD_URL is blank the Lovable leg
     * is skipped and the versioned GitHub URL is used directly.
     * A Lovable download is accepted only when the APK bytes
     * contain the expected release tag (see containsTag).
     */
    suspend fun download(
        context: Context,
        info: ReleaseInfo,
        destDir: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        if (info.proxyUrl.isNotBlank()) {
            val proxy = attemptDownload(context, info.proxyUrl, true, info, destDir, onProgress)
            if (proxy.isSuccess) {
                val file = proxy.getOrNull()!!
                if (containsTag(file, info.tag)) return@withContext proxy
                Log.i(TAG, "Update download: Lovable served a file without ${info.tag} — falling back to GitHub")
                file.delete()
            } else {
                Log.i(TAG, "Update download: Lovable failed — falling back to GitHub")
            }
        }
        attemptDownload(context, info.apkUrl, false, info, destDir, onProgress)
    }

    /** True when the APK's dex contains the release tag. The tag
     * string lives in the APK's dex, so a stale Lovable download
     * (an older release's APK) does not contain the new tag. The
     * dex entries are DEFLATE-compressed inside the APK, so the
     * file is scanned through a ZipInputStream, not raw.
     * Verified on the VM against the v1.0.28 APK: "v1.0.28" found
     * in classes.dex, "v1.0.29" not found. */
    fun containsTag(file: File, tag: String): Boolean = try {
        val needle = tag.toByteArray(Charsets.UTF_8)
        var found = false
        ZipInputStream(file.inputStream().buffered()).use { zip ->
            var entry = zip.nextEntry
            while (entry != null && !found) {
                if (entry.name.startsWith("classes") && entry.name.endsWith(".dex")) {
                    val bytes = zip.readBytes()
                    outer@ for (i in 0..bytes.size - needle.size) {
                        for (j in needle.indices) {
                            if (bytes[i + j] != needle[j]) continue@outer
                        }
                        found = true
                        break
                    }
                }
                entry = zip.nextEntry
            }
        }
        found
    } catch (_: Exception) {
        false
    }

    /**
     * One download attempt from [url] into [destDir], reporting
     * progress 0..100 via [onProgress]. Returns the downloaded
     * file. Writes to a .tmp file and renames only on success, so
     * a killed download can never masquerade as a complete APK.
     */
    private suspend fun attemptDownload(
        context: Context,
        url: String,
        viaProxy: Boolean,
        info: ReleaseInfo,
        destDir: File,
        onProgress: (Int) -> Unit
    ): Result<File> = withContext(Dispatchers.IO) {
        try {
            destDir.mkdirs()
            val dest = File(destDir, info.apkName)
            val tmp = File(destDir, info.apkName + ".tmp")
            if (tmp.exists()) tmp.delete()
            val client = boundClient(context, downloadClient) ?: downloadClient
            val req = Request.Builder()
                .url(url)
                .header("User-Agent", "TugyiPro (Android update)")
                .get()
                .build()
            Log.i(TAG, "Update download: start " + info.apkName + if (viaProxy) " via Lovable" else " via GitHub")
            val t0 = SystemClock.elapsedRealtime()
            client.newCall(req).execute().use { resp ->
                if (!resp.isSuccessful) {
                    Log.i(TAG, "Update download: HTTP " + resp.code)
                    return@withContext Result.failure(
                        IOException("HTTP ${resp.code}")
                    )
                }
                val body = resp.body
                    ?: return@withContext Result.failure(IOException("Empty body"))
                val total = body.contentLength()
                Log.i(TAG, "Update download: headers HTTP " + resp.code + " size=" + total + " after=" + (SystemClock.elapsedRealtime() - t0) + "ms")
                body.byteStream().use { input ->
                    tmp.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        var read = 0L
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            read += n
                            if (total > 0) {
                                onProgress((read * 100 / total).toInt())
                            }
                        }
                    }
                }
            }
            if (tmp.length() == 0L) {
                tmp.delete()
                Result.failure(IOException("Empty APK"))
            } else {
                if (dest.exists()) dest.delete()
                if (!tmp.renameTo(dest)) {
                    tmp.delete()
                    Result.failure(IOException("Rename failed"))
                } else {
                    onProgress(100)
                    Log.i(TAG, "Update download: done " + dest.length() + " bytes")
                    Result.success(dest)
                }
            }
        } catch (e: Exception) {
            try {
                File(destDir, info.apkName + ".tmp").delete()
            } catch (_: Exception) {
            }
            Log.i(TAG, "Update download: failed " + e.javaClass.simpleName + ": " + e.message)
            Result.failure(e)
        }
    }

    /** Dotted numeric compare: "1.0.29" > "1.0.28"; missing parts = 0. */
    fun isNewer(candidate: String, installed: String): Boolean {
        val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
        val b = installed.split('.').map { it.toIntOrNull() ?: 0 }
        for (i in 0 until maxOf(a.size, b.size)) {
            val x = a.getOrElse(i) { 0 }
            val y = b.getOrElse(i) { 0 }
            if (x != y) return x > y
        }
        return false
    }

    private fun boundClient(context: Context, base: OkHttpClient): OkHttpClient? {
        val net = underlyingNetwork(context) ?: return null
        return try {
            base.newBuilder()
                .socketFactory(net.socketFactory)
                .dns(Dns { hostname -> net.getAllByName(hostname).toList() })
                .build()
        } catch (_: Exception) {
            null
        }
    }

    /** The physical network underneath any VPN, or null. */
    private fun underlyingNetwork(context: Context): Network? {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE)
            as? ConnectivityManager ?: return null
        return cm.allNetworks.firstOrNull { n ->
            val caps = cm.getNetworkCapabilities(n) ?: return@firstOrNull false
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                !caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN) &&
                (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) ||
                    caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))
        }
    }
}
