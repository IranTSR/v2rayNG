package kittoku.osc

import android.net.VpnService
import kittoku.osc.preference.AUTH_PROTOCOL_MSCHAPv2
import kittoku.osc.preference.AUTH_PROTOCOl_PAP
import kittoku.osc.terminal.IPTerminal
import kittoku.osc.terminal.SSLTerminal
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

internal enum class Where {
    CERT, CERT_PATH, SSL, PROXY, SSTP_DATA, SSTP_CONTROL, SSTP_REQUEST, SSTP_HASH,
    PPP, PAP, CHAP, MSCHAPV2, EAP, LCP, LCP_MRU, LCP_AUTH, IPCP, IPCP_IP,
    IPV6CP, IPV6CP_IDENTIFIER, IP, IPv4, IPv6, ROUTE, INCOMING, OUTGOING
}

internal data class ControlMessage(
    val from: Where,
    val result: Result,
    val supplement: String? = null
)

internal enum class Result {
    PROCEEDED,
    ERR_TIMEOUT, ERR_COUNT_EXHAUSTED, ERR_UNKNOWN_TYPE, ERR_UNEXPECTED_MESSAGE,
    ERR_PARSING_FAILED, ERR_VERIFICATION_FAILED,
    ERR_NEGATIVE_ACKNOWLEDGED, ERR_ABORT_REQUESTED, ERR_DISCONNECT_REQUESTED,
    ERR_TERMINATE_REQUESTED, ERR_PROTOCOL_REJECTED, ERR_CODE_REJECTED,
    ERR_AUTHENTICATION_FAILED, ERR_ADDRESS_REJECTED, ERR_OPTION_REJECTED,
    ERR_INVALID_ADDRESS, ERR_INVALID_PACKET_SIZE
}

internal class SharedBridge(
    internal val service: SstpEngine,
    internal val vpnService: VpnService,
    internal val hostname: String,
    internal val port: Int,
    internal val HOME_USERNAME: String,
    internal val HOME_PASSWORD: String
) {
    internal val builder = vpnService.Builder()
    internal lateinit var handler: CoroutineExceptionHandler
    internal val controlMailbox = Channel<ControlMessage>(Channel.BUFFERED)
    internal var sslTerminal: SSLTerminal? = null
    internal var ipTerminal: IPTerminal? = null

    internal val PPP_MRU = 1500
    internal val PPP_MTU = 1500
    internal val PPP_AUTH_PROTOCOLS = setOf(AUTH_PROTOCOl_PAP, AUTH_PROTOCOL_MSCHAPv2)
    internal val PPP_IPv4_ENABLED = true
    internal val PPP_IPv6_ENABLED = false

    internal var hlak: ByteArray? = null
    internal val nonce = ByteArray(32)
    internal val guid = UUID.randomUUID().toString()
    internal var hashProtocol: Byte = 0

    private val mutex = Mutex()
    private var frameID = -1

    internal var currentMRU = PPP_MRU
    internal var currentAuth = ""
    internal val currentIPv4 = ByteArray(4)
    internal val currentIPv6 = ByteArray(8)
    internal val currentProposedDNS = ByteArray(4)

    internal fun isEnabled(authProtocol: String): Boolean = authProtocol in PPP_AUTH_PROTOCOLS

    internal fun attachSSLTerminal() {
        sslTerminal = SSLTerminal(this)
    }

    internal fun attachIPTerminal() {
        ipTerminal = IPTerminal(this)
    }

    internal suspend fun allocateNewFrameID(): Byte {
        mutex.withLock {
            frameID += 1
            return frameID.toByte()
        }
    }
}
