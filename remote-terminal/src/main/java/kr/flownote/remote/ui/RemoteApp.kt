package kr.flownote.remote.ui

import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.zIndex
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import kr.flownote.remote.RemoteViewModel
import kr.flownote.remote.connection.HostProfile
import kr.flownote.remote.connection.Phase
import kr.flownote.remote.terminal.TerminalView
import org.json.JSONObject

@Composable
fun RemoteApp(model: RemoteViewModel, onClose: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        val configuration = LocalConfiguration.current
        val compact = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE || configuration.screenHeightDp < 600
        Column(Modifier.fillMaxSize().safeDrawingPadding().imePadding()) {
            if (model.screenTerminal) RemoteSessionScreen(model, compact) else ComputerHome(model, onClose)
        }
    }
    model.error?.let { message ->
        AlertDialog(onDismissRequest = { model.error = null }, title = { Text("확인") }, text = { Text(message) },
            confirmButton = { TextButton(onClick = { model.error = null }) { Text("닫기") } })
    }
}

@Composable
private fun ColumnScope.RemoteSessionScreen(model: RemoteViewModel, compact: Boolean) {
    val imeVisible = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    if (!(compact && imeVisible)) TabRow(selectedTabIndex = if (model.codeOpen) 1 else 0) {
        listOf("터미널", "코드").forEachIndexed { index, label ->
            Tab(selected = model.codeOpen == (index == 1), onClick = {
                focus.clearFocus(); keyboard?.hide(); model.codeOpen = index == 1
            }, modifier = Modifier.height(44.dp).testTag(if (index == 0) "mode-terminal" else "mode-code")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(if (index == 0) Icons.Default.Terminal else Icons.Default.Code, null, Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp)); Text(label)
                }
            }
        }
    }
    Box(Modifier.weight(1f).fillMaxWidth()) {
        val terminal = if (model.codeOpen) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
        Column(Modifier.fillMaxSize().then(terminal).zIndex(if (model.codeOpen) 0f else 1f)) { TerminalScreen(model, compact) }
        val code = if (!model.codeOpen) Modifier.alpha(0f).clearAndSetSemantics {} else Modifier
        Column(Modifier.fillMaxSize().then(code).zIndex(if (model.codeOpen) 1f else 0f)) { CodeScreen(model, compact && imeVisible) }
    }
}

