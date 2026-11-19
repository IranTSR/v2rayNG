package kittoku.osc

import android.net.VpnService
import kittoku.osc.control.Controller
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel

/**
 * SSTP/PPP engine adapted from Open-SSTP-Client.
 * The host VpnService remains the sole Android VPN owner.
 */
class SstpEngine(
    vpnService: VpnService,
    hostname: String,
    port: Int,
    username: String,
    password: String,
    private val listener: Listener
) {
    interface Listener {
        fun onConnected()
        fun onDisconnected()
        fun onFailure(message: String)
    }

    internal val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val bridge = SharedBridge(this, vpnService, hostname, port, username, password)
    private val controller = Controller(bridge)

    fun connect() {
        controller.launchJobMain()
    }

    fun disconnect() {
        controller.disconnect()
    }

    internal fun notifyConnected() {
        listener.onConnected()
    }

    internal fun notifyDisconnected() {
        listener.onDisconnected()
        scope.cancel()
    }

    internal fun notifyFailure(message: String) {
        listener.onFailure(message)
        scope.cancel()
    }
}
