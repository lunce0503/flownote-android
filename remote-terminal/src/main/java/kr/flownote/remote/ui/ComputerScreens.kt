package kr.flownote.remote.ui

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import com.google.zxing.client.android.Intents
import kr.flownote.remote.QrCaptureActivity
import kr.flownote.remote.RemoteViewModel
import kr.flownote.remote.connection.HostProfile
import kr.flownote.remote.connection.PairingInvitation
import kr.flownote.remote.security.SavedHost
import org.json.JSONObject

@Composable
fun ComputerHome(model: RemoteViewModel, onClose: () -> Unit) {
    var invitation by remember { mutableStateOf<PairingInvitation?>(null) }
    var invitationVersion by remember { mutableStateOf(-1) }
    LaunchedEffect(model.editorOpen) { if (!model.editorOpen) invitation = null }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        if (result.contents != null) {
            runCatching { PairingInvitation.parse(result.contents) }.onSuccess {
                model.addComputer(); invitation = it; invitationVersion = model.editorVersion
            }.onFailure { model.error = "등록 QR이 잘못되었거나 만료되었습니다. PC에서 새 QR을 생성하세요." }
        } else if (result.originalIntent?.getBooleanExtra(Intents.Scan.MISSING_CAMERA_PERMISSION, false) == true) {
            model.error = "카메라 권한이 필요합니다. 앱 설정에서 카메라 권한을 허용하세요."
        }
    }
    fun scan() { scanner.launch(ScanOptions().setDesiredBarcodeFormats(ScanOptions.QR_CODE).setCaptureActivity(QrCaptureActivity::class.java)
        .setPrompt("").setBeepEnabled(false).setBarcodeImageEnabled(false).setOrientationLocked(false)) }
    if (model.editorOpen) key(model.editorVersion) {
        ComputerEditor(model, invitation.takeIf { invitationVersion == model.editorVersion }, ::scan) { invitation = null; model.showComputers() }
    } else ComputerList(model, { invitation = null; model.addComputer() }, ::scan, onClose)
}

