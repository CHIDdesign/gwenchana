package com.askthecar

import androidx.compose.foundation.shape.CircleShape
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.os.SystemClock
import android.car.drivingstate.CarUxRestrictionsManager
import android.view.Gravity
import android.view.ViewGroup
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File

/**
 * 사고가 감지되면 내비 위를 덮는 화면 (사용자 설계):
 * ① 팝업 "괜찮으신가요?" [괜찮아요 / 다쳤어요 / 사고 아니에요]. 답할 때까지 기다림
 * ② 3D 상황 + 무엇과 부딪혔나요(추정을 미리 선택). 고르거나 건너뜀
 * ③ 맞춤 가이드: 직접 진행. 비상등은 켜지면 넘어가고, 통화를 마치고 돌아오면 다음 단계로("이전"으로 돌아온 단계는 그대로 머문다)
 * ④ 기록(재현 반복 재생·진술·경위서) → 참고(비슷한 사고 유형 후보 — 판단 없음)
 * 정차 전(1.5초 이상 멈추기 전)에는 위쪽 안내줄만(아래 내비가 보이게 투명 창, 창 높이도 줄임).
 * 정차 판단: 속도 1km/h 미만 또는 P 단이 1.5초 이어지면 정차, 5km/h 이상이어야 다시 주행(떨림 방지).
 * 다시 달리기 시작하면(20km/h 이상 60초) 화면을 닫고 알림으로 남긴다.
 */
class CrashActivity : ComponentActivity() {
    // BANNER 는 stage 에 담기지 않고 screenOf() 가 정차 전에 돌려주는 값
    private enum class Stage { POPUP, BANNER, SCENE, GUIDE, PHOTOS, RECORD, REFERENCE, ASK }

