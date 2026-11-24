package kr.flownote.remote.workspace

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.*
import kr.flownote.remote.connection.HostProfile
import org.json.JSONObject
import java.util.UUID

data class WorkspaceEntry(val name: String, val path: String, val directory: Boolean)
data class CodeDocument(val path: String, val content: String, val base: String, val revision: String) {
    val dirty get() = content != base
    val name get() = path.substringAfterLast('/')
    fun saved(snapshot: String, nextRevision: String) = copy(base = snapshot, revision = nextRevision)
}

class WorkspaceModel(private val scope: CoroutineScope, private val failure: (String) -> Unit) {
    var entries by mutableStateOf<List<WorkspaceEntry>>(emptyList()); private set
    var folder by mutableStateOf(""); private set
    var rootName by mutableStateOf("프로젝트"); private set
    var nextOffset by mutableStateOf<Int?>(null); private set
    var documents by mutableStateOf<List<CodeDocument>>(emptyList()); private set
    var activePath by mutableStateOf<String?>(null); private set
    var busy by mutableStateOf(false); private set
    var loaded by mutableStateOf(false); private set
    var line by mutableStateOf(1); private set
    var column by mutableStateOf(1); private set
    var wrap by mutableStateOf(false); private set
    val active get() = documents.find { it.path == activePath }
    val dirty get() = documents.any { it.dirty }
    var renderer: ((JSONObject) -> Unit)? = null
    private var ready = false
    private var epoch = 0
    private var client: WorkspaceClient? = null
    private var job: Job? = null
    private var snapshot: CompletableDeferred<String>? = null
    private var snapshotId: String? = null
    private var snapshotPath: String? = null

    fun attach(profile: HostProfile?) {
        job?.cancel(); epoch++; ready = false; renderer = null; client = profile?.let(::WorkspaceClient)
        entries = emptyList(); documents = emptyList(); activePath = null; folder = ""; rootName = "프로젝트"; busy = false; loaded = false
        snapshot?.cancel(); snapshot = null
    }
    private fun perform(block: suspend (WorkspaceClient) -> Unit) {
        val api = client ?: return
        if (busy) return
        val generation = epoch
        busy = true
        job = scope.launch {
            try { block(api) }
            catch (timeout: TimeoutCancellationException) { if (generation == epoch) failure("편집기 응답이 지연되어 저장하지 않았습니다. 다시 시도하세요.") }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (error: Exception) { if (generation == epoch) failure(error.message ?: "파일 요청을 완료하지 못했습니다.") }
            finally { if (generation == epoch) busy = false }
        }
    }
    fun list(path: String = folder, more: Boolean = false) = perform { api ->
        val result = api.call("list", JSONObject().put("path", path).put("offset", if (more) nextOffset ?: 0 else 0))
        val values = result.getJSONArray("entries")
        val rows = (0 until values.length()).map { index -> values.getJSONObject(index).let {
            WorkspaceEntry(it.getString("name"), it.getString("path"), it.getString("kind") == "directory")
        } }
        entries = (if (more && path == folder) entries + rows else rows).distinctBy { it.path }
        folder = result.getString("path"); rootName = result.getString("rootName")
        nextOffset = if (result.isNull("nextOffset")) null else result.getInt("nextOffset"); loaded = true
    }
    fun open(path: String) {
        if (documents.any { it.path == path }) { activePath = path; render(); return }
        if (documents.size >= 8) { failure("파일 탭은 최대 8개까지 열 수 있습니다."); return }
        perform { api ->
            val result = api.call("read", JSONObject().put("path", path))
            val content = result.getString("content")
            documents = documents + CodeDocument(path, content, content, result.getString("revision"))
            activePath = path; render()
        }
    }
    fun reload() {
        val path = activePath ?: return
        perform { api ->
            val result = api.call("read", JSONObject().put("path", path)); val content = result.getString("content")
            documents = documents.map { if (it.path == path) CodeDocument(path, content, content, result.getString("revision")) else it }
            render()
        }
    }
    fun close(path: String) {
        if (busy) return
        command("close", JSONObject().put("path", path))
        documents = documents.filterNot { it.path == path }
        if (activePath == path) { activePath = documents.lastOrNull()?.path; render() }
    }
    fun save() {
        val document = active ?: return
        if (!ready || renderer == null || busy || !document.dirty) return
        perform { api ->
            val pending = CompletableDeferred<String>()
            snapshot = pending; snapshotId = UUID.randomUUID().toString(); snapshotPath = document.path
            try {
                command("snapshot", JSONObject().put("id", snapshotId))
                val content = withTimeout(3000) { pending.await() }
                val result = api.call("write", JSONObject().put("path", document.path).put("content", content).put("revision", document.revision))
                documents = documents.map { if (it.path == document.path) it.saved(content, result.getString("revision")) else it }
            } finally { snapshot = null; snapshotId = null; snapshotPath = null }
        }
    }
    fun command(type: String, extra: JSONObject = JSONObject()) { renderer?.invoke(extra.put("type", type).put("epoch", epoch)) }
    fun toggleWrap() { wrap = !wrap; command("wrap", JSONObject().put("enabled", wrap)) }
    private fun render() {
        if (!ready) return
        active?.let { command("load", JSONObject().put("path", it.path).put("content", it.content)); command("wrap", JSONObject().put("enabled", wrap)) }
    }
    fun editorReleased() { renderer = null; ready = false }
    fun onBridge(message: JSONObject) {
        val type = message.optString("type")
        if (type == "failed") { ready = false; failure("코드 편집기를 불러오지 못했습니다. 앱과 Android System WebView를 업데이트하세요."); return }
        if (type == "ready") { ready = true; render(); return }
        if (message.optInt("epoch", -1) != epoch) return
        val path = message.optString("path")
        when (type) {
            "change", "snapshot" -> {
                val content = message.optString("content")
                if (content.toByteArray(Charsets.UTF_8).size > 256 * 1024) { failure("편집 내용이 256 KiB를 초과했습니다. 실행 취소로 크기를 줄이세요."); return }
                documents = documents.map { if (it.path == path) it.copy(content = content) else it }
                if (type == "snapshot" && path == snapshotPath && message.optString("id") == snapshotId) snapshot?.complete(content)
            }
            "save" -> save()
            "limit" -> failure("편집 내용은 256 KiB까지 지원합니다. 붙여넣을 내용을 줄이세요.")
            "cursor" -> if (path == activePath) { line = message.optInt("line", 1); column = message.optInt("column", 1) }
        }
    }
}
