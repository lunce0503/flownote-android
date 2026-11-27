package kr.flownote.remote.connection

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.cert.X509Certificate
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocket
import kr.flownote.remote.security.PinnedTrustManager
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import okio.ByteString.Companion.toByteString

internal class DesktopTunnel(
    environment: String,
    private val ticket: String,
    private val accessKey: String,
    private val tlsName: String,
    private val fingerprint: String,
    private val onReady: () -> Unit,
    private val onFrame: (ByteArray) -> Unit,
    private val onEnded: (String?) -> Unit,
) : Closeable {
    private val url = when (environment) {
        "staging" -> "wss://flownote-api-staging.up.railway.app/api/remote-desktop/v1/viewer"
        "production" -> "wss://flownote-api-production.up.railway.app/api/remote-desktop/v1/viewer"
        else -> throw IllegalArgumentException("원격 데스크톱 환경을 확인하세요.")
    }
    private val workers = Executors.newFixedThreadPool(4) { daemon(it, "fot-desktop-io") }
    private val relay = AtomicReference<WebSocket?>()
    private val started = AtomicBoolean()
    private val joined = AtomicBoolean()
    private val ready = AtomicBoolean()
    private val ended = AtomicBoolean()
    private val encrypted = ArrayBlockingQueue<ByteArray>(32)
    private val input = ArrayBlockingQueue<ByteArray>(16)
    private val owned = mutableListOf<Closeable>()
    @Volatile private var deadline: ScheduledFuture<*>? = null

    init {
        require(ticket.matches(code) && accessKey.matches(code) && tlsName.matches(validTlsName)) {
            "원격 데스크톱 QR의 연결 정보를 확인하세요."
        }
    }

    fun start() {
        if (!started.compareAndSet(false, true) || ended.get()) return
        deadline = timer.schedule({ finish("PC 연결 확인 시간이 초과되었습니다.") }, 25, TimeUnit.SECONDS)
        val request = Request.Builder().url(url).header("Authorization", "Bearer $ticket").build()
        val socket = relayClient.newWebSocket(request, object : WebSocketListener() {
            override fun onMessage(webSocket: WebSocket, text: String) {
                if (ended.get()) return
                if (text != "connected" || !joined.compareAndSet(false, true)) {
                    finish("원격 데스크톱 중계 응답을 확인하세요.")
                    return
                }
                runInWorker { establish(webSocket) }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                if (ended.get()) return
                if (!joined.get() || bytes.size !in 1..MAX_FRAME || !encrypted.offer(bytes.toByteArray())) {
                    finish("원격 데스크톱 중계의 수신 한도를 초과했습니다.")
                }
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = finish(null)

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val message = if (response?.code in setOf(403, 404)) "QR이 만료되었거나 이미 사용되었습니다."
                    else "원격 데스크톱 중계에 연결할 수 없습니다."
                response?.body?.close()
                finish(message)
            }
        })
        relay.set(socket)
        if (ended.get()) {
            deadline?.cancel(false)
            timer.execute { relay.getAndSet(null)?.cancel() }
        }
    }

    fun send(frame: ByteArray): Boolean {
        if (!ready.get() || ended.get()) return false
        if (frame.size !in 1..MAX_FRAME || !input.offer(frame.copyOf())) {
            finish("원격 데스크톱의 입력 전송 한도를 초과했습니다.")
            return false
        }
        return true
    }

    override fun close() = finish(null, notify = false)

    private fun finish(message: String?, notify: Boolean = true) {
        if (!ended.compareAndSet(false, true)) return
        ready.set(false)
        deadline?.cancel(false)
        workers.shutdownNow()
        timer.execute {
            relay.getAndSet(null)?.cancel()
            synchronized(owned) {
                owned.forEach { runCatching { it.close() } }
                owned.clear()
            }
            encrypted.clear()
            input.clear()
            if (notify) onEnded(message)
        }
    }

    private fun runInWorker(action: () -> Unit) {
        try {
            workers.execute {
                try { action() }
                catch (_: InterruptedException) { Thread.currentThread().interrupt() }
                catch (_: Exception) { finish("PC의 원격 데스크톱 연결을 확인하세요.") }
            }
        } catch (_: java.util.concurrent.RejectedExecutionException) {
            if (!ended.get()) finish("원격 데스크톱 연결을 시작할 수 없습니다.")
        }
    }

    private fun <T : Closeable> own(resource: T): T {
        synchronized(owned) {
            if (!ended.get()) {
                owned.add(resource)
                return resource
            }
        }
        resource.close()
        throw IOException("Session closed")
    }

    private fun establish(webSocket: WebSocket) {
        val loopback = InetAddress.getByName("127.0.0.1")
        val listener = own(ServerSocket(0, 1, loopback)).apply { soTimeout = 10_000 }
        val transport = own(Socket(loopback, listener.localPort))
        val upstream = listener.use { own(it.accept()) }
        check(upstream.inetAddress == loopback && upstream.port == transport.localPort)
        upstream.tcpNoDelay = true
        transport.tcpNoDelay = true
        runInWorker {
            val output = upstream.getOutputStream()
            while (!ended.get()) output.write(encrypted.take())
        }
        runInWorker {
            val data = upstream.getInputStream()
            val buffer = ByteArray(CHUNK_SIZE)
            while (!ended.get()) {
                val size = data.read(buffer)
                if (size < 0) break
                if (webSocket.queueSize() > 1024 * 1024L || !webSocket.send(buffer.toByteString(0, size))) {
                    throw IOException("Relay cannot accept desktop bytes")
                }
            }
            finish(null)
        }

        val context = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf(PinnedTrustManager(fingerprint)), null)
        }
        val tls = own(context.socketFactory.createSocket(transport, tlsName, 443, true) as SSLSocket)
        tls.enabledProtocols = tls.supportedProtocols.filter { it == "TLSv1.2" || it == "TLSv1.3" }.toTypedArray()
        tls.soTimeout = 10_000
        tls.startHandshake()
        val identity = tls.session.peerCertificates.first() as X509Certificate
        require(matchesName(identity, tlsName))
        tls.outputStream.write("$accessKey\n".toByteArray(Charsets.US_ASCII))
        tls.outputStream.flush()
        val incoming = tls.inputStream
        if (incoming.read() != 'O'.code || incoming.read() != 'K'.code || incoming.read() != '\n'.code) {
            throw IOException("Desktop authentication was not acknowledged")
        }
        if (ended.get()) return
        deadline?.cancel(false)
        tls.soTimeout = 0
        ready.set(true)
        runInWorker {
            val output = tls.outputStream
            while (!ended.get()) {
                output.write(input.take())
                output.flush()
            }
        }
        onReady()
        val buffer = ByteArray(CHUNK_SIZE)
        while (!ended.get()) {
            val size = incoming.read(buffer)
            if (size < 0) break
            if (size > 0 && !ended.get()) onFrame(buffer.copyOf(size))
        }
        finish(null)
    }

    companion object {
        private const val MAX_FRAME = 64 * 1024
        private const val CHUNK_SIZE = 32 * 1024
        private val code = Regex("[A-Za-z0-9_-]{43}")
        private val validTlsName = Regex("[A-Za-z0-9.:-]{1,253}")
        private val ipv4 = Regex("[0-9]{1,3}(\\.[0-9]{1,3}){3}")
        private val ipv6 = Regex("[A-Fa-f0-9:.]+")
        private val relayClient = OkHttpClient.Builder().followRedirects(false).followSslRedirects(false)
            .connectTimeout(10, TimeUnit.SECONDS).pingInterval(15, TimeUnit.SECONDS).build()
        private val timer = Executors.newScheduledThreadPool(2) { daemon(it, "fot-desktop-timeout") }

        private fun daemon(action: Runnable, name: String) = Thread(action, name).apply { isDaemon = true }

        private fun matchesName(cert: X509Certificate, name: String): Boolean {
            val address = ':' in name || ipv4.matches(name)
            return cert.subjectAlternativeNames?.any { item ->
                val actual = item.getOrNull(1) as? String ?: return@any false
                val type = item.firstOrNull()
                if (!address) type == 2 && actual.equals(name, ignoreCase = true)
                else if (type != 7) false
                else if (':' in name && ipv6.matches(name) && ipv6.matches(actual)) {
                    InetAddress.getByName(actual) == InetAddress.getByName(name)
                } else actual == name
            } == true
        }
    }
}