    private var stage by mutableStateOf(Stage.POPUP)
    private var textBottom by mutableStateOf(0f) // 그림 위 글의 아래 끝(화면 기준 px)
    /** 화면이 보이는 중. 전화 화면 등으로 가려지면 자동 넘김·음성을 멈춤 */
    private var onScreen by mutableStateOf(true)
    private var stopped by mutableStateOf(false)
    private var parked by mutableStateOf(false) // P단. 기록 화면의 재현·영상은 P단에서만
    /** 홈에서 고른 지난 사고(폴더 이름). 있으면 진행 중 사고를 건드리지 않고 그 기록만 보여 준다 */
    private var pastDir by mutableStateOf<String?>(null)
    private var past by mutableStateOf<CrashRecord?>(null)
    private var pastLoaded by mutableStateOf(false)
    private val viewed get() = if (pastDir != null) past else CrashFlow.record
    private val viewedKind get() = if (pastDir != null) past?.kind ?: CrashKind.UNKNOWN else CrashFlow.kind
    private val viewedChosen get() = if (pastDir != null) past?.kindChosen == true else CrashFlow.kindChosen
    private var askSeek: Double? = null // 차에게 묻기 → 기록에서 보기: 충돌 기준 초
    /** 화면을 닫았다 다시 열어도 하던 단계부터(사고마다 CrashWatcher 가 0으로) */
    private var guideIndex by CrashFlow::guideStep
    private var shotIndex by mutableIntStateOf(0)
    /** 전화 버튼을 누른 단계. 통화를 마치고 돌아오면 다음 단계로 넘어간다 */
    private var dialedStep = -1
    /** dialedStep 에서 실제 통화가 있었음 */
    private var dialTalked = false
    /** "이전"으로 돌아온 단계. 여기서는 저절로 다시 넘어가지 않는다 */
    private var backTo = -1
    /** 안내 화면이 마지막으로 쓴 단계 목록. 사진·안내줄 화면에 가 있는 동안 목록이 바뀌어도 돌아올 때 번호를 맞추려고 GuideView 밖에 둔다 */
    private var shownSteps: List<GuideStep>? = null
    private var confirmLeave by mutableStateOf(false)
    /** 불·연기 안내를 열어 둠. 닫으면 보던 화면으로 돌아간다 */
    private var fireOpen by mutableStateOf(false)
    /** 차가 주행 규제(CarUxRestrictions, 영상·움직이는 그림 금지)를 거는 중. 차 서비스를 못 쓰면 false(속도 판정만 씀) */
    private var uxRestricted by mutableStateOf(false)
    private var car: android.car.Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    /** 움직이는 그림·영상을 보여도 되는 때: 정차 + 차가 규제하지 않음 (가이드: 주행 규제는 차 신호를 따른다) */
    private val calm get() = stopped && !uxRestricted
    /** 멈춰 있어도 차가 규제 중일 수 있어서(D단 정차 등) "멈추면"이라고만 하지 않는다 */
    private fun whenCalm(what: String) = if (stopped) "P단에 두면 $what 보여 드려요" else "차가 멈추면 $what 보여 드려요"
    private var fail3d by mutableStateOf(false) // WebGL 이 안 되면 2D 궤적으로
    private var bannerSpokenFor = 0.0 // 안내줄 음성은 사고마다 한 번만
    /** 서버가 알고 있는 지금 단계(사고 t, 번호): 차가 보냈거나 휴대폰에서 받은 것. 받은 단계를 되돌려 보내지 않게 */
    private var syncedStep: Pair<Double, Int>? = null
    private lateinit var persona: Persona
    private lateinit var scene: Scene3D

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        persona = Personas.load(this) ?: Personas.guest
        pastDir = intent.getStringExtra(EXTRA_DIR)
        if (pastDir == null) CrashFlow.screenOpen = true
        // 3D 화면 배경색이 정해지기 전에 만들어지므로 테마를 시스템 설정으로 먼저 알려 준다
        Pleos.dark = (resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES
        stopped = CarState.now.let { it.speedKph < 1 || it.gear == "P" } // 이미 서 있으면 바로 정차로 본다
        scene = Scene3D(this) { fail3d = true } // 팝업 동안 미리 3D 엔진을 읽어 둔다
        watchUx()
        if (savedInstanceState != null) { // 다시 만들어진 화면: 하던 곳에서 이어 가고 사진을 또 찍지 않는다
            savedInstanceState.getString(KEY_STAGE)?.let { stage = Stage.valueOf(it) }
            shotIndex = savedInstanceState.getInt(KEY_SHOT)
        } else if (intent.getBooleanExtra(EXTRA_RECORD, false)) stage = recordStage(intent) else carPhoto()
        setContent {
            PleosTheme {
                LaunchedEffect(Unit) { trackMotion() }
                LaunchedEffect(Unit) { sendSteps() }
                val closedAtOpen = remember { CrashFlow.closed }
                LaunchedEffect(CrashFlow.closed) { if (CrashFlow.closed != closedAtOpen) { Voice.stop(); finish() } } // 진행 중 사고가 취소됨
                LaunchedEffect(Unit) { followPhone() } // 화면이 없어지면 함께 멈춤
                LaunchedEffect(pastDir) {
                    pastLoaded = false
                    past = pastDir?.let { name -> withContext(Dispatchers.IO) { CrashWatcher.load(File(filesDir, "crashes/$name")) } }
                    pastLoaded = true
                }
                val inc = CrashFlow.incident
                val screen = screenOf(inc)
                // 안내줄 모드: 창을 위쪽 띠로 줄여 아래 내비가 터치도 받게
                LaunchedEffect(screen == Stage.BANNER) {
                    val h = if (screen == Stage.BANNER) (BANNER_DP * resources.displayMetrics.density).toInt() else ViewGroup.LayoutParams.MATCH_PARENT
                    window.setLayout(ViewGroup.LayoutParams.MATCH_PARENT, h)
                    window.setGravity(Gravity.TOP)
                    // 창을 줄여도 기본(터치 독점)이면 띠 밖 터치를 삼킴 → 안내줄일 때만 밖 터치를 아래 내비로
                    if (screen == Stage.BANNER) window.addFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                    else window.clearFlags(WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL)
                }
                BackHandler { onBack(screen) }
                Box(Modifier.fillMaxSize()) {
                    AnimatedContent(screen, transitionSpec = { crossfade }, label = "screen") { s ->
                        val i = CrashFlow.incident
                        // 안내줄은 아래 내비가 비쳐야 해서 바탕을 칠하지 않음
                        Box(Modifier.fillMaxSize().then(if (s == Stage.BANNER) Modifier else Modifier.background(Pleos.backgroundPrimary))) {
                            when {
                                s == Stage.REFERENCE -> ReferenceView()
                                s == Stage.ASK -> AskFloor()
                                s == Stage.RECORD -> RecordView()
                                i == null -> Unit
                                s == Stage.POPUP -> FirstResponse(i)
                                s == Stage.BANNER -> Banner(i)
                                s == Stage.SCENE -> SceneView(i)
                                s == Stage.GUIDE -> GuideView(i)
                                else -> PhotoPages(i)
                            }
                        }
                    }
                    if (inc != null && pastDir == null && screen in STRIP_SCREENS) {
                        RecordBar(inc, Modifier.align(Alignment.BottomStart))
                        if (LocalConfiguration.current.screenWidthDp >= Pleos.wideMinWidth.value) when (screen) {
                            Stage.POPUP -> Unit // 첫 화면은 왼쪽 지평선 위(FirstResponse)
                            Stage.RECORD -> RecordLabel(inc, Modifier.align(Alignment.BottomStart).padding(start = 420.dp, bottom = 36.dp)) // 기록지가 오른쪽 끝까지 덮는다
                            else -> RecordLabel(inc, Modifier.align(Alignment.BottomEnd).padding(end = 48.dp, bottom = 30.dp))
                        }
                    }
                    if (fireOpen && screen in FIRE_SCREENS) FireView()
                    if (confirmLeave) LeaveDialog()
                }
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putString(KEY_STAGE, stage.name)
        outState.putInt(KEY_SHOT, shotIndex)
    }

    private fun screenOf(inc: Incident?) = when {
        stage == Stage.REFERENCE -> Stage.REFERENCE
        stage == Stage.ASK -> Stage.ASK
        stage == Stage.RECORD || inc == null -> Stage.RECORD
        !stopped -> Stage.BANNER // 주행 중엔 팝업도 띄우지 않음(내비를 가리지 않게). 정차하면 팝업부터
        stage == Stage.POPUP -> Stage.POPUP
        else -> stage
    }

    /** 정차 판단(디바운스) + 다시 달리면 화면 닫기 */
    private suspend fun trackMotion() {
        var stopSince = 0L
        var movingSince = 0L
        while (true) {
            val n = CarState.now
            parked = n.gear == "P"
            val now = SystemClock.uptimeMillis()
            if (n.speedKph < 1 || n.gear == "P") {
                if (stopSince == 0L) stopSince = now
                if (now - stopSince >= 1_500) stopped = true
            } else {
                stopSince = 0L
                if (n.speedKph >= 5) stopped = false // 1~5km/h 는 지금 상태 유지
            }
            if (n.speedKph >= 20 && stage != Stage.RECORD) {
                if (movingSince == 0L) movingSince = now
                // 안내줄은 내비를 가리지 않으니 넉넉히: 고속도로에서 갓길·쉼터를 찾는 동안 안내가 사라지지 않게
                else if (now - movingSince > 60_000) { notifySaved(); finish() }
            } else movingSince = 0L
            delay(200)
        }
    }

    private fun stepsOf(inc: Incident) = Guides.steps(inc.highway, persona, inc.severe, CrashFlow.kind, CrashFlow.hurt, !inc.roadKnown, CrashFlow.near)

    /** 답에 따라 건너뛰는 단계를 지나 닿는 번호 */
    private fun targetFrom(steps: List<GuideStep>, from: Int, dir: Int) =
        guideTarget(steps.map { it.art }, from, dir) { it.skipped(CrashFlow.near, CrashFlow.moved, CrashFlow.kind == CrashKind.PERSON) }

    /** 차에서 안내 단계를 옮기면 휴대폰도 같은 단계로 */
    private suspend fun sendSteps() = snapshotFlow {
        val inc = CrashFlow.incident
        if (inc == null || stage != Stage.GUIDE) null
        else inc.t to guideIndex.coerceIn(0, stepsOf(inc).lastIndex)
    }.collect { cur -> if (cur != null && cur != syncedStep) { syncedStep = cur; CarSync.step(cur.first, cur.second) } }

    /** 휴대폰에서 단계를 옮기면 차도 따라감 (3D·사진 화면이어도 안내 화면으로). 실패하면 2초 뒤 다시, 화면은 막지 않음 */
    private suspend fun followPhone() {
        var since = 0L
        var first = true
        while (true) {
            if (!CarSync.allowed()) { delay(2_000); continue }
            val s = withContext(Dispatchers.IO) { runCatching { CarSync.client().poll(since) }.getOrNull() }
            CarSync.ok = s != null
            if (s == null) { delay(2_000); continue }
            since = s.version // 서버가 다시 켜져 번호가 줄어도 그 번호부터
            CarSync.seen = s.seen
            if (pastDir != null) continue // 지난 사고를 보는 중엔 안내 단계로 끌려가지 않는다
            val opening = first
            first = false
            val inc = CrashFlow.incident ?: continue
            if (s.incident == null) { CarSync.publish(); continue } // 서버가 다시 켜져 비었으면 진행 중 사고를 다시 올린다
            val step = s.step ?: continue
            val same = s.incident?.t?.let { kotlin.math.abs(it - inc.t) < 0.001 } == true
            if (s.stepBy != "phone" || s.closed || !same || syncedStep == (inc.t to step)) continue
            syncedStep = inc.t to step
            if (step < guideIndex) backTo = step
            guideIndex = step; dialedStep = -1
            // 기록을 보려고 막 연 화면이면 밀려 있던 휴대폰 단계로 끌려가지 않게 번호만 맞춘다
            if (!opening || stage != Stage.RECORD) { confirmLeave = false; stage = Stage.GUIDE }
        }
    }

    private fun onBack(screen: Stage) {
        when {
            confirmLeave -> confirmLeave = false
            fireOpen -> fireOpen = false
            screen == Stage.REFERENCE || screen == Stage.ASK -> stage = Stage.RECORD
            screen == Stage.RECORD -> if (pastDir != null) leavePast() else finish()
            screen == Stage.POPUP || screen == Stage.BANNER -> Unit // 실수로 닫히지 않게 버튼으로만
            screen == Stage.SCENE -> confirmLeave = true
            screen == Stage.GUIDE -> {
                val back = shownSteps?.let { targetFrom(it, guideIndex, -1) } ?: (guideIndex - 1)
                if (back >= 0) { backTo = back; guideIndex = back } else confirmLeave = true
            }
            screen == Stage.PHOTOS -> if (shotIndex > 0) shotIndex-- else stage = Stage.GUIDE
        }
    }

    // 화면이 열려 있는 채로 새 사고(또는 연습 사고)가 오면 처음부터. 기록만 열 때는 하던 단계를 그대로 둔다
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        if (intent.getBooleanExtra(EXTRA_RESUME, false)) return // 앱 아이콘으로 돌아옴: 하던 단계 그대로
        persona = Personas.load(this) ?: Personas.guest
        if (intent.getStringExtra(EXTRA_DIR) != null && pastDir == null && CrashFlow.screenOpen) stageBeforePast = stage
        pastDir = intent.getStringExtra(EXTRA_DIR); past = null
        if (pastDir == null) CrashFlow.screenOpen = true
        stage = if (intent.getBooleanExtra(EXTRA_RECORD, false)) recordStage(intent) else Stage.POPUP
        dialedStep = -1; backTo = -1; confirmLeave = false; fireOpen = false; shownSteps = null
        if (stage == Stage.POPUP) { guideIndex = 0; shotIndex = 0; carPhoto() }
    }

    private fun recordStage(i: Intent) = if (i.getBooleanExtra(EXTRA_ASK, false)) Stage.ASK else Stage.RECORD

    /** 지난 사고 닫기: 뒤에 진행 중 안내가 살아 있으면 그 화면으로 돌아간다 */
    private fun leavePast() { if (CrashFlow.screenOpen) { pastDir = null; stage = stageBeforePast ?: Stage.GUIDE } else finish() }
    private var stageBeforePast: Stage? = null // 지난 사고를 열기 전에 보던 단계

    /** 사고 순간 차 카메라 사진(있으면). 되돌린 사고는 이미 저장된 사진을 쓴다 */
    private fun carPhoto() {
        val inc = CrashFlow.incident ?: return
        if (CrashFlow.record?.dir == CrashFlow.dir) return
        val f = File(cacheDir, "camera_${inc.t.toLong()}.jpg")
        CarCamera.snapshot(this, f) { ok -> if (ok) CrashFlow.snapshot = f }
    }

    override fun onResume() { super.onResume(); onScreen = true; scene.onResume() }
    override fun onPause() { super.onPause(); onScreen = false; scene.onPause(); Voice.stop() } // 전화 중에 안내 음성이 겹치지 않게
    override fun onDestroy() {
        runCatching { uxManager?.unregisterListener() }; runCatching { car?.disconnect() }
        if (isFinishing && pastDir == null) CrashFlow.screenOpen = false; Voice.stop(); Statement.stop(); scene.destroy(); super.onDestroy()
    }

    /** 차의 주행 규제 상태를 따라간다. 차 서비스가 없거나 막히면 속도 판정(stopped)만 쓴다 */
    private fun watchUx() {
        runCatching {
            val c = android.car.Car.createCar(this) ?: return
            car = c
            val m = c.getCarManager(android.car.Car.CAR_UX_RESTRICTION_SERVICE) as CarUxRestrictionsManager
            uxManager = m
            uxRestricted = m.currentCarUxRestrictions?.isRequiresDistractionOptimization == true
            m.registerListener { r -> uxRestricted = r.isRequiresDistractionOptimization }
        }.onFailure { android.util.Log.w("AskTheCar", "주행 규제 정보 없음", it) }
    }

    /** 안내 끝(기록 닫기)·"사고 아니에요": 휴대폰 알림·안내도 닫힘 */
    private fun close(notAccident: Boolean = false) { CrashFlow.incident?.let { CarSync.close(it.t, notAccident) }; CrashFlow.setIncident { null }; Voice.stop(); finish() }

    /** "사고 아니에요": 기록에 오탐 표시(홈의 회색 줄에서 7일 동안 되돌릴 수 있고 그 뒤 지워짐). 외부 AI 전송은 CrashWatcher 가 이 표시를 보고 멈춘다 */
    private fun dismiss() {
        CrashFlow.dir?.let { d -> runCatching { d.mkdirs(); File(d, "false_alarm").createNewFile() } } // 빈 파일 하나라 바로 쓴다(스레드면 홈 목록이 먼저 읽을 수 있음)
        notifySaved(falseAlarm = true); close(notAccident = true)
    }

    /** 통화 중인지(번호·기록은 안 읽음). 권한이 없거나 차가 막아 알 수 없으면 null: 그때는 앱으로 돌아오면 다음 단계로 간다 */
    private fun inCall() = runCatching { getSystemService(android.telecom.TelecomManager::class.java).isInCall }.getOrNull()

    /** 사고 화면을 닫을 때 기록을 다시 열 수 있게 알림 하나 */
    private fun notifySaved(falseAlarm: Boolean = false) {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("crash_record", "사고 기록", NotificationManager.IMPORTANCE_HIGH))
        val target = if (falseAlarm) Intent(this, MainActivity::class.java).putExtra(MainActivity.EXTRA_HOME, true) else Intent(this, CrashActivity::class.java).putExtra(EXTRA_RECORD, true)
        val open = PendingIntent.getActivity(this, if (falseAlarm) 4 else 1, target, PendingIntent.FLAG_IMMUTABLE)
        nm.notify(2, android.app.Notification.Builder(this, "crash_record").setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(if (falseAlarm) "사고 아님으로 표시했어요" else "사고 기록은 저장되어 있어요")
            .setContentText(if (falseAlarm) "기록은 7일 뒤 지워져요. 잘못 눌렀다면 눌러서 되돌리세요" else "눌러서 사고 기록과 대처 안내를 다시 볼 수 있어요")
            .setContentIntent(open).setAutoCancel(true).build())
    }
    /** ① 첫 응답(시안 B): 큰 질문과 충격 지점이 보이는 차, 답은 지평선 아래 버튼 셋. 고속도로는 답을 기다리지 않고 음성 대피 지시가 먼저 */
    @Composable
    private fun FirstResponse(inc: Incident) {
        LaunchedEffect(inc.t) {
            Voice.say(if (inc.highway) "비상등을 켜 주세요. 차가 움직이면 갓길로 옮겨 세우고, 트렁크를 연 뒤 휴대폰을 들고 가드레일 밖으로 나가세요. 다쳤다면 다쳤어요를 누르세요. 불이나 연기가 보이면 바로 차에서 멀어지세요."
                      else "${describe(inc)}. 다치셨나요?")
        }
        val clock = FactTimeline.ts(inc.t).take(8)
        val side = inc.impactDeg?.let { PhotoGuide.part(it).removeSuffix("쪽") + "쪽 " }.orEmpty()
        val fact = when {
            inc.reason.startsWith("에어백") -> "${side}에어백 신호를 기록했어요"
            inc.reason.startsWith("운전자") -> "직접 기록을 시작했어요"
            inc.reason.startsWith("속도 급감") -> "${side}속도 급감을 기록했어요"
            else -> "${side}충격을 기록했어요"
        }
        val hurt: () -> Unit = { // 다쳤으면 3D·대상 질문을 건너뛰고 119부터. 뒤에서 부상을 다시 묻지 않는다
            CrashFlow.hurt = true; CarSync.publish()
            guideIndex = 0; stage = Stage.GUIDE
        }
        val fine: () -> Unit = {
            if (CrashFlow.hurt) { CrashFlow.hurt = false; CarSync.publish() } // "다쳤어요"를 잘못 눌렀다가 되돌아온 경우
            stage = Stage.SCENE
        }
        StageFloor { horizon ->
            if (maxWidth >= Pleos.wideMinWidth) {
                CarFigure(carViewFor(inc.impactDeg), Modifier.carBox(carViewFor(inc.impactDeg), 760.dp, 290.dp, 880.dp).riseIn(), inc.impactDeg, animate = true)
                Text("다치셨나요?", style = Pleos.headlineL.copy(fontSize = 104.sp, lineHeight = 124.sp, letterSpacing = (-2).sp), modifier = Modifier.offset(x = 80.dp, y = 130.dp))
                Column(Modifier.offset(x = 80.dp, y = 300.dp).width(660.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) { // 시안 줄 간격 40: 한 줄짜리 Text 는 38 로 잘림
                    Text(clock, style = B30, color = Pleos.textSecondary)
                    Text(fact, style = B30, color = Pleos.textSecondary)
                    if (inc.impactDeg != null) Text("[센서 추정]", style = B30, color = Pleos.textTertiary)
                }
                FloorButton(FireGuide.LINK, FloorKind.BASIC, Modifier.align(Alignment.TopEnd).offset(x = (-64).dp, y = 48.dp), icon = BIcon.FLAME) { fireOpen = true }
                RecordLabel(inc, Modifier.offset(x = 80.dp, y = horizon - 70.dp))
                Row(Modifier.offset(x = 80.dp, y = horizon + 28.dp).width(maxWidth - 160.dp), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                    FloorButton("다쳤어요", FloorKind.RED, Modifier.weight(1.2f), onClick = hurt)
                    FloorButton("안 다쳤어요", FloorKind.DARK, Modifier.weight(1.2f), onClick = fine)
                    if (!inc.severe) FloorButton("사고가 아니에요", FloorKind.BASIC, Modifier.weight(1f)) { dismiss() } // 에어백 사고는 끌 수 없다(기획 §7)
                }
            } else Column(Modifier.fillMaxSize().padding(40.dp)) {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    CarFigure(CarView.TOP, Modifier.fillMaxHeight(0.86f), inc.impactDeg, animate = true)
                }
                Text(listOf(clock, fact).joinToString(" ") + if (inc.impactDeg != null) " [센서 추정]" else "", style = Pleos.bodyM, modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                Spacer(Modifier.height(32.dp))
                Text("다치셨나요?", style = Pleos.headlineL)
                Spacer(Modifier.height(32.dp))
                PleosButton("다쳤어요", ButtonKind.EMERGENCY, modifier = Modifier.fillMaxWidth(), onClick = hurt)
                Spacer(Modifier.height(24.dp))
                PleosButton("안 다쳤어요", ButtonKind.PRIMARY, modifier = Modifier.fillMaxWidth(), onClick = fine)
                Row { if (!inc.severe) PleosLink("사고가 아니에요") { dismiss() }; FireLink() }
            }
        }
    }

    /** 아직 달리는 중: 위쪽 안내줄만(아래 내비는 그대로). 멈추면 다음 화면이 자동으로 그려진다 */
    @Composable
    private fun Banner(inc: Incident) {
        val stopLine = if (inc.highway) "비상등을 켜고, 갈 수 있으면 갓길에 세워 주세요." else "안전한 곳에 차를 세워 주세요."
        LaunchedEffect(inc.t) {
            if (bannerSpokenFor != inc.t) { bannerSpokenFor = inc.t; Voice.say("$stopLine 차가 멈추면 이어서 안내할게요.") }
        }
        BoxWithConstraints(Modifier.fillMaxSize().background(Pleos.backgroundPopup, RectangleShape)) {
            val wide = maxWidth >= Pleos.wideMinWidth
            Row(
                Modifier.fillMaxSize().padding(horizontal = 48.dp, vertical = 20.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp),
            ) {
                LineArt(LineArts.impact(inc.impactDeg), Modifier.size(80.dp), animate = false) // 주행 중: 시안 "충격 기록" 끝 모습
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stopLine, style = (if (wide) Pleos.titleL else Pleos.titleM).copy(fontWeight = FontWeight.Bold))
                    if (wide) Text("차가 멈추면 이어서 안내할게요", style = Pleos.bodyM, color = Pleos.textSecondary)
                }
                if (!inc.severe) PleosButton("사고가 아니에요", ButtonKind.SECONDARY, large = false) { dismiss() }
            }
        }
    }

    /** 뒤로 가기: 첫 화면에서 나가려 할 때 한 번 더 묻는다 */
    @Composable
    private fun LeaveDialog() {
        Box(Modifier.fillMaxSize().background(SCRIM).pointerInput(Unit) { detectTapGestures { } }.padding(40.dp), contentAlignment = Alignment.Center) {
            PleosPopup("안내를 여기서 멈출까요?", "사고 기록은 저장되어 있어요. 알림을 눌러 언제든 다시 볼 수 있어요.") {
                PleosButton("계속 안내 받기", ButtonKind.PRIMARY, modifier = Modifier.weight(1f)) { confirmLeave = false }
                PleosButton("그만 볼게요", ButtonKind.SECONDARY, modifier = Modifier.weight(1f)) { confirmLeave = false; notifySaved(); finish() }
            }
        }
    }

    /** ② 사고 순간 3D + 무엇과 부딪혔나요(추정을 미리 선택). 자동으로 넘어가지 않는다 */
    @Composable
    private fun SceneView(inc: Incident) {
        val kind = CrashFlow.kind
        val wide = LocalConfiguration.current.screenWidthDp >= Pleos.wideMinWidth.value
        LaunchedEffect(inc.t) {
            Voice.say(describe(inc) + ". 무엇과 부딪혔나요?")
            if (!wide) scene.buildAndShow { sceneJson(inc.preLog, inc.t, inc.impactDeg, CrashFlow.kind, inc.highway, "scene", floor = true) } // 재현과 같은 그림(시안 2A floor)
        }
        if (wide) return KindFloor()
        LaunchedEffect(kind) { scene.setKind(kind) } // 카메라는 그대로, 상대 물체만 바꿈
        val unknown = kind == CrashKind.UNKNOWN
        val meta = listOfNotNull(inc.impactDeg?.let { "${PhotoGuide.part(it)}에서 충격 [추정]" }, if (inc.highway) "고속도로" else "시내", FactTimeline.ts(inc.t).take(8))
        val hint = inc.suggestWhy?.takeIf { !CrashFlow.kindChosen }?.let { "기록을 보고 미리 골라 두었어요. $it" } ?: "고른 대상에 맞춰 신고 안내와 찍을 사진이 달라져요."
        val preview: @Composable (Modifier) -> Unit = { m ->
            Box(m.clip(Pleos.shapeL).background(Pleos.backgroundSecondary, Pleos.shapeL)) {
                when {
                    !calm -> Text(whenCalm("사고 순간을"), style = Pleos.bodyL, color = Pleos.textTertiary, modifier = Modifier.align(Alignment.Center))
                    fail3d -> TrackView(inc.preLog, Modifier.fillMaxSize().padding(24.dp))
                    else -> Scene3DView(scene, Modifier.fillMaxSize())
                }
                Tag("사고 순간 [추정]")
            }
        }
        val heading: @Composable ColumnScope.() -> Unit = {
            Text("무엇과 부딪혔나요?", style = Pleos.headlineM)
            Text(meta.joinToString("  ·  "), style = Pleos.bodyL, color = Pleos.textSecondary)
            FireLink()
        }
        val tiles: @Composable (Modifier) -> Unit = { m ->
            PleosCard(m, padding = 24.dp, gap = 20.dp) {
                Text(hint, style = Pleos.bodyM, color = Pleos.textSecondary)
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    KIND_CHOICES.forEach { k -> KindTile(k, k == kind, Modifier.weight(1f).height(220.dp), iconSize = 96.dp) { CrashFlow.chooseKind(k) } } // 아이콘+글자 높이에 맞춤(4:3 이면 아래 2/3 가 빈 판, 검수 2026-11-10)
                }
            }
        }
        val next: @Composable RowScope.() -> Unit = {
            PleosButton(if (unknown) "건너뛰기" else "다음", if (unknown) ButtonKind.SECONDARY else ButtonKind.PRIMARY, modifier = Modifier.weight(1f)) {
                if (!unknown && !CrashFlow.kindChosen) CrashFlow.chooseKind(kind) // 미리 골라 둔 대상을 보고 넘어감 = 운전자 확인
                stage = Stage.GUIDE
            }
        }
        // 넓은 창: 위 = 재현 카드(왼쪽) + 질문·버튼(오른쪽), 아래 = 화면 폭 그대로 큰 대상 카드 3개. 좁은 창: 위아래로
        BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp)) {
            if (maxWidth >= Pleos.wideMinWidth) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(48.dp)) {
                    preview(Modifier.weight(1.5f).fillMaxHeight())
                    Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        heading()
                        Spacer(Modifier.weight(1f))
                        Row(Modifier.fillMaxWidth(), content = next)
                    }
                }
                tiles(Modifier.fillMaxWidth())
            } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                preview(Modifier.fillMaxWidth().height(320.dp))
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { heading(); tiles(Modifier.fillMaxWidth()) }
                Row(Modifier.fillMaxWidth(), content = next)
            }
        }
    }

    /** ② 대상 고르기(시안 cKind B): 어두운 큰 타일 셋. 고르면 안내로, 건너뛰면 기록으로 추정한 값(운전자 확인 아님)을 그대로 둔다 */
    @Composable
    private fun KindFloor() = StageFloor { horizon ->
        Text("무엇과 부딪혔나요?", style = Pleos.headlineL.copy(letterSpacing = (-0.6).sp), modifier = Modifier.offset(x = 80.dp, y = 99.dp))
        FloorButton(FireGuide.LINK, FloorKind.BASIC, Modifier.align(Alignment.TopEnd).offset(x = (-64).dp, y = 48.dp), icon = BIcon.FLAME) { fireOpen = true }
        Row(Modifier.offset(x = 80.dp, y = 250.dp).size(maxWidth - 160.dp, horizon - 290.dp), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            KIND_CHOICES.forEach { k -> KindCard(k, Modifier.weight(1f).fillMaxHeight()) { CrashFlow.chooseKind(k); stage = Stage.GUIDE } }
        }
        FloorButton("건너뛰기", FloorKind.BASIC, Modifier.offset(x = 80.dp, y = horizon + 28.dp)) { stage = Stage.GUIDE }
    }

    /**
     * ③ 가이드: 위 [‹ 이전] 단계 막대 몇/몇, 아래 큰 그림(16:9) + 제목 + 한 줄 + 버튼 최대 2개.
     * 자동으로 넘어가는 건 안심 단계뿐. 비상등은 켜진 게 확인되면 넘어가고, 값을 못 받는 차면 "켰어요"를 누른다.
     * 전화 단계는 통화를 마치고 돌아오면 다음으로 넘어가고, 걸지 않고 돌아오면 그 단계에 머문다.
     * 통화 여부를 알 수 없는 차(권한 없음 등)는 돌아오면 넘어간다. "이전"으로 돌아온 단계는 저절로 넘어가지 않는다.
     * 마지막 "기록을 저장해 두었어요" 단계는 차에선 건너뛰고 바로 사고 기록으로(같은 말을 두 번 하지 않게, 휴대폰은 그 단계를 보여 줌).
     */
    @Composable
    private fun GuideView(inc: Incident) {
        val kind = CrashFlow.kind
        val steps = remember(inc.highway, inc.severe, kind, persona, CrashFlow.hurt, inc.roadKnown, CrashFlow.near) { stepsOf(inc) }
        // 목록이 바뀌어도(에어백 승격·늦은 추정) 보던 단계를 그대로 보여 준다
        if (shownSteps != steps) {
            val old = shownSteps
            fun moved(i: Int) = old?.getOrNull(i)?.let(steps::indexOf) ?: -1
            val at = moved(guideIndex)
            backTo = moved(backTo); dialedStep = moved(dialedStep)
            shownSteps = steps
            if (at >= 0) guideIndex = at
        }
        val index = guideIndex.coerceIn(0, steps.lastIndex)
        val step = steps[index]
        val back = backTo == index
        // 고속도로: 대피 뒤로는 차로 돌아오면 안 되므로 차 화면은 고정, 안내는 휴대폰이 이어 간다
        val walkAt = steps.indexOfFirst { it.art == StepArt.WALK }
        val outside = inc.highway && walkAt >= 0 && index > walkAt
        var telltales by remember { mutableStateOf(CarState.telltalesSeen) }
        val waitHazard = step.art == StepArt.HAZARD && telltales
        val auto = step.art == StepArt.CALM && !back
        var hazardOn by remember(index) { mutableStateOf(false) }
        var hazardStuck by remember(index) { mutableStateOf(false) }
        var trunkMsg by remember(index) { mutableStateOf<String?>(null) }
        var trunkOpen by remember(index) { mutableStateOf(false) }
        val progress = remember(index) { mutableFloatStateOf(0f) }
        // 다음이 마지막 "기록 저장" 단계면 그 화면 대신 바로 사고 기록으로
        val toRecord = targetFrom(steps, index, 1).let { it > steps.lastIndex || steps[it].art == StepArt.RECORD }
        fun go(i: Int) = when {
            i < 0 -> { backTo = -1; shownSteps = null; stage = if (CrashFlow.hurt) Stage.POPUP else Stage.SCENE } // 앞 화면에서 답을 바꾸면 처음부터
            i > steps.lastIndex -> stage = Stage.RECORD
            else -> guideIndex = i
        }
        fun next() {
            val to = targetFrom(steps, index, 1)
            if (to <= steps.lastIndex && steps[to].art != StepArt.RECORD) return go(to)
            syncedStep = inc.t to steps.lastIndex; CarSync.step(inc.t, steps.lastIndex) // 휴대폰은 마지막 단계로
            stage = Stage.RECORD
        }
        fun dial(n: String) {
            if (step.art == StepArt.PERSON && !CrashFlow.near) { CrashFlow.near = true; CarSync.publish() }
            if (runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:$n"))) }.isSuccess) { dialedStep = index; dialTalked = false }
        }
        /** 두 답 중 하나: 고속도로 여부는 목록을 바꾸고, 차를 옮겼는지는 뒤의 단계를 정한다 */
        fun answer(first: Boolean) {
            if (step.art == StepArt.ROAD) { CrashFlow.setIncident { it?.copy(highway = first, roadKnown = true) }; CarSync.publish(); return }
            if (step.art == StepArt.MOVE_RISK && CrashFlow.moved != first) { CrashFlow.moved = first; CarSync.publish() }
            next()
        }
        LaunchedEffect(index, step.title, outside) {
            if (outside) { Voice.say(stayOut(CarSync.ok).joinToString(" ")); return@LaunchedEffect }
            Voice.say(step.voice)
            if (auto) {
                while (progress.floatValue < 1f) { delay(50); if (onScreen && !confirmLeave && !fireOpen) progress.floatValue += 50f / 6000f }
                next()
            }
        }
        if (step.art == StepArt.HAZARD) LaunchedEffect(index) {
            val since = SystemClock.uptimeMillis()
            while (true) {
                hazardOn = CarState.hazard; telltales = CarState.telltalesSeen
                if (hazardOn && !back && !fireOpen) { delay(1_200); next(); return@LaunchedEffect }
                if (SystemClock.uptimeMillis() - since > 15_000) hazardStuck = true // 버튼이 고장 나도 갇히지 않게
                delay(200)
            }
        }
        // 전화 앱은 뒤로 가기로 돌아오지 않는다(에뮬레이터 확인). 통화가 끝나면 안내를 다시 앞으로, 통화했으면 다음 단계로
        LaunchedEffect(onScreen, index) {
            if (onScreen) {
                if (dialedStep == index) {
                    val advance = dialTalked || inCall() != false // false = 통화 없이 돌아옴: 이 단계에 머문다
                    dialedStep = -1
                    if (advance) { delay(400); next() }
                }
                return@LaunchedEffect
            }
            while (dialedStep == index) {
                if (inCall() == true) dialTalked = true
                else if (dialTalked) { startActivity(Intent(this@CrashActivity, CrashActivity::class.java).putExtra(EXTRA_RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return@LaunchedEffect }
                delay(1_000)
            }
        }
        // 버튼은 한 화면에 최대 2개: 주된 행동(진한) + 다른 답(빈 칸)
        val actions: @Composable RowScope.() -> Unit = {
            val w = Modifier.weight(1f)
            when {
                step.call != null -> {
                    val n = step.call
                    val emergency = n == "119" || n == "112"
                    PleosButton(if (emergency) "$n 전화" else "전화 걸기", if (emergency) ButtonKind.EMERGENCY else ButtonKind.SAFE, modifier = w) { dial(n) }
                    PleosButton(step.decline ?: "다음", ButtonKind.SECONDARY, modifier = w) {
                        if (step.art == StepArt.PERSON && CrashFlow.near) { CrashFlow.near = false; CarSync.publish() } // 119를 잘못 눌렀다가 "없어요"
                        next()
                    }
                }
                // 비상등을 켜야 하는 동안은 진행 버튼이 없다(15초 뒤에만 건너뛰기). 되돌아온 단계는 저절로 안 넘어가니 항상 버튼
                waitHazard -> if (back || (!hazardOn && hazardStuck)) PleosButton(if (hazardOn) "다음" else "건너뛰기", ButtonKind.SECONDARY, modifier = w) { next() }
                // 차가 허용하면 앱이 트렁크를 대신 열어 줌 (Pleos Vehicle SDK Door REAR). 비상등은 SDK 로 켤 수 없어 운전자가 켬
                step.art == StepArt.TRUNK && !trunkOpen -> {
                    PleosButton("트렁크 열기", ButtonKind.PRIMARY, modifier = w) {
                        trunkMsg = "트렁크를 여는 중이에요"
                        CarControl.setTrunk(this@CrashActivity, true) { ok, why ->
                            if (!ok) android.util.Log.w("AskTheCar", "트렁크 열기 실패: $why")
                            trunkMsg = if (ok) "트렁크를 열었어요" else "안 열리면 그냥 두고 바로 대피해 주세요."
                            trunkOpen = ok
                        }
                    }
                    PleosButton("다음", ButtonKind.SECONDARY, modifier = w) { next() }
                }
                step.art.isPhoto -> {
                    PleosButton(step.next ?: "찍을 곳 알려 주세요", ButtonKind.PRIMARY, modifier = w) { shotIndex = 0; stage = Stage.PHOTOS }
                    PleosButton(step.decline ?: "건너뛰기", ButtonKind.SECONDARY, modifier = w) { next() }
                }
                step.decline != null -> {
                    PleosButton(step.next ?: "다음", ButtonKind.PRIMARY, modifier = w) { answer(true) }
                    PleosButton(step.decline, ButtonKind.SECONDARY, modifier = w) { answer(false) }
                }
                else -> PleosButton(if (toRecord) "사고 기록 보기" else step.next ?: "다음", ButtonKind.PRIMARY, modifier = w) { next() }
            }
        }
        // 넓은 창: 1A·2A 와 같은 바닥. 버튼이 지평선 아래 줄에 오도록 아래 여백을 맞춘다
        StageFloorIfWide(if (outside) StepArt.PHONE else step.art) { rail ->
        Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 64.dp, top = 16.dp, bottom = rail)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PleosLink("‹  이전") { val to = targetFrom(steps, index, -1); backTo = to; go(to) }
                Spacer(Modifier.width(24.dp))
                // 건너뛰는 단계는 세지 않는다
                val shown = steps.count { !it.art.skipped(CrashFlow.near, CrashFlow.moved, kind == CrashKind.PERSON) }
                val at = (steps.take(index + 1).count { !it.art.skipped(CrashFlow.near, CrashFlow.moved, kind == CrashKind.PERSON) } - 1).coerceAtLeast(0)
                StepProgress(at, shown, Modifier.weight(1f))
                Spacer(Modifier.width(24.dp))
                Text("${at + 1} / $shown", style = Pleos.labelM, color = Pleos.textTertiary)
                Spacer(Modifier.width(24.dp))
                FireLink()
            }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp)) {
                AnimatedContent(if (outside) -1 else index, transitionSpec = { crossfade }, label = "step") { i ->
                    val st = steps[i.coerceIn(0, steps.lastIndex)]
                    if (i < 0) StayOutView() else when (st.art) {
                        StepArt.HANDOFF -> HandoffView(steps, i.coerceIn(0, steps.lastIndex), actions)
                        StepArt.REPLAY -> ReplayView(inc, st, actions)
                        else -> StepContent(st, actions) {
                            if (st.art == StepArt.HAZARD) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                                // 빈 삼각형 = 켜 주세요 대기, 차가 실제 신호를 받으면 빨강이 덧그려지고 체크로 접힘(시안 "비상등 확인"). 100dp = 버튼 높이
                                if (waitHazard) LineArt(LineArts.HAZARD_OK, Modifier.size(100.dp), animate = calm, until = if (hazardOn) LineArts.HAZARD_OK.end else 0)
                                if (waitHazard) Status(
                                    if (hazardOn) "비상등이 켜졌어요" else if (back) "비상등을 켜 주세요" else "비상등을 켜면 저절로 다음으로 넘어가요",
                                    if (hazardOn) Pleos.textActive else Pleos.textTertiary,
                                )
                            }
                            if (st.art == StepArt.TRUNK) trunkMsg?.let { Status(it, if (trunkOpen) Pleos.textActive else Pleos.textTertiary) }
                            if (st.art == StepArt.CALM && auto) TimerBar({ progress.floatValue }, Modifier.padding(top = 8.dp))
                        }
                    }
                }
            }
        }
        }
    }

    @Composable
    private fun StageFloorIfWide(art: StepArt, content: @Composable (bottom: Dp) -> Unit) = BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth < Pleos.wideMinWidth) content(48.dp)
        else StageFloor { horizon ->
            // 그림은 지평선에 발을 딛는다. 글 아래로 잉크가 56dp 이상 떨어지도록 실제 글 높이에 맞춰 크기를 정한다
            var floorTop by remember { mutableStateOf(0f) }
            val stageWidth = maxWidth
            val density = LocalDensity.current
            AnimatedContent(art, transitionSpec = { crossfade }, label = "art") { a ->
                val top = with(density) { (textBottom - floorTop).toDp() }
                val fit = (horizon - top - 56.dp) / (1 - a.textClear)
                val artWidth = minOf(stageWidth, minOf(horizon - 88.dp, fit) * (16f / 9f))
                val paper = if (Pleos.dark) Color(0xFF1D1E21) else Color.White // 바닥을 그림 종이색으로: 그림 테두리가 네모로 보이지 않게
                if (a.fillsStage) Box(Modifier.fillMaxWidth().height(horizon).background(paper).clipToBounds().onGloballyPositioned { floorTop = it.boundsInRoot().top }, contentAlignment = if (a.rightInk != null) Alignment.BottomEnd else Alignment.BottomCenter) {
                    Box(Modifier.width(artWidth).offset(x = artWidth * (1 - (a.rightInk ?: 1f)))) {
                        StepHero(a, Modifier.fillMaxWidth(), calm, RectangleShape)
                        // 왼쪽에서 끊긴 선(시트 등)은 종이로 녹인다
                        if (artWidth < stageWidth) Box(Modifier.matchParentSize().drawBehind {
                            val w = size.width * 0.1f
                            drawRect(Brush.horizontalGradient(0.3f to paper, 1f to paper.copy(alpha = 0f), endX = w), size = Size(w, size.height))
                        })
                    }
                }
            }
            content((maxHeight - horizon - 128.dp).coerceAtLeast(24.dp))
        }
    }

    /** 일반 단계: 큰 그림 + 제목 + 한 줄(+ 전화 상대, 용어 풀이, 상태 한 줄) + 버튼 */
    @Composable
    private fun StepContent(step: GuideStep, actions: @Composable RowScope.() -> Unit, extra: @Composable ColumnScope.() -> Unit) {
        val k = if (persona.group == Group.SENIOR) 1.2f else 1f // 고령자: 글자 크게
        val tip = step.tip?.takeIf { persona.group == Group.NEWBIE }
        val over = step.art.fillsStage
        TextStep(
            // 트렁크는 "어디"가 중요해서 3D 차 뒷모습 위에 위치 표시, 사람 행동(비상등 누르기·사진 찍기)은 그림
            picture = when {
                step.art == StepArt.TRUNK -> { m -> Box(m.aspectRatio(16f / 10f).carStage(), contentAlignment = Alignment.Center) {
                    CarFigure(CarView.REAR, Modifier.fillMaxSize(0.94f), CrashFlow.incident?.impactDeg, listOf(CarPin("trunk", StepArt.TRUNK)), pinSize = 72.dp) } }
                step.art.hasPicture -> { m -> StepHero(step.art, m, calm) }
                else -> null
            },
            // 안심 단계는 기록이 저장되기 전엔 원만 그리고, 저장되면 체크(시안 "저장 완료")
            icon = { StepIcon(step.art, 112.dp, animate = calm, until = if (step.art == StepArt.CALM && CrashFlow.record == null) LineArts.CALM_RING else null) },
            actions = actions,
            overArt = over,
            onTextBottom = { textBottom = it },
            rail = extra.takeIf { over },
            side = if (over) null else ({
                tip?.let { t -> PleosCard(Modifier.fillMaxWidth(), label = "용어 풀이", padding = 24.dp, gap = 8.dp) { Text(t, style = Pleos.bodyM, color = Pleos.textSecondary) } }
                if (!step.art.hasPicture) RecordedFacts() // 그림 대신 차가 실제로 기록한 값(증인 역할, 보고서 "사고 앱 그림 사용 방식")
            }),
        ) {
            Text(step.title, style = Pleos.headlineM.copy(fontSize = Pleos.headlineM.fontSize * k, lineHeight = Pleos.headlineM.lineHeight * k))
            if (persona.mode != GuideMode.VOICE) Text(step.body, style = Pleos.bodyL, color = Pleos.textSecondary)
            if (over) tip?.let { Text(it, style = Pleos.bodyM, color = Pleos.textSecondary) }
            // 119·112 가 아닌 전화는 누구에게 거는지 번호와 함께 (버튼 글자는 짧게)
            step.call?.takeIf { it != "119" && it != "112" }?.let { n ->
                Text("${step.callName?.removeSuffix(" (가상)") ?: "전화"}  ·  $n", style = Pleos.titleS, color = Pleos.textPrimary)
            }
            if (!over) extra() // 그림 위 글은 짧게: 상태 표시는 버튼 옆으로
        }
    }

    /** 고속도로 대피 뒤 차 화면에 고정되는 글. 다쳐서 못 나온 운전자에게 "돌아오지 마세요"는 맞지 않아 뺀다 [추론] */
    private fun stayOut(connected: Boolean) = when {
        CrashFlow.hurt -> listOf("안내는 휴대폰에 있어요.", "못 나왔다면 119에 그렇게 말하고 안내를 따라 주세요.")
        connected -> listOf("차로 돌아오지 마세요. 안내는 휴대폰에 있어요.")
        else -> listOf("차로 돌아오지 말고 112나 119에 바로 전화해 주세요.")
    }

    @Composable
    private fun StayOutView() = TextStep(picture = null, actions = null) {
        val lines = stayOut(CarSync.ok)
        val inc = CrashFlow.incident
        LineArt(LineArts.EVIDENCE, Modifier.size(160.dp), animate = calm, until = if (inc != null && CarSync.recordAcked == inc.t) LineArts.EVIDENCE.end else LineArts.EVIDENCE_SENT)
        Text(lines.first(), style = Pleos.headlineM)
        lines.drop(1).forEach { Text(it, style = Pleos.bodyL, color = Pleos.textSecondary) }
        PleosLink("사고 기록 보기") { stage = Stage.RECORD }
    }

    /** 모든 안내 화면의 같은 자리(위 막대 오른쪽 끝)에 놓는 조용한 링크 */
    @Composable
    private fun FireLink(modifier: Modifier = Modifier) = PleosLink(FireGuide.LINK, modifier) { fireOpen = true } // 빨강은 경보처럼 보인다

    /** 불·연기 안내: 보던 화면 위에 덮고, "이전"으로 돌아간다 */
    @Composable
    private fun FireView() {
        LaunchedEffect(Unit) { Voice.say("${FireGuide.TITLE}. ${FireGuide.BODY}") }
        Column(Modifier.fillMaxSize().background(Pleos.backgroundPrimary).pointerInput(Unit) { detectTapGestures { } }.padding(start = 40.dp, end = 64.dp, top = 16.dp, bottom = 48.dp)) {
            PleosLink("‹  이전") { Voice.stop(); fireOpen = false }
            Spacer(Modifier.height(24.dp))
            Box(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp)) {
                TextStep(
                    picture = null, icon = { StepIcon(StepArt.FIRE, 112.dp, animate = calm) },
                    actions = { PleosButton("119 전화", ButtonKind.EMERGENCY, modifier = Modifier.weight(1f)) { runCatching { startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:119"))) } } },
                ) {
                    Text(FireGuide.TITLE, style = Pleos.headlineM)
                    Text(FireGuide.BODY, style = Pleos.bodyL, color = Pleos.textSecondary)
                }
            }
        }
    }

    /** 차가 기록한 사실 카드: 감지 시각·장소·충격 방향[추정]·기록 상태. 판단 단어 없이 값만 */
    @Composable
    private fun RecordedFacts() {
        val inc = CrashFlow.incident ?: return
        // 위: 위에서 본 3D 차 + 충격 자리 주황 빛(차량 제어 화면 방식), 아래: 값만 (판단 단어 없음)
        Column(Modifier.fillMaxWidth().pleosShadow(1, Pleos.shapeL).clip(Pleos.shapeL).background(Pleos.basic00)) {
            Box(Modifier.fillMaxWidth().height(400.dp).carStage(RectangleShape), contentAlignment = Alignment.Center) {
                CarFigure(CarView.TOP, Modifier.fillMaxHeight().padding(vertical = 20.dp), inc.impactDeg)
                Text("차가 기록한 것", style = Pleos.bodyS, color = Pleos.textTertiary, modifier = Modifier.align(Alignment.TopStart).padding(20.dp))
            }
            Column(Modifier.padding(horizontal = 28.dp, vertical = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                @Composable fun row(k: String, v: String, c: Color = Pleos.textPrimary) = Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Text(k, style = Pleos.bodyM, color = Pleos.textTertiary, modifier = Modifier.width(150.dp)); Text(v, style = Pleos.bodyM, color = c, modifier = Modifier.weight(1f))
                }
                inc.impactDeg?.let { row("충격 방향", "${PhotoGuide.part(it)} [추정]", Pleos.negative) } // 충격 = 증언색 하나
                row("감지 시각", FactTimeline.ts(inc.t).take(8))
                if (inc.roadKnown) row("장소", if (inc.highway) "고속도로" else "시내") // 제한속도 값이 없으면 속도 추정일 뿐이라 답을 받기 전엔 쓰지 않는다
                row("기록", CrashFlow.status)
            }
        }
    }

    /** 상태 한 줄(비상등 켜짐·트렁크 열림 등) */
    @Composable
    private fun Status(text: String, color: Color) = Text(text, style = Pleos.titleS, color = color)

    /** 촬영 안내: 3D에 찍을 위치 번호, 순서 목록. [찍을 곳 알려 주세요]로 각도 제안 가이드 */
    /** 휴대폰으로 이어서: 여기부터는 차 밖이라 휴대폰 앱이 같은 단계를 이어서 보여 줌. 남은 단계는 제목만(차에서 계속 [다음]도 됨) */
    @Composable
    private fun HandoffView(steps: List<GuideStep>, index: Int, actions: @Composable RowScope.() -> Unit) {
        val step = steps[index]
        val visible = steps.filterNot { it.art.skipped(CrashFlow.near, CrashFlow.moved, CrashFlow.kind == CrashKind.PERSON) }
        val at = visible.indexOf(step)
        val rest = visible.drop(at + 1).filter { it.art != StepArt.RECORD }
        val inc = CrashFlow.incident
        TextStep(
            picture = null, actions = actions,
            side = rest.takeIf { it.isNotEmpty() }?.let { r -> {
                PleosCard(Modifier.fillMaxWidth(), label = "휴대폰에서 이어질 일", padding = 24.dp, gap = 10.dp) {
                    r.take(3).forEachIndexed { k, st -> NumberedLine(at + k + 2, st.title) }
                    if (r.size > 3) Text("그 밖에 ${r.size - 3}단계", style = Pleos.bodyS, color = Pleos.textTertiary)
                }
            } },
        ) {
            // 시안 "기록 전송": 문서가 휴대폰으로 날아가고, 체크는 서버가 이 사고 기록을 받았다고 답한 뒤에만(CarSync.recordAcked)
            LineArt(LineArts.EVIDENCE, Modifier.size(160.dp), animate = calm, until = if (inc != null && CarSync.recordAcked == inc.t) LineArts.EVIDENCE.end else LineArts.EVIDENCE_SENT)
            Text(step.title, style = Pleos.headlineM)
            // 서버 응답이 없으면(실차는 아직 서버가 없음) 휴대폰에 보인다고 말하지 않는다. "연결됨"은 서버 응답일 뿐 휴대폰 로그인 여부가 아님
            Text(if (CarSync.ok) step.body else "휴대폰 연결이 없으면 차에 저장된 기록을 그대로 쓰세요.", style = Pleos.bodyL, color = Pleos.textSecondary)
        }
    }

    /** 번호 + 한 줄(촬영 순서·남은 단계) */
    @Composable
    private fun NumberedLine(n: Int, text: String) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(Modifier.size(44.dp).border(1.dp, Pleos.outline, CircleShape), contentAlignment = Alignment.Center) { Text("$n", style = Pleos.labelM) }
        Text(text, style = Pleos.bodyM, color = Pleos.textSecondary, modifier = Modifier.weight(1f))
    }

    /** 각도 제안 가이드: 한 장면씩 그림, 자동 넘김 없음(사용자가 직접 이전·다음) */
    @Composable
    private fun PhotoPages(inc: Incident) {
        val shots = PhotoGuide.shots(inc.impactDeg, CrashFlow.kind, inc.highway)
        val i = shotIndex.coerceIn(0, shots.lastIndex)
        val shot = shots[i]
        LaunchedEffect(i) { Voice.say(shot.title) }
        val header: @Composable () -> Unit = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                PleosLink("‹  이전") { if (i == 0) stage = Stage.GUIDE else shotIndex = i - 1 }
                Spacer(Modifier.width(24.dp))
                StepProgress(i, shots.size, Modifier.weight(1f))
                Spacer(Modifier.width(24.dp))
                Text("사진 ${i + 1} / ${shots.size}", style = Pleos.labelM, color = Pleos.textTertiary)
                Spacer(Modifier.width(24.dp))
                FireLink()
            }
        }
        val text: @Composable ColumnScope.() -> Unit = {
            Text(shot.title, style = Pleos.headlineM)
            Text(shot.line, style = Pleos.bodyL, color = Pleos.textSecondary)
            if (shot.suggestion) Text("앱 제안 · 공식 안내에서 찾지 못한 방법이에요", style = Pleos.labelM, color = Pleos.textSecondary)
        }
        val next: @Composable RowScope.() -> Unit = {
            PleosButton(if (i == shots.lastIndex) "다 찍었어요" else "다음", ButtonKind.PRIMARY, modifier = Modifier.weight(1f)) {
                if (i == shots.lastIndex) { stage = Stage.GUIDE; guideIndex = targetFrom(stepsOf(inc), guideIndex, 1) } else shotIndex = i + 1
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            // 넓은 창: 위에서 본 현장 선 그림을 바닥(stage) 위에 화면 높이 절반 넘게, 글은 왼쪽 빈 자리, 버튼은 지평선 아래(다른 단계와 같은 규칙)
            if (maxWidth >= Pleos.wideMinWidth) StageFloor { horizon ->
                // 그림은 오른쪽 600dp 안, 제목은 900dp 까지(720 이면 "주세요" 한 단어가 둘째 줄에 홀로 떨어짐, 검수 2026-11-10)
                ShotSchematic(shot, CrashFlow.kind, inc.impactDeg, Modifier.offset(x = maxWidth - 664.dp, y = 96.dp).size(600.dp, horizon - 120.dp), calm)
                Column(Modifier.padding(start = 40.dp, end = 64.dp, top = 16.dp).fillMaxWidth()) {
                    header()
                    Spacer(Modifier.height(32.dp))
                    Column(Modifier.widthIn(max = 960.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = text)
                }
                Row(Modifier.offset(x = 40.dp, y = horizon + 28.dp).width(900.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = next)
            } else Column(Modifier.fillMaxSize().padding(start = 40.dp, end = 64.dp, top = 16.dp, bottom = 48.dp)) {
                header()
                Spacer(Modifier.height(24.dp))
                Box(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp)) {
                    TextStep(
                        picture = { m -> Box(m.aspectRatio(16f / 9f).clip(Pleos.shapeL).background(Pleos.backgroundSecondary, Pleos.shapeL)) { ShotSchematic(shot, CrashFlow.kind, inc.impactDeg, Modifier.fillMaxSize(), calm) } },
                        actions = next, content = text,
                    )
                }
            }
        }
    }

    /** 사고 장면 재현: 기록이 준비되면(10초 기록 + 사진 분석) 3D 로 재생, 아니면 준비 중 %. 재생·시간 이동은 3D 화면 아래 막대 */
    @Composable
    private fun ReplayView(inc: Incident, step: GuideStep, actions: @Composable RowScope.() -> Unit) {
        val r = CrashFlow.record
        if (r != null) LaunchedEffect(r.dir, r.seen) { // 경위서 문장만 바뀔 때는 재생을 처음으로 돌리지 않는다
            scene.buildAndShow { sceneJson(r.log, inc.t, r.impactDeg, CrashFlow.kind, inc.highway, "replay", r.seen, floor = true) } // 기록 화면과 같은 그림
        }
        Split(
            hero = { m ->
                Box(m.fillMaxHeight().clip(Pleos.shapeL).background(Pleos.backgroundSecondary, Pleos.shapeL), contentAlignment = Alignment.Center) {
                    when {
                        !calm -> Text(whenCalm("재현을"), style = Pleos.bodyL, color = Pleos.textTertiary)
                        r == null -> ProgressRing(CrashFlow.progress, Modifier.size(200.dp))
                        fail3d -> TrackView(r.log, Modifier.fillMaxSize())
                        else -> Scene3DView(scene, Modifier.fillMaxSize())
                    }
                    if (r != null && calm) Tag("끌어서 돌려 볼 수 있어요", Modifier.align(Alignment.TopStart))
                }
            },
            actions = actions, heroWeight = 1.5f,
        ) {
            if (r == null) {
                Text("사고 장면을 그리고 있어요", style = Pleos.headlineM)
                Text(CrashFlow.status, style = Pleos.bodyL, color = Pleos.textSecondary)
            } else {
                Text(step.title, style = Pleos.headlineM)
                Text(step.body, style = Pleos.bodyL, color = Pleos.textSecondary)
            }
        }
    }


    /** 기록 화면에서 한 번에 읽어 오는 파일 정보(재구성마다 파일을 읽지 않게 IO 스레드에서) */
    private class RecordFiles(val photo: ImageBitmap?, val hash: String, val crashT: Double, val video: File?, val lockedAt: Double?)

    /** 사고 기록: 왼쪽은 재현 반복 재생, 오른쪽은 진술·경위서. 진술이 가장 먼저 보인다 */
    @Composable
    private fun RecordView() {
        if (pastDir != null && !pastLoaded) return
        val r = viewed
        val markDir = r?.dir ?: CrashFlow.dir.takeIf { pastDir == null }
        val wide = LocalConfiguration.current.screenWidthDp >= Pleos.wideMinWidth.value
        var falseAlarm by remember(markDir) { mutableStateOf(markDir?.let { File(it, "false_alarm").exists() } == true) }
        val undoFalseAlarm: () -> Unit = { markDir?.let { if (CrashFlow.reopen(this, it)) { pastDir = null; falseAlarm = false; stage = Stage.POPUP } } } // 첫 질문부터, 휴대폰도 다시 열림
        var shareMsg by remember { mutableStateOf<String?>(null) }
        var sharing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        var showVideo by remember(r?.dir) { mutableStateOf(false) }
        val files by produceState<RecordFiles?>(null, r) { // 사진은 첫 표시 뒤에 복사되므로 기록이 갱신될 때마다 다시 읽는다
            value = r?.let { rec ->
                withContext(Dispatchers.IO) {
                    val photo = File(rec.dir, "camera.jpg")
                    RecordFiles(
                        // 원본 크기 그대로(화면에서 1배 넘게 늘리지 않음). 에뮬레이터 가짜 카메라 같은 160px 이하 그림은 깨진 화면으로 보여 숨긴다
                        photo.takeIf { it.exists() }?.let { BitmapFactory.decodeFile(it.path)?.takeIf { b -> b.width > 160 && b.height > 160 }?.asImageBitmap() },
                        runCatching { File(rec.dir, "log.sha256").readText().take(16) }.getOrDefault(""),
                        runCatching { JSONObject(File(rec.dir, "impact.json").readText()).getDouble("t") }.getOrNull()
                            ?: CrashFlow.incident?.t ?: ((rec.log.lastOrNull()?.t ?: 10.0) - 10),
                        File(rec.dir, CameraVideo.FILE).takeIf { it.exists() },
                        File(rec.dir, "log.sha256").takeIf { it.exists() }?.let { it.lastModified() / 1000.0 },
                    )
                }
            }
        }
        val f = files
        if (r != null && f != null) LaunchedEffect(r.dir, r.seen, f.crashT) {
            scene.buildAndShow { sceneJson(r.log, f.crashT, r.impactDeg, viewedKind, CrashFlow.incident?.takeIf { pastDir == null }?.highway ?: false, "replay", r.seen, floor = true) }
            askSeek?.let { delay(800); scene.seek(it); askSeek = null } // shortcut: 페이지가 새 장면을 받았다는 신호가 없어 잠깐 기다림, 느린 차에서 시각 이동이 안 될 수 있음
        }
        if (wide && r != null) {
            RecordFloor(r, f, falseAlarm, undoFalseAlarm)
            return
        }
        val kindLine = "부딪힌 대상: ${viewedKind.label}" + if (viewedKind == CrashKind.UNKNOWN) "" else " (${if (viewedChosen) "운전자 선택" else "기록으로 추정"})"
        Column(Modifier.fillMaxSize().padding(start = 56.dp, end = 56.dp, top = 24.dp, bottom = 40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            // 머리: 왼쪽 제목·한 줄 요약, 오른쪽 조용한 이동(대처 안내·닫기). 되돌리기 버튼만 진하게
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("사고 기록", style = Pleos.headlineS)
                    if (r != null) Text(listOfNotNull(f?.crashT?.let { FactTimeline.ts(it).take(8) }, kindLine).joinToString("  ·  "), style = Pleos.bodyM, color = Pleos.textTertiary)
                }
                // "사고 아니에요"를 잘못 눌렀을 때 되돌리기: 표시를 지우면 보통 기록처럼 30일 보관
                if (falseAlarm) PleosButton("사고가 맞아요", ButtonKind.PRIMARY, large = false, onClick = undoFalseAlarm)
                if (CrashFlow.incident != null && pastDir == null) PleosLink("대처 안내") { stage = Stage.GUIDE }
                PleosLink("닫기") { if (pastDir != null) leavePast() else close() }
            }
            if (r == null) {
                PleosCard(Modifier.fillMaxWidth()) {
                    Text(if (CrashFlow.incident != null || falseAlarm) CrashFlow.status + "\n사고 후 10초까지 기록한 뒤 경위서를 만들어요." else "저장된 사고 기록이 없어요.",
                        style = Pleos.bodyL, color = Pleos.textSecondary)
                }
                return@Column
            }
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                // 왼쪽: 재현 카드(재생 막대는 3D 화면 안). 주행 중엔 그리지 않는다
                Box(Modifier.weight(if (showVideo) 3f else 1.15f).fillMaxHeight().clip(Pleos.shapeL).background(Pleos.backgroundSecondary, Pleos.shapeL)) {
                    when {
                        !calm -> Text(whenCalm("재현을"), style = Pleos.bodyL, color = Pleos.textTertiary, modifier = Modifier.align(Alignment.Center))
                        fail3d -> TrackView(r.log, Modifier.fillMaxSize().padding(24.dp))
                        else -> Scene3DView(scene, Modifier.fillMaxSize())
                    }
                    if (calm && showVideo) f?.video?.let { CameraVideoView(it, r.dir, r.log, f.crashT, Modifier.fillMaxSize().background(Pleos.backgroundSecondary)) }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (!showVideo) Tag("사고 장면 재현 [추정]")
                        if (calm && f?.video != null) PleosButton(if (showVideo) "재현 보기" else "카메라 영상 보기", ButtonKind.SECONDARY, large = false, modifier = Modifier.padding(20.dp)) { showVideo = !showVideo }
                    }
                }
                // 오른쪽: 카드(진술 → 경위 설명문 → 사진)는 스크롤, 할 일 버튼은 아래에 고정
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                        StatementView(r)
                        PleosCard(Modifier.fillMaxWidth(), label = "경위 설명문", gap = 14.dp) {
                            ReportLines(r.report)
                            r.vision.forEach { Text(it, style = Pleos.bodyM, color = Pleos.textSecondary) }
                            if (!f?.hash.isNullOrEmpty()) Text("기록 지문(SHA-256) ${f?.hash}…", style = Pleos.labelS, color = Pleos.textTertiary)
                        }
                        if (f?.photo != null && calm) PleosCard(Modifier.fillMaxWidth(), label = "현장 사진 · ${FactTimeline.ts(f.crashT).take(8)}") { PhotoCell(f.photo) }
                    }
                    shareMsg?.let { Text(it, style = Pleos.bodyM, color = Pleos.textSecondary) }
                    Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        PleosButton("공유하기 (PDF 기록서)", ButtonKind.PRIMARY, modifier = Modifier.weight(1.4f)) {
                            if (sharing) return@PleosButton
                            sharing = true; shareMsg = "기록서를 만들고 있어요"
                            scope.launch { shareMsg = share(r); sharing = false }
                        }
                        PleosButton("읽어 주기", ButtonKind.SECONDARY, modifier = Modifier.weight(1f)) { Voice.say((listOf(r.report) + r.vision).joinToString("\n")) }
                    }
                    PleosLink("비슷한 사고 유형 보기  ›", Modifier.align(Alignment.End)) { stage = Stage.REFERENCE }
                }
            }
        }
    }

    /** ⑤ 사고 기록(시안 2A): 재현 위의 시간이 주인공, 오른쪽 기록지 한 장, 버튼은 지평선 아래. 재현·영상은 P단에서만 */
    @Composable
    private fun RecordFloor(r: CrashRecord, f: RecordFiles?, falseAlarm: Boolean, undoFalseAlarm: () -> Unit) {
        var showVideo by remember(r.dir) { mutableStateOf(false) }
        var shareMsg by remember { mutableStateOf<String?>(null) }
        var sharing by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()
        val statement by produceState<Pair<String, Long>?>(null, Statement.status, r.dir) {
            value = withContext(Dispatchers.IO) { File(r.dir, "statement.txt").takeIf { it.exists() }?.let { it.readText() to it.lastModified() } }
        }
        DisposableEffect(Unit) { onDispose { Statement.stop() } } // 멈춤 버튼이 없는 화면으로 가면 마이크도 끈다
        val rows = remember(r, f, statement) { ledgerRows(r, f?.crashT, f?.lockedAt, statement) }
        val watch = calm && parked
        val where = CrashFlow.incident?.takeIf { pastDir == null }?.let { if (it.highway) " · 고속도로" else " · 시내" }.orEmpty()
        StageFloor { horizon ->
            Box(Modifier.size(1096.dp, horizon), contentAlignment = Alignment.Center) {
                when {
                    !calm -> Text(whenCalm("재현을"), style = Pleos.bodyL, color = Pleos.textTertiary)
                    !parked -> Text("P단에 두면 재현을 보여 드려요", style = Pleos.bodyL, color = Pleos.textTertiary)
                    fail3d -> TrackView(r.log, Modifier.fillMaxSize().padding(80.dp))
                    else -> Scene3DView(scene, Modifier.fillMaxSize())
                }
                if (watch && showVideo) f?.video?.let { CameraVideoView(it, r.dir, r.log, f.crashT, Modifier.fillMaxSize().background(Pleos.stageMiddle).padding(start = 60.dp, top = 124.dp)) }
            }
            Box(Modifier.offset(x = 80.dp, y = 32.dp).size(80.dp).clip(Pleos.shapeIcon).background(Pleos.buttonBasic, Pleos.shapeIcon).pressable {
                when {
                    showVideo -> showVideo = false
                    pastDir != null -> leavePast()
                    CrashFlow.incident != null -> stage = Stage.GUIDE
                    else -> finish()
                }
            }, contentAlignment = Alignment.Center) { PleosIcon(PleosIcons.BACK, Pleos.textPrimary, Modifier.size(48.dp)) }
            Text("사고 기록", style = Pleos.titleL, modifier = Modifier.offset(x = 176.dp, y = 30.dp))
            // 3D 도로선 위에 뜨는 조작부라 불투명 판으로 띄운다(시안 2A 위쪽엔 뒤로·제목뿐)
            if (watch) PleosButton("차에게 묻기", ButtonKind.SECONDARY, large = false, modifier = Modifier.offset(x = 760.dp, y = 32.dp).width(300.dp).pleosShadow(1, Pleos.shapeM).background(Pleos.backgroundPopup, Pleos.shapeM)) { stage = Stage.ASK }
            Text((f?.crashT?.let { FactTimeline.ts(it).take(8) }.orEmpty() + where).removePrefix(" · "), style = Pleos.bodyM, color = Pleos.textSecondary, modifier = Modifier.offset(x = 176.dp, y = 84.dp))
            if (watch && !showVideo && f?.video != null) {
                Text("앵글 영상 · 시연: 카메라 접근이 열릴 경우", style = Pleos.labelM, color = Pleos.textSecondary, modifier = Modifier.offset(x = 80.dp, y = horizon - 312.dp))
                Row(Modifier.offset(x = 80.dp, y = horizon - 270.dp).size(640.dp, 128.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf("뒤", "앞", "왼쪽", "오른쪽").forEach { name ->
                        Box(Modifier.weight(1f).fillMaxHeight().clip(Pleos.shapeS).background(Color(0xFF2B2D33), Pleos.shapeS).pressable { showVideo = true }.padding(12.dp), contentAlignment = Alignment.BottomStart) {
                            Text(name, style = Pleos.labelM, color = Color.White)
                        }
                    }
                }
            }
            // 기록지 한 장: 왼쪽만 둥글고 오른쪽은 창 끝까지
            Column(Modifier.offset(x = 1120.dp, y = 24.dp).size(maxWidth - 1120.dp, maxHeight - 48.dp).pleosShadow(1, Pleos.shapeSheet).background(Pleos.backgroundSecondary, Pleos.shapeSheet)) {
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(start = 32.dp, end = 40.dp, top = 28.dp)) {
                    rows.forEachIndexed { i, row ->
                        if (i > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pleos.divider))
                        LedgerLine(row, Modifier.fillMaxWidth().then(if (row.t != null && watch) Modifier.pressable { scene.seek(row.t) } else Modifier))
                    }
                    if (f?.photo != null && watch) {
                        Box(Modifier.fillMaxWidth().height(1.dp).background(Pleos.divider))
                        Text("현장 사진 · ${FactTimeline.ts(f.crashT).take(8)}", style = Pleos.labelM, color = Pleos.textTertiary, modifier = Modifier.padding(top = 20.dp, bottom = 8.dp))
                        PhotoCell(f.photo)
                        Spacer(Modifier.height(20.dp))
                    }
                }
                // 덜 중요한 이동 둘은 작은 링크 한 줄로(시안 2A 의 주 행동은 "휴대폰으로 이어 보기" 하나). 스크롤 밖 고정: 기록이 길어도 "안내 끝내기"가 버튼에 가려지지 않게(검수 2026-11-10)
                Box(Modifier.fillMaxWidth().padding(horizontal = 32.dp).height(1.dp).background(Pleos.divider))
                Row(Modifier.padding(horizontal = 32.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Text("기록으로 본 사고 모양", style = Pleos.bodyM, color = Pleos.textSecondary, modifier = Modifier.pressable { stage = Stage.REFERENCE }.padding(vertical = 8.dp))
                    if (pastDir == null) {
                        Text("·", style = Pleos.bodyM, color = Pleos.textTertiary)
                        Text("안내 끝내기", style = Pleos.bodyM, color = Pleos.textSecondary, modifier = Modifier.pressable { close() }.padding(vertical = 8.dp))
                    }
                }
                shareMsg?.let { Text(it, style = Pleos.bodyM, color = Pleos.textSecondary, modifier = Modifier.padding(horizontal = 32.dp, vertical = 8.dp)) }
                PleosButton("휴대폰으로 이어 보기", ButtonKind.PRIMARY, icon = PleosIcons.DOC, modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                    if (sharing) return@PleosButton
                    sharing = true; shareMsg = "기록서를 만들고 있어요"
                    scope.launch { shareMsg = share(r); sharing = false }
                }
            }
            // 말로 남기기·읽어 주기는 아이콘 버튼(이름표는 아래 작은 글자): 주 버튼은 기록지의 "휴대폰으로 이어 보기" 하나
            IconAction(if (Statement.active) "멈춤" else if (statement == null) "말로 남기기" else "다시 말하기", PleosIcons.MIC, Modifier.offset(x = 80.dp, y = horizon + 24.dp), active = Statement.active) {
                if (Statement.active) Statement.stop() else Statement.start(r.dir)
            }
            IconAction("읽어 주기", PleosIcons.SPEAKER, Modifier.offset(x = 240.dp, y = horizon + 24.dp)) { Voice.say((listOf(r.report) + r.vision).joinToString("\n")) }
            when {
                falseAlarm -> PleosButton("사고가 맞아요", ButtonKind.PRIMARY, large = false, modifier = Modifier.offset(x = 760.dp, y = horizon + 38.dp), onClick = undoFalseAlarm)
                Statement.status.isNotEmpty() -> Text(Statement.status, style = Pleos.bodyM, color = Pleos.textTertiary, modifier = Modifier.offset(x = 760.dp, y = horizon + 60.dp).width(330.dp))
                else -> Text("실선 = 차 기록 · 점선 = 추정\n도로선은 참고 그림", style = Pleos.labelM, color = Pleos.textSecondary, modifier = Modifier.offset(x = 760.dp, y = horizon + 50.dp))
            }
        }
    }

    /** 시안 5A 차 화면판: 증언 한 문장과 전폭 시간. 정차(P단)에서만, 다시 달리면 기록으로 돌아간다 */
    @Composable
    private fun AskFloor() {
        val r = viewed
        if (r == null || !parked) { LaunchedEffect(Unit) { stage = Stage.RECORD }; return }
        val crash by produceState<Double?>(null, r.dir) { value = withContext(Dispatchers.IO) { crashTimeOf(r) } }
        val t = crash ?: return
        var ask by rememberSaveable { mutableStateOf(Ask.HAZARD) }
        val lamps = CarState.telltalesSeen || r.log.any { it.lamps.isNotEmpty() } // 경고등 값을 못 받는 차는 "기록에 없어요"
        fun answer(q: Ask) = CarAnswer.of(q, r.log, t, controls = true, steer = true, lamps = lamps, facts = r.facts)
        val a = remember(r, t, ask, lamps) { answer(ask) }
        StageFloor { horizon ->
            Box(Modifier.offset(x = 80.dp, y = 32.dp).size(80.dp).clip(Pleos.shapeIcon).background(Pleos.buttonBasic, Pleos.shapeIcon).pressable { stage = Stage.RECORD }, contentAlignment = Alignment.Center) {
                PleosIcon(PleosIcons.BACK, Pleos.textPrimary, Modifier.size(48.dp))
            }
            Row(Modifier.offset(x = 176.dp, y = 40.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Ask.entries.forEach { q ->
                    val on = q == ask
                    Box(Modifier.height(64.dp).clip(Pleos.shapeS).pressable { ask = q; Voice.say(answer(q).plain) }.background(if (on) Pleos.buttonFilled else Pleos.buttonBasic, Pleos.shapeS).padding(horizontal = 24.dp), contentAlignment = Alignment.Center) {
                        Text(q.chip, style = Pleos.labelM.copy(fontWeight = FontWeight.Bold), color = if (on) Pleos.basic00 else Pleos.textPrimary)
                    }
                }
            }
            Text("\"${ask.quoted}\"", style = Pleos.bodyM, color = Pleos.textTertiary, modifier = Modifier.offset(x = 80.dp, y = 176.dp))
            Text(boldMarks(a.text), style = Pleos.headlineS.copy(fontWeight = FontWeight.Normal), modifier = Modifier.offset(x = 80.dp, y = 232.dp).width(1020.dp))
            a.source?.let { Text(it, style = Pleos.bodyM.copy(fontFeatureSettings = "tnum"), color = Pleos.textSecondary, modifier = Modifier.offset(x = 80.dp, y = 444.dp)) }
            AskStrip(r.log, t, a.at, a.source == null, 6.dp, 50.dp, Modifier.offset(x = 80.dp, y = horizon - 430.dp).size(maxWidth - 160.dp, 430.dp).semantics { contentDescription = "시간에 따른 속도와 그 순간" })
            PleosButton("다시 듣기", ButtonKind.SECONDARY, modifier = Modifier.offset(x = 80.dp, y = horizon + 28.dp).width(300.dp)) { Voice.say(a.plain) }
            PleosLink("기록에서 보기", Modifier.offset(x = 380.dp, y = horizon + 38.dp)) { askSeek = a.at?.let { it - t }; stage = Stage.RECORD }
        }
    }

    /** 충돌 시각: impact.json > 진행 중 사고 > 기록 끝(충돌 후 10초 기록)에서 역산 */
    private fun crashTimeOf(r: CrashRecord) = runCatching { JSONObject(File(r.dir, "impact.json").readText()).getDouble("t") }.getOrNull()
        ?: CrashFlow.incident?.t ?: ((r.log.lastOrNull()?.t ?: 10.0) - 10)

    /**
     * ④ 참고: 기록된 사실을 "사고 모양"으로 말로 옮겨 보여 준다. 유형 "판정"이 아니라 사실 나열이고,
     * 판단은 보험사·분쟁심의위원회 몫임을 크게 알린다. 도표 번호·비율·외부 과실 사이트는 차 화면에 두지 않는다(증인 원칙, 2026-11-09 검수).
     * 공식 자료는 휴대폰 접수 화면(FaultPage)에서만 연다. 후보 계산은 typeCandidates() (Guide.kt)
     */
    @Composable
    private fun ReferenceView() {
        val r = viewed
        val kind = viewedKind
        val candidates by produceState<List<TypeCandidate>?>(null, r?.dir, kind) {
            value = if (r == null) emptyList() else withContext(Dispatchers.IO) {
                typeCandidates(r.log, crashTimeOf(r), r.impactDeg, kind, zone = ClaimText.KST, chosen = viewedChosen)
            }
        }
        val body: @Composable ColumnScope.() -> Unit = {
            Column(
                Modifier.fillMaxWidth().background(Pleos.backgroundPopup, Pleos.shapeL).border(1.dp, Pleos.divider, Pleos.shapeL).padding(36.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text("이 앱은 과실 비율을 판단하지 않아요.", style = Pleos.titleL)
                Text("실제 판단은 보험사·과실비율분쟁심의위원회가 합니다. 공식 자료는 휴대폰 접수 화면에서 볼 수 있어요.", style = Pleos.bodyL, color = Pleos.textSecondary)
            }
            Text("기록된 사실을 말로 옮긴 것이에요. 실제 사고 유형이 같은지는 이 앱이 알 수 없어요.", style = Pleos.labelM, color = Pleos.textTertiary)
            val list = candidates
            when {
                list == null -> Text("기록을 살펴보는 중이에요", style = Pleos.bodyM, color = Pleos.textTertiary)
                list.isEmpty() -> Text("기록된 사실만으로는 사고 모양을 말로 옮기기 어려워요.", style = Pleos.bodyL, color = Pleos.textSecondary)
                else -> list.forEach { c ->
                    PleosCard(Modifier.fillMaxWidth(), gap = 10.dp) {
                        Text(c.name, style = Pleos.titleL)
                        Text("이 기록에서 나왔어요", style = Pleos.labelM, color = Pleos.textTertiary)
                        c.facts.forEach { Text("· $it", style = Pleos.bodyM, color = Pleos.textSecondary) }
                    }
                }
            }
        }
        BoxWithConstraints(Modifier.fillMaxSize()) {
            if (maxWidth < Pleos.wideMinWidth) Column(Modifier.fillMaxSize().padding(40.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("참고 · 기록으로 본 사고 모양", style = Pleos.headlineS)
                    Spacer(Modifier.weight(1f))
                    PleosButton("기록으로 돌아가기", ButtonKind.SECONDARY, large = false) { stage = Stage.RECORD }
                }
                body()
            } else StageFloor { horizon -> // 다른 화면과 같은 바닥·지평선, 버튼은 지평선 아래 줄
                Box(Modifier.offset(x = 80.dp, y = 32.dp).size(80.dp).clip(Pleos.shapeIcon).background(Pleos.buttonBasic, Pleos.shapeIcon).pressable { stage = Stage.RECORD }, contentAlignment = Alignment.Center) { PleosIcon(PleosIcons.BACK, Pleos.textPrimary, Modifier.size(48.dp)) }
                Text("참고 · 기록으로 본 사고 모양", style = Pleos.titleL, modifier = Modifier.offset(x = 176.dp, y = 30.dp))
                Column(Modifier.offset(x = 80.dp, y = 136.dp).size(maxWidth - 160.dp, horizon - 160.dp).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(24.dp), content = body)
                PleosButton("기록으로 돌아가기", ButtonKind.PRIMARY, modifier = Modifier.offset(x = 80.dp, y = horizon + 28.dp).width(maxWidth - 160.dp)) { stage = Stage.RECORD }
            }
        }
    }

    /** 운전자 진술: 말로 남기기 (STT, 안 되면 녹음). 사고 기록에서 가장 먼저 보이는 칸 */
    @Composable
    private fun StatementView(r: CrashRecord) {
        val saved by produceState<String?>(null, Statement.status, r.dir) {
            value = withContext(Dispatchers.IO) { File(r.dir, "statement.txt").takeIf { it.exists() }?.readText() }
        }
        DisposableEffect(Unit) { onDispose { Statement.stop() } } // 멈춤 버튼이 없는 화면으로 가면 마이크도 끈다
        PleosCard(Modifier.fillMaxWidth(), label = "운전자 진술") {
            Text(saved?.let { "“$it”" } ?: "기억이 흐려지기 전에 무슨 일이 있었는지 말로 남겨 두세요.", style = Pleos.bodyL,
                color = if (saved != null) Pleos.textPrimary else Pleos.textSecondary)
            if (Statement.status.isNotEmpty()) Text(Statement.status, style = Pleos.bodyS, color = Pleos.textTertiary)
            if (Statement.active) PleosButton("멈춤", ButtonKind.SECONDARY, large = false, modifier = Modifier.fillMaxWidth()) { Statement.stop() }
            else PleosButton(if (saved == null) "말로 남기기" else "다시 말하기", ButtonKind.SECONDARY, large = false, modifier = Modifier.fillMaxWidth()) { Statement.start(r.dir) }
        }
    }

    /** 화면에 보여 줄 안내를 돌려준다. 토스트는 이 화면에서 안 보였다(에뮬레이터에서 직접 눌러 확인). PDF(사진·도표)는 무거워 IO 스레드에서 만든다 */
    private suspend fun share(r: CrashRecord): String? {
        val p = persona; val kind = viewedKind; val chosen = viewedChosen
        withContext(Dispatchers.IO) { runCatching { FactSheet.write(this@CrashActivity, r, p, kind, chosen) }.onFailure { android.util.Log.w("AskTheCar", "PDF 기록서 실패", it) } }
        val uris = listOf("report.pdf", "report.txt", "log.json", "camera.jpg", CameraVideo.FILE, "statement.m4a").map { File(r.dir, it) }.filter { it.exists() }
            .map { FileProvider.getUriForFile(this, "com.askthecar.files", it) }
        val send = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_TEXT, r.report)
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList<Uri>(uris))
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        // 차에 공유받을 앱이 없을 수 있다(에뮬레이터 확인). 그래도 PDF는 차 안에 남아 있음
        if (packageManager.queryIntentActivities(send, 0).isEmpty()) {
            if (pastDir != null) return "기록서는 차 안에 저장되어 있어요." // 지난 사고는 휴대폰에 다시 보내는 경로가 아직 없다
            if (!CarSync.allowed() || !CarSync.ok) return "휴대폰 연결을 확인하고 있어요. 기록서는 차 안에 저장되어 있어요."
            CarSync.publish()
            return "휴대폰으로 보냈어요. 보험사 전달은 휴대폰에서 이어 하세요."
        }
        startActivity(Intent.createChooser(send, "사고 기록 공유"))
        return null
    }

    companion object {
        const val EXTRA_RECORD = "record"
        /** 지난 사고 폴더 이름: 그 사고의 기록을 연다 */
        const val EXTRA_DIR = "dir"
        /** 기록 대신 차에게 묻기로 연다 */
        const val EXTRA_ASK = "ask"
        /** 앱 아이콘으로 돌아올 때: 단계를 처음으로 돌리지 않음 */
        const val EXTRA_RESUME = "resume"
        private const val KEY_STAGE = "stage"
        private const val KEY_SHOT = "shot"
        private const val BANNER_DP = 180
        /** "확인 안 됨"은 칸으로 두지 않는다(차와 안내가 같아 고를 이유가 없음). 안 고르면 건너뛰기 */
        private val KIND_CHOICES = listOf(CrashKind.VEHICLE, CrashKind.OBJECT, CrashKind.PERSON)
        private val FIRE_SCREENS = setOf(Stage.POPUP, Stage.SCENE, Stage.GUIDE, Stage.PHOTOS)
        private val STRIP_SCREENS = setOf(Stage.POPUP, Stage.SCENE, Stage.GUIDE, Stage.PHOTOS, Stage.RECORD, Stage.ASK)
        private val crossfade = fadeIn(tween(Pleos.durationMedium, easing = Pleos.easing)) togetherWith fadeOut(tween(Pleos.durationMedium, easing = Pleos.easing))
    }
}