@Composable
private fun ComputerList(model: RemoteViewModel, add: () -> Unit, scan: () -> Unit, onClose: () -> Unit) {
    var deleting by remember { mutableStateOf<SavedHost?>(null) }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, "원격 터미널 닫기", action = onClose)
            Text("원격 터미널", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Tool(Icons.Default.QrCodeScanner, "QR 등록", action = scan)
            Tool(Icons.Default.Add, "PC 추가", action = add)
        }
        Text("컴퓨터 · ${model.catalog.hosts.size}", Modifier.padding(16.dp), style = MaterialTheme.typography.titleMedium)
        if (model.catalog.hosts.isEmpty()) {
            Text("저장된 컴퓨터 없음", Modifier.padding(horizontal = 16.dp), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        LazyColumn(Modifier.fillMaxSize().testTag("computer-list")) {
            items(model.catalog.hosts, key = { it.id }) { host ->
                Row(Modifier.fillMaxWidth().clickable { model.openComputer(host.id) }.padding(horizontal = 8.dp, vertical = 8.dp).testTag("computer-${host.id}"),
                    verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Default.Computer, null, Modifier.padding(8.dp).size(24.dp))
                    Column(Modifier.weight(1f).padding(horizontal = 8.dp)) {
                        Text(host.profile.name, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium)
                        Text(host.profile.endpoint, maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Tool(Icons.Default.Edit, "${host.profile.name} 편집") { model.editComputer(host.id) }
                    Tool(Icons.Default.DeleteOutline, "${host.profile.name} 삭제") { deleting = host }
                }
                HorizontalDivider()
            }
        }
    }
    deleting?.let { host -> Confirm("PC 삭제", "${host.profile.name}의 저장된 설정을 삭제합니다. PC의 기기 토큰은 별도로 폐기해야 합니다.", "삭제", { deleting = null }) {
        model.deleteComputer(host.id); deleting = null
    } }
}

@Composable
private fun ComputerEditor(model: RemoteViewModel, invitation: PairingInvitation?, scan: () -> Unit, back: () -> Unit) {
    val saved = model.editorProfile
    var name by remember { mutableStateOf(invitation?.name ?: saved?.name ?: "내 PC") }
    var endpoint by remember { mutableStateOf(invitation?.endpoint ?: saved?.endpoint ?: "") }
    var token by remember { mutableStateOf(saved?.token ?: "") }
    var fingerprint by remember { mutableStateOf(invitation?.fingerprint ?: saved?.fingerprint ?: "") }
    var verified by remember { mutableStateOf(saved != null && invitation == null) }
    var importOpen by remember { mutableStateOf(false) }
    var importText by remember { mutableStateOf("") }
    var replacing by remember { mutableStateOf<Boolean?>(null) }
    val busy = model.pairing
    val focus = LocalFocusManager.current
    BackHandler { if (!busy) back() }
    fun save(connect: Boolean) {
        if (invitation != null) model.pairComputer(invitation, name, connect)
        else model.saveComputer(HostProfile(name, endpoint.trim(), token.trim(), fingerprint), connect)
    }
    fun submit(connect: Boolean) {
        focus.clearFocus()
        val normalized = runCatching { HostProfile(name, endpoint.trim(), if (invitation == null) token.trim() else "pairing-validation-token", fingerprint).validated().endpoint }.getOrNull()
        if (model.editingId == null && model.catalog.hosts.any { it.profile.endpoint == normalized }) replacing = connect else save(connect)
    }
    Column(Modifier.fillMaxSize().padding(horizontal = 16.dp)) {
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp), verticalAlignment = Alignment.CenterVertically) {
            Tool(Icons.AutoMirrored.Filled.ArrowBack, "컴퓨터 목록", !busy, back)
            Text("원격 터미널", Modifier.weight(1f), style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Tool(Icons.Default.QrCodeScanner, "QR 등록", !busy, scan)
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(if (saved == null) "PC 추가" else "PC 편집", style = MaterialTheme.typography.titleMedium)
            OutlinedTextField(name, { name = it }, label = { Text("PC 이름") }, enabled = !busy, singleLine = true, modifier = Modifier.fillMaxWidth().testTag("host-name"))
            OutlinedTextField(endpoint, { endpoint = it; verified = false }, label = { Text("WSS 주소") }, readOnly = invitation != null, enabled = !busy,
                singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri), modifier = Modifier.fillMaxWidth().testTag("host-endpoint"))
            if (invitation == null) OutlinedTextField(token, { token = it; verified = false }, label = { Text("기기 토큰") }, enabled = !busy, singleLine = true,
                visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password), modifier = Modifier.fillMaxWidth().testTag("host-token"))
            OutlinedTextField(fingerprint, { fingerprint = it; verified = false }, label = { Text("인증서 SHA-256 지문") }, readOnly = invitation != null, enabled = !busy,
                minLines = 2, maxLines = 4, textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace, fontSize = 13.sp), modifier = Modifier.fillMaxWidth().testTag("host-fingerprint"))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(verified, { verified = it; focus.clearFocus() }, enabled = !busy, modifier = Modifier.testTag("trust-confirm"))
                Text("PC에 표시된 인증서 지문과 일치함", Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            }
            if (invitation == null) TextButton(onClick = { importOpen = true }, enabled = !busy, modifier = Modifier.align(Alignment.CenterHorizontally)) {
                Icon(Icons.Default.ContentPaste, null); Spacer(Modifier.width(8.dp)); Text("Host 등록 JSON 가져오기")
            }
            Spacer(Modifier.height(8.dp))
        }
        Box(Modifier.fillMaxWidth().height(4.dp)) { if (busy) LinearProgressIndicator(Modifier.fillMaxWidth()) }
        val enabled = !busy && verified && name.isNotBlank() && endpoint.isNotBlank() && (invitation != null || token.isNotBlank())
        Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            OutlinedIconButton(onClick = { submit(false) }, enabled = enabled, modifier = Modifier.size(48.dp).testTag("save-computer")) {
                Icon(Icons.Default.Save, "저장")
            }
            Button(onClick = { submit(true) }, enabled = enabled, modifier = Modifier.weight(1f).height(48.dp).testTag("connect")) {
                Icon(Icons.Default.Power, null); Spacer(Modifier.width(8.dp)); Text(if (busy) "등록 중" else "저장하고 연결")
            }
        }
    }
    if (importOpen) AlertDialog(onDismissRequest = { importOpen = false; importText = "" }, title = { Text("Host 등록 정보") },
        text = { OutlinedTextField(importText, { importText = it }, label = { Text("등록 JSON") }, minLines = 4, maxLines = 8, visualTransformation = PasswordVisualTransformation()) },
        dismissButton = { TextButton(onClick = { importOpen = false; importText = "" }) { Text("취소") } },
        confirmButton = { TextButton(onClick = {
            runCatching { require(importText.length <= 8192); HostProfile.fromJson(JSONObject(importText)) }.onSuccess {
                name = it.name; endpoint = it.endpoint; token = it.token; fingerprint = it.fingerprint; verified = false
                importOpen = false; importText = ""
            }.onFailure { model.error = "등록 JSON의 주소, 토큰과 인증서 지문을 확인하세요." }
        }) { Text("가져오기") } })
    replacing?.let { connect -> Confirm("저장된 PC 변경", "같은 주소의 PC 설정을 새 등록 정보로 변경합니다.", "변경", { replacing = null }) {
        replacing = null; save(connect)
    } }
}