@Composable
private fun ColumnScope.TerminalScreen(model: RemoteViewModel, compact: Boolean) {
    val context = LocalContext.current
    var closeDialog by remember { mutableStateOf(false) }
    var editDialog by remember { mutableStateOf(false) }
    var newDialog by remember { mutableStateOf(false) }
    var pasteText by remember { mutableStateOf<String?>(null) }
    var composeInput by remember { mutableStateOf(false) }
    var inputText by remember { mutableStateOf("") }
    var settings by remember { mutableStateOf(false) }
    var fontSize by remember { mutableIntStateOf(14) }
    val connected = model.state.phase == Phase.CONNECTED
    BackHandler(enabled = !model.codeOpen) { editDialog = true }
    LaunchedEffect(model.pasteRequested) {
        if (model.pasteRequested) {
            model.pasteRequested = false
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val text = clipboard.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString()
            if (text != null && text.length <= 262144) pasteText = text
            else model.error = "클립보드가 비어 있거나 붙여넣기 크기(256K자)를 초과했습니다."
        }
    }
    if (compact) {
        Text("${model.profile?.name ?: "터미널"} · ${model.state.detail}", maxLines = 1,
            overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.labelSmall,
            modifier = Modifier.fillMaxWidth().height(24.dp).padding(horizontal = 8.dp, vertical = 4.dp).testTag("connection-status"))
    } else Row(Modifier.fillMaxWidth().heightIn(min = 56.dp), verticalAlignment = Alignment.CenterVertically) {
        Tool(Icons.AutoMirrored.Filled.ArrowBack, "컴퓨터 목록") { editDialog = true }
        Column(Modifier.weight(1f)) {
            Text(model.profile?.name ?: "터미널", maxLines = 1, style = MaterialTheme.typography.titleMedium)
            Text(model.state.detail, style = MaterialTheme.typography.labelSmall,
                color = if (connected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.secondary,
                modifier = Modifier.testTag("connection-status"))
        }
        Tool(Icons.Default.Settings, "터미널 설정") { settings = !settings }
        Tool(Icons.Default.Close, "세션 종료", connected || model.state.phase == Phase.RECOVERY) { closeDialog = true }
    }
    if (settings && !compact) Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text("글자 크기", Modifier.padding(start = 16.dp))
        Slider(fontSize.toFloat(), { fontSize = it.toInt(); model.font(fontSize) }, valueRange = 10f..24f, steps = 13, modifier = Modifier.weight(1f))
        Text("$fontSize", Modifier.padding(end = 16.dp))
    }
    if (!connected) Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically) {
        when (model.state.phase) {
            Phase.RETRYING, Phase.CONNECTING, Phase.AUTHENTICATING -> TextButton(onClick = model::disconnect) { Text("연결 취소") }
            Phase.RECOVERY -> TextButton(onClick = { newDialog = true }) { Text("새 세션") }
            else -> TextButton(onClick = model::reconnect) { Icon(Icons.Default.Refresh, null); Text("다시 연결") }
        }
    }
    if (remember { TerminalView.supported() }) {
        AndroidView(factory = { ctx -> TerminalView(ctx, model::onBridge).also { view -> model.renderer = view.renderer } },
            update = { view ->
                view.visibility = if (model.codeOpen) android.view.View.INVISIBLE else android.view.View.VISIBLE
                if (model.codeOpen) view.clearFocus()
            },
            onRelease = { view ->
                if (model.renderer === view.renderer) model.renderer = null
                view.release()
            },
            modifier = Modifier.weight(1f).fillMaxWidth().clipToBounds().testTag("terminal"))
    } else {
        Text("Android System WebView를 업데이트한 뒤 앱을 다시 실행하세요.", Modifier.weight(1f).padding(24.dp))
    }
    fun submit() { if (connected && inputText.isNotEmpty() && model.input(inputText + "\r")) inputText = "" }
    if (composeInput && !compact) {
        CommandInput(inputText, { inputText = it }, connected, ::submit, Modifier.fillMaxWidth().padding(horizontal = 8.dp))
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
      if (composeInput && compact) {
          CommandInput(inputText, { inputText = it }, connected, ::submit, Modifier.weight(1f).padding(start = 4.dp))
      }
      Row((if (composeInput && compact) Modifier.weight(1f) else Modifier.fillMaxWidth()).horizontalScroll(rememberScrollState()), verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { model.input("\u001b") }, enabled = connected) { Text("Esc") }
        TextButton(onClick = { model.input("\t") }, enabled = connected) { Text("Tab") }
        TextButton(onClick = { model.modifier("ctrl") }, enabled = connected) { Text("Ctrl", color = if (model.ctrl) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) }
        TextButton(onClick = { model.modifier("alt") }, enabled = connected) { Text("Alt", color = if (model.alt) MaterialTheme.colorScheme.secondary else MaterialTheme.colorScheme.primary) }
        Tool(Icons.Default.Stop, "Ctrl+C", connected) { model.input("\u0003") }
        Tool(Icons.Default.ArrowUpward, "위쪽 방향키", connected) { model.input("\u001b[A") }
        Tool(Icons.Default.ArrowDownward, "아래쪽 방향키", connected) { model.input("\u001b[B") }
        Tool(Icons.Default.KeyboardArrowLeft, "왼쪽 방향키", connected) { model.input("\u001b[D") }
        Tool(Icons.Default.KeyboardArrowRight, "오른쪽 방향키", connected) { model.input("\u001b[C") }
        Tool(Icons.Default.ContentPaste, "붙여넣기", connected) { model.pasteRequested = true }
        Tool(Icons.Default.Edit, "명령 입력창", connected) { composeInput = !composeInput }
        Tool(Icons.Default.Keyboard, "키보드", connected) { model.command("focus") }
        Tool(Icons.Default.LinkOff, "연결 해제") { model.disconnect() }
        if (compact) {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, "컴퓨터 목록") { editDialog = true }
            Tool(Icons.Default.Settings, "터미널 설정") { settings = !settings }
            Tool(Icons.Default.Close, "세션 종료", connected || model.state.phase == Phase.RECOVERY) { closeDialog = true }
        }
      }
    }
    if (settings && compact) AlertDialog(onDismissRequest = { settings = false }, title = { Text("글자 크기 · $fontSize") },
        text = { Slider(fontSize.toFloat(), { fontSize = it.toInt(); model.font(fontSize) }, valueRange = 10f..24f, steps = 13) },
        confirmButton = { TextButton(onClick = { settings = false }) { Text("닫기") } })
    if (closeDialog) Confirm("세션 종료", "PC의 현재 터미널을 종료합니다.", "종료", { closeDialog = false }) { model.closeSession(); closeDialog = false }
    if (newDialog) Confirm("새 세션", "기존 터미널을 종료하고 새 터미널을 엽니다.", "시작", { newDialog = false }) { model.newSession(); newDialog = false }
    if (editDialog) Confirm("컴퓨터 목록으로 이동", if (model.workspace.dirty) "저장하지 않은 코드 변경을 버리고 연결을 해제합니다." else "연결을 해제합니다. 돌아오면 새 터미널을 선택해야 합니다.", "이동", { editDialog = false }) { model.editProfile(); editDialog = false }
    pasteText?.let { content -> AlertDialog(onDismissRequest = { pasteText = null }, title = { Text("붙여넣기 확인") },
        text = { Column(Modifier.heightIn(max = 300.dp).verticalScroll(rememberScrollState())) {
            if (content.contains('\n') || content.contains('\r')) Text("여러 줄 입력은 명령을 즉시 실행할 수 있습니다.", color = MaterialTheme.colorScheme.secondary)
            Text(content.take(8000), fontFamily = FontFamily.Monospace, fontSize = 12.sp)
            if (content.length > 8000) Text("미리보기 생략 · 전체 ${content.length}자")
        } }, dismissButton = { TextButton(onClick = { pasteText = null }) { Text("취소") } },
        confirmButton = { TextButton(onClick = { model.paste(content); pasteText = null }) { Text("붙여넣기") } }) }
}

