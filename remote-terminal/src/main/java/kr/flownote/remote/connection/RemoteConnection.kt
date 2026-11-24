package kr.flownote.remote.connection

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kr.flownote.remote.security.pinnedClient
import okhttp3.*
import okio.ByteString
import org.json.JSONObject
import java.util.UUID
import javax.net.ssl.SSLException

enum class Phase { IDLE, CONNECTING, AUTHENTICATING, CONNECTED, RETRYING, DISCONNECTED, RECOVERY, ERROR, ENDED }
data class ConnectionState(val phase: Phase = Phase.IDLE, val detail: String = "연결 안 됨", val attempt: Int = 0)

class RemoteConnection(
    private val scope: CoroutineScope,
    private val stateChanged: (ConnectionState) -> Unit,
    private val output: (String, Long) -> Unit,
    private val resetTerminal: () -> Unit,
    private val sessionChanged: (String?) -> Unit,
) {
    var state = ConnectionState(); private set
    var sessionId: String? = null; private set
    private var profile: HostProfile? = null
    private var client: OkHttpClient? = null
    private var socket: WebSocket? = null
    private var retry: Job? = null
    private var deadline: Job? = null
    private var reconnectAttempt = 0
    private var wantsConnection = false
    private var lastAck = 0L
    private var received = 0L
    private var cols = 80
    private var rows = 24
    private var createRequest = UUID.randomUUID().toString()
    private var closingForNew = false
    private var stateLost = false
    private var bytesPending = 0
    private val pendingBytes = linkedMapOf<Long, Int>()

    private fun set(phase: Phase, detail: String) {
        state = ConnectionState(phase, detail, reconnectAttempt)
        stateChanged(state)
    }

    fun connect(host: HostProfile, forgottenSession: String? = null) {
        if (profile != host) {
            stopSocket(); sessionId = forgottenSession; lastAck = 0; received = 0
            stateLost = forgottenSession != null
            profile = host.validated(); client?.dispatcher?.executorService?.shutdown()
            client?.connectionPool?.evictAll(); client = pinnedClient(host)
        }
        wantsConnection = true
        reconnectAttempt = 0
        open()
    }

    private fun open() {
        retry?.cancel(); deadline?.cancel()
        stopSocket()
        val host = profile ?: return
        set(Phase.CONNECTING, "PC에 연결 중")
        val request = Request.Builder().url(host.endpoint).header("Authorization", "Bearer ${host.token}").build()
        socket = client!!.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) = dispatch(webSocket) {
                set(Phase.AUTHENTICATING, "세션 확인 중")
                send("hello", JSONObject().put("clientName", "Flownote Android"))
            }
            override fun onMessage(webSocket: WebSocket, text: String) = dispatch(webSocket) {
                try { handle(Protocol.decode(text)) } catch (_: Exception) { fail("서버 메시지를 처리할 수 없습니다. Host 버전을 확인하세요.") }
            }
            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = dispatch(webSocket) { fail("바이너리 메시지는 지원하지 않습니다.") }
            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) = dispatch(webSocket) {
                webSocket.close(code, null)
                if (code == 4403) fail("기기 토큰이 폐기되었습니다. PC에서 다시 등록하세요.")
                else if (code == 4409) fail("다른 클라이언트가 연결 중입니다.")
                else lost()
            }
            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) = dispatch(webSocket) { lost() }
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) = dispatch(webSocket) {
                when {
                    t is SSLException -> fail("인증서 지문 또는 PC 주소가 일치하지 않습니다. PC에서 확인 후 다시 등록하세요.")
                    response?.code == 401 || response?.code == 403 -> fail("기기 토큰이 잘못되었거나 폐기되었습니다.")
                    response?.code == 409 -> fail("다른 클라이언트가 연결 중입니다.")
                    else -> lost()
                }
                response?.close()
            }
        })
        armDeadline()
    }

    private fun armDeadline() {
        deadline?.cancel()
        deadline = scope.launch { delay(20_000); if (state.phase != Phase.CONNECTED && state.phase != Phase.RECOVERY) lost() }
    }

    private fun dispatch(ws: WebSocket, action: () -> Unit) { scope.launch { if (socket === ws) action() } }
    private fun handle(message: JSONObject) {
        val payload = message.getJSONObject("payload")
        when (message.getString("type")) {
            "hello.result" -> {
                if (stateLost) { deadline?.cancel(); set(Phase.RECOVERY, "이전 터미널 화면을 복원할 수 없습니다. 새 세션을 시작하세요.") }
                else if (sessionId != null) {
                    received = lastAck
                    send("terminal.attach", JSONObject().put("sessionId", sessionId).put("lastSeq", lastAck))
                } else create()
            }
            "terminal.created", "terminal.attached" -> {
                sessionId = payload.getString("sessionId"); sessionChanged(sessionId)
                deadline?.cancel(); reconnectAttempt = 0
                set(Phase.CONNECTED, "연결됨")
                resize(cols, rows)
            }
            "terminal.output" -> {
                if (stateLost || payload.getString("sessionId") != sessionId) return
                val seq = payload.getLong("seq")
                if (seq <= received) return
                if (seq != received + 1) { recovery("출력 일부가 유실되었습니다. 새 세션을 시작하세요."); return }
                val data = payload.getString("data")
                received = seq
                if (!pendingBytes.containsKey(seq)) {
                    val size = data.toByteArray(Charsets.UTF_8).size
                    bytesPending += size; pendingBytes[seq] = size
                }
                if (bytesPending > 1024 * 1024) { recovery("화면 출력이 버퍼 한도를 넘었습니다. 새 세션을 시작하세요."); return }
                output(data, seq)
            }
            "terminal.closed", "terminal.exited" -> {
                if (payload.getString("sessionId") != sessionId) return
                clearSession()
                if (closingForNew) { closingForNew = false; create() }
                else { wantsConnection = false; stopSocket(); set(Phase.ENDED, "터미널 종료됨") }
            }
            "error" -> when (payload.getString("code")) {
                "SESSION_NOT_FOUND" -> {
                    clearSession(); closingForNew = false
                    set(Phase.RECOVERY, "PC의 이전 세션이 종료되었습니다. 새 세션을 시작하세요.")
                    deadline?.cancel()
                }
                "REPLAY_UNAVAILABLE" -> recovery("출력 재생 범위를 벗어났습니다. 새 세션을 시작하세요.")
                "SESSION_CONFLICT" -> fail("PC에 기존 터미널이 남아 있습니다. 기존 앱에서 종료하거나 Host를 재시작하세요.")
                else -> fail(payload.optString("message", "연결 요청이 거부되었습니다.").take(240))
            }
        }
    }

    private fun create() {
        set(Phase.AUTHENTICATING, "새 터미널 여는 중")
        armDeadline()
        send("terminal.create", JSONObject().put("cols", cols).put("rows", rows), createRequest)
    }
    private fun send(type: String, payload: JSONObject, id: String = UUID.randomUUID().toString()): Boolean =
        socket?.send(Protocol.message(type, payload, id)) == true

    fun input(text: String): Boolean {
        if (state.phase != Phase.CONNECTED || sessionId == null || text.length > 262144) return false
        for (chunk in Protocol.inputChunks(text)) {
            if ((socket?.queueSize() ?: 0L) > 256 * 1024 || !send("terminal.input", JSONObject().put("sessionId", sessionId).put("data", chunk))) {
                lost(); return false
            }
        }
        return true
    }
    fun acknowledge(seq: Long) {
        if (seq > received || seq <= lastAck) return
        lastAck = seq
        pendingBytes.keys.filter { it <= seq }.forEach { bytesPending -= pendingBytes.remove(it) ?: 0 }
    }
    fun resize(columns: Int, lines: Int) {
        cols = columns.coerceIn(20, 300); rows = lines.coerceIn(5, 200)
        if (state.phase == Phase.CONNECTED) send("terminal.resize", JSONObject().put("sessionId", sessionId).put("cols", cols).put("rows", rows))
    }
    fun newSession() {
        stateLost = false
        if (socket == null) { clearSession(); profile?.let { connect(it) }; return }
        if (sessionId != null) {
            closingForNew = true
            set(Phase.AUTHENTICATING, "기존 터미널 종료 중")
            armDeadline()
            send("terminal.close", JSONObject().put("sessionId", sessionId))
        } else { clearSession(); create() }
    }
    fun closeSession() {
        if (state.phase != Phase.CONNECTED && state.phase != Phase.RECOVERY) return
        if (sessionId != null) {
            set(Phase.AUTHENTICATING, "터미널 종료 중")
            armDeadline()
            send("terminal.close", JSONObject().put("sessionId", sessionId))
        }
        else disconnect()
    }
    fun disconnect() {
        wantsConnection = false; retry?.cancel(); deadline?.cancel(); stopSocket()
        set(Phase.DISCONNECTED, "연결 해제됨")
    }
    private fun recovery(detail: String) { stateLost = true; deadline?.cancel(); set(Phase.RECOVERY, detail) }
    private fun fail(detail: String) { wantsConnection = false; retry?.cancel(); deadline?.cancel(); stopSocket(); set(Phase.ERROR, detail) }
    private fun lost() {
        stopSocket(); deadline?.cancel()
        if (!wantsConnection) return
        reconnectAttempt++
        val seconds = listOf(1L, 2L, 4L, 8L, 15L)[(reconnectAttempt - 1).coerceAtMost(4)]
        set(Phase.RETRYING, "${seconds}초 후 재연결 · ${reconnectAttempt}회")
        retry?.cancel(); retry = scope.launch { delay(seconds * 1000); open() }
    }
    private fun clearSession() {
        sessionId = null; lastAck = 0; received = 0; bytesPending = 0; pendingBytes.clear()
        createRequest = UUID.randomUUID().toString(); stateLost = false
        sessionChanged(null); resetTerminal()
    }
    private fun stopSocket() { val old = socket; socket = null; old?.cancel() }
    fun dispose() { disconnect(); client?.dispatcher?.executorService?.shutdown(); client?.connectionPool?.evictAll() }
}
