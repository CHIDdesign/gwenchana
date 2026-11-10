package com.askthecar.phone

import androidx.compose.foundation.shape.CircleShape
import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.SystemBarStyle
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.SideEffect
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.ImageCapture
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import com.askthecar.ButtonKind
import com.askthecar.StepIcon
import com.askthecar.LineArt
import com.askthecar.LineArts
import com.askthecar.lineArtOf
import com.askthecar.Ask
import com.askthecar.fillsStage
import com.askthecar.hasPicture
import com.askthecar.PhotoGuide
import com.askthecar.carStage
import com.askthecar.CarPin
import com.askthecar.CarView
import com.askthecar.CarFigure
import com.askthecar.CrashKind
import com.askthecar.HandoffData
import com.askthecar.KindTile
import com.askthecar.Pleos
import com.askthecar.PleosButton
import com.askthecar.PleosTheme
import com.askthecar.ReportLines
import com.askthecar.Scene3D
import com.askthecar.Scene3DView
import com.askthecar.ShotSchematic
import com.askthecar.FireGuide
import com.askthecar.StepArt
import com.askthecar.guideTarget
import com.askthecar.isPhoto
import com.askthecar.skipped
import com.askthecar.StepArtView
import com.askthecar.StepHero
import com.askthecar.TrackView
import com.askthecar.pressable
import com.askthecar.sceneJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/** 차와 같은 계정으로 로그인 → 차가 사고를 알리면 알림 → 차에서 하던 안내를 이어서 → 각도별 사진 코칭 */
class PhoneActivity : ComponentActivity() {
    private val openGuide = mutableIntStateOf(0)
    private var scene: Scene3D? = null
    /** WebGL 이 안 되면 2D 궤적으로 (차 앱과 같음) */
    val fail3d = mutableStateOf(false)
    /** 3D 화면 하나를 재현·촬영·기록 화면이 돌려 쓴다(차 앱과 같음). 3D 엔진이 무거워 처음 볼 때 만든다 */
    fun scene3d() = scene ?: Scene3D(this) { fail3d.value = true }.also { scene = it }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        if (savedInstanceState == null) ClaimStore.clearFiles(this) // 지난번 임시 기록서(상대 기록 포함)를 남기지 않음
        Sync.load(this)
        Sync.start(this)
        if (intent?.getBooleanExtra(Notify.EXTRA_GUIDE, false) == true) openGuide.intValue++
        setContent { PleosTheme {
            // 앱을 켠 채 낮/밤이 바뀌면(configChanges=uiMode) 시스템 막대는 그대로라 아래 내비 바가 밝은 띠로 남았음 → 테마마다 다시 맞춘다
            val dark = Pleos.dark
            SideEffect { enableEdgeToEdge(if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT),
                if (dark) SystemBarStyle.dark(android.graphics.Color.TRANSPARENT) else SystemBarStyle.light(android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)) }
            App(openGuide.intValue)
        } }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(Notify.EXTRA_GUIDE, false)) openGuide.intValue++
    }

    override fun onResume() { super.onResume(); scene?.onResume() }
    override fun onPause() { super.onPause(); scene?.onPause() }
    override fun onDestroy() { scene?.destroy(); super.onDestroy() }
}

private enum class Screen { HOME, CASE, LIST, SETTINGS, CONTACT, FAMILY, GUIDE, RECORD, PHOTOS, SHOOT, LINK, CALL, ASK, SEND, FAULT, FIRE }

// ── 휴대폰 화면 틀 (LinkScreen.kt·ClaimShare.kt 도 같이 씀). 간격은 8/16/24 단계만 ──

/** Pleos 글자 단계는 차 큰 화면용이라 휴대폰에 맞게 줄인 것 (글꼴은 그대로, 제목은 차처럼 Bold) */
internal object T {
    val head = Pleos.titleL.copy(fontSize = 26.sp, lineHeight = 34.sp, fontWeight = FontWeight.Bold)
    val title = Pleos.titleS.copy(fontSize = 19.sp, lineHeight = 26.sp, fontWeight = FontWeight.Bold)
    val body = Pleos.bodyL.copy(fontSize = 17.sp, lineHeight = 25.sp)
    val small = Pleos.labelM.copy(fontSize = 14.sp, lineHeight = 20.sp)
}

/** 휴대폰 크기 Pleos 버튼 */
@Composable
internal fun Btn(text: String, kind: ButtonKind = ButtonKind.PRIMARY, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) =
    PleosButton(text, kind, large = false, modifier = modifier, compact = true, onClick = onClick)

@Composable
internal fun Line() = Box(Modifier.fillMaxWidth().height(1.dp).background(Pleos.divider))

/** 내용 묶음 하나 = 카드 하나(옅은 회색 판). label = 맨 위 작은 머리글. PleosCard 의 머리글은 차 크기라 휴대폰 크기로 따로 씀 */
@Composable
internal fun Card(label: String? = null, modifier: Modifier = Modifier, gap: Dp = 16.dp, content: @Composable ColumnScope.() -> Unit) =
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(gap)) { // 시안처럼 판 없이 가는 선으로 나눈다
        Line()
        label?.let { Text(it, style = S.date, color = Pleos.textTertiary) }
        content()
    }

