package kr.flownote.remote

import kotlinx.coroutines.*
import kr.flownote.remote.connection.*
import kr.flownote.remote.connection.Protocol
import okhttp3.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.tls.HandshakeCertificates
import okhttp3.tls.HeldCertificate
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class ConnectionTest {
    private class Peer : AutoCloseable {
        val server = MockWebServer()
        val dispatcher = Executors.newSingleThreadExecutor().asCoroutineDispatcher()
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        val session = UUID.randomUUID().toString()
        val hostId = UUID.randomUUID().toString()
        val messages = CopyOnWriteArrayList<JSONObject>()
        val outputs = CopyOnWriteArrayList<Pair<String, Long>>()
        @Volatile var socket: WebSocket? = null
        @Volatile var phase = Phase.IDLE
        var attachError: String? = null
        val profile: HostProfile
        val connection = RemoteConnection(scope, { phase = it.phase }, { text, seq -> outputs.add(text to seq) }, {}, {})

        init {
            val certificate = HeldCertificate.Builder().commonName("localhost").addSubjectAlternativeName("localhost").build()
            server.useHttps(HandshakeCertificates.Builder().heldCertificate(certificate).build().sslSocketFactory(), false)
            server.start()
            val fingerprint = MessageDigest.getInstance("SHA-256").digest(certificate.certificate.encoded).joinToString("") { "%02x".format(it) }
            profile = HostProfile("Test", server.url("/v1/connect").toString().replace("https://", "wss://"), "a".repeat(43), fingerprint).validated()
        }
        fun enqueue() {
            server.enqueue(MockResponse().withWebSocketUpgrade(object : WebSocketListener() {
                override fun onOpen(webSocket: WebSocket, response: Response) { socket = webSocket }
                override fun onMessage(webSocket: WebSocket, text: String) {
                    val message = JSONObject(text)
                    messages.add(message)
                    val payload = message.getJSONObject("payload")
                    fun reply(type: String, value: JSONObject) { webSocket.send(Protocol.message(type, value, message.getString("requestId"))) }
                    fun status() = JSONObject().put("sessionId", session).put("ownerDeviceId", "phone-01").put("lastSeq", 0).put("attached", true)
                    when (message.getString("type")) {
                        "hello" -> reply("hello.result", JSONObject().put("hostId", hostId).put("protocolVersion", 1).put("features", org.json.JSONArray(listOf("terminal", "resize", "reattach", "replay"))))
                        "terminal.create" -> reply("terminal.created", status())
                        "terminal.attach" -> if (attachError == null) reply("terminal.attached", status()) else reply("error", JSONObject().put("code", attachError).put("message", "Recovery required"))
                        "terminal.close" -> reply("terminal.closed", JSONObject().put("sessionId", payload.getString("sessionId")))
                    }
                }
                override fun onClosing(webSocket: WebSocket, code: Int, reason: String) { webSocket.close(code, null) }
            }))
        }
        fun action(block: (RemoteConnection) -> Unit) = runBlocking(dispatcher) { block(connection) }
        fun await(predicate: () -> Boolean) = runBlocking { withTimeout(10_000) { while (!predicate()) delay(20) } }
        fun awaitPhase(expected: Phase) = await { phase == expected }
        fun output(seq: Long, text: String = "data") {
            socket!!.send(JSONObject().put("version", 1).put("type", "terminal.output").put("payload", JSONObject().put("sessionId", session).put("seq", seq).put("data", text)).toString())
        }
        fun requests(type: String) = messages.filter { it.getString("type") == type }
        override fun close() {
            action { it.dispose() }
            scope.cancel(); dispatcher.close(); server.shutdown()
        }
    }

    @Test fun reconnectAttachesAfterRenderedSequenceWithoutReplayingInput() {
        Peer().use { p ->
            p.enqueue(); p.action { it.connect(p.profile) }; p.awaitPhase(Phase.CONNECTED)
            p.action { assertTrue(it.input("printf 'once'\r")) }
            p.await { p.requests("terminal.input").size == 1 }
            p.output(1); p.await { p.outputs.size == 1 }; p.action { it.acknowledge(1) }
            p.enqueue(); p.socket!!.close(1012, "Restart transport")
            p.await { p.requests("terminal.attach").size == 1 }; p.awaitPhase(Phase.CONNECTED)
            assertEquals(1, p.requests("terminal.attach").single().getJSONObject("payload").getLong("lastSeq"))
            assertEquals(1, p.requests("terminal.create").size)
            assertEquals(1, p.requests("terminal.input").size)
            p.output(2, "after reconnect"); p.await { p.outputs.size == 2 }
            p.action { it.disconnect(); assertFalse(it.input("must not be queued")) }
            assertEquals(1, p.requests("terminal.input").size)
        }
    }

    @Test fun lostScreenRequiresExplicitNewSessionAndClosesOldSessionFirst() {
        Peer().use { p ->
            p.enqueue(); p.action { it.connect(p.profile, p.session) }; p.awaitPhase(Phase.RECOVERY)
            assertTrue(p.requests("terminal.attach").isEmpty())
            assertTrue(p.requests("terminal.create").isEmpty())
            p.action { it.newSession() }; p.awaitPhase(Phase.CONNECTED)
            assertEquals(1, p.requests("terminal.close").size)
            assertEquals(1, p.requests("terminal.create").size)
        }
    }

    @Test fun outputGapAndReplayOverflowDisableInputUntilExplicitRecovery() {
        Peer().use { p ->
            p.enqueue(); p.action { it.connect(p.profile) }; p.awaitPhase(Phase.CONNECTED)
            p.output(2); p.awaitPhase(Phase.RECOVERY)
            p.output(1, "must be ignored"); p.action { assertFalse(it.input("no")) }
            assertTrue(p.outputs.isEmpty())
            p.action { it.newSession() }; p.awaitPhase(Phase.CONNECTED)
            p.attachError = "REPLAY_UNAVAILABLE"
            p.enqueue(); p.socket!!.close(1012, "Disconnect")
            p.awaitPhase(Phase.RECOVERY)
            p.action { assertFalse(it.input("no")); it.newSession() }; p.awaitPhase(Phase.CONNECTED)
            assertEquals(3, p.requests("terminal.create").size)
        }
    }

    @Test fun revokedConnectionDoesNotRetryAndMalformedServerMessageFailsClosed() {
        Peer().use { p ->
            p.enqueue(); p.action { it.connect(p.profile) }; p.awaitPhase(Phase.CONNECTED)
            p.socket!!.close(4403, "Device revoked"); p.awaitPhase(Phase.ERROR)
            p.action { assertFalse(it.input("no")) }
            p.enqueue(); p.action { it.connect(p.profile) }; p.awaitPhase(Phase.CONNECTED)
            p.socket!!.send("{bad json"); p.awaitPhase(Phase.ERROR)
            assertEquals(2, p.requests("hello").size)
        }
    }
}
