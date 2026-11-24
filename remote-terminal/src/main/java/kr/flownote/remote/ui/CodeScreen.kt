package kr.flownote.remote.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import kr.flownote.remote.RemoteViewModel
import kr.flownote.remote.terminal.TerminalView
import kr.flownote.remote.workspace.*

@Composable
internal fun ColumnScope.CodeScreen(model: RemoteViewModel, keyboardCompact: Boolean) {
    val workspace = model.workspace
    val context = LocalContext.current
    var files by remember { mutableStateOf(true) }
    var closing by remember { mutableStateOf<CodeDocument?>(null) }
    var reload by remember { mutableStateOf(false) }
    var leaving by remember { mutableStateOf(false) }
    LaunchedEffect(model.codeOpen) { if (model.codeOpen && !workspace.loaded) workspace.list("") }
    BackHandler(enabled = model.codeOpen) { model.codeOpen = false }
    if (!keyboardCompact) Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
        Tool(Icons.AutoMirrored.Filled.ArrowBack, "컴퓨터 목록") { leaving = true }
        Text(model.profile?.name ?: "코드", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
        Tool(Icons.Default.FolderOpen, "파일 탐색", !workspace.busy) { files = !files }
    }
    if (workspace.documents.isNotEmpty() && !keyboardCompact) Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).height(44.dp)) {
        workspace.documents.forEach { document ->
            Row(Modifier.widthIn(max = 240.dp).fillMaxHeight().clickable(enabled = !workspace.busy) { workspace.open(document.path); files = false }
                .padding(start = 12.dp).testTag("tab-${document.path}"), verticalAlignment = Alignment.CenterVertically) {
                Text(document.name + if (document.dirty) " *" else "", Modifier.widthIn(max = 160.dp), maxLines = 1,
                    overflow = TextOverflow.Ellipsis, color = if (document.path == workspace.activePath) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                Tool(Icons.Default.Close, "${document.name} 닫기", !workspace.busy) { if (document.dirty) closing = document else workspace.close(document.path) }
            }
        }
    }
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val wide = maxWidth >= 720.dp
        Row(Modifier.fillMaxSize()) {
            if (wide || files || workspace.active == null) FileBrowser(workspace, Modifier.fillMaxHeight().then(if (wide) Modifier.width(260.dp) else Modifier.fillMaxWidth())) { path ->
                workspace.open(path); files = false
            }
            if (workspace.active != null) Column(Modifier.then(if (wide) Modifier.weight(1f) else if (files) Modifier.width(0.dp) else Modifier.fillMaxWidth()).fillMaxHeight()) {
                Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
                    Row(Modifier.weight(1f).horizontalScroll(rememberScrollState())) {
                        if (keyboardCompact) {
                            Tool(Icons.Default.Terminal, "터미널로 전환") { model.codeOpen = false }
                            Tool(Icons.Default.FolderOpen, "파일 탐색", !workspace.busy) { files = true }
                        }
                        Tool(Icons.Default.Undo, "실행 취소", !workspace.busy) { workspace.command("undo") }
                        Tool(Icons.Default.Redo, "다시 실행", !workspace.busy) { workspace.command("redo") }
                        Tool(Icons.Default.Search, "찾기 및 바꾸기") { workspace.command("search") }
                        Tool(Icons.Default.FormatListNumbered, "줄로 이동") { workspace.command("goto") }
                        Tool(Icons.Default.FormatIndentIncrease, "들여쓰기") { workspace.command("indent") }
                        Tool(Icons.Default.FormatIndentDecrease, "내어쓰기") { workspace.command("outdent") }
                        Tool(Icons.Default.WrapText, if (workspace.wrap) "자동 줄바꿈 끄기" else "자동 줄바꿈 켜기") { workspace.toggleWrap() }
                        Tool(Icons.Default.ContentCopy, "코드 복사") {
                            (context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("code", workspace.active?.content ?: ""))
                        }
                        Tool(Icons.Default.Refresh, "파일 다시 불러오기", !workspace.busy) { if (workspace.active?.dirty == true) reload = true else workspace.reload() }
                    }
                    if (workspace.busy) Box(Modifier.size(48.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp) }
                    else Tool(Icons.Default.Save, "코드 저장", workspace.active?.dirty == true) { workspace.save() }
                }
                if (remember { TerminalView.supported() }) AndroidView(
                    factory = { ctx -> CodeEditorView(ctx, workspace::onBridge).also { workspace.renderer = it.renderer } },
                    update = { view ->
                        val visible = model.codeOpen && (wide || !files)
                        view.visibility = if (visible) View.VISIBLE else View.INVISIBLE
                        if (!visible) view.clearFocus()
                    },
                    onRelease = { view -> if (workspace.renderer === view.renderer) workspace.editorReleased(); view.release() },
                    modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("code-editor"))
                else Text("Android System WebView를 업데이트하세요.", Modifier.padding(16.dp))
                if (!keyboardCompact) Row(Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text(workspace.activePath ?: "", Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall)
                    Text("Ln ${workspace.line}, Col ${workspace.column}", style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                }
            }
        }
    }
    closing?.let { document -> Confirm("변경 버리기", "${document.name}의 저장하지 않은 변경을 버립니다.", "버리기", { closing = null }) { workspace.close(document.path); closing = null } }
    if (reload) Confirm("파일 다시 불러오기", "저장하지 않은 변경을 버리고 PC의 파일을 불러옵니다.", "불러오기", { reload = false }) { workspace.reload(); reload = false }
    if (leaving) Confirm("컴퓨터 목록으로 이동", if (workspace.dirty) "저장하지 않은 코드 변경을 버리고 연결을 해제합니다." else "현재 PC 연결을 해제합니다.", "이동", { leaving = false }) { model.editProfile(); leaving = false }
}

