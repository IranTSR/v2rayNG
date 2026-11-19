package kittoku.osc.control

import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kittoku.osc.client.SSTP_REQUEST_TIMEOUT
import kittoku.osc.client.SstpClient
import kittoku.osc.client.ppp.IpcpClient
import kittoku.osc.client.ppp.LCPClient
import kittoku.osc.client.ppp.PPPClient
import kittoku.osc.client.ppp.PPP_NEGOTIATION_TIMEOUT
import kittoku.osc.client.ppp.auth.ChapMSCHAPV2Client
import kittoku.osc.client.ppp.auth.PAPClient
import kittoku.osc.io.OutgoingManager
import kittoku.osc.io.incoming.IncomingManager
import kittoku.osc.preference.AUTH_PROTOCOL_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOl_PAP
import kittoku.osc.terminal.SSL_REQUEST_INTERVAL
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_ABORT
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_CONNECTED
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT
import kittoku.osc.unit.sstp.SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean

internal class Controller(private val bridge: SharedBridge) {
    private var sstpClient: SstpClient? = null
    private var pppClient: PPPClient? = null
    private var incomingManager: IncomingManager? = null
    private var outgoingManager: OutgoingManager? = null
    private var lcpClient: LCPClient? = null
    private var papClient: PAPClient? = null
    private var chapClient: ChapMSCHAPV2Client? = null
    private var ipcpClient: IpcpClient? = null
    private var jobMain: Job? = null
    private val terminating = AtomicBoolean(false)

    internal fun launchJobMain() {
        bridge.handler = kotlinx.coroutines.CoroutineExceptionHandler { _, throwable ->
            cleanup()
            bridge.service.notifyFailure(throwable.message ?: throwable.javaClass.simpleName)
        }

        jobMain = bridge.service.scope.launch(bridge.handler) {
            try {
                bridge.attachSSLTerminal()
                bridge.attachIPTerminal()
                bridge.sslTerminal!!.initialize()
                if (!expectProceeded(Where.SSL, SSL_REQUEST_INTERVAL)) return@launch

                IncomingManager(bridge).also {
                    it.launchJobMain()
                    incomingManager = it
                }

                SstpClient(bridge).also {
                    sstpClient = it
                    incomingManager!!.registerMailbox(it)
                    it.launchJobRequest()
                    if (!expectProceeded(Where.SSTP_REQUEST, SSTP_REQUEST_TIMEOUT)) return@launch
                    it.launchJobControl()
                }

                PPPClient(bridge).also {
                    pppClient = it
                    incomingManager!!.registerMailbox(it)
                    it.launchJobControl()
                }

                LCPClient(bridge).also {
                    lcpClient = it
                    incomingManager!!.registerMailbox(it)
                    it.launchJobNegotiation()
                    if (!expectProceeded(Where.LCP, PPP_NEGOTIATION_TIMEOUT)) return@launch
                    incomingManager!!.unregisterMailbox(it)
                }

                when (bridge.currentAuth) {
                    AUTH_PROTOCOl_PAP -> PAPClient(bridge).also {
                        papClient = it
                        incomingManager!!.registerMailbox(it)
                        it.launchJobAuth()
                        if (!expectProceeded(Where.PAP, PPP_AUTH_TIMEOUT)) return@launch
                        incomingManager!!.unregisterMailbox(it)
                    }
                    AUTH_PROTOCOL_MSCHAPv2 -> ChapMSCHAPV2Client(bridge).also {
                        chapClient = it
                        incomingManager!!.registerMailbox(it)
                        it.launchJobAuth()
                        if (!expectProceeded(Where.CHAP, PPP_AUTH_TIMEOUT)) return@launch
                    }
                    else -> {
                        fail("Server requested an unsupported PPP authentication method", SSTP_MESSAGE_TYPE_CALL_ABORT)
                        return@launch
                    }
                }

                sstpClient!!.sendCallConnected()

                if (bridge.PPP_IPv4_ENABLED) {
                    IpcpClient(bridge).also {
                        ipcpClient = it
                        incomingManager!!.registerMailbox(it)
                        it.launchJobNegotiation()
                        if (!expectProceeded(Where.IPCP, PPP_NEGOTIATION_TIMEOUT)) return@launch
                        incomingManager!!.unregisterMailbox(it)
                    }
                }

                bridge.ipTerminal!!.initialize()
                if (!expectProceeded(Where.IP, null)) return@launch

                OutgoingManager(bridge).also {
                    it.launchJobMain()
                    outgoingManager = it
                }

                bridge.service.notifyConnected()
                expectProceeded(Where.SSTP_CONTROL, null)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (throwable: Throwable) {
                fail(throwable.message ?: throwable.javaClass.simpleName, SSTP_MESSAGE_TYPE_CALL_ABORT)
            }
        }
    }

    internal fun disconnect() {
        if (!terminating.compareAndSet(false, true)) return
        bridge.service.scope.launch {
            try {
                sstpClient?.sendLastPacket(SSTP_MESSAGE_TYPE_CALL_DISCONNECT)
            } catch (_: Throwable) {
            }
            jobMain?.cancelAndJoin()
            cleanup()
            bridge.service.notifyDisconnected()
        }
    }

    private suspend fun expectProceeded(where: Where, timeout: Long?): Boolean {
        val received = if (timeout != null) {
            withTimeoutOrNull(timeout) {
                bridge.controlMailbox.receive()
            } ?: ControlMessage(where, Result.ERR_TIMEOUT)
        } else {
            bridge.controlMailbox.receive()
        }

        if (received.from == where && received.result == Result.PROCEEDED) return true

        val packetType = when (received.result) {
            Result.ERR_DISCONNECT_REQUESTED -> SSTP_MESSAGE_TYPE_CALL_DISCONNECT_ACK
            else -> SSTP_MESSAGE_TYPE_CALL_ABORT
        }
        val detail = buildString {
            append(received.from.name)
            append(": ")
            append(received.result.name)
            received.supplement?.let {
                append(" — ")
                append(it)
            }
        }
        fail(detail, packetType, received.result == Result.ERR_DISCONNECT_REQUESTED)
        return false
    }

    private suspend fun fail(message: String, packetType: Short, disconnected: Boolean = false) {
        if (!terminating.compareAndSet(false, true)) return
        try {
            sstpClient?.sendLastPacket(packetType)
        } catch (_: Throwable) {
        }
        cleanup()
        if (disconnected) bridge.service.notifyDisconnected()
        else bridge.service.notifyFailure(message)
    }

    private fun cleanup() {
        lcpClient?.cancel()
        papClient?.cancel()
        chapClient?.cancel()
        ipcpClient?.cancel()
        sstpClient?.cancel()
        pppClient?.cancel()
        incomingManager?.cancel()
        outgoingManager?.cancel()
        bridge.sslTerminal?.close()
        bridge.ipTerminal?.close()
        bridge.controlMailbox.close()
    }

    private companion object {
        const val PPP_AUTH_TIMEOUT = 30_000L
    }
}