/** 카드 안의 조용한 선택 한 줄(보조 동작): 글자 + 설명 + 오른쪽 표시 */
@Composable
internal fun ActionRow(text: String, sub: String? = null, trailing: String = "›", trailingColor: Color = Pleos.textTertiary, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().pressable(onClick = onClick).heightIn(min = 56.dp).padding(vertical = 8.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
) {
    Column(Modifier.weight(1f)) {
        Text(text, style = T.body)
        sub?.let { Text(it, style = T.small, color = Pleos.textTertiary) }
    }
    Text(trailing, style = if (trailing.length > 1) T.body else T.title, color = trailingColor)
}

/** 누르는 것은 버튼으로 보인다(글자만 있는 링크 금지) */
@Composable
internal fun QuietLink(text: String, modifier: Modifier = Modifier.fillMaxWidth(), onClick: () -> Unit) = Btn(text, ButtonKind.SECONDARY, modifier, onClick)

/** 위 막대: ‹ 뒤로 + 제목(+ 작은 설명) */
@Composable
internal fun TopBar(title: String, sub: String? = null, onBack: (() -> Unit)? = null, end: (@Composable () -> Unit)? = null) {
    Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).padding(start = if (onBack != null) 0.dp else 16.dp, end = 8.dp),
        verticalAlignment = Alignment.CenterVertically) {
        if (onBack != null) Box(Modifier.size(56.dp).pressable(onClick = onBack), contentAlignment = Alignment.Center) { Text("‹", style = T.head) }
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(title, style = T.head)
            sub?.let { Text(it, style = S.date, color = Pleos.textSecondary) }
        }
        end?.invoke()
    }
}

/** 화면 하나: 위 막대(선택) + 스크롤 내용 + 아래 고정 칸(선택, 주 버튼 자리) */
@Composable
internal fun Page(
    title: String? = null, sub: String? = null, onBack: (() -> Unit)? = null,
    bottom: (@Composable () -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) = Column(Modifier.fillMaxSize()) {
    if (title != null) TopBar(title, sub, onBack)
    Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
    bottom?.let { Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { it() } }
}

/** 큰 제목 + 설명 한 덩어리 */
@Composable
internal fun Heading(title: String, body: String? = null) = Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
    Text(title, style = S.display)
    body?.let { Text(it, style = S.body, color = Pleos.textSecondary) }
}

private fun kindLine(d: HandoffData) = "부딪힌 대상: ${d.kind}" + kindNote(d)
private fun kindNote(d: HandoffData) = when {
    d.kind == CrashKind.UNKNOWN.label -> ""
    d.kindChosen -> " (운전자 선택)"
    else -> " (기록으로 추정)"
}

