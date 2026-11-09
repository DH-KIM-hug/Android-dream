package com.androiddream.callrelay.ui

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.PowerManager
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import com.androiddream.callrelay.data.ChannelId
import com.androiddream.callrelay.data.History
import com.androiddream.callrelay.data.Settings
import com.androiddream.callrelay.data.SmtpSecurity
import com.androiddream.callrelay.data.TranscriptMode
import com.androiddream.callrelay.relay.KakaoAuth
import com.androiddream.callrelay.scan.DocumentScanner
import com.androiddream.callrelay.work.RelayEngine
import com.androiddream.callrelay.work.Scheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.provider.Settings as SysSettings

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { AppTheme { MainScreen() } }
    }
}

@Composable
fun AppTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    val ctx = LocalContext.current
    val scheme = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(ctx)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(ctx)
        dark -> darkColorScheme()
        else -> lightColorScheme()
    }
    MaterialTheme(colorScheme = scheme, content = content)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val ctx = LocalContext.current
    val s = remember { Settings(ctx) }
    val history = remember { History(ctx) }
    var refresh by remember { mutableIntStateOf(0) }
    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { refresh++ }

    Scaffold(topBar = { TopAppBar(title = { Text("통화녹음 전달") }) }) { pad ->
        Column(
            Modifier.fillMaxSize().padding(pad).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            key(refresh) { StatusSection(s, onChanged = { refresh++ }) }
            key(refresh) { FolderSection(s, history, onChanged = { refresh++ }) }
            TranscriptSection(s)
            Text("전송 채널", style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(top = 8.dp))
            EmailCard(s)
            ChannelCard(s, ChannelId.TEAMS, "Teams 채널 → ⋯ → 워크플로 → '웹후크 요청이 수신되면 채널에 게시' 로 만든 URL") {
                PrefText("Teams 웹훅 URL", s.teamsWebhook) { s.teamsWebhook = it }
            }
            key(refresh) { KakaoCard(s, onChanged = { refresh++ }) }
            ChannelCard(s, ChannelId.SLACK, "Slack 앱 → Incoming Webhooks 에서 발급한 URL") {
                PrefText("Slack 웹훅 URL", s.slackWebhook) { s.slackWebhook = it }
            }
            ChannelCard(s, ChannelId.TELEGRAM, "@BotFather 로 봇 생성 후 토큰, 받을 대화의 chat_id") {
                PrefText("봇 토큰", s.telegramToken, password = true) { s.telegramToken = it }
                PrefText("chat_id", s.telegramChatId) { s.telegramChatId = it }
                PrefSwitch("녹음 파일도 보내기 (50MB 이하)", s.telegramSendAudio) { s.telegramSendAudio = it }
            }
            ChannelCard(s, ChannelId.DISCORD, "채널 설정 → 연동 → 웹후크 URL") {
                PrefText("Discord 웹훅 URL", s.discordWebhook) { s.discordWebhook = it }
                PrefSwitch("녹음 파일도 보내기 (25MB 이하)", s.discordAttachAudio) { s.discordAttachAudio = it }
            }
            ChannelCard(s, ChannelId.WEBHOOK, "JSON(contact, datetime, transcript, text …)을 POST 합니다. Make·Zapier·n8n·Power Automate 로 네이버웍스·잔디·구글시트 등 어디로든 연결") {
                PrefText("URL", s.customWebhook) { s.customWebhook = it }
                PrefText("Authorization 헤더 (선택)", s.customWebhookAuth, password = true) { s.customWebhookAuth = it }
            }
            key(refresh) { LogSection(history, onCleared = { refresh++ }) }
            Spacer(Modifier.padding(24.dp))
        }
    }
}

