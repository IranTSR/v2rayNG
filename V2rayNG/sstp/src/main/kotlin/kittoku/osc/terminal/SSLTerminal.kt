package kittoku.osc.terminal

import kittoku.osc.ControlMessage
import kittoku.osc.Result
import kittoku.osc.SharedBridge
import kittoku.osc.Where
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.security.cert.Certificate
import java.util.UUID
import javax.net.ssl.SSLSocket
import javax.net.ssl.SSLSocketFactory

internal const val SSL_REQUEST_INTERVAL = 10_000L

internal class SSLTerminal(private val bridge: SharedBridge) {
    private val mutex = Mutex()
    private var rawSocket: Socket? = null
    private var socket: SSLSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null
    private var jobInitialize: Job? = null

    internal fun initialize() {
        jobInitialize = bridge.service.scope.launch(bridge.handler) {
            try {
                val raw = Socket()
                rawSocket = raw
                if (!bridge.vpnService.protect(raw)) {
                    error("Unable to protect SSTP transport socket from VPN routing")
                }
                withContext(Dispatchers.IO) {
                    raw.connect(InetSocketAddress(bridge.hostname, bridge.port), 15_000)
                }

                val ssl = (SSLSocketFactory.getDefault() as SSLSocketFactory)
                    .createSocket(raw, bridge.hostname, bridge.port, true) as SSLSocket
                socket = ssl
                ssl.sslParameters = ssl.sslParameters.apply {
                    endpointIdentificationAlgorithm = "HTTPS"
                }
                withContext(Dispatchers.IO) { ssl.startHandshake() }
                input = ssl.inputStream
                output = ssl.outputStream

                val request = listOf(
                    "SSTP_DUPLEX_POST /sra_{BA195980-CD49-458b-9E23-C84EE0ADCD75}/ HTTP/1.1",
                    "Content-Length: 18446744073709551615",
                    "Host: ${bridge.hostname}",
                    "SSTPCORRELATIONID: {${bridge.guid}}"
                ).joinToString("\r\n", postfix = "\r\n\r\n").toByteArray(Charsets.US_ASCII)

                withContext(Dispatchers.IO) {
                    output!!.write(request)
                    output!!.flush()
                    val response = ByteArrayOutputStream()
                    val delimiter = byteArrayOf(13, 10, 13, 10)
                    while (response.size() < 16 * 1024) {
                        val value = input!!.read()
                        if (value < 0) error("SSTP server closed during HTTP negotiation")
                        response.write(value)
                        val bytes = response.toByteArray()
                        if (bytes.size >= 4 && bytes.copyOfRange(bytes.size - 4, bytes.size).contentEquals(delimiter)) {
                            val status = bytes.toString(Charsets.US_ASCII).lineSequence().firstOrNull().orEmpty()
                            if (!status.contains(" 200 ")) error("SSTP HTTP negotiation rejected: $status")
                            break
                        }
                    }
                    if (response.size() >= 16 * 1024) error("SSTP HTTP response header is too large")
                    ssl.soTimeout = 1000
                }

                bridge.controlMailbox.send(ControlMessage(Where.SSL, Result.PROCEEDED))
            } catch (t: Throwable) {
                bridge.controlMailbox.send(
                    ControlMessage(Where.SSL, Result.ERR_VERIFICATION_FAILED, t.message ?: t.javaClass.simpleName)
                )
            }
        }
    }

    internal fun getServerCertificate(): ByteArray =
        socket?.session?.peerCertificates?.firstOrNull()?.encoded
            ?: error("SSTP TLS peer certificate is unavailable")

    internal fun getApplicationBufferSize(): Int = 65_536

    internal suspend fun receive(buffer: ByteBuffer) {
        val start = buffer.position()
        val oldLimit = buffer.limit()
        if (oldLimit >= buffer.capacity()) return
        try {
            val count = withContext(Dispatchers.IO) {
                input?.read(buffer.array(), oldLimit, buffer.capacity() - oldLimit) ?: -1
            }
            if (count < 0) error("SSTP TLS stream closed")
            if (count > 0) buffer.limit(oldLimit + count)
        } catch (_: SocketTimeoutException) {
            // Periodic read timeouts allow the PPP/SSTP keepalive timers to run.
        } finally {
            buffer.position(start)
        }
    }

    internal suspend fun send(buffer: ByteBuffer) {
        mutex.withLock {
            val stream = output ?: error("SSTP TLS stream is not initialized")
            val count = buffer.remaining()
            if (count <= 0) return
            withContext(Dispatchers.IO) {
                stream.write(buffer.array(), buffer.position(), count)
                stream.flush()
            }
            buffer.position(buffer.limit())
        }
    }

    internal fun close() {
        jobInitialize?.cancel()
        try {
            socket?.close()
        } finally {
            rawSocket?.close()
            socket = null
            rawSocket = null
            input = null
            output = null
        }
    }
}
