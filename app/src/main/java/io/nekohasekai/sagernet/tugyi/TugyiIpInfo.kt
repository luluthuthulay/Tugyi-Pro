package io.nekohasekai.sagernet.tugyi

import io.nekohasekai.sagernet.ktx.Logs
import org.json.JSONObject
import java.net.URL

/**
 * Real IP + real location (Hsu, 2026-11-05 17:22-17:23).
 *
 * The displayed location must come from the ACTUAL exit IP, not from
 * the country button. If the chosen node actually exits via a China
 * IP, the app must show China, so Hsu knows the real region.
 *
 * How: fetch https://ipinfo.io/json from the app itself.
 * - Disconnected: the request goes direct, so it returns the phone's
 *   real IP/location (Myanmar).
 * - Connected: the request goes through the VPN tunnel (Android routes
 *   the app's own traffic through the tunnel), so it returns the exit
 *   node's real IP/location (e.g. Singapore, or China if the node
 *   actually exits there).
 * No API key needed (ipinfo.io free tier). Returns null on failure,
 * and the caller keeps the last known / assumed location.
 */
object TugyiIpInfo {

    data class Info(
        val ip: String,
        val city: String,
        val country: String,
        val lat: Double,
        val lon: Double
    ) {
        val label: String
            get() = if (city.isNotBlank()) "$city, $country" else country
    }

    fun fetch(): Info? {
        return try {
            val conn = URL("https://ipinfo.io/json").openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 8000
            conn.readTimeout = 8000
            conn.setRequestProperty("User-Agent", "TugyiPro/1.0")
            val text = conn.inputStream.bufferedReader().readText()
            val json = JSONObject(text)
            val loc = json.optString("loc", "") // "lat,lon"
            val parts = loc.split(",")
            val lat = parts.getOrNull(0)?.toDoubleOrNull() ?: return null
            val lon = parts.getOrNull(1)?.toDoubleOrNull() ?: return null
            Info(
                ip = json.optString("ip", ""),
                city = json.optString("city", ""),
                country = json.optString("country", ""),
                lat = lat,
                lon = lon
            )
        } catch (e: Exception) {
            Logs.d("Tugyi IP info failed: ${e.message}")
            null
        }
    }
}
