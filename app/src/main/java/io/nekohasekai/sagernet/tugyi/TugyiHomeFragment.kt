package io.nekohasekai.sagernet.tugyi

import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import io.nekohasekai.sagernet.R
import io.nekohasekai.sagernet.SagerNet
import io.nekohasekai.sagernet.bg.BaseService
import io.nekohasekai.sagernet.database.DataStore
import io.nekohasekai.sagernet.ktx.onMainDispatcher
import io.nekohasekai.sagernet.ktx.runOnDefaultDispatcher
import io.nekohasekai.sagernet.ui.ToolbarFragment
import io.nekohasekai.sagernet.ui.VpnRequestActivity
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay

/**
 * Tugyi Pro home.
 *
 * Home shows ONLY SG, JP, US. No node list, no IPs, no node names here.
 * Tap a country = connect immediately to that country's best live node
 * (profile #1 in its group). Tapping another country while connected
 * stops the old tunnel and starts the new country's best node.
 * If a whole country has no live node, show that and do NOT auto-switch
 * to another country.
 *
 * In-country failover: if the best node fails to reach Connected within
 * the watch window, the fragment tries #2, then #3, same country only.
 * A manual tap cancels any in-flight failover watch.
 */
class TugyiHomeFragment : ToolbarFragment(R.layout.tugyi_home) {

    private lateinit var statusView: TextView
    private var watchJob: Job? = null
    private var session = 0L

    private val connect = registerForActivityResult(VpnRequestActivity.StartService()) {
        if (it) setStatus("VPN permission denied")
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        statusView = view.findViewById(R.id.tugyi_status)
        view.findViewById<Button>(R.id.btn_sg).setOnClickListener { onCountryTapped(TugyiCountry.SG) }
        view.findViewById<Button>(R.id.btn_jp).setOnClickListener { onCountryTapped(TugyiCountry.JP) }
        view.findViewById<Button>(R.id.btn_us).setOnClickListener { onCountryTapped(TugyiCountry.US) }
        setStatus("Tap SG, JP or US to connect")
    }

    override fun onResume() {
        super.onResume()
        // Auto-pull on open, and keep polling while the app is open.
        // This never touches a live tunnel (see TugyiAutoPull).
        TugyiAutoPull.checkNow()
        TugyiAutoPull.startWhileOpen()
    }

    override fun onPause() {
        super.onPause()
        TugyiAutoPull.stopWhileOpen()
    }

    override fun onDestroyView() {
        watchJob?.cancel()
        super.onDestroyView()
    }

    private fun setStatus(text: String) {
        if (::statusView.isInitialized) statusView.text = text
    }

    private fun onCountryTapped(country: TugyiCountry) {
        // A new tap cancels any previous failover watch.
        session++
        val mySession = session
        watchJob?.cancel()
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
                    onMainDispatcher { setStatus("${country.code}: no live node") }
                    return@runOnDefaultDispatcher
                }
                // Connect to #1, with in-country failover handled by watch.
                connectToIndex(country, 0, mySession)
            } catch (e: Exception) {
                onMainDispatcher { setStatus("${country.code}: ${e.message}") }
            }
        }
    }

    private suspend fun connectToIndex(country: TugyiCountry, index: Int, mySession: Long) {
        val id = TugyiEngine.selectProfile(country, index) ?: run {
            onMainDispatcher { setStatus("${country.code}: no live node") }
            return
        }
        onMainDispatcher {
            if (mySession == session) {
                setStatus("Connecting ${country.code} (node ${index + 1})…")
                connect.launch(null)
            }
        }
        // Watch for a successful connection, then fail over in-country.
        watchJob = runOnDefaultDispatcher {
            var waited = 0
            while (waited < 30_000 && mySession == session) {
                delay(500)
                waited += 500
                val state = DataStore.serviceState
                if (state == BaseService.State.Connected) {
                    onMainDispatcher { setStatus("Connected: ${country.code}") }
                    return@runOnDefaultDispatcher
                }
                if (state == BaseService.State.Stopped) {
                    // #index failed. Try the next node in the SAME country.
                    if (index + 1 < 3) {
                        onMainDispatcher { setStatus("${country.code}: trying next node…") }
                        connectToIndex(country, index + 1, mySession)
                    } else {
                        onMainDispatcher { setStatus("${country.code}: no live node") }
                    }
                    return@runOnDefaultDispatcher
                }
            }
        }
    }
}