@Composable
private fun FileBrowser(workspace: WorkspaceModel, modifier: Modifier, open: (String) -> Unit) {
    var filter by remember(workspace.folder) { mutableStateOf("") }
    Column(modifier.testTag("file-browser")) {
        Row(Modifier.fillMaxWidth().height(48.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(Icons.Default.Home, "프로젝트 루트", !workspace.busy) { workspace.list("") }
            Tool(Icons.Default.ArrowUpward, "상위 폴더", !workspace.busy && workspace.folder.isNotEmpty()) { workspace.list(workspace.folder.substringBeforeLast('/', "")) }
            Text(workspace.folder.ifEmpty { workspace.rootName }, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall)
            Tool(Icons.Default.Refresh, "폴더 새로고침", !workspace.busy) { workspace.list() }
        }
        OutlinedTextField(filter, { filter = it }, singleLine = true, label = { Text("파일 필터") },
            leadingIcon = { Icon(Icons.Default.Search, null) }, modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp).testTag("file-filter"))
        Box(Modifier.fillMaxWidth().height(4.dp)) { if (workspace.busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        LazyColumn(Modifier.weight(1f).fillMaxWidth()) {
            items(workspace.entries.filter { it.name.contains(filter, ignoreCase = true) }, key = { it.path }) { entry ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !workspace.busy) {
                    if (entry.directory) workspace.list(entry.path) else open(entry.path)
                }.padding(horizontal = 12.dp, vertical = 8.dp).testTag("file-${entry.path}"), verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (entry.directory) Icons.Default.Folder else Icons.Default.InsertDriveFile, null, Modifier.size(20.dp), tint = if (entry.directory) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.width(12.dp)); Text(entry.name, Modifier.weight(1f), maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodyMedium)
                    if (entry.directory) Icon(Icons.Default.ChevronRight, null, Modifier.size(18.dp))
                }
            }
            if (workspace.loaded && workspace.entries.isEmpty()) item { Text("빈 폴더", Modifier.padding(16.dp), style = MaterialTheme.typography.bodySmall) }
            if (workspace.nextOffset != null) item { TextButton(onClick = { workspace.list(more = true) }, enabled = !workspace.busy, modifier = Modifier.fillMaxWidth()) { Text("더 보기") } }
        }
    }
}