/** 기록지 한 줄. mark 는 앱 전체 기록 문법: 0 = 차가 기록(채운 점), 1 = 추정(점선 고리), 2 = 운전자가 말함(빈 동그라미) */
private class LedgerRow(val t: Double?, val time: String, val text: String, val mark: Int)

private fun clockSeconds(s: String) = runCatching { s.split(":").let { it[0].toInt() * 3600 + it[1].toInt() * 60 + it[2].toDouble() } }.getOrNull()

/** 기록지 행: 사실 목록(시작·종료 줄 제외) + 기록 잠금 + 운전자 진술 + 사진 인식. 시각 순서 */
private fun ledgerRows(r: CrashRecord, crashT: Double?, lockedAt: Double?, statement: Pair<String, Long>?): List<LedgerRow> {
    val crash = crashT?.let { clockSeconds(FactTimeline.ts(it)) }
    val atCrash = crashT?.let { ClaimText.near(r.log, it) }?.let { "%.0f km/h".format(it.speedKph) }
    // 화면은 충돌 -10초~기록 끝(재현 창과 같음)만 쉬운 말로. 더 이른 줄과 원문은 PDF·경위서에 그대로 남는다. 불리한 줄(가속·브레이크 뗌)도 뺀 것 없음
    val rows = r.facts.filter { "기록 시작" !in it && "기록 종료" !in it }.mapNotNull { line ->
        val time = line.substringBefore(' ')
        val dt = clockSeconds(time)?.let { s -> crash?.let { s - it } }
        if (dt != null && dt < -10) null else LedgerRow(dt, time, plainFact(line.substringAfter(' '), r.impactDeg, atCrash), if ("추정" in line) 1 else 0)
    }.toMutableList()
    lockedAt?.let { rows += LedgerRow(null, FactTimeline.ts(it).take(8), "기록을 잠갔어요. 이후 수정할 수 없어요", 0) }
    statement?.let { (text, at) -> rows += LedgerRow(null, FactTimeline.ts(at / 1000.0).take(8), "운전자: “${text.trim()}”", 2) }
    return rows.sortedBy { it.time } + r.vision.map { LedgerRow(null, "사진", it, 1) }
}