internal fun dial(ctx: Context, phone: String) {
    // DIAL = 번호만 띄우고 거는 건 사람이 누름 (전화 권한 필요 없음)
    runCatching { ctx.startActivity(Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", phone, null))) }
}

@Composable
private fun App(openGuide: Int) {
    val ctx = LocalContext.current
    var account by remember { mutableStateOf(Sync.account(ctx)) }
    val state by Sync.state.collectAsState()
    val cases by Sync.cases.collectAsState()
    val follow by Sync.follow.collectAsState()
    val connected by Sync.connected.collectAsState()
    var screen by rememberSaveable { mutableStateOf(Screen.HOME) }
    var openT by rememberSaveable { mutableStateOf<Double?>(null) } // 지금 연 사고. 안내(Guide)는 최신 사고에만
    var caseTab by rememberSaveable { mutableIntStateOf(0) }
    var shot by rememberSaveable { mutableIntStateOf(0) }
    var photoTick by remember { mutableIntStateOf(0) } // 사진을 찍으면 미리보기를 다시 찾음
    var recordFrom by rememberSaveable { mutableStateOf(Screen.CASE) } // 기록 화면에서 돌아갈 곳
    var photosFrom by rememberSaveable { mutableStateOf(Screen.GUIDE) }
    var askQ by rememberSaveable { mutableStateOf(Ask.HAZARD) } // 기록을 보고 돌아와도 고른 질문 유지
    var askFrom by rememberSaveable { mutableStateOf(Screen.CASE) }
    var contactFrom by rememberSaveable { mutableStateOf(Screen.CASE) }
    var contactAt by rememberSaveable { mutableStateOf<Int?>(null) }
    var fireFrom by rememberSaveable { mutableStateOf(Screen.GUIDE) }
    val onFire = { fireFrom = screen; screen = Screen.FIRE }

    // 안드로이드 13+: 사고 알림을 띄우려면 알림 권한이 필요
    val askNotify = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(account) {
        if (account != null && Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            askNotify.launch(Manifest.permission.POST_NOTIFICATIONS)
    }

    val latest = state?.incident
    val closed = state?.closed == true
    val d = cases.firstOrNull { it.t == openT } ?: latest ?: cases.firstOrNull()
    val helper = account == Logic.FAMILY
    val hasGuide = latest != null && !closed && latest.steps.isNotEmpty()
    // 알림을 눌렀거나, 열려 있는 동안 새 사고가 오면 안내로
    LaunchedEffect(openGuide) { if (openGuide > 0 && latest != null) { openT = latest.t; caseTab = 0; screen = if (hasGuide) Screen.GUIDE else Screen.CASE } }
    LaunchedEffect(latest?.t) { if (latest != null && hasGuide && state?.notAccident != true && screen == Screen.HOME && Logic.fresh(latest) && !helper) { openT = latest.t; screen = Screen.GUIDE } }
    // 사고 아님·게임 리셋은 끝낸 안내가 아니다. 사진 화면에서 돌아올 때도 걸리게 screen 도 지켜본다
    LaunchedEffect(state?.notAccident, screen) { if (state?.notAccident == true && screen == Screen.GUIDE) screen = Screen.HOME }

    BackHandler(screen != Screen.HOME) {
        screen = when (screen) {
            Screen.RECORD -> recordFrom; Screen.PHOTOS -> photosFrom; Screen.SHOOT -> Screen.PHOTOS; Screen.CONTACT -> contactFrom; Screen.ASK -> askFrom; Screen.FIRE -> fireFrom
            Screen.LINK, Screen.CALL, Screen.SEND, Screen.FAULT, Screen.FAMILY -> Screen.CASE
            else -> Screen.HOME
        }
    }
    if (d == null && screen != Screen.HOME && screen != Screen.SETTINGS) screen = Screen.HOME

    fun open(c: HandoffData) { openT = c.t; caseTab = 0; screen = Screen.CASE }
    fun go(g: Go) {
        when (g) {
            Go.GUIDE -> { openT = latest?.t; screen = Screen.GUIDE }
            Go.CALL -> screen = Screen.CALL
            Go.SEND -> screen = Screen.SEND
            Go.ASK -> { askFrom = Screen.CASE; screen = Screen.ASK }
            Go.RECORD -> { recordFrom = Screen.CASE; screen = Screen.RECORD }
            Go.PHOTOS -> { photosFrom = Screen.CASE; shot = 0; screen = Screen.PHOTOS }
            Go.LINK -> screen = Screen.LINK
            Go.FAMILY -> screen = Screen.FAMILY
            Go.FAULT -> screen = Screen.FAULT
            Go.SETTINGS -> screen = Screen.SETTINGS
        }
    }
    fun doTodo(c: HandoffData, todo: Todo) {
        openT = c.t
        when (todo.task) {
            Task.CHECK_119 -> dial(ctx, "119") // 번호만 띄운다. 끝낸 표시는 "했어요"
            Task.SCENE -> go(Go.GUIDE)
            Task.REST -> if (todo.second != null) go(Go.FAMILY) else ClaimStore.check(ctx, c.t, "self", "서류는 제가 하겠다고 표시함")
            Task.CARD -> ClaimStore.check(ctx, c.t, "card", "블랙박스 카드를 챙겼어요")
            Task.CLAIM -> go(Go.CALL)
            Task.SEND -> go(Go.SEND)
            Task.WAIT -> go(if (c.samples.isNotEmpty()) Go.ASK else Go.SEND)
            Task.NOT_ACCIDENT -> go(Go.RECORD)
        }
    }
    fun doSecond(c: HandoffData, todo: Todo) {
        openT = c.t
        when (todo.task) {
            Task.CHECK_119 -> ClaimStore.check(ctx, c.t, "called119", "119에 연락했다고 표시함")
            Task.REST -> ClaimStore.check(ctx, c.t, "self", "서류는 제가 하겠다고 표시함")
            else -> go(Go.ASK)
        }
    }
    val logout = { Sync.logout(ctx); account = null; screen = Screen.HOME }

    Box(Modifier.fillMaxSize().background(Pleos.backgroundPrimary)) {
    // 어두운 윗부분·회색 바탕이 상태 막대 밑까지 이어지는 화면은 안쪽에서 여백을 잡는다
    Box(Modifier.fillMaxSize().then(if (screen == Screen.CASE || screen == Screen.SEND) Modifier else Modifier.safeDrawingPadding())) {
        val acc = account
        if (acc == null) Login { id -> Sync.login(ctx, id); account = id; Sync.load(ctx) }
        else when (screen) {
            Screen.HOME -> WalletHome(acc, cases, latest?.t, connected, ::open, ::doTodo, ::doSecond,
                onSettings = { screen = Screen.SETTINGS }, onList = { screen = Screen.LIST },
                onManual = { openT = Sync.startManual(ctx); caseTab = 0; screen = Screen.CASE })
            Screen.LIST -> CaseList(cases, latest?.t, helper, ::open) { screen = Screen.HOME }
            Screen.SETTINGS -> SettingsPage(helper, onBack = { screen = if (d != null && openT != null) Screen.CASE else Screen.HOME }, onLogout = logout)
            Screen.CASE -> d?.let { c -> CaseScreen(c, c.t == latest?.t, helper, caseTab, { caseTab = it }, ::go, onBack = { screen = Screen.HOME },
                onTodo = { doTodo(c, it) }, onSecond = { doSecond(c, it) }, onContact = { contactAt = it; contactFrom = Screen.CASE; screen = Screen.CONTACT }) }
            Screen.CONTACT -> d?.let { ContactPage(it, contactAt) { screen = contactFrom } }
            Screen.FAMILY -> d?.let { c -> ClaimStore.guardian(ctx, c)?.let { g -> HandOverScreen(c, g) { ClaimStore.check(ctx, c.t, "self", "서류는 제가 하겠다고 표시함"); screen = Screen.CASE } } }
            Screen.CALL -> d?.let { CallScript(it) { screen = Screen.CASE } }
            Screen.ASK -> d?.let { AskPage(it, askQ, { q -> askQ = q }) { recordFrom = Screen.ASK; screen = Screen.RECORD } }
            Screen.SEND -> d?.let { SendScreen(it, onBack = { screen = Screen.CASE }, onSent = { caseTab = 1; screen = Screen.CASE },
                onAddContact = { contactAt = null; contactFrom = Screen.SEND; screen = Screen.CONTACT }) }
            Screen.FAULT -> d?.let { FaultPage(it) { askFrom = Screen.FAULT; screen = Screen.ASK } }
            Screen.GUIDE -> latest?.let {
                Guide(it, follow.step, closed, connected, { s -> Sync.move(ctx, s) },
                    onRecord = { openT = it.t; recordFrom = Screen.GUIDE; screen = Screen.RECORD }, onPhotos = { openT = it.t; photosFrom = Screen.GUIDE; shot = 0; screen = Screen.PHOTOS }, onFire = onFire, onHome = { screen = Screen.HOME })
            }
            Screen.FIRE -> FirePage { screen = fireFrom }
            Screen.RECORD -> d?.let { Record(it) { screen = recordFrom } }
            Screen.LINK -> d?.let { LinkScreen(it) { screen = Screen.CASE } }
            Screen.PHOTOS -> d?.let { Photos(it, shot, photoTick, { s -> shot = s }, onShoot = { screen = Screen.SHOOT }, onFire = onFire, onDone = { screen = photosFrom }) }
            Screen.SHOOT -> d?.let { Shoot(it, shot, onBack = { screen = Screen.PHOTOS }) { photoTick++; screen = Screen.PHOTOS } }
        }
    }
    }
}

/** 서버 주소 (에뮬레이터 = 10.0.2.2, 실제 휴대폰 = 같은 와이파이의 PC 주소). 평소엔 한 줄, 누르면 고치는 칸 */
@Composable
internal fun ServerCard() {
    val ctx = LocalContext.current
    var url by remember { mutableStateOf(Sync.server(ctx)) }
    var open by remember { mutableStateOf(false) }
    Card("연동 서버", gap = 8.dp) {
        if (!open) ActionRow(url, trailing = "바꾸기") { open = true }
        else {
            BasicTextField(url, { url = it; Sync.setServer(ctx, it) }, singleLine = true,
                textStyle = T.body.copy(color = Pleos.textPrimary, fontFamily = FontFamily.Monospace), cursorBrush = SolidColor(Pleos.textPrimary),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                modifier = Modifier.fillMaxWidth().background(Pleos.backgroundPrimary, Pleos.shapeXS).border(1.dp, Pleos.divider, Pleos.shapeXS).padding(16.dp))
            Text("실제 휴대폰이면 같은 와이파이에 있는 PC 주소를 넣어요 (예: http://192.168.0.10:8767)", style = T.small, color = Pleos.textSecondary)
            QuietLink("닫기") { open = false }
        }
    }
}

/** 차와 같은 계정으로 로그인. shortcut: 진짜 현대차·Pleos 계정은 일반 앱이 못 읽어서 시연용 운전자 중에서 고름 */
@Composable
private fun Login(onPick: (String) -> Unit) = Page {
    LineArt(LineArts.EVIDENCE, Modifier.size(72.dp), animate = false, until = LineArts.EVIDENCE_SENT) // 문서와 휴대폰(체크 없음)
    Heading("괜차나", "차에서 쓰는 운전자로 로그인해 주세요. 사고가 나면 이 휴대폰으로도 알려 드려요.")
    Card("운전자 선택", gap = 0.dp) {
        Logic.logins.forEachIndexed { k, (id, name) ->
            if (k > 0) Line()
            DriverRow(name) { onPick(id) }
        }
    }
    ServerCard()
}

/** 운전자 한 줄: 실제 Pleos 프로필 목록처럼 네모 머리글자 + 이름 */
@Composable
private fun DriverRow(name: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().pressable(onClick = onClick).heightIn(min = 64.dp).padding(vertical = 12.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp),
) {
    Box(Modifier.size(40.dp).background(Pleos.basic100, CircleShape), contentAlignment = Alignment.Center) { Text(name.take(1), style = T.title) }
    Text(name, style = T.body, modifier = Modifier.weight(1f))
    Text("›", style = T.title, color = Pleos.textTertiary)
}

/** 카메라 권한이 없으면 이유를 쉬운 말로 설명하고 묻는다 */
@Composable
private fun NeedCamera(why: String, picture: Boolean, onBack: () -> Unit, content: @Composable () -> Unit) {
    val ctx = LocalContext.current
    var granted by remember { mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) }
    var denied by remember { mutableStateOf(false) }
    val ask = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it; denied = !it }
    if (granted) { content(); return }
    Page(bottom = {
        Btn("카메라 허용하기") { ask.launch(Manifest.permission.CAMERA) }
        QuietLink("돌아가기", onClick = onBack)
    }) {
        if (picture) Box(Modifier.fillMaxWidth().height(180.dp).clip(Pleos.shapeM), contentAlignment = Alignment.Center) { StepHero(StepArt.CAMERA, Modifier.fillMaxWidth(), animate = true, shape = RectangleShape) }
        Heading("카메라를 써도 될까요?", why)
        if (denied) Card { Text("허용하지 않았어요. 휴대폰 설정 > 앱 > 괜차나 > 권한에서 카메라를 켤 수 있어요.", style = T.body, color = Pleos.textOrange) }
    }
}

/** 차와 같은 3D 화면, 같은 계산(sceneJson). mode = replay / photo. 샘플이 없어도 photo 는 충돌 지점 기준으로 그려진다 */
@Composable
private fun Scene(d: HandoffData, mode: String, modifier: Modifier) {
    val activity = LocalContext.current as PhoneActivity
    if (activity.fail3d.value) { TrackView(d.samples, modifier); return }
    val scene = remember { activity.scene3d() }
    // 경위서 문장만 바뀔 때는 재생을 처음으로 돌리지 않는다
    LaunchedEffect(d.t, d.kind, d.impactDeg, d.highway, d.samples.size, d.seen, mode) {
        scene.buildAndShow { sceneJson(d.samples, d.t, d.impactDeg, Logic.kindOf(d), d.highway, mode, d.seen.takeIf { mode == "replay" }, floor = true) } // 차 기록 화면과 같은 그림(격자·안개 없음)
    }
    Scene3DView(scene, modifier)
}

/** 사고 장면 재현. 기록이 저장되기 전(사고 후 약 10초)에는 기다린다는 안내 */
@Composable
private fun ReplayPanel(d: HandoffData, modifier: Modifier) = Box(modifier.clip(Pleos.shapeM).background(Pleos.backgroundSecondary, Pleos.shapeM)) {
    if (d.samples.isEmpty()) Column(
        Modifier.fillMaxSize().padding(24.dp),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterVertically),
    ) {
        if (d.hash == null) { LineArt(LineArts.EVIDENCE, Modifier.size(96.dp), animate = true, until = LineArts.EVIDENCE_SENT); Spacer(Modifier.height(8.dp)) } // 기록이 오는 중
        Text(if (d.hash == null) "기록 저장 중이에요" else "재현할 기록을 받지 못했어요", style = T.title)
        if (d.hash == null) Text("사고 후 10초까지 기록한 뒤 여기에 사고 장면이 나와요.", style = T.small, color = Pleos.textSecondary, textAlign = TextAlign.Center)
    } else {
        Scene(d, "replay", Modifier.fillMaxSize())
        Text("사고 장면 재현 [추정]", style = T.small, modifier = Modifier.padding(8.dp).background(Pleos.backgroundPopup, Pleos.shapeXS).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** 진행 막대(차 화면과 같은 모양) */
@Composable
private fun Progress(i: Int, count: Int) = Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(3.dp).background(Pleos.basic100)) {
    Box(Modifier.fillMaxWidth((i + 1f) / count).fillMaxHeight().background(Pleos.textPrimary))
}

/** 아래 유리 도크(시안 3A~6A): 주 버튼 + 두 번째 선택. 글자가 길면 위아래로. 모든 선택이 스크롤 없이 보인다 */
@Composable
private fun StepDock(first: String, firstKind: ButtonKind, onFirst: () -> Unit, second: String? = null, onSecond: () -> Unit = {}) {
    if (second != null && first.length + second.length > 13) GlassDockStack {
        Btn(first, firstKind, onClick = onFirst)
        Btn(second, ButtonKind.SECONDARY, onClick = onSecond)
    } else GlassDock {
        Btn(first, firstKind, Modifier.weight(1f), onFirst)
        if (second != null) Btn(second, ButtonKind.SECONDARY, Modifier.weight(1f), onSecond)
    }
}

/** 차와 같은 단계를 한 장씩. phoneFrom 전 단계 = 차 화면에서 진행 중(차를 따라감), 그 뒤 = 휴대폰이 중심 */
@Composable
private fun Guide(d: HandoffData, step: Int, closed: Boolean, connected: Boolean, setStep: (Int) -> Unit,
                  onRecord: () -> Unit, onPhotos: () -> Unit, onFire: () -> Unit, onHome: () -> Unit) {
    val count = d.steps.size
    var nearHere by rememberSaveable(d.t) { mutableStateOf(false) } // 이 휴대폰에서 119를 누름(차에서 누른 것은 d.near)
    var movedHere by rememberSaveable(d.t) { mutableStateOf(false) }
    val skip = { a: StepArt -> a.skipped(nearHere || d.near, movedHere || d.moved, Logic.kindOf(d) == CrashKind.PERSON) }
    val arts = d.steps.map { Logic.artOf(it.art) }
    val paper = !Pleos.dark && !closed && count > 0 && Logic.artOf(d.steps[step.coerceIn(0, count - 1)].art)?.fillsStage == true
    Column(Modifier.fillMaxSize().then(if (paper) Modifier.background(Color.White) else Modifier)) { // 흰 종이 그림이 네모로 보이지 않게 화면 전체를 종이색으로
        if (closed || count == 0) {
            Text(Logic.time(d.t), style = S.date, color = Pleos.textSecondary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp))
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(20.dp),
                verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) {
                StepIcon(StepArt.RECORD, 72.dp, until = if (d.hash != null) null else LineArts.EVIDENCE_SENT) // 체크는 기록이 실제로 온 뒤에만
                Heading("안내가 끝났어요", "기록은 언제든 다시 볼 수 있어요.")
            }
            QuietLink("처음 화면", onClick = onHome)
            StepDock("기록 보기", ButtonKind.PRIMARY, onRecord)
        } else {
            val i = step.coerceIn(0, count - 1)
            val s = Logic.forPhone(d.steps[i])
            val art = Logic.artOf(s.art)
            // 고속도로 여부 답은 차 안내 목록을 바꾸므로 차 화면에서만 받는다
            val twoAnswers = s.call == null && s.decline != null && s.next != null && art?.isPhoto != true && art != StepArt.ROAD
            fun advance() = setStep(guideTarget(arts, i, 1, skip).coerceAtMost(count - 1))
            fun answer(first: Boolean) { if (art == StepArt.MOVE_RISK) movedHere = first; setStep(guideTarget(arts, i, 1) { a -> a.skipped(nearHere || d.near, (art == StepArt.MOVE_RISK && first) || movedHere || d.moved, Logic.kindOf(d) == CrashKind.PERSON) }.coerceAtMost(count - 1)) }
            val shown = arts.count { it == null || !skip(it) }
            val at = (arts.take(i + 1).count { it == null || !skip(it) } - 1).coerceAtLeast(0)
            Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                if (i > 0) QuietLink("‹ 이전", Modifier, onClick = { setStep(guideTarget(arts, i, -1, skip).coerceAtLeast(0)) }) else Spacer(Modifier.height(48.dp))
                Spacer(Modifier.weight(1f))
                QuietLink("기록 보기 ›", Modifier, onClick = onRecord)
            }
            Progress(at, shown)
            val where = when {
                i < d.phoneFrom -> "차 화면에서 진행 중이에요"
                !connected -> "연동 서버와 연결이 끊겼어요"
                else -> Logic.time(d.t)
            }
            Text("$where · ${at + 1} / $shown", style = S.date, color = Pleos.textSecondary, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 4.dp))
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (art) {
                    StepArt.REPLAY -> Column(Modifier.fillMaxSize()) {
                        Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(s.title, style = S.display)
                            if (d.samples.isNotEmpty()) Text("조작 기록으로 다시 그린 움직임이에요. 끌어서 돌려 볼 수 있어요.", style = S.body, color = Pleos.textSecondary)
                        }
                        ReplayPanel(d, Modifier.weight(1f).fillMaxWidth())
                    }
                    StepArt.CAMERA, StepArt.CAMERA_FAR -> PhotoStep(d, s, art)
                    else -> StepBody(s, art, d.impactDeg, acked = d.hash != null)
                }
            }
            FireLink(onFire)
            val last = i == count - 1
            val label = when {
                last -> "기록 보기"
                art == StepArt.PERSON && (nearHere || d.near) -> "다음" // 119를 눌렀으면 "없어요"가 아니다
                art == StepArt.ROAD -> "다음"
                else -> s.decline ?: s.next ?: "다음"
            }
            val ctx = LocalContext.current
            val n = s.call
            when {
                last -> StepDock(label, ButtonKind.PRIMARY, onRecord)
                art?.isPhoto == true && d.shots.isNotEmpty() -> StepDock(s.next ?: "사진 안내 시작", ButtonKind.PRIMARY, onPhotos, label) { advance() }
                n != null -> {
                    val emergency = n == "119" || n == "112"
                    StepDock(if (emergency || s.callName == null) "$n 전화" else "전화 걸기", if (emergency) ButtonKind.EMERGENCY else ButtonKind.SAFE, { if (art == StepArt.PERSON && n == "119") nearHere = true; dial(ctx, n) }, label) { advance() }
                }
                twoAnswers -> StepDock(s.next ?: "다음", ButtonKind.PRIMARY, { answer(true) }, s.decline) { answer(false) }
                else -> StepDock(label, ButtonKind.PRIMARY, { advance() })
            }
        }
    }
}