// ───────────────────────── 상태 / 권한 ─────────────────────────
@Composable
private fun StatusSection(s: Settings, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { onChanged() }
    val phoneOk = granted(ctx, Manifest.permission.READ_PHONE_STATE)
    val notiOk = Build.VERSION.SDK_INT < 33 || granted(ctx, Manifest.permission.POST_NOTIFICATIONS)
    val batteryOk = ctx.getSystemService(PowerManager::class.java).isIgnoringBatteryOptimizations(ctx.packageName)

    SectionCard("자동 전달") {
        PrefSwitch("통화 녹음이 생기면 자동으로 보내기", s.autoEnabled) {
            s.autoEnabled = it
            Scheduler.ensureScheduled(ctx)
        }
        CheckRow("통화 종료 감지 (전화 상태 권한)", phoneOk)
        CheckRow("알림 권한", notiOk)
        CheckRow("배터리 최적화 제외 (삼성 절전에 의한 중단 방지)", batteryOk)
        FlowButtons {
            if (!phoneOk || !notiOk) {
                Button(onClick = {
                    val perms = buildList {
                        add(Manifest.permission.READ_PHONE_STATE)
                        if (Build.VERSION.SDK_INT >= 33) add(Manifest.permission.POST_NOTIFICATIONS)
                    }
                    permLauncher.launch(perms.toTypedArray())
                }) { Text("권한 허용") }
            }
            if (!batteryOk) {
                OutlinedButton(onClick = {
                    runCatching {
                        ctx.startActivity(
                            Intent(SysSettings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:${ctx.packageName}"))
                        )
                    }
                }) { Text("배터리 최적화 제외") }
            }
            OutlinedButton(onClick = {
                Scheduler.scanNow(ctx)
                Toast.makeText(ctx, "녹음 폴더를 검사합니다", Toast.LENGTH_SHORT).show()
            }) { Text("지금 검사") }
        }
        Hint("삼성 전화 앱 → 설정 → 통화 녹음 → '자동으로 통화 녹음' 을 켜 두세요.")
    }
}

// ───────────────────────── 녹음 폴더 ─────────────────────────
@Composable
private fun FolderSection(s: Settings, history: History, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val pickRec = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) {
            persist(ctx, uri)
            if (s.recordingTreeUri == null || s.baselineMillis == 0L) s.baselineMillis = System.currentTimeMillis()
            s.recordingTreeUri = uri
            Scheduler.ensureScheduled(ctx)
            onChanged()
        }
    }
    val pickTxt = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) { persist(ctx, uri); s.transcriptTreeUri = uri; onChanged() }
    }
    val callDir = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Recordings/Call")
    val recOk = DocumentScanner.hasPermission(ctx, s.recordingTreeUri)

    SectionCard("통화 녹음 폴더") {
        CheckRow(s.recordingTreeUri?.let { "녹음 폴더: ${pretty(it)}" } ?: "녹음 폴더를 선택하세요", recOk)
        Hint("보통 내장 메모리 → Recordings → Call 입니다. (구형 기종: Call 폴더)")
        Text(
            "텍스트 폴더: " + (s.transcriptTreeUri?.let { pretty(it) } ?: "녹음 폴더와 같음"),
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowButtons {
            Button(onClick = { pickRec.launch(callDir) }) { Text("녹음 폴더 선택") }
            OutlinedButton(onClick = { pickTxt.launch(callDir) }) { Text("텍스트 폴더 선택") }
            if (s.transcriptTreeUri != null) TextButton(onClick = { s.transcriptTreeUri = null; onChanged() }) { Text("텍스트 폴더 해제") }
        }
        Text(
            "기준 시각: " + if (s.baselineMillis == 0L) "전체" else fmt(s.baselineMillis) + " 이후 녹음만 전송",
            style = MaterialTheme.typography.bodyMedium,
        )
        FlowButtons {
            OutlinedButton(onClick = {
                s.baselineMillis = System.currentTimeMillis() - 24 * 3600_000L
                Scheduler.scanNow(ctx); onChanged()
            }) { Text("최근 24시간 것도 보내기") }
            TextButton(onClick = {
                history.clearStates()
                Toast.makeText(ctx, "처리 기록을 초기화했습니다 (기준 시각 이후 녹음 재전송)", Toast.LENGTH_LONG).show()
            }) { Text("처리 기록 초기화") }
        }
        PrefText("특정 상대만 (파일명에 포함된 이름/번호, 쉼표 구분 · 비우면 전체)", s.contactFilter) { s.contactFilter = it }
    }
}

// ───────────────────────── 텍스트 변환 ─────────────────────────
@Composable
private fun TranscriptSection(s: Settings) {
    var mode by remember { mutableStateOf(s.transcriptMode) }
    SectionCard("통화 텍스트(전사)") {
        Hint(
            "갤럭시 통화 녹음의 텍스트 변환 결과가 .txt 파일로 저장되면 같은 이름의 파일을 찾아 함께 보냅니다. " +
                "텍스트가 앱 안에만 있는 경우, 해당 화면에서 '공유 → 통화녹음 전달' 을 누르면 바로 전송됩니다. " +
                "완전 자동을 원하면 아래 음성인식 API 를 설정하세요."
        )
        TranscriptMode.entries.forEach { m ->
            Row(
                Modifier.fillMaxWidth().clickable { mode = m; s.transcriptMode = m },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = mode == m, onClick = { mode = m; s.transcriptMode = m })
                Text(m.label)
            }
        }
        PrefText("텍스트 파일 대기 시간(분)", s.transcriptWaitMinutes.toString(), keyboard = KeyboardType.Number) {
            it.toIntOrNull()?.let { v -> s.transcriptWaitMinutes = v.coerceIn(0, 120) }
        }
        HorizontalDivider()
        Text("음성인식 API (OpenAI 호환)", fontWeight = FontWeight.SemiBold)
        PrefText("API 주소", s.sttEndpoint) { s.sttEndpoint = it }
        PrefText("API 키", s.sttApiKey, password = true) { s.sttApiKey = it }
        PrefText("모델 (whisper-1, gpt-4o-transcribe 등)", s.sttModel) { s.sttModel = it }
        PrefText("언어 코드", s.sttLanguage) { s.sttLanguage = it }
    }
}