/** 기록지 화면용 쉬운 말(시안 2A). 숫자·판단 단어 없이 사실만, g 값은 뺀다(원문은 PDF) */
private fun plainFact(text: String, impactDeg: Double?, speedAtCrash: String?): String = when {
    "브레이크 페달 밟음" in text -> "브레이크를 밟았어요"
    "브레이크 페달 뗌" in text -> "브레이크를 뗐어요"
    text.startsWith("계기판 경고등 켜짐: 비상등") -> "비상등이 켜졌어요"
    text.startsWith("계기판 경고등 꺼짐: 비상등") -> "비상등이 꺼졌어요"
    text.startsWith("계기판 경고등 켜짐: ") -> "${text.substringAfter(": ")} 경고등이 켜졌어요"
    text.startsWith("계기판 경고등 꺼짐: ") -> "${text.substringAfter(": ")} 경고등이 꺼졌어요"
    text.startsWith("가속 시작") -> "가속을 시작했어요"
    text.startsWith("좌측 방향지시등") -> "왼쪽 방향지시등이 켜졌어요"
    text.startsWith("우측 방향지시등") -> "오른쪽 방향지시등이 켜졌어요"
    text.startsWith("방향지시등 꺼짐") -> "방향지시등이 꺼졌어요"
    text.startsWith("에어백 전개 신호") -> "에어백 신호를 기록했어요"
    text.startsWith("충격 감지") -> (impactDeg?.let { "${PhotoGuide.part(it)}에서 온 충격을 기록했어요 [추정]" } ?: "충격을 기록했어요") + (speedAtCrash?.let { "\n그때 속도 $it" } ?: "")
    text.startsWith("사고 감지") -> "사고를 기록했어요 (충격 센서 값 없음)"
    text.startsWith("기어 ") -> "기어를 ${text.removePrefix("기어 ").substringBefore("단")}로 바꿨어요"
    text.startsWith("조향 직진") -> "핸들을 바로 했어요"
    text.startsWith("좌측 조향") -> "왼쪽으로 핸들을 돌렸어요"
    text.startsWith("우측 조향") -> "오른쪽으로 핸들을 돌렸어요"
    text.startsWith("크루즈 컨트롤") -> "크루즈 컨트롤이 ${text.substringAfterLast(' ')}어요".replace("켜짐어요", "켜졌어요").replace("꺼짐어요", "꺼졌어요")
    else -> text
}