/** 불·연기 안내로 가는 조용한 링크. 모든 안내 화면에서 진행 막대 바로 아래 오른쪽 */
@Composable
private fun FireLink(onFire: () -> Unit) = Box(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 8.dp), contentAlignment = Alignment.CenterEnd) {
    QuietLink(FireGuide.LINK, Modifier, onClick = onFire)
}

@Composable
private fun FirePage(onBack: () -> Unit) {
    val ctx = LocalContext.current
    Page("불이나 연기가 날 때", onBack = onBack, bottom = { Btn("119 전화", ButtonKind.EMERGENCY) { dial(ctx, "119") } }) {
        StepIcon(StepArt.FIRE, 72.dp, animate = true)
        Heading(FireGuide.TITLE, FireGuide.BODY)
    }
}

/** 보통 단계(시안처럼 위에 글, 아래에 가장자리까지 큰 그림). 버튼은 아래 도크에 */
@Composable
private fun StepBody(s: HandoffData.Step, art: StepArt?, impactDeg: Double?, acked: Boolean = true) {
    val ctx = LocalContext.current
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        Column(Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 4.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            // 한 번 그리고 멈춤. 기록 전송 그림의 체크는 서버에서 기록(지문)을 받은 뒤에만
            art?.takeIf { !it.hasPicture }?.let { StepIcon(it, 72.dp, animate = true, until = if (!acked && lineArtOf(it) === LineArts.EVIDENCE) LineArts.EVIDENCE_SENT else null); Spacer(Modifier.height(8.dp)) }
            Text(s.title, style = S.display)
            if (s.body.isNotEmpty()) Text(s.body, style = S.body, color = Pleos.textSecondary)
            val who = s.callName?.removeSuffix(" (가상)")
            s.call?.takeIf { who != null && it != "119" && it != "112" }?.let { n -> Text("$who · $n", style = S.body, color = Pleos.textPrimary) }
            s.call2?.let { n -> Spacer(Modifier.height(8.dp)); Btn("$n 전화", ButtonKind.SAFE) { dial(ctx, n) } }
        }
        // 트렁크는 3D 차 뒷모습 위 위치 표시(차 화면과 같음), 사람 행동은 그림
        if (art == StepArt.TRUNK) Box(Modifier.padding(horizontal = 20.dp).fillMaxWidth().height(200.dp).carStage(Pleos.shapeM), contentAlignment = Alignment.Center) {
            CarFigure(CarView.REAR, Modifier.fillMaxHeight().padding(8.dp), impactDeg, listOf(CarPin("trunk", StepArt.TRUNK)), pinSize = 44.dp)
        } else art?.takeIf { it.hasPicture }?.let { StepHero(it, Modifier.fillMaxWidth(), animate = true, shape = RectangleShape) } // 가장자리까지 크게
        // 그림 없는 단계: 차가 기록한 충격 자리(위에서 본 3D 차) — 장식 대신 사실
        if (art?.hasPicture != true && impactDeg != null) Column(Modifier.padding(horizontal = 20.dp)) {
            Card {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    CarFigure(CarView.TOP, Modifier.height(72.dp), impactDeg)
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("차가 기록한 것", style = S.date, color = Pleos.textTertiary)
                        Text("${PhotoGuide.part(impactDeg)}에서 충격 [추정]", style = S.body)
                    }
                }
            }
        }
    }
}

