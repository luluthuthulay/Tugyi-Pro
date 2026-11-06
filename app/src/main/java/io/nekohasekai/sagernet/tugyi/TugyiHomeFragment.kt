package io.nekohasekai.sagernet.tugyi

import android.graphics.Color
import android.os.Bundle
import android.text.SpannableString
import android.text.Spanned
import android.text.style.ForegroundColorSpan
import android.util.TypedValue
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import com.google.android.material.bottomsheet.BottomSheetBehavior
import com.google.android.material.bottomsheet.BottomSheetDialog
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.database.ProfileManager
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

    companion object {
        /** Home layout reference height: Hsu's phone is 857dp
         * tall (2400px / 2.8). The uniform-shrink scale is
         * screenHeightDp / this. */
        private const val HOME_REF_HEIGHT_DP = 857f
        /** Clamp the shrink/grow so an extreme screen (a very
         * short phone, a foldable) never makes the UI unusable. */
        private const val HOME_SCALE_MIN = 0.70f
        private const val HOME_SCALE_MAX = 1.10f
        /** Idle watch (Hsu, 2026-11-06): while Connected, check
         * every 5s; if no traffic at all flows for 30s, verify
         * with trafficOk() and fail over if the node is dead. */
        private const val IDLE_CHECK_MS = 5_000L
        private const val IDLE_TRIGGER_MS = 30_000L
    }

    private lateinit var connStatusView: TextView
    private lateinit var pingView: TextView
    private lateinit var downloadView: TextView
    private lateinit var uploadView: TextView
    private lateinit var downloadUnitView: TextView
    private lateinit var uploadUnitView: TextView
    private lateinit var countryPill: TextView
    private lateinit var powerBtn: ImageButton
    private lateinit var routeMap: TugyiRouteMapView
    private var watchJob: Job? = null
    private var session = 0L
    private var selectedCountry = TugyiCountry.SG
    private var idleWatchJob: Job? = null
    private var lastTrafficAt = 0L

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) setStatus("VPN permission denied")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        // Exactly like the design Hsu sent (2026-11-05): the blue
        // header is at the very top, so the pink NekoBox appbar is
        // hidden on this home. The header has its own hamburger menu.
        view.findViewById<View>(R.id.appbar)?.visibility = View.GONE
        // Screen fit (Hsu, 2026-11-06): the app is edge-to-edge
        // (targetSdk 35), so with the v1.0.16 bottom-anchored
        // layout the Developed line ended up UNDER the phone's
        // bottom keys. Pad the bottom of the home by the
        // navigation bar inset, so the pill + Developed stay
        // above the keys. Only the bottom is inset - the blue
        // header keeps drawing behind the status bar, like before.
        // No fitsSystemWindows is set anywhere in the layouts, so
        // the insets reach this view.
        ViewCompat.setOnApplyWindowInsetsListener(view) { v, insets ->
            val navBar =
                insets.getInsets(WindowInsetsCompat.Type.navigationBars()).bottom
            v.setPadding(v.paddingLeft, v.paddingTop, v.paddingRight, navBar)
            insets
        }
        view.findViewById<ImageButton>(R.id.tugyi_menu_btn).setOnClickListener {
            (activity as? MainActivity)?.binding?.drawerLayout
                ?.openDrawer(GravityCompat.START)
        }
        connStatusView = view.findViewById(R.id.tugyi_conn_status)
        pingView = view.findViewById(R.id.tugyi_ping)
        downloadView = view.findViewById(R.id.tugyi_download)
        uploadView = view.findViewById(R.id.tugyi_upload)
        downloadUnitView = view.findViewById(R.id.tugyi_download_unit)
        uploadUnitView = view.findViewById(R.id.tugyi_upload_unit)
        countryPill = view.findViewById(R.id.tugyi_country_pill)
        powerBtn = view.findViewById(R.id.tugyi_power_btn)
        routeMap = view.findViewById(R.id.tugyi_map)
        // Uniform shrink (Hsu, 2026-11-06): the WHOLE Home UI
        // shrinks/grows uniformly on smaller/bigger phones. Applied
        // once per fresh inflation, before the first layout.
        applyHomeUiScale(view)
        // Tapping the country pill opens the region list as a
        // bottom sheet that slides up from the bottom (Hsu, 2026-11-05).
        // Singapore is default.
        countryPill.setOnClickListener { showRegionSheet() }
        // Tapping the power button OR the Connected/Disconnected
        // status text toggles the connection (Hsu, 2026-11-05:
        // tapping Connected did nothing because only the power
        // button had a click listener).
        powerBtn.setOnClickListener { toggleConnection() }
        connStatusView.setOnClickListener { toggleConnection() }
        selectCountry(selectedCountry)
        refreshConnectionStatus()
    }

    // Uniform shrink (Hsu, 2026-11-06): the WHOLE Home UI must
    // shrink/grow uniformly on smaller/bigger phones - not just
    // the map. The layout is authored for Hsu's phone (857dp
    // tall). Scale every fixed size (heights, margins, paddings,
    // text sizes) by screenHeightDp / 857, clamped to [0.70,
    // 1.10]. The map view (0dp + weight 1) takes the proportional
    // leftover space, so it keeps its ~29% share on every phone.
    // match_parent (-1), wrap_content (-2) and 0dp (weight) are
    // <= 0 and left alone. This is the same idea as Intuit's
    // sdp/ssp library, but keyed to screen HEIGHT: sdp keys to
    // width, which does not fix our problem (a small phone is 25%
    // shorter but only 7% narrower than Hsu's). Applied once per
    // fresh inflation in onViewCreated, so it never compounds.
    private fun applyHomeUiScale(root: View) {
        val dm = resources.displayMetrics
        val screenHdp = dm.heightPixels / dm.density
        val scale = (screenHdp / HOME_REF_HEIGHT_DP)
            .coerceIn(HOME_SCALE_MIN, HOME_SCALE_MAX)
        routeMap.uiScale = scale
        if (kotlin.math.abs(scale - 1f) < 0.005f) return
        scaleViewTree(root, scale)
    }

    private fun scaleViewTree(v: View, scale: Float) {
        // Text size (px, already includes the user's font scale).
        if (v is TextView) {
            v.setTextSize(TypedValue.COMPLEX_UNIT_PX, v.textSize * scale)
        }
        // Fixed pixel sizes and margins. match_parent, wrap_content
        // and 0dp (weight) are <= 0 and untouched.
        val lp = v.layoutParams
        if (lp != null) {
            if (lp.width > 0) lp.width = (lp.width * scale).toInt()
            if (lp.height > 0) lp.height = (lp.height * scale).toInt()
            if (lp is ViewGroup.MarginLayoutParams) {
                lp.leftMargin = (lp.leftMargin * scale).toInt()
                lp.topMargin = (lp.topMargin * scale).toInt()
                lp.rightMargin = (lp.rightMargin * scale).toInt()
                lp.bottomMargin = (lp.bottomMargin * scale).toInt()
            }
            v.layoutParams = lp
        }
        v.setPadding(
            (v.paddingLeft * scale).toInt(),
            (v.paddingTop * scale).toInt(),
            (v.paddingRight * scale).toInt(),
            (v.paddingBottom * scale).toInt()
        )
        if (v is ViewGroup) {
            for (i in 0 until v.childCount) scaleViewTree(v.getChildAt(i), scale)
        }
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
        // Ping-gate on open (Hsu, 2026-11-06): check ms FIRST and
        // only allow VPN on once the check is stable, so the app
        // never connects to a dead node. While the check runs the
        // user is told ("Checking nodes…"). Only when disconnected
        // - while connected the tunnel is already live.
        if (!DataStore.serviceState.connected) ensurePingReady(selectedCountry)
        // Live speed from MainActivity (1s updates).
        // Fix for the v1.0.2/v1.0.4 build failure: onMainDispatcher is
        // a suspend function and cannot be called from this plain
        // lambda. Post to the main thread instead.
        (activity as? MainActivity)?.onTugyiSpeedUpdate = { txRate, rxRate ->
            activity?.runOnUiThread { showSpeed(txRate, rxRate) }
        }
        refreshConnectionStatus()
        // Place the map dots at the REAL location (phone's real
        // location when disconnected, exit location when connected).
        // The IP itself is NOT shown on the home anymore
        // (Hsu, 2026-11-06: remove the IP line).
        refreshMapLocation()
        // Watch the live tunnel: if traffic stops and the node is
        // dead, fail over to the next node (Hsu, 2026-11-06).
        startIdleWatch()
    }

    override fun onPause() {
        idleWatchJob?.cancel()
        (activity as? MainActivity)?.onTugyiSpeedUpdate = null
        TugyiAutoPull.stopWhileOpen()
        super.onPause()
    }

    override fun onDestroyView() {
        idleWatchJob?.cancel()
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
        // Light sheet: BottomSheetDialog's default theme is DARK, so
        // on Hsu's phone the sheet came out dark with dark text
        // (2026-11-05 screenshot). Force the light Material theme.
        val dialog = BottomSheetDialog(
            requireContext(),
            com.google.android.material.R.style.Theme_Design_Light_BottomSheetDialog
        )
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

    // Status dot colour, like the design: green = connected,
    // red = disconnected, amber = connecting. The dot is coloured,
    // the text stays white. (On Hsu's phone 2026-11-05 the dot was
    // white, not red.)
    private fun setConnStatus(text: String, dotColor: Int) {
        if (!::connStatusView.isInitialized) return
        val s = SpannableString("● $text")
        s.setSpan(
            ForegroundColorSpan(dotColor), 0, 1,
            Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
        )
        connStatusView.text = s
    }

    // Power button and Connected/Disconnected status text do the
    // same thing: connect when disconnected, disconnect when
    // connected (tap a country to switch country while connected).
    private fun toggleConnection() {
        if (DataStore.serviceState.canStop) {
            // Connecting OR Connected: this tap means DISCONNECT.
            // (Bug fix, Hsu 2026-11-06: the old code checked
            // state.connected, and Connecting.connected is false,
            // so a tap during "Connecting…" started a NEW connect.)
            session++
            idleWatchJob?.cancel()
            watchJob?.cancel()
            SagerNet.stopService()
            // Set the Disconnected UI DIRECTLY. stopService() only
            // sends an async CLOSE broadcast, so the state still
            // reads Connected right after it - refreshConnectionStatus()
            // would re-set the green "Connected" label (the old bug).
            setDisconnectedUi()
            // Back to the phone's real location (map origin dot).
            refreshMapLocation()
            setStatus("Disconnected")
        } else {
            onCountryTapped(selectedCountry)
        }
    }

    /** The Disconnected look, set directly (the same as
     * refreshConnectionStatus()'s disconnected branch). Used when
     * a tap disconnects: the service state has not updated yet, so
     * a refresh here would wrongly re-show "Connected". */
    private fun setDisconnectedUi() {
        setConnStatus("Disconnected", Color.parseColor("#F44336"))
        if (::powerBtn.isInitialized) powerBtn.setImageResource(R.drawable.tugyi_power_off)
        if (::countryPill.isInitialized) countryPill.setTextColor(Color.parseColor("#9E9E9E"))
        if (::routeMap.isInitialized) routeMap.connected = false
        refreshPing()
        showSpeed(0, 0)
    }

    // Idle watch (Hsu, 2026-11-06: "watch; when it goes idle and
    // disconnects, reconnect"): while a tunnel is Connected, watch
    // the live speed (1s updates from MainActivity). If no traffic
    // at all flows for IDLE_TRIGGER_MS, verify with trafficOk()
    // (ipinfo, then generate_204). If traffic still flows it was
    // only idle - reset the timer and stay on this node. If the
    // node is really dead, fail over to the next node in the SAME
    // country (up to 5), exactly like the connect-time no-traffic
    // failover; if none is left, disconnect with "no live node".
    // WG Tunnel has the same idea as "Seamless Recovery", but it
    // watches the WireGuard handshake age (stale after 210s) -
    // Tugyi has no WireGuard handshake (sing-box VLESS/Hy2/SS),
    // so this watches real traffic instead. The check itself is
    // harmless: a false alarm only costs one tiny fetch, never a
    // failover, because failover needs trafficOk() to actually fail.
    // Runs only while this home screen is visible ("while using");
    // a manual tap cancels it via the session.
    private fun startIdleWatch() {
        idleWatchJob?.cancel()
        idleWatchJob = runOnDefaultDispatcher {
            val mySession = session
            lastTrafficAt = System.currentTimeMillis()
            while (mySession == session) {
                delay(IDLE_CHECK_MS)
                if (mySession != session) return@runOnDefaultDispatcher
                if (!DataStore.serviceState.connected) continue
                if (System.currentTimeMillis() - lastTrafficAt < IDLE_TRIGGER_MS) continue
                if (TugyiIpInfo.trafficOk()) {
                    lastTrafficAt = System.currentTimeMillis()
                    continue
                }
                // Dead node - but re-check the session first: a
                // manual tap during the check must win over this
                // failover.
                if (mySession != session) return@runOnDefaultDispatcher
                val country = TugyiPing.connectedCountry() ?: continue
                val ranked = TugyiPing.rankedIds(country)
                val ids = TugyiEngine.profileIds(country)
                // Same order as a tap: ranked first, fresh unranked
                // nodes after them (see onCountryTapped).
                val order = if (ranked.isNotEmpty()) ranked + (ids - ranked) else ids
                val current = order.indexOf(DataStore.selectedProxy)
                val next = if (current >= 0) current + 1 else 0
                if (next < order.size && next < 5) {
                    val nextSession = ++session
                    onMainDispatcher {
                        setStatus("${country.code}: no traffic, trying next node…")
                    }
                    SagerNet.stopService()
                    delay(900)
                    connectToOrder(country, order, next, nextSession)
                } else {
                    ++session
                    SagerNet.stopService()
                    onMainDispatcher {
                        setConnStatus("Disconnected", Color.parseColor("#F44336"))
                        setStatus("${country.code}: no live node")
                    }
                }
                return@runOnDefaultDispatcher
            }
        }
    }

    private fun refreshConnectionStatus() {
        if (!::connStatusView.isInitialized) return
        when (DataStore.serviceState) {
            BaseService.State.Connected -> {
                TugyiPing.connectedCountry()?.let { selectCountry(it) }
                setConnStatus("Connected", Color.parseColor("#4CAF50"))
                // Connected vs Disconnected must look clearly
                // different (Hsu, 2026-11-05, design pictures):
                // blue power symbol + dark country name when
                // connected, grey power symbol + grey country name
                // when disconnected. The map destination dot also
                // only shows when connected (see TugyiRouteMapView).
                if (::powerBtn.isInitialized) powerBtn.setImageResource(R.drawable.tugyi_power)
                if (::countryPill.isInitialized) countryPill.setTextColor(Color.parseColor("#212121"))
                // Show the route Myanmar -> connected country on the map.
                if (::routeMap.isInitialized) routeMap.connected = true
                refreshPing()
                // NO "Connected: <CODE>" toast here (Hsu, 2026-11-06):
                // this runs on every onResume, so the pill popped up
                // every time he came back to the app. The home screen
                // itself already shows Connected (green dot + text).
            }
            BaseService.State.Connecting -> {
                setConnStatus("Connecting…", Color.parseColor("#FFC107"))
            }
            else -> {
                setConnStatus("Disconnected", Color.parseColor("#F44336"))
                if (::powerBtn.isInitialized) powerBtn.setImageResource(R.drawable.tugyi_power_off)
                if (::countryPill.isInitialized) countryPill.setTextColor(Color.parseColor("#9E9E9E"))
                if (::routeMap.isInitialized) routeMap.connected = false
                refreshPing()
                showSpeed(0, 0)
            }
        }
    }

    /**
     * Last measured ms for the connected node, on the home screen
     * under the country pill (Hsu, 2026-11-05). The value is the
     * background ping test's result (TugyiPing writes profile.ping);
     * the connected country's own list is not re-tested while its
     * tunnel is live, so this is the node's last known ms. DB read
     * runs off the main thread, like refreshMapLocation().
     */
    private fun refreshPing() {
        if (!::pingView.isInitialized) return
        if (!DataStore.serviceState.connected) {
            pingView.text = "--"
            return
        }
        runOnDefaultDispatcher {
            val ms = runCatching {
                ProfileManager.getProfile(DataStore.selectedProxy)?.ping ?: 0
            }.getOrDefault(0)
            onMainDispatcher {
                if (!::pingView.isInitialized) return@onMainDispatcher
                pingView.text = if (ms > 0) "$ms ms" else "--"
            }
        }
    }

    /**
     * Fetch the real IP ONLY to place the map dots at the real
     * location. Disconnected: phone's real location (Myanmar) ->
     * origin dot. Connected: exit node's real location -> the map
     * destination dot moves to the real exit location, so a node
     * that actually exits via China shows China. The IP itself is
     * NOT displayed anywhere on the home (Hsu, 2026-11-06: the IP
     * line was removed). On failure the previous dots are kept.
     * TugyiIpInfo.trafficOk() (the failover check) is separate
     * and untouched.
     */
    private fun refreshMapLocation() {
        runOnDefaultDispatcher {
            val info = TugyiIpInfo.fetch() ?: return@runOnDefaultDispatcher
            onMainDispatcher {
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
            downloadUnitView.text = "mbps"
            uploadUnitView.text = "mbps"
            return
        }
        // Any traffic at all resets the idle-watch timer.
        if (txRate + rxRate > 0) lastTrafficAt = System.currentTimeMillis()
        setSpeed(downloadView, downloadUnitView, rxRate)
        setSpeed(uploadView, uploadUnitView, txRate)
    }

    private fun setSpeed(valueView: TextView, unitView: TextView, bytesPerSec: Long) {
        // Live speed, 1s interval. Mbps when >= 1 Mbps, else KB/s, so
        // a small upload (tens of KB/s while browsing) shows a real
        // number instead of rounding to 0.0 Mbps and looking dead
        // (Hsu, 2026-11-05: "Download ပဲပြတယ် upload မပြ").
        val mbps = bytesPerSec * 8.0 / 1_000_000.0
        if (mbps >= 1.0) {
            valueView.text = String.format(Locale.US, "%.1f", mbps)
            unitView.text = "mbps"
        } else {
            valueView.text = String.format(Locale.US, "%.0f", bytesPerSec / 1000.0)
            unitView.text = "KB/s"
        }
    }

    // Ping-gate (Hsu, 2026-11-06: "App opens -> check ms first;
    // only allow VPN on once it is stable; tell the user while the
    // ping is running"): a connect is only allowed once this
    // country's ping ranking is settled. Ready means: the user is
    // already on this country (the live tunnel proves a node
    // works), or there is nothing to test yet (the tap will say
    // "no live node" itself), or a test finished recently.
    private fun isPingReady(country: TugyiCountry): Boolean {
        if (TugyiPing.connectedCountry() == country) return true
        if (TugyiEngine.profileIds(country).isEmpty()) return true
        return TugyiPing.isRankingFresh(country)
    }

    /** Returns true when this country's ping ranking is settled.
     * When not ready it starts (or waits for) the test, tells the
     * user "Checking nodes…", and refuses the tap - the next tap
     * after the test settles connects. So the app never connects
     * to a dead node. */
    private fun ensurePingReady(country: TugyiCountry): Boolean {
        if (isPingReady(country)) return true
        setStatus("Checking nodes…")
        TugyiPing.testCountryAsync(country, force = true)
        return false
    }

    private fun onCountryTapped(country: TugyiCountry) {
        // Ping-gate: do not connect while the open-time ping check
        // for this country is still running.
        if (!ensurePingReady(country)) return
        // A new tap cancels any previous failover watch.
        session++
        val mySession = session
        idleWatchJob?.cancel()
        watchJob?.cancel()
        selectCountry(country)
        setConnStatus("Connecting…", Color.parseColor("#FFC107"))
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
                        setConnStatus("Disconnected", Color.parseColor("#F44336"))
                        setStatus("${country.code}: no live node")
                    }
                    return@runOnDefaultDispatcher
                }
                // Lowest-ping first: use the background ping ranking if
                // it exists, otherwise the engine's order. n/a nodes
                // are already dropped from the ping ranking.
                val ranked = TugyiPing.rankedIds(country)
                if (ranked.isEmpty()) {
                    if (TugyiPing.isRankingFresh(country)) {
                        // The ping-gate already ensured a test ran for
                        // this country: an empty ranking means the test
                        // found no live node. Do NOT connect to a dead
                        // node - report it and re-test in the
                        // background so a later tap can connect if a
                        // node comes back.
                        onMainDispatcher {
                            setStatus("${country.code}: no live node")
                        }
                        TugyiPing.testCountryAsync(country, force = true)
                        return@runOnDefaultDispatcher
                    }
                    // No ranking yet (first run): start a background
                    // test for the next tap, and connect now in the
                    // engine's order - the connect watch still
                    // verifies real traffic and fails over.
                    TugyiPing.testCountryAsync(country)
                }
                // Fresh nodes the ping has not ranked yet go after
                // the ranked ones, so a list rotation never hides
                // them: if the ranked nodes fail, the fresh ones are
                // still tried instead of reporting "no live node"
                // (Hsu, 2026-11-06).
                val order = if (ranked.isNotEmpty()) ranked + (ids - ranked) else ids
                // Connect to the lowest-ping node, with in-country
                // failover handled by watch.
                connectToOrder(country, order, 0, mySession)
            } catch (e: Exception) {
                onMainDispatcher {
                    setConnStatus("Disconnected", Color.parseColor("#F44336"))
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
                    // "Connected" only means the tunnel started - it
                    // does NOT prove traffic flows. A node can show
                    // Connected yet open nothing (Hsu, 2026-11-05).
                    // Verify real traffic through the tunnel first;
                    // if none flows, fail over to the next node in
                    // the SAME country instead of staying stuck.
                    if (!TugyiIpInfo.trafficOk()) {
                        if (index + 1 < order.size && index + 1 < 5) {
                            onMainDispatcher {
                                setStatus("${country.code}: connected but no traffic, trying next node…")
                            }
                            SagerNet.stopService()
                            delay(900)
                            connectToOrder(country, order, index + 1, mySession)
                        } else {
                            SagerNet.stopService()
                            onMainDispatcher {
                                setConnStatus("Disconnected", Color.parseColor("#F44336"))
                                setStatus("${country.code}: no live node")
                            }
                        }
                        return@runOnDefaultDispatcher
                    }
                    onMainDispatcher {
                        selectCountry(country)
                        refreshConnectionStatus()
                        // Move the map destination dot to the REAL
                        // exit location (may differ from the tapped
                        // country, e.g. China).
                        refreshMapLocation()
                        // Re-arm the idle watch for this new tunnel.
                        startIdleWatch()
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
                            setConnStatus("Disconnected", Color.parseColor("#F44336"))
                            setStatus("${country.code}: no live node")
                        }
                    }
                    return@runOnDefaultDispatcher
                }
            }
        }
    }
}