// ───────────────────────── 채널 카드 ─────────────────────────
@Composable
private fun EmailCard(s: Settings) {
    var rev by remember { mutableIntStateOf(0) }
    ChannelCard(s, ChannelId.EMAIL, "Gmail 은 2단계 인증 후 '앱 비밀번호', 네이버는 메일 설정에서 'IMAP/SMTP 사용' 후 비밀번호(또는 앱 비밀번호)를 입력하세요.") {
        FlowButtons {
            listOf(
                Triple("Gmail", "smtp.gmail.com", 465 to SmtpSecurity.SSL),
                Triple("Naver", "smtp.naver.com", 465 to SmtpSecurity.SSL),
                Triple("Daum", "smtp.daum.net", 465 to SmtpSecurity.SSL),
                Triple("Outlook", "smtp.office365.com", 587 to SmtpSecurity.STARTTLS),
            ).forEach { (label, host, ps) ->
                OutlinedButton(onClick = {
                    s.smtpHost = host; s.smtpPort = ps.first; s.smtpSecurity = ps.second; rev++
                }) { Text(label) }
            }
        }
        key(rev) {
            PrefText("SMTP 서버", s.smtpHost) { s.smtpHost = it }
            PrefText("포트", s.smtpPort.toString(), keyboard = KeyboardType.Number) { v -> v.toIntOrNull()?.let { s.smtpPort = it } }
            var sec by remember { mutableStateOf(s.smtpSecurity) }
            Row(verticalAlignment = Alignment.CenterVertically) {
                SmtpSecurity.entries.forEach { m ->
                    RadioButton(selected = sec == m, onClick = { sec = m; s.smtpSecurity = m })
                    Text(m.name, Modifier.padding(end = 8.dp))
                }
            }
        }
        PrefText("계정 (아이디/이메일)", s.smtpUser, keyboard = KeyboardType.Email) { s.smtpUser = it }
        PrefText("비밀번호 / 앱 비밀번호", s.smtpPassword, password = true) { s.smtpPassword = it }
        PrefText("보내는 주소 (비우면 계정)", s.mailFrom, keyboard = KeyboardType.Email) { s.mailFrom = it }
        PrefText("받는 주소 (여러 개는 쉼표)", s.mailTo, keyboard = KeyboardType.Email) { s.mailTo = it }
        PrefSwitch("녹음 파일 첨부 (20MB 이하)", s.mailAttachAudio) { s.mailAttachAudio = it }
    }
}

@Composable
private fun KakaoCard(s: Settings, onChanged: () -> Unit) {
    val ctx = LocalContext.current
    val login = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { onChanged() }
    ChannelCard(
        s, ChannelId.KAKAO,
        "developers.kakao.com 에서 앱 생성 → 카카오 로그인 활성화 → Redirect URI 에 아래 주소 등록 → 동의항목 '카카오톡 메시지 전송' 설정 → REST API 키 입력 후 로그인. " +
            "카카오 정책상 본인 카톡(나에게 보내기)으로만 자동 전송되며, 1건당 200자라 나눠 보냅니다.",
    ) {
        PrefText("REST API 키", s.kakaoRestKey) { s.kakaoRestKey = it }
        PrefText("Client Secret (사용 시)", s.kakaoClientSecret, password = true) { s.kakaoClientSecret = it }
        PrefText("Redirect URI", s.kakaoRedirectUri) { s.kakaoRedirectUri = it }
        PrefText("최대 분할 건수", s.kakaoMaxParts.toString(), keyboard = KeyboardType.Number) {
            it.toIntOrNull()?.let { v -> s.kakaoMaxParts = v.coerceIn(1, 30) }
        }
        val loggedIn = s.kakaoRefreshToken.isNotBlank()
        CheckRow(if (loggedIn) "카카오 로그인됨" else "로그인 필요", loggedIn)
        FlowButtons {
            Button(onClick = { login.launch(Intent(ctx, KakaoLoginActivity::class.java)) }) {
                Text(if (loggedIn) "다시 로그인" else "카카오 로그인")
            }
            if (loggedIn) TextButton(onClick = { KakaoAuth.logout(s); onChanged() }) { Text("로그아웃") }
        }
    }
}