/** 번호 칸(네모) + 글자 한 줄 */
@Composable
private fun NumberRow(n: Int, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
    Box(Modifier.size(28.dp).border(1.dp, Pleos.outline, CircleShape), contentAlignment = Alignment.Center) { Text("$n", style = T.small) }
    Text(text, style = T.body, modifier = Modifier.weight(1f))
}

/** 촬영 단계: 글 + 찍을 위치 그림 + 장면 목록. 시작·건너뛰기는 아래 도크라 스크롤하지 않아도 보인다 */
@Composable
private fun PhotoStep(d: HandoffData, s: HandoffData.Step, art: StepArt) = Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
    Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(s.title, style = S.display)
        Text(s.body, style = S.body, color = Pleos.textSecondary)
    }
    // 차 화면과 같은 선 그림(첫 장면 = 현장 전체). 고속도로: 차 곁으로 가는 그림을 보이지 않는다
    if (art == StepArt.CAMERA_FAR) Box(Modifier.padding(start = 20.dp, top = 24.dp)) { StepIcon(art, 72.dp, animate = true) }
    else if (d.shots.isNotEmpty()) ShotSchematic(Logic.shotOf(d.shots[0]), Logic.kindOf(d), d.impactDeg, Modifier.fillMaxWidth().height(240.dp).padding(16.dp), animate = true)
    else Scene(d, "photo", Modifier.fillMaxWidth().height(240.dp).padding(top = 16.dp))
    if (d.shots.isNotEmpty()) Column(Modifier.padding(horizontal = 20.dp, vertical = 16.dp)) {
        Card("찍을 장면 ${d.shots.size}곳", gap = 8.dp) { d.shots.forEachIndexed { k, shot -> NumberRow(k + 1, shot.title) } }
    }
}

