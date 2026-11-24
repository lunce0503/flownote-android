package kr.flownote.remote

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kr.flownote.remote.connection.*
import kr.flownote.remote.security.ProfileStore
import kr.flownote.remote.security.ProfileCatalog
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import org.json.JSONObject
import kr.flownote.remote.workspace.WorkspaceModel

class RemoteViewModel(application: Application) : AndroidViewModel(application) {
    private val store = ProfileStore(application)
    var profile by mutableStateOf<HostProfile?>(null); private set
    var catalog by mutableStateOf(ProfileCatalog()); private set
    var editorOpen by mutableStateOf(true); private set
    var editingId by mutableStateOf<String?>(null); private set
    var editorProfile by mutableStateOf<HostProfile?>(null); private set
    var editorVersion by mutableStateOf(0); private set
    var pairing by mutableStateOf(false); private set
    private var pairingJob: Job? = null
    private var activeHostId: String? = null
    var screenTerminal by mutableStateOf(false); private set
    var state by mutableStateOf(ConnectionState()); private set
    var error by mutableStateOf<String?>(null)
    var ctrl by mutableStateOf(false); private set
    var alt by mutableStateOf(false); private set
    var pasteRequested by mutableStateOf(false)
    var renderer: ((JSONObject) -> Unit)? = null
    val workspace = WorkspaceModel(viewModelScope) { error = it }
    var codeOpen by mutableStateOf(false)
    private var ready = false
    private var wasConnected = false
    private var connection = makeConnection()
    init {
        runCatching { reload(); editorOpen = catalog.hosts.isEmpty() }.onFailure { error = "저장된 인증 정보를 읽을 수 없습니다. PC 정보를 다시 등록하세요." }
    }
    private fun makeConnection() = RemoteConnection(viewModelScope, { next ->
        state = next
        renderer?.invoke(JSONObject().put("type", "enabled").put("value", next.phase == Phase.CONNECTED))
    }, { text, seq ->
        renderer?.invoke(JSONObject().put("type", "output").put("seq", seq).put("data", text))
    }, { renderer?.invoke(JSONObject().put("type", "reset")) }, { id ->
        activeHostId?.let { hostId -> store.rememberSession(id, hostId); catalog = store.catalog() }
    })

    private fun reload() { catalog = store.catalog(); profile = catalog.selected()?.profile }
    fun addComputer() { editingId = null; editorProfile = null; editorVersion++; editorOpen = true }
    fun editComputer(id: String) {
        catalog.hosts.find { it.id == id }?.let { editingId = id; editorProfile = it.profile; editorVersion++; editorOpen = true }
    }
    fun showComputers() { pairingJob?.cancel(); pairing = false; editorOpen = false }
    fun deleteComputer(id: String) {
        runCatching { store.remove(id); reload(); editorOpen = false }.onFailure { error = "PC 정보를 삭제하지 못했습니다." }
    }
    fun saveComputer(host: HostProfile, connect: Boolean) {
        runCatching {
            val saved = store.save(host, editingId)
            reload(); editorOpen = false
            if (connect) openComputer(saved.id)
        }.onFailure { error = it.message ?: "PC 정보를 저장하지 못했습니다." }
    }
    fun openComputer(id: String) {
        runCatching {
            connection.dispose(); renderer = null; ready = false; wasConnected = false
            store.select(id); reload(); activeHostId = id
            workspace.attach(profile); codeOpen = false
            connection = makeConnection(); screenTerminal = true; error = null
        }.onFailure { error = "PC 연결 설정을 읽지 못했습니다." }
    }
    fun pairComputer(invitation: PairingInvitation, name: String, connect: Boolean) {
        if (pairing) return
        pairing = true
        pairingJob = viewModelScope.launch {
            try {
                // Reject local validation/capacity errors before consuming the one-time code.
                catalog.upsert(invitation.profile("pairing-validation-token", name), editingId)
                saveComputer(PairingClient().redeem(invitation, name), connect)
            }
            catch (error: CancellationException) { throw error }
            catch (failure: Exception) { error = failure.message ?: "PC QR 등록에 실패했습니다." }
            finally { pairing = false }
        }
    }

    fun saveAndOpen(host: HostProfile) {
        saveComputer(host, true)
    }
    fun onBridge(message: JSONObject) {
        when (message.getString("type")) {
            "ready" -> {
                // A replacement WebView has no previous ANSI screen, even if the ViewModel survived.
                if (ready) { connection.dispose(); connection = makeConnection() }
                ready = true
                connection.resize(message.getInt("cols"), message.getInt("rows"))
                profile?.let { connection.connect(it, store.rememberedSession(activeHostId)) }
            }
            "input" -> if (!connection.input(message.getString("data"))) error = "입력을 보내지 못했습니다. 연결 상태를 확인하세요. 입력은 자동 재전송되지 않습니다."
            "resize" -> connection.resize(message.getInt("cols"), message.getInt("rows"))
            "ack" -> connection.acknowledge(message.getLong("seq"))
            "modifiers" -> { ctrl = message.getBoolean("ctrl"); alt = message.getBoolean("alt") }
            "paste" -> pasteRequested = true
        }
    }
    fun input(text: String): Boolean {
        val sent = connection.input(text)
        if (sent) command("follow")
        if (!sent) error = "입력을 보내지 못했습니다. 연결 상태와 PC의 실행 결과를 확인하세요. 입력은 자동 재전송되지 않습니다."
        return sent
    }
    fun reconnect() { if (ready) profile?.let { connection.connect(it) } }
    fun disconnect() = connection.disconnect()
    fun newSession() = connection.newSession()
    fun closeSession() = connection.closeSession()
    fun command(type: String) { renderer?.invoke(JSONObject().put("type", type)) }
    fun modifier(key: String) { renderer?.invoke(JSONObject().put("type", "modifier").put("key", key)) }
    fun paste(text: String) { renderer?.invoke(JSONObject().put("type", "paste").put("data", text)) }
    fun font(size: Int) { renderer?.invoke(JSONObject().put("type", "font").put("size", size)) }
    fun background() { wasConnected = state.phase in listOf(Phase.CONNECTED, Phase.CONNECTING, Phase.AUTHENTICATING, Phase.RETRYING); connection.disconnect() }
    fun foreground() { if (wasConnected) { wasConnected = false; reconnect() } }
    fun editProfile() {
        connection.dispose(); renderer = null; ready = false; wasConnected = false
        connection = makeConnection(); screenTerminal = false; editorOpen = false
        workspace.attach(null); codeOpen = false
    }
    fun forget() { activeHostId?.let { editProfile(); deleteComputer(it) } }
    override fun onCleared() { connection.dispose() }
}