@Composable
private fun CommandInput(value: String, change: (String) -> Unit, enabled: Boolean, submit: () -> Unit, modifier: Modifier) {
    Row(modifier, verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(value, change, placeholder = { Text("명령 입력") }, singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send), keyboardActions = KeyboardActions(onSend = { submit() }),
            modifier = Modifier.weight(1f).height(56.dp).testTag("command-input"))
        Tool(Icons.Default.Send, "명령 전송", enabled) { submit() }
    }
}

@Composable
internal fun Confirm(title: String, message: String, action: String, dismiss: () -> Unit, confirm: () -> Unit) {
    AlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = { Text(message) },
        dismissButton = { TextButton(onClick = dismiss) { Text("취소") } },
        confirmButton = { TextButton(onClick = confirm) { Text(action) } })
}

@Composable
@OptIn(ExperimentalMaterial3Api::class)
internal fun Tool(icon: ImageVector, label: String, enabled: Boolean = true, action: () -> Unit) {
    TooltipBox(positionProvider = TooltipDefaults.rememberPlainTooltipPositionProvider(),
        tooltip = { PlainTooltip { Text(label) } }, state = rememberTooltipState()) {
        IconButton(onClick = action, enabled = enabled, modifier = Modifier.size(48.dp)) { Icon(icon, contentDescription = label) }
    }
}