/** 차 기록 화면과 같은 내용을 세로로: 재현 → 부딪힌 대상 → 진술 → 경위 설명문 → 연락처 */
@Composable
private fun Record(d: HandoffData, onBack: () -> Unit) = Page("사고 기록", Logic.time(d.t), onBack) {
    val ctx = LocalContext.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        ReplayPanel(d, Modifier.fillMaxWidth().height(360.dp))
        if (d.samples.isNotEmpty()) Text("조작 기록으로 다시 그린 움직임이에요. 끌어서 돌려 볼 수 있어요.", style = T.small, color = Pleos.textSecondary)
    }

    Card("부딪힌 대상" + kindNote(d)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val shown = Logic.kindOf(d)
            listOf(CrashKind.VEHICLE, CrashKind.OBJECT, CrashKind.PERSON).forEach { KindTile(it, it == shown, Modifier.weight(1f), 48.dp, T.small) }
        }
    }

    Card("운전자 진술") {
        Text(d.statement?.let { "“$it”" } ?: "아직 남긴 진술이 없어요. 차 화면의 사고 기록에서 말로 남길 수 있어요.", style = T.body,
            color = if (d.statement != null) Pleos.textPrimary else Pleos.textSecondary)
    }

    Card("경위 설명문", gap = 8.dp) {
        val report = d.report ?: d.facts.joinToString("\n").ifEmpty { null }
        if (report == null) Text("기록 저장 중이에요. 사고 후 10초까지 기록한 뒤 경위서를 만들어요.", style = T.body, color = Pleos.textSecondary)
        else ReportLines(report, T.small, T.body, timeWidth = 84.dp, gap = 8.dp)
        d.vision.forEach { Text(it, style = T.body, color = Pleos.textOrange) }
        d.hash?.let {
            Spacer(Modifier.height(8.dp))
            Line()
            Spacer(Modifier.height(8.dp))
            Text("기록 지문 (SHA-256)", style = T.small, color = Pleos.textTertiary)
            Text("$it…", style = T.small.copy(fontFamily = FontFamily.Monospace), color = Pleos.textTertiary)
            Text("차에 저장된 원본과 같은지 확인하는 지문이에요", style = T.small, color = Pleos.textTertiary)
        }
    }

    if (d.contacts.isNotEmpty()) Card("연락처", gap = 0.dp) {
        d.contacts.forEachIndexed { k, c ->
            if (k > 0) Line()
            ActionRow(c.name.removeSuffix(" (가상)"), c.phone, "전화", Pleos.textActive) { dial(ctx, c.phone) }
        }
    }
}