@Composable
private fun LedgerLine(row: LedgerRow, modifier: Modifier) {
    val ink = Pleos.textPrimary
    Row(modifier.padding(vertical = 22.dp), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Canvas(Modifier.padding(top = 8.dp).size(24.dp)) {
            val r = 8.dp.toPx(); val w = 3.dp.toPx()
            when (row.mark) {
                0 -> drawCircle(ink, r)
                1 -> drawCircle(ink, r, style = Stroke(w, pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
                else -> drawCircle(ink, r, style = Stroke(w))
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(row.time, style = Pleos.titleS)
            Text(row.text, style = Pleos.bodyM, color = Pleos.textSecondary)
        }
    }
}

/** 사고 설명 한 줄(판단 없이): "앞쪽에서 강한 충격이 감지되었어요" */
private fun describe(inc: Incident): String {
    val side = inc.impactDeg?.let { PhotoGuide.part(it).removeSuffix("쪽") + "쪽에서 " } ?: ""
    return when {
        inc.reason.startsWith("에어백") -> "${side}에어백이 터질 만큼 큰 충격이 있었어요"
        inc.reason.startsWith("충격") -> "${side}충격이 감지되었어요"
        inc.reason.startsWith("속도 급감") -> "속도가 갑자기 줄었어요"
        else -> "${side}충격이 감지되었어요"
    }
}

/** 자동 넘김 타이머: 막대만(글자 없음). 디자인 규칙 Slider 처럼 둥근 막대. 값은 그리기 단계에서만 읽어 화면을 다시 구성하지 않는다 */
@Composable
fun TimerBar(progress: () -> Float, modifier: Modifier = Modifier) {
    val track = Pleos.basic200; val fill = Pleos.buttonFilled
    Canvas(modifier.fillMaxWidth().height(10.dp)) {
        drawLine(track, Offset(5.dp.toPx(), size.height / 2), Offset(size.width - 5.dp.toPx(), size.height / 2), size.height, StrokeCap.Round)
        val x = 5.dp.toPx() + (size.width - 10.dp.toPx()) * progress().coerceIn(0f, 1f)
        drawLine(fill, Offset(5.dp.toPx(), size.height / 2), Offset(x, size.height / 2), size.height, StrokeCap.Round)
    }
}

/** 준비 진행도 고리 + 가운데 % */
@Composable
private fun ProgressRing(p: Float, modifier: Modifier) {
    val track = Pleos.basic200; val fill = Pleos.positive
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.fillMaxSize()) {
            val sw = size.minDimension * 0.08f
            drawArc(track, 0f, 360f, false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw))
            drawArc(fill, -90f, 360f * p.coerceIn(0f, 1f), false, Offset(sw / 2, sw / 2), Size(size.width - sw, size.height - sw), style = Stroke(sw, cap = StrokeCap.Round))
        }
        Text("${(p * 100).toInt()}%", style = Pleos.headlineS.copy(fontWeight = FontWeight.Bold))
    }
}

