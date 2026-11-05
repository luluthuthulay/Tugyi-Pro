package io.nekohasekai.sagernet.tugyi

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.MainActivity
import io.nekohasekai.sagernet.ui.ToolbarFragment
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import java.util.Locale

/**
 * Tugyi Pro home, redesign 2026-11-05 (Hsu: change to the design he sent).
 *
 * Blue gradient header with a big power button, live Download/Upload
 * speed in Mbps, world map, and SG/JP/US selector pill.
 *
 * Home shows ONLY SG, JP, US. No node list, no IPs, no node names here.
 * Tap a country = connect immediately to that country's best live node.
 * Tapping another country while connected stops the old tunnel and
 * starts the new country's best node. The power button disconnects
 * when connected, and connects to the selected country when not.
 * If a whole country has no live node, show that and do NOT auto-switch
 * to another country.
 *
 * In-country failover: if the best node fails to reach Connected within
 * the watch window, the fragment tries the next node, same country only.
 *
 * Lowest-ping first (Hsu, 2026-11-05 13:48): the app ping-checks each
 * country's list in the background (TugyiPing, NekoBox UrlTest) - n/a
 * nodes are dropped, ms nodes are kept sorted by lowest ping. A tap
 * connects to the lowest-ping node; if it breaks, the next lowest-ping
 * node is tried, up to 5 nodes. If no ping ranking exists yet (first
 * run), the engine's order (#1, #2, ...) is used instead.
 * A manual tap cancels any in-flight failover watch.
 *
 * Live speed: MainActivity forwards cbSpeedUpdate (bytes/s, 1s interval
 * since the v1.0.1 speedInterval fix) to this fragment, which shows
 * Download/Upload in Mbps. When disconnected the speed shows "--".
 */
class TugyiHomeFragment : ToolbarFragment(R.layout.tugyi_home) {