private fun photosDir(ctx: Context) = File(ctx.filesDir, "photos")

@Composable
private fun Photos(d: HandoffData, shot: Int, tick: Int, setShot: (Int) -> Unit, onShoot: () -> Unit, onFire: () -> Unit, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val i = shot.coerceIn(0, d.shots.size - 1)
    val s = d.shots[i]
    val photo = remember(i, tick) {
        val dir = photosDir(ctx)
        Logic.latestPhoto(dir.list()?.toList().orEmpty(), i, s.art, (d.t * 1000).toLong(), Sync.photoUntil(d))?.let { File(dir, it) }
    }
    Column(Modifier.fillMaxSize()) {
        TopBar("현장 사진", "${i + 1} / ${d.shots.size}", onDone, end = if (i > 0) ({ QuietLink("‹ 이전", Modifier, onClick = { setShot(i - 1) }) }) else null)
        Progress(i, d.shots.size)
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            // 차 화면과 같은 움직이는 그림: 휴대폰 아이콘이 찍을 위치로 이동 → 촬영 범위 표시
            ShotSchematic(Logic.shotOf(s), Logic.kindOf(d), d.impactDeg, Modifier.fillMaxWidth().height(280.dp).padding(16.dp), animate = true)
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                Heading(s.title, s.line)
                if (s.suggestion) Text("앱 제안 — 공식 안내에서 찾지 못한 방법이에요", style = T.small, color = Pleos.textOrange)
                if (s.art != "SAFE") {
                    if (photo != null) Card("찍은 사진", gap = 8.dp) {
                        Thumb(photo)
                        Text("사진은 이 휴대폰에만 저장돼요.", style = T.small, color = Pleos.textSecondary)
                        QuietLink("다시 찍기", onClick = onShoot)
                    }
                }
            }
        }
        val last = i == d.shots.size - 1
        val go = { if (last) onDone() else setShot(i + 1) }
        FireLink(onFire)
        // 아직 안 찍었으면 "찍기"가 주 선택
        if (photo != null || s.art == "SAFE") StepDock(if (last) "촬영 끝" else "다음", ButtonKind.PRIMARY, go)
        else StepDock("찍기", ButtonKind.PRIMARY, onShoot, if (last) "촬영 끝" else "다음", go)
    }
}