/** 팝업 뒤 어두운 막 */
private val SCRIM = Color(0x66000000)

/** 현장 사진: 원본보다 크게 늘리지 않고(최대 1배) 비율 그대로 */
@Composable
private fun PhotoCell(photo: ImageBitmap) {
    val maxW = with(LocalDensity.current) { photo.width.toDp() }
    Image(photo, "사고 직후 차 카메라 사진", Modifier.widthIn(max = maxW).fillMaxWidth().aspectRatio(photo.width.toFloat() / photo.height).clip(Pleos.shapeS), contentScale = ContentScale.Fit)
}

/** 동그란 아이콘 버튼 + 아래 작은 이름표(기록 화면의 보조 행동) */
@Composable
private fun IconAction(label: String, icon: String, modifier: Modifier, active: Boolean = false, onClick: () -> Unit) =
    Column(modifier.width(140.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(Modifier.size(80.dp).clip(Pleos.shapeIcon).background(if (active) Pleos.buttonFilled else Pleos.buttonBasic, Pleos.shapeIcon).pressable(onClick = onClick), contentAlignment = Alignment.Center) {
            PleosIcon(icon, if (active) Pleos.basic00 else Pleos.textPrimary, Modifier.size(44.dp))
        }
        Text(label, style = Pleos.labelM, color = Pleos.textSecondary)
    }

/**
 * 넓은 창(1앱 가로 1700dp): 왼쪽 큰 그림 + 오른쪽 글·버튼(세로 가운데).
 * 좁은 창(2앱 분할 840dp·세로): 위 그림, 가운데 글(넘치면 손으로 스크롤), 맨 아래 버튼.
 * 버튼 줄은 최대 760dp(가이드 Medium 팝업 폭)까지만 넓어진다.
 */
@Composable
private fun Split(hero: @Composable (Modifier) -> Unit, actions: (@Composable RowScope.() -> Unit)?, heroWeight: Float = 1.12f, content: @Composable ColumnScope.() -> Unit) =
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (maxWidth >= Pleos.wideMinWidth) Column(Modifier.fillMaxSize()) { // 버튼은 다른 단계처럼 왼쪽 아래(지평선 아래 줄)
            Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                hero(Modifier.weight(heroWeight))
                Spacer(Modifier.width(72.dp))
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp), content = content)
            }
            actions?.let { Row(Modifier.padding(top = 24.dp).widthIn(max = 900.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), content = it) }
        } else Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(32.dp)) {
            hero(Modifier.fillMaxWidth().heightIn(max = 480.dp))
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(20.dp)) { content() }
            actions?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), content = it) }
        }
    }

