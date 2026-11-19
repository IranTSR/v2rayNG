package kittoku.osc.terminal

import android.os.ParcelFileDescriptor
import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.InetAddress
import java.nio.ByteBuffer

internal class IPTerminal(private val bridge: SharedBridge) {
    private var fd: ParcelFileDescriptor? = null
    private var inputStream: FileInputStream? = null
    private var outputStream: FileOutputStream? = null

    internal suspend fun initialize() {
        if (!bridge.PPP_IPv4_ENABLED || bridge.currentIPv4.contentEquals(ByteArray(4))) {
            bridge.controlMailbox.send(ControlMessage(Where.IPv4, Result.ERR_INVALID_ADDRESS))
            return
        }

        bridge.builder.addAddress(InetAddress.getByAddress(bridge.currentIPv4), 32)
        if (!bridge.currentProposedDNS.contentEquals(ByteArray(4))) {
            bridge.builder.addDnsServer(InetAddress.getByAddress(bridge.currentProposedDNS))
        }
        bridge.builder.addRoute("0.0.0.0", 0)
        // Route IPv6 into the TUN and drop it in OutgoingManager until IPv6CP is negotiated.
        // This prevents IPv6 traffic from bypassing the SSTP tunnel on dual-stack networks.
        bridge.builder.addAddress(InetAddress.getByName("fd00::1"), 128)
        bridge.builder.addRoute("::", 0)
        bridge.builder.setSession("SSTP: ${bridge.hostname}")
        bridge.builder.setMtu(bridge.PPP_MTU)
        bridge.builder.setBlocking(true)

        val established = bridge.builder.establish()
            ?: error("Android refused to establish the SSTP VPN interface")
        fd = established
        inputStream = FileInputStream(established.fileDescriptor)
        outputStream = FileOutputStream(established.fileDescriptor)
        bridge.controlMailbox.send(ControlMessage(Where.IP, Result.PROCEEDED))
    }

    internal fun writePacket(start: Int, size: Int, buffer: ByteBuffer) {
        outputStream?.write(buffer.array(), start, size)
    }

    internal fun readPacket(buffer: ByteBuffer) {
        buffer.clear()
        val count = inputStream?.read(buffer.array(), 0, bridge.PPP_MTU) ?: -1
        if (count < 0) error("SSTP VPN interface closed")
        buffer.position(count)
        buffer.flip()
    }

    internal fun close() {
        runCatching { inputStream?.close() }
        runCatching { outputStream?.close() }
        runCatching { fd?.close() }
        inputStream = null
        outputStream = null
        fd = null
    }
}