/** 찍은 사진 작게 (EXIF 방향대로 돌림) */
@Composable
private fun Thumb(f: File) {
    val bmp by produceState<ImageBitmap?>(null, f) {
        value = withContext(Dispatchers.IO) {
            runCatching {
                val b = BitmapFactory.decodeFile(f.path, BitmapFactory.Options().apply { inSampleSize = 8 })
                val deg = when (ExifInterface(f.path).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> 90f; ExifInterface.ORIENTATION_ROTATE_180 -> 180f; ExifInterface.ORIENTATION_ROTATE_270 -> 270f; else -> 0f
                }
                (if (deg == 0f) b else Bitmap.createBitmap(b, 0, 0, b.width, b.height, Matrix().apply { postRotate(deg) }, true)).asImageBitmap()
            }.getOrNull()
        }
    }
    bmp?.let { Image(it, "찍은 사진", Modifier.fillMaxWidth().height(220.dp).clip(Pleos.shapeS), contentScale = ContentScale.Crop) }
}

@Composable
private fun Shoot(d: HandoffData, shot: Int, onBack: () -> Unit, onSaved: () -> Unit) =
    NeedCamera("현장 사진을 찍을 때 카메라를 써요. 사진은 이 휴대폰에만 저장되고 어디에도 올리지 않아요.", picture = !d.highway, onBack) { // 고속도로: 차 옆에 선 그림을 보이지 않는다
        val ctx = LocalContext.current
        val i = shot.coerceIn(0, d.shots.size - 1)
        val s = d.shots[i]
        val capture = remember { ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).build() }
        var busy by remember { mutableStateOf(false) }
        var failed by remember { mutableStateOf(false) }
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f).fillMaxWidth()) {
                CameraPreview(capture, Modifier.fillMaxSize())
                Box(Modifier.fillMaxSize().padding(horizontal = 24.dp, vertical = 72.dp).border(3.dp, Color.White.copy(alpha = 0.85f), Pleos.shapeM))
                Text(s.title, style = T.title, color = Color.White,
                    modifier = Modifier.align(Alignment.TopCenter).fillMaxWidth().padding(16.dp)
                        .background(Color.Black.copy(alpha = 0.6f), Pleos.shapeS).padding(16.dp))
            }
            Column(Modifier.fillMaxWidth().background(Pleos.backgroundPrimary).padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (failed) Text("저장하지 못했어요. 다시 눌러 주세요.", style = T.body, color = Pleos.textOrange)
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    QuietLink("돌아가기", Modifier.widthIn(min = 96.dp), onClick = onBack)
                    Btn(if (busy) "저장 중…" else "찍기", ButtonKind.PRIMARY, Modifier.weight(1f)) {
                        if (busy) return@Btn
                        busy = true; failed = false
                        takePhoto(capture, File(photosDir(ctx), Logic.photoName(i, s.art, System.currentTimeMillis())), ctx) { ok ->
                            busy = false
                            if (ok) onSaved() else failed = true
                        }
                    }
                }
            }
        }
    }