/**
 * 단계 화면(넓은 창): 왼쪽 = 글 먼저(작은 아이콘·제목·설명) + 아래 버튼, 오른쪽 = 정보 그림(있을 때만, 화면의 약 40%).
 * 그림이 없으면 글 칸이 넓어지고 [side](용어 풀이 등)는 오른쪽에. 좁은 창은 위아래로.
 * 예전 HeroStack(매 단계 화면 폭 큰 그림)은 "광고 같다"는 지적으로 바꿈(2026-11-07).
 */
@Composable
private fun TextStep(
    picture: (@Composable (Modifier) -> Unit)?, icon: (@Composable () -> Unit)? = null, actions: (@Composable RowScope.() -> Unit)?,
    side: (@Composable ColumnScope.() -> Unit)? = null, overArt: Boolean = false, onTextBottom: (Float) -> Unit = {}, rail: (@Composable ColumnScope.() -> Unit)? = null, content: @Composable ColumnScope.() -> Unit,
) = BoxWithConstraints(Modifier.fillMaxSize()) {
    val wide = maxWidth >= Pleos.wideMinWidth
    if (wide && overArt) {
        Column(Modifier.fillMaxSize()) {
            // 폭 800 = 그림 왼쪽 52% 안(Art.kt textClear 를 잰 칸)
            Column(Modifier.widthIn(max = 800.dp).onGloballyPositioned { onTextBottom(it.boundsInRoot().bottom) }, verticalArrangement = Arrangement.spacedBy(12.dp), content = content)
            Spacer(Modifier.weight(1f))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                actions?.let { Row(Modifier.width(900.dp), horizontalArrangement = Arrangement.spacedBy(16.dp), content = it) }
                rail?.let { Column(content = it) }
            }
        }
        return@BoxWithConstraints
    }
    val text: @Composable ColumnScope.() -> Unit = {
        if (picture == null && icon != null) { icon(); Spacer(Modifier.height(8.dp)) }
        content()
    }
    if (!wide) Column(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(24.dp)) {
        picture?.invoke(Modifier.fillMaxWidth().heightIn(max = 360.dp))
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) { text(); side?.invoke(this) }
        actions?.let { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), content = it) }
    } else Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(64.dp)) {
        Column(Modifier.weight(1f).fillMaxHeight()) {
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp, Alignment.CenterVertically), content = text)
            actions?.let { Row(Modifier.padding(top = 24.dp).widthIn(max = 900.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), content = it) }
        }
        if (picture != null || side != null) Column(Modifier.weight(0.6f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(24.dp, Alignment.CenterVertically)) { // 오른쪽 보조 칸 ≈ 화면의 3분의 1 + 조금
            picture?.invoke(Modifier.fillMaxWidth())
            side?.invoke(this)
        }
    }
}

/** 단계 막대: 지난 단계·지금 단계는 진하게, 남은 단계는 옅게(각진 칸) */
@Composable
private fun StepProgress(index: Int, count: Int, modifier: Modifier) =
    Row(modifier.height(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        repeat(count) { k -> Box(Modifier.weight(1f).fillMaxHeight().background(if (k <= index) Pleos.buttonFilled else Pleos.basic200)) }
    }

/** 그림 위 작은 이름표(왼쪽 위) */
@Composable
private fun Tag(text: String, modifier: Modifier = Modifier) =
    Text(text, style = Pleos.labelM, color = Pleos.textSecondary, modifier = modifier.padding(20.dp).background(Pleos.backgroundPopup, Pleos.shapeXS).padding(horizontal = 18.dp, vertical = 10.dp))