    private lateinit var connStatusView: TextView
    private lateinit var downloadView: TextView
    private lateinit var uploadView: TextView
    private lateinit var countryPill: TextView
    private lateinit var powerBtn: ImageButton
    private lateinit var routeMap: TugyiRouteMapView
    private lateinit var ipView: TextView
    private var watchJob: Job? = null
    private var session = 0L
    private var selectedCountry = TugyiCountry.SG

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) setStatus("VPN permission denied")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Exactly like the design Hsu sent (2026-11-05): the blue
        // header is at the very top, so the pink NekoBox appbar is
        // hidden on this home. The header has its own hamburger menu.
        view.findViewById<View>(R.id.appbar)?.visibility = View.GONE
        view.findViewById<ImageButton>(R.id.tugyi_menu_btn).setOnClickListener {
            (activity as? MainActivity)?.binding?.drawerLayout
                ?.openDrawer(GravityCompat.START)
        }
        connStatusView = view.findViewById(R.id.tugyi_conn_status)
        downloadView = view.findViewById(R.id.tugyi_download)
        uploadView = view.findViewById(R.id.tugyi_upload)
        countryPill = view.findViewById(R.id.tugyi_country_pill)
        powerBtn = view.findViewById(R.id.tugyi_power_btn)
        routeMap = view.findViewById(R.id.tugyi_map)
        ipView = view.findViewById(R.id.tugyi_ip)
        // Tapping the country pill opens the region list as a
        // bottom sheet that slides up from the bottom (Hsu, 2026-11-05).
        // Singapore is default.
        countryPill.setOnClickListener { showRegionSheet() }
        powerBtn.setOnClickListener {
            if (DataStore.serviceState.connected) {
                session++
                watchJob?.cancel()
                SagerNet.stopService()
                refreshConnectionStatus()
                // Back to the phone's real IP/location.
                refreshIpInfo()
                setStatus("Disconnected")
            } else {
                onCountryTapped(selectedCountry)
            }
        }
        selectCountry(selectedCountry)
        refreshConnectionStatus()
    }

    override fun onResume() {
        super.onResume()
        // Auto-pull on open, and keep polling while the app is open.
        // This never touches a live tunnel (see TugyiAutoPull).
        TugyiAutoPull.checkNow()
        TugyiAutoPull.startWhileOpen()
        // Background ping-check of the lists already in the app.
        // Idle only, n/a dropped, ms kept lowest-ping first.
        TugyiPing.testAllAsync()
        // Live speed from MainActivity (1s updates).
        // Fix for the v1.0.2/v1.0.4 build failure: onMainDispatcher is
        // a suspend function and cannot be called from this plain
        // lambda. Post to the main thread instead.
        (activity as? MainActivity)?.onTugyiSpeedUpdate = { txRate, rxRate ->
            activity?.runOnUiThread { showSpeed(txRate, rxRate) }
        }
        refreshConnectionStatus()
        // Real IP + real location (direct IP when disconnected,
        // exit IP when connected).
        refreshIpInfo()
    }

    override fun onPause() {
        (activity as? MainActivity)?.onTugyiSpeedUpdate = null
        TugyiAutoPull.stopWhileOpen()
        super.onPause()
    }

    override fun onDestroyView() {
        watchJob?.cancel()
        super.onDestroyView()
    }

    // The home has no status line (design, Hsu 2026-11-05): short
    // messages go out as a toast instead of extra text on screen.
    private fun setStatus(text: String) {
        activity?.runOnUiThread {
            Toast.makeText(requireContext(), text, Toast.LENGTH_SHORT).show()
        }
    }

    private fun countryLabel(country: TugyiCountry): String =
        "${country.flag} ${country.fullName}"

    private fun selectCountry(country: TugyiCountry) {
        selectedCountry = country
        // Move the map destination dot to this country.
        if (::routeMap.isInitialized) {
            routeMap.destination = TugyiRouteMapView.placeOf(country)
        }
        if (::countryPill.isInitialized) countryPill.text = countryLabel(country)
    }

    /**
     * Region list as a bottom sheet (Hsu, 2026-11-05): tapping the
     * country pill slides the sheet up from the bottom of the screen.
     * Rows are built from TugyiCountry.all(), so a region added later
     * appears here with no UI change. Screen fit: the sheet is capped
     * at 60% of the screen height and its list scrolls inside it.
     */
    private fun showRegionSheet() {
        val dialog = BottomSheetDialog(requireContext())
        val sheet = LayoutInflater.from(requireContext())
            .inflate(R.layout.tugyi_region_sheet, null)
        val list = sheet.findViewById<LinearLayout>(R.id.tugyi_region_list)
        for (country in TugyiCountry.all()) {
            val row = LayoutInflater.from(requireContext())
                .inflate(R.layout.tugyi_region_row, list, false)
            row.findViewById<TextView>(R.id.tugyi_row_flag).text = country.code
            row.findViewById<TextView>(R.id.tugyi_row_name).text =
                countryLabel(country)
            row.findViewById<TextView>(R.id.tugyi_row_check).visibility =
                if (country == selectedCountry) View.VISIBLE else View.GONE
            row.setOnClickListener {
                dialog.dismiss()
                selectCountry(country)
                onCountryTapped(country)
            }
            list.addView(row)
        }
        dialog.setContentView(sheet)
        dialog.behavior.maxHeight =
            (resources.displayMetrics.heightPixels * 0.6).toInt()
        dialog.behavior.state = BottomSheetBehavior.STATE_EXPANDED
        dialog.show()
    }

    private fun refreshConnectionStatus() {
        if (!::connStatusView.isInitialized) return
        when (DataStore.serviceState) {
            BaseService.State.Connected -> {
                TugyiPing.connectedCountry()?.let { selectCountry(it) }
                connStatusView.text = "● Connected"
                // Show the route Myanmar -> connected country on the map.
                if (::routeMap.isInitialized) routeMap.connected = true
                setStatus("Connected: ${selectedCountry.code}")
            }
            BaseService.State.Connecting -> {
                connStatusView.text = "● Connecting…"
            }
            else -> {
                connStatusView.text = "● Disconnected"
                if (::routeMap.isInitialized) routeMap.connected = false
                showSpeed(0, 0)
            }
        }
    }

    /**
     * Fetch the real IP + real location and show it.
     * Disconnected: phone's real IP (Myanmar) -> origin dot.
     * Connected: exit node's real IP -> the label AND the map
     * destination dot move to the real exit location, so a node
     * that actually exits via China shows China.
     * On failure the previous display is kept.
     */
    private fun refreshIpInfo() {
        runOnDefaultDispatcher {
            val info = TugyiIpInfo.fetch() ?: return@runOnDefaultDispatcher
            onMainDispatcher {
                if (!::ipView.isInitialized) return@onMainDispatcher
                ipView.text = "IP: ${info.ip} • ${info.label}"
                if (!::routeMap.isInitialized) return@onMainDispatcher
                val place = TugyiRouteMapView.Place(info.lon, info.lat)
                if (DataStore.serviceState.connected) {
                    // Real exit location, not the assumed country.
                    routeMap.destination = place
                } else {
                    // Real origin location.
                    routeMap.origin = place
                    routeMap.destination =
                        TugyiRouteMapView.placeOf(selectedCountry)
                }
            }
        }
    }

    private fun showSpeed(txRate: Long, rxRate: Long) {
        if (!::downloadView.isInitialized) return
        if (!DataStore.serviceState.connected) {
            downloadView.text = "--"
            uploadView.text = "--"
            return
        }
        // bytes/s -> Mbps, 1 decimal, live.
        downloadView.text = String.format(Locale.US, "%.1f", rxRate * 8.0 / 1_000_000.0)
        uploadView.text = String.format(Locale.US, "%.1f", txRate * 8.0 / 1_000_000.0)
    }

    private fun onCountryTapped(country: TugyiCountry) {
        // A new tap cancels any previous failover watch.
        session++
        val mySession = session
        watchJob?.cancel()
        selectCountry(country)
        connStatusView.text = "● Connecting…"
        setStatus("Connecting ${country.code}…")
        runOnDefaultDispatcher {
            try {
                // Apply a pending auto-pull list first, but only if idle.
                // If a tunnel is live, stop it first, then apply, then connect.
                if (DataStore.serviceState.connected) {
                    SagerNet.stopService()
                    delay(900)
                }
                if (TugyiEngine.isPending(country)) {
                    TugyiEngine.applyUpdateIfIdle(country)
                }
                TugyiEngine.ensureGroup(country)
                var ids = TugyiEngine.profileIds(country)
                if (ids.isEmpty()) {
                    // First run, or the engine list changed: load the group now.
                    TugyiEngine.applyUpdateIfIdle(country)
                    ids = TugyiEngine.profileIds(country)
                }
                if (ids.isEmpty()) {
                    onMainDispatcher {
                        connStatusView.text = "● Disconnected"
                        setStatus("${country.code}: no live node")
                    }
                    return@runOnDefaultDispatcher
                }
                // Lowest-ping first: use the background ping ranking if
                // it exists, otherwise the engine's order. n/a nodes
                // are already dropped from the ping ranking.
                val ranked = TugyiPing.rankedIds(country)
                val order = if (ranked.isNotEmpty()) ranked else ids
                // If there is no ranking yet, start a background test
                // now so the next tap has one.
                if (ranked.isEmpty()) TugyiPing.testCountryAsync(country)
                // Connect to the lowest-ping node, with in-country
                // failover handled by watch.
                connectToOrder(country, order, 0, mySession)
            } catch (e: Exception) {
                onMainDispatcher {
                    connStatusView.text = "● Disconnected"
                    setStatus("${country.code}: ${e.message}")
                }
            }
        }
    }

    private suspend fun connectToOrder(
        country: TugyiCountry, order: List<Long>, index: Int, mySession: Long
    ) {
        val id = order.getOrNull(index) ?: run {
            onMainDispatcher { setStatus("${country.code}: no live node") }
            return
        }
        val groupId = TugyiEngine.groupId(country) ?: run {
            onMainDispatcher { setStatus("${country.code}: no live node") }
            return
        }
        TugyiEngine.selectProfileById(groupId, id)
        onMainDispatcher {
            if (mySession == session) {
                setStatus("Connecting ${country.code} (node ${index + 1})…")
                connect.launch(null)
            }
        }
        // Watch for a successful connection, then fail over in-country
        // to the next lowest-ping node (up to 5 tries, same country).
        watchJob = runOnDefaultDispatcher {
            var waited = 0
            while (waited < 30_000 && mySession == session) {
                delay(500)
                waited += 500
                val state = DataStore.serviceState
                if (state == BaseService.State.Connected) {
                    onMainDispatcher {
                        selectCountry(country)
                        refreshConnectionStatus()
                        // Show the REAL exit IP/location (may differ
                        // from the tapped country, e.g. China).
                        refreshIpInfo()
                    }
                    return@runOnDefaultDispatcher
                }
                if (state == BaseService.State.Stopped) {
                    // This node broke. Try the next one in the SAME country.
                    if (index + 1 < order.size && index + 1 < 5) {
                        onMainDispatcher { setStatus("${country.code}: trying next node…") }
                        connectToOrder(country, order, index + 1, mySession)
                    } else {
                        onMainDispatcher {
                            connStatusView.text = "● Disconnected"
                            setStatus("${country.code}: no live node")
                        }
                    }
                    return@runOnDefaultDispatcher
                }
            }
        }
    }
}