@Composable
private fun ChannelCard(s: Settings, id: ChannelId, hint: String, content: @Composable ColumnScope.() -> Unit) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var on by remember { mutableStateOf(s.isEnabled(id)) }
    var expanded by remember { mutableStateOf(false) }
    var testing by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth().clickable { expanded = !expanded }, verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(id.label, style = MaterialTheme.typography.titleSmall)
                    Text(if (on) "사용 중" else "꺼짐", style = MaterialTheme.typography.bodySmall)
                }
                Switch(checked = on, onCheckedChange = { on = it; s.setEnabled(id, it); if (it) expanded = true })
            }
            if (expanded) {
                Hint(hint)
                content()
                OutlinedButton(enabled = !testing, onClick = {
                    testing = true
                    scope.launch {
                        val r = withContext(Dispatchers.IO) { RelayEngine.test(ctx, id) }
                        testing = false
                        Toast.makeText(
                            ctx,
                            r.fold({ "${id.label} 테스트 전송 성공" }, { "실패: ${it.message}" }),
                            Toast.LENGTH_LONG,
                        ).show()
                    }
                }) { Text(if (testing) "보내는 중…" else "테스트 전송") }
            }
        }
    }
}

// ───────────────────────── 기록 ─────────────────────────
@Composable
private fun LogSection(history: History, onCleared: () -> Unit) {
    val ctx = LocalContext.current
    var logs by remember { mutableStateOf(history.logs()) }
    val scope = rememberCoroutineScope()
    SectionCard("전송 기록") {
        FlowButtons {
            OutlinedButton(onClick = {
                Scheduler.scanNow(ctx)
                scope.launch { delay(4000); logs = history.logs() }
            }) { Text("검사 후 새로고침") }
            TextButton(onClick = { history.clearLogs(); onCleared() }) { Text("기록 지우기") }
        }
        if (logs.isEmpty()) Hint("아직 전송 기록이 없습니다.")
        logs.take(50).forEach { e ->
            Column(Modifier.fillMaxWidth().padding(vertical = 4.dp)) {
                Text(
                    (if (e.ok) "✅ " else "⚠️ ") + e.title,
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium,
                )
                Text(fmt(e.time) + " · " + e.detail, style = MaterialTheme.typography.bodySmall)
            }
            HorizontalDivider()
        }
    }
}

// ───────────────────────── 공용 컴포넌트 ─────────────────────────
@Composable
private fun SectionCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowButtons(content: @Composable () -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) { content() }
}

@Composable
private fun PrefText(
    label: String,
    initial: String,
    password: Boolean = false,
    keyboard: KeyboardType = KeyboardType.Text,
    onSave: (String) -> Unit,
) {
    var v by remember { mutableStateOf(initial) }
    OutlinedTextField(
        value = v,
        onValueChange = { v = it; onSave(it) },
        label = { Text(label) },
        singleLine = true,
        visualTransformation = if (password) PasswordVisualTransformation() else VisualTransformation.None,
        keyboardOptions = KeyboardOptions(keyboardType = if (password) KeyboardType.Password else keyboard),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun PrefSwitch(label: String, initial: Boolean, onSave: (Boolean) -> Unit) {
    var v by remember { mutableStateOf(initial) }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, Modifier.weight(1f))
        Switch(checked = v, onCheckedChange = { v = it; onSave(it) })
    }
}

@Composable
private fun CheckRow(text: String, ok: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(if (ok) "●" else "○", color = if (ok) Color(0xFF2E7D32) else Color(0xFFC62828))
        Spacer(Modifier.width(8.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun Hint(text: String) {
    Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

private fun granted(ctx: Context, perm: String) =
    ContextCompat.checkSelfPermission(ctx, perm) == PackageManager.PERMISSION_GRANTED

private fun persist(ctx: Context, uri: Uri) {
    runCatching {
        ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}

private fun pretty(uri: Uri): String = runCatching {
    DocumentsContract.getTreeDocumentId(uri).replace("primary:", "내장 메모리/")
}.getOrDefault(uri.toString())

private fun fmt(t: Long): String = SimpleDateFormat("MM-dd HH:mm", Locale.KOREA).format(Date(t))
