package com.askthecar

import ai.pleos.playground.stt.SpeechToText
import ai.pleos.playground.stt.listener.ResultListener
import ai.pleos.playground.stt.constant.ErrorType as SttError
import ai.pleos.playground.stt.constant.Mode as SttMode
import ai.pleos.playground.stt.listener.OnInitializeListener as SttInitListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener as SttServerListener
import ai.pleos.playground.tts.TextToSpeech
import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.media.MediaRecorder
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.util.Base64
import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.concurrent.thread
import kotlin.math.sqrt

/**
 * 사고 하나의 결과물. dir 안에 log.json, log.sha256, report.txt,
 * (있으면) camera.jpg·camera.sha256(차 카메라 사진), cameras.mp4·cameras.sha256·cameras_end.txt(여러 카메라 영상, 시연), statement.txt 또는 statement.m4a(운전자 진술), report.pdf
 * vision = "[영상 분석 추정]" 문장들 (숫자·판단 단어 검증을 통과한 것만)
 */
data class CrashRecord(
    val dir: File, val log: List<Sample>, val report: String, val facts: List<String>, val vision: List<String> = emptyList(),
    val impactDeg: Double? = null, val seen: Sighting? = null,
    /** 폴더의 kind.txt. 없으면 null(지금 사고의 값은 CrashFlow 에) */
    val kind: CrashKind? = null, val kindChosen: Boolean = false,
)

/** 지금 진행 중인 사고. 사고 화면(CrashActivity)이 이것을 보고 그린다. */
/** impactDeg = 가속도 센서로 추정한 충격 방향(앞 0°, 오른쪽 90°), 센서 충격이 없었으면 null */
data class Incident(
    val t: Double, val reason: String, val severe: Boolean, val highway: Boolean, val impactDeg: Double? = null,
    /** 충돌 전 10초 기록 — 사고 직후 3D 상황 화면용(전체 기록은 10초 뒤 CrashRecord) */
    val preLog: List<Sample> = emptyList(),
    /** 추정 근거 한 줄 */
    val suggestWhy: String? = null,
    /** false = 제한속도 값이 없어 highway 가 평균 속도 추정일 뿐. 안내에서 운전자에게 묻는다 */
    val roadKnown: Boolean = true,
)

/** 쓰는 도중 전원이 끊겨도 앞의 파일이 잘리지 않게 임시 파일에 쓰고 바꿔 넣는다 */
private fun File.writeAtomic(text: String) {
    val tmp = File(parentFile, "$name.tmp")
    tmp.writeText(text)
    if (!tmp.renameTo(this)) throw java.io.IOException("rename failed: $name")
}

object CrashFlow {
    var incident by mutableStateOf<Incident?>(null)
    var record by mutableStateOf<CrashRecord?>(null)
    var status by mutableStateOf("주행 기록 중")
    /** 팝업에서 운전자가 "다쳤어요"를 고름. 안내가 119부터 시작하고 부상을 다시 묻지 않는다 */
    @Volatile var hurt = false
    /** 이 사고에서 차가 한 번이라도 멈췄음(속도<1 또는 P). 휴대폰은 멈춘 뒤에만 울린다 */
    @Volatile var halted = false
    /** 이 사고의 기록 저장이 실패함. 없으면 아래 띠가 "저장 중"에 머무른다 */
    var saveFailed by mutableStateOf<Double?>(null)
    /** 안내 "다친 사람이 있나요?"에서 119를 눌렀음 */
    var near by mutableStateOf(false)
    /** "차가 위험한 자리에 있나요?"에서 옮겼다고 답함 */
    var moved by mutableStateOf(false)
    /** 지금 보는 대처 단계 번호. 화면을 닫았다 다시 열어도 이어지게 여기 둔다 */
    var guideStep by mutableIntStateOf(0)
    /** 운전자가 고른 사고 종류(처음엔 제안값) */
    var kind by mutableStateOf(CrashKind.UNKNOWN)
    /** 지금 사고의 폴더: 운전자가 고른 종류를 kind.txt 로 남겨 재시작 뒤 기록·PDF 에도 같은 값 */
    @Volatile var dir: File? = null

    /** 운전자가 직접 골랐으면 추정이 나중에 바뀌어도 덮어쓰지 않음 */
    @Volatile var kindChosen = false
    fun chooseKind(k: CrashKind, byDriver: Boolean = true) {
        if (byDriver) kindChosen = true
        kind = k
        CarSync.publish() // 휴대폰 앱도 같은 종류·단계로
        dir?.let { d -> thread(isDaemon = true) { runCatching { d.mkdirs(); File(d, "kind.txt").writeText(k.name + if (kindChosen) "" else " guess") } } }
    }
    /** 사고 기록·재현 준비 진행도 0..1 (재현 화면의 "준비 중" 게이지) */
    var progress by mutableStateOf(0f)
    /** 바뀌면 열린 사고 화면을 닫는다 */
    var closed by mutableStateOf(0)
    /** 사고 순간 차 카메라(Camera2) 사진 */
    @Volatile var snapshot: File? = null
    /** 사고 화면이 열려 있음(전화 등으로 가려져도). 앱 아이콘을 누르면 이 화면으로 돌아감 */
    @Volatile var screenOpen = false

    /** 감지기는 센서·기록·1초 뒤 보정 스레드에서 incident 를 바꾼다 → 읽고 바꾸기를 한 곳에서 묶음 */
    @Synchronized fun setIncident(f: (Incident?) -> Incident?) { incident = f(incident) }

    /** "사고 아님" 되돌리기: 표시를 지우고 그 사고를 다시 진행 중으로(안내는 첫 질문부터, 사진·기록은 그대로). 못 읽으면 false */
    fun reopen(ctx: Context, d: File): Boolean {
        val r = CrashWatcher.load(d) ?: return false
        val t = CrashWatcher.crashTime(d) ?: return false
        File(d, "false_alarm").delete()
        ctx.getSystemService(android.app.NotificationManager::class.java).cancel(2) // "사고 아님으로 표시했어요"
        dir = d; kind = r.kind ?: CrashKind.UNKNOWN; kindChosen = r.kindChosen
        hurt = false; near = false; moved = false; guideStep = 0; progress = 1f; halted = true; saveFailed = null
        record = r
        val highway = r.log.filter { it.t in (t - 30)..t }.map { it.speedKph }.average().let { !it.isNaN() && it >= 80 }
        setIncident { Incident(t, if (File(d, CrashWatcher.MANUAL).exists()) "운전자 직접 기록" else "충격 감지", r.log.any { it.airbag }, highway, r.impactDeg, r.log.filter { it.t in (t - 10)..t }, roadKnown = false) } // 제한속도는 그때 값이 없어 안내에서 묻는다
        CarSync.reopen(t)
        CarSync.step(t, 0) // 차는 첫 질문부터라, 서버에 남은 옛 단계로 휴대폰이 어긋나지 않게
        CarSync.publish()
        return true
    }
}

/** Pleos TTS. 키(secrets.properties)가 있으면 HYBRID + 앱 등록, 없으면 등록이 필요 없는 ON_DEVICE */
object Voice {
    private var tts: TextToSpeech? = null

    fun init(ctx: Context) {
        val hasKey = BuildConfig.PLEOS_CLIENT_ID.isNotBlank()
        tts = TextToSpeech(ctx, if (hasKey) Mode.HYBRID else Mode.ON_DEVICE).apply {
            initialize(object : OnInitializeListener {
                override fun onSuccess() {
                    setLocale(Locale.KOREA)
                    if (hasKey) registerApp(
                        clientID = BuildConfig.PLEOS_CLIENT_ID,
                        clientSecret = BuildConfig.PLEOS_CLIENT_SECRET,
                        listener = object : OnServerConnectionListener {
                            override fun onConnected() { Log.i("AskTheCar", "TTS 서버 연결됨") }
                            override fun onFailed(errorType: ErrorType) { Log.w("AskTheCar", "TTS 앱 등록 실패 $errorType") }
                        },
                    )
                }
                override fun onFailure(errorType: ErrorType) { Log.w("AskTheCar", "TTS 초기화 실패 $errorType") }
            })
        }
    }

    fun say(text: String) {
        val link = Sim.link
        if (link != null && link.connected) link.say(text) { tts?.run { stop(); speak(text) } }
        else tts?.run { stop(); speak(text) }
    }
    fun stop() { if (Sim.connected) Sim.link?.say("") {} ; tts?.stop() }
    fun release() { tts?.release(); tts = null }
}

/**
 * 운전자 진술 1건. Pleos STT로 글자로 받아 statement.txt 에 저장한다.
 * STT가 오류를 내면(에뮬레이터 마이크·서버 등) 마이크 녹음 statement.m4a 로 대신 남긴다.
 */
object Statement {
    var status by mutableStateOf("")
        private set
    var active by mutableStateOf(false)
        private set
    private lateinit var app: Context
    private var stt: SpeechToText? = null
    private var ready = false
    private var dir: File? = null
    private var recorder: MediaRecorder? = null
    private var heard = false
    private const val NOT_HEARD = "말씀이 인식되지 않았어요. 다시 눌러 말씀해 주세요."

    private val listener = object : ResultListener {
        override fun onReady() {}
        override fun onStartedRecognition() { status = "듣고 있어요. 사고 상황을 말씀해 주세요." }
        override fun onUpdated(stt: String, completed: Boolean) {
            status = "\u201c$stt\u201d"
            if (completed && stt.isNotBlank()) dir?.let { File(it, "statement.txt").writeText(stt); heard = true; status = "진술을 저장했어요."; CarSync.publish() }
        }
        override fun onUpdatedEpdData(on: Long, off: Long) {}
        override fun onUpdatedRms(rms: Double) {}
        override fun onEndedRecognition() { if (recorder == null) { active = false; if (!heard) status = NOT_HEARD } }
        override fun onError(errorType: SttError) {
            Log.w("AskTheCar", "STT 오류 $errorType — 녹음으로 대신함")
            if (active) dir?.let(::record) // 멈춤 뒤에 온 오류로 마이크가 몰래 켜지지 않게
        }
    }

    fun init(ctx: Context) {
        app = ctx.applicationContext
        val hasKey = BuildConfig.PLEOS_CLIENT_ID.isNotBlank()
        stt = SpeechToText(app, if (hasKey) SttMode.HYBRID else SttMode.ON_DEVICE).apply {
            initialize(object : SttInitListener {
                override fun onSuccess() {
                    ready = true
                    setLocale(Locale.KOREA)
                    addListener(listener)
                    if (hasKey) registerApp(BuildConfig.PLEOS_CLIENT_ID, BuildConfig.PLEOS_CLIENT_SECRET, object : SttServerListener {
                        override fun onConnected() { Log.i("AskTheCar", "STT 서버 연결됨") }
                        override fun onFailed(errorType: SttError) { Log.w("AskTheCar", "STT 앱 등록 실패 $errorType") }
                    })
                }
                override fun onFailure(errorType: SttError) { Log.w("AskTheCar", "STT 초기화 실패 $errorType") }
            })
        }
    }

    fun start(into: File) {
        if (!Consent.allowed(Consent.Item.VOICE)) { status = "음성 진술 동의가 꺼져 있어요 (설정 > 개인정보 보호)"; return }
        dir = into
        heard = false
        active = true
        status = "준비 중…"
        Voice.stop() // 안내 음성이 마이크에 들어가지 않게
        if (ready) runCatching { stt?.request() }.onFailure { record(into) } else record(into)
    }

    fun stop() {
        runCatching { stt?.stop() }
        recorder?.let { r ->
            runCatching { r.stop() }.onFailure { Log.w("AskTheCar", "녹음 정지 실패", it) }
            r.release()
            recorder = null
            status = "진술을 녹음 파일로 저장했어요."
        } ?: run { if (active && !heard) status = NOT_HEARD }
        active = false
    }

    fun release() { stop(); stt?.run { removeListener(listener); release() }; stt = null; ready = false }

    private fun record(into: File) {
        if (recorder != null) return
        if (app.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            status = "음성 인식이 안 되고 마이크 권한도 없어 진술을 남길 수 없어요."
            active = false
            return
        }
        recorder = runCatching {
            @Suppress("DEPRECATION")
            (if (Build.VERSION.SDK_INT >= 31) MediaRecorder(app) else MediaRecorder()).apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setOutputFile(File(into, "statement.m4a").path)
                prepare()
                start()
            }
        }.onFailure { Log.w("AskTheCar", "녹음 시작 실패", it) }.getOrNull()
        status = if (recorder != null) "녹음 중이에요. 다 말씀하시면 '멈춤'을 누르세요." else "진술을 녹음할 수 없어요."
        active = recorder != null
    }
}

/**
 * 안드로이드 가속도 센서로 충격(g)을 잰다. 중력은 느린 평균으로 빼서 기기 방향과 무관하게 크기만 본다.
 * 게임 연동 시에는 브리지가 BeamNG 가속도를 에뮬레이터 센서에 넣는다.
 */
class ImpactSensor(ctx: Context, private val onImpact: (Double) -> Unit) : SensorEventListener {
    private val sm = ctx.getSystemService(SensorManager::class.java)
    private val gravity = DoubleArray(3)
    private var primed = false
    /** 최근 3초 (시각, g, 충격 방향). 사고 순간 앞뒤만 보려고 (전체 최대값을 들고 있으면 옛 충격이 섞임) */
    private val recent = ArrayDeque<Triple<Double, Double, Double>>()

    // 메인 스레드가 화면 그리기로 바쁠 때 충격 값이 밀리지 않게 전용 스레드에서 받는다
    private val looper = HandlerThread("impact").apply { start() }

    /** 자이로 세로축 회전(라디안/초, 왼쪽 +). 한 번도 움직임을 못 느꼈으면 null (에뮬레이터는 주입 없으면 늘 0 → 조향각으로) */
    private var gyroRate: Double? = null
    private var gyroAlive = false
    private var gyroAngle = 0.0
    private var gyroAt = 0L
    private var readAngle = 0.0
    private var readAt = 0L

    /** 지난번 읽은 뒤의 평균 회전. 순간값을 10Hz 로 뽑으면 충돌 때 짧은 회전을 부풀리거나 놓친다 */
    @Synchronized fun yawRate(): Double? {
        val rate = gyroRate ?: return null
        val now = android.os.SystemClock.elapsedRealtimeNanos()
        val angle = gyroAngle + rate * (now - gyroAt) / 1e9
        val mean = if (readAt == 0L) rate else (angle - readAngle) / ((now - readAt) / 1e9)
        readAngle = angle; readAt = now
        return mean
    }

    @Synchronized private fun onGyro(rate: Double, at: Long) {
        if (gyroRate != null) gyroAngle += gyroRate!! * (at - gyroAt) / 1e9
        gyroRate = rate; gyroAt = at
    }

    /** 가속도 센서가 있고 등록됐는지. 없으면 속도 급감을 충격 확인 없이 받는다 */
    @Volatile var available = false; private set

    fun start(): Boolean {
        sm?.getDefaultSensor(Sensor.TYPE_GYROSCOPE)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, Handler(looper.looper)) }
            ?: Log.w("AskTheCar", "자이로 센서 없음 — 궤적은 조향각으로")
        available = sm?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME, Handler(looper.looper)) } ?: false
        return available
    }
    fun stop() { sm?.unregisterListener(this); looper.quitSafely() }
    /** from~to 사이 가장 센 충격 (g, 방향). 없으면 (0, null) */
    fun peak(from: Double, to: Double): Pair<Double, Double?> = synchronized(recent) {
        recent.filter { it.first in from..to }.maxByOrNull { it.second }?.let { it.second to it.third } ?: (0.0 to null)
    }

    override fun onSensorChanged(e: SensorEvent) {
        if (e.sensor.type == Sensor.TYPE_GYROSCOPE) {
            val r = e.values[UP_AXIS].toDouble()
            if (kotlin.math.abs(r) > 0.005) gyroAlive = true
            if (gyroAlive) onGyro(r, e.timestamp)
            return
        }
        if (!primed) { for (i in 0..2) gravity[i] = e.values[i].toDouble(); primed = true }
        val d = DoubleArray(3)
        for (i in 0..2) {
            gravity[i] += (e.values[i] - gravity[i]) * 0.02 // 느린 평균 = 중력
            d[i] = e.values[i] - gravity[i]
        }
        // 세기는 세 축 전체(중력 뺀 값): 실차 장착 축이 달라도 정면·후방 충격을 버리지 않게. 방향만 축 설정값으로 계산
        val g = sqrt(d[0] * d[0] + d[1] * d[1] + d[2] * d[2]) / SensorManager.GRAVITY_EARTH
        val now = System.currentTimeMillis() / 1000.0
        synchronized(recent) {
            recent.addLast(Triple(now, g, impactFromDeg(RIGHT_SIGN * d[RIGHT_AXIS], FORWARD_SIGN * d[FORWARD_AXIS])))
            while (recent.first().first < now - 3) recent.removeFirst()
        }
        if (g >= IMPACT_G) onImpact(g)
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    companion object {
        const val IMPACT_G = 2.5
        // 센서 축 → 차 축. 브리지가 게임 가속도를 x=좌우, z=앞뒤로 넣는다(beamng_bridge.set_acceleration).
        // 부호는 BeamNG에서 정면·측면 충돌로 아직 확인 못 함(미확인). 실차 헤드유닛 장착 방향도 미확인.
        const val RIGHT_SIGN = 1.0
        const val FORWARD_SIGN = 1.0
        // 센서 축 번호(0=x, 1=y, 2=z). 브리지 주입 기준 x=좌우, y=위아래, z=앞뒤. 실차 헤드유닛 축은 미확인 → 한 번 재서 여기만 바꾼다
        const val RIGHT_AXIS = 0
        const val UP_AXIS = 1
        const val FORWARD_AXIS = 2
    }
}

/**
 * 10Hz로 CarState를 기록하고 사고를 감지한다.
 * - 큰 사고(severe): 에어백 전개
 * - 충격 감지: 가속도 ≥ 2.5g, 또는 0.3초 안에 15km/h 이상 속도 급감 + 가속도 1.5g 이상(급감만으로는 안 봄),
 *   또는 디버그 앱의 연습 사고 신호
 * 감지되면 onIncident 로 사고 화면을 띄우고, 10초 뒤 전 30초 + 후 10초를 저장한다.
 */
class CrashWatcher(private val ctx: Context, private val onLeftReverse: () -> Unit, private val onIncident: (Incident) -> Unit) {
    val recorder = Recorder(windowSec = 60.0)
    private val impact = ImpactSensor(ctx) { g -> trigger("충격 감지 (%.1fg)".format(g), severe = false) }
    @Volatile private var running = false
    @Volatile private var pendingSince: Double? = null
    @Volatile private var peakAtCrash: Pair<Double, Double?> = 0.0 to null
    /** 가장 최근 사고 시각. 옛 사고의 경위서 스레드가 새 사고 화면 상태를 덮어쓰지 않게 */
    @Volatile private var latestT = 0.0

    fun start() {
        if (!impact.start()) Log.w("AskTheCar", "가속도 센서 없음 — 속도 급감·에어백으로만 감지")
        running = true
        val started = System.currentTimeMillis() / 1000.0
        thread(name = "recorder", isDaemon = true) {
            var prev = CarState.now
            var heldSince = 0.0; var heldSec = 0.0 // 지난 속도 값이 머문 시간. 신호가 느리면 정상 감속도 급감처럼 보임
            while (running) {
                val s = CarState.now.copy(t = System.currentTimeMillis() / 1000.0, yawRate = impact.yawRate())
                recorder.add(s)
                val before = recorder.between(s.t - 0.35, s.t - 0.25).firstOrNull()
                if (s.speedKph != prev.speedKph) { heldSec = s.t - heldSince; heldSince = s.t }
                if (prev.gear == "R" && s.gear != "R") onLeftReverse()
                if (CrashFlow.incident != null && !CrashFlow.halted && halted(s)) { CrashFlow.halted = true; CarSync.publish() } // 휴대폰이 정차 신호를 받고 울리게
                when {
                    // 시작 직후 3초는 무시: 앱이 다시 켜지면 이미 터져 있던 에어백(게임 차 그대로)이 새 사고처럼 보임.
                    // 앱이 죽었다 살아나 에어백 값이 3초 넘어 늦게 와도, 방금(60초 안) 저장한 사고가 있으면 같은 사고로 본다.
                    // 켜진 지 60초가 지난 뒤엔 억제 안 함: 디스크의 직전 기록 때문에 "리셋하고 바로 다시 부딪힌" 사고를 놓쳤음
                    s.airbag && !prev.airbag && s.t - started > 3 && (s.t - started > REPEAT_SEC || s.t - lastIncidentT() > REPEAT_SEC) -> trigger("에어백 전개", severe = true)
                    // 속도 급감만으로는 사고로 안 봄(순간이동·센서 오류도 급감처럼 보임) → 가속도 센서도 1.5g 넘게 느꼈을 때만.
                    // 센서가 없는 차면 급감만으로 (없으면 에어백 사고만 잡히게 됨)
                    before != null && suddenDrop(before.speedKph - s.speedKph, heldSec, SUDDEN_DROP_KPH) && (!impact.available || impact.peak(s.t - 1.0, s.t).first >= 1.5) ->
                        trigger("속도 급감 (%.0f→%.0f km/h)".format(before.speedKph, s.speedKph), severe = false)
                }
                pendingSince?.let { if (s.t - it >= AFTER_SEC) runCatching { finish(it) }.onFailure { e -> Log.w("AskTheCar", "사고 기록 저장 실패", e); CrashFlow.saveFailed = it } }
                prev = s
                Thread.sleep(100)
            }
        }
    }

    fun stop() { running = false; impact.stop() }

    @Synchronized
    /** practice = 디버그 앱의 연습 사고: 기록 폴더에 practice 표시(최근 사고 기록·보관 기간에서 따로 다룸), manual = 운전자가 홈에서 직접 시작 */
    fun trigger(reason: String, severe: Boolean, practice: Boolean = false, manual: Boolean = false) {
        // incident 가 비었으면 "사고 아니에요"로 닫힌 뒤: 10초 대기 중이어도 새 사고로 받는다
        if (pendingSince != null && CrashFlow.incident != null) {
            // 충격 감지 후 에어백까지 터지면 큰 사고로 올린다. 대처 단계가 달라지므로 휴대폰에도 알림
            if (severe) { CrashFlow.setIncident { cur -> if (cur != null && !cur.severe) cur.copy(severe = true, reason = reason) else cur }; CarSync.publish() }
            return
        }
        val t = System.currentTimeMillis() / 1000.0
        pendingSince = t
        latestT = t
        // 전원이 끊겨도 남도록 충돌 전 30초를 바로 저장 (10초 뒤 finish 가 전체 구간으로 덮어씀)
        val pre = recorder.between(t - BEFORE_SEC, t)
        val peakNow = impact.peak(t - 1.5, t)
        val deg = peakNow.let { (g, d) -> d.takeIf { g >= 1.0 } }
        peakAtCrash = peakNow // 1초 뒤 한 번 더 본다(에어백 신호가 센서보다 먼저 올 수 있음)
        thread(name = "pre-save", isDaemon = true) {
            runCatching {
                val dir = dirFor(t).apply { mkdirs() }
                if (practice) File(dir, "practice").createNewFile()
                if (manual) File(dir, MANUAL).createNewFile()
                val f = File(dir, "log.json").apply { writeAtomic(toJson(pre)) }
                File(dir, "log.sha256").writeText(sha256(f))
                // 이 기록은 충돌 순간에 끝나므로, 없으면 "기록 끝 - 10초"로 계산해 충돌 시각이 10초 일러진다
                saveImpact(dir, t, peakNow.first, deg)
            }.onFailure { Log.w("AskTheCar", "충돌 전 기록 저장 실패", it) }
        }
        thread(isDaemon = true) {
            Thread.sleep(1000)
            peakAtCrash = impact.peak(t - 1.5, t + 1.0)
            val (g, d) = peakAtCrash
            CrashFlow.setIncident { cur -> if (cur?.t == t && g >= 1.0) cur.copy(impactDeg = d) else cur }
            if (g >= 1.0) suggest(t, inferKind(recorder.between(t - 10, t), t, d))
        }
        val guess = inferKind(recorder.between(t - 10, t), t, deg)
        val incident = Incident(t, reason, severe, isHighway(t), deg, recorder.between(t - 10, t), guess?.why, roadKnown = CarState.roadLimitKph > 0)
        CrashFlow.dir = dirFor(t)
        CrashFlow.kindChosen = false
        CrashFlow.hurt = false
        CrashFlow.halted = halted(CarState.now)
        CrashFlow.saveFailed = null
        CrashFlow.near = false
        CrashFlow.moved = false
        CrashFlow.guideStep = 0
        CrashFlow.kind = guess?.kind ?: CrashKind.UNKNOWN
        CrashFlow.progress = 0.05f
        CrashFlow.snapshot = null
        CrashFlow.setIncident { incident }
        CrashFlow.record = null
        CrashFlow.status = "사고 감지: $reason"
        onIncident(incident)
    }

    /** 운전자가 아직 안 골랐으면 새 추정으로 바꾼다 (충격 방향·사진 인식이 늦게 들어옴) */
    private fun suggest(t: Double, g: KindGuess?) {
        if (g == null || CrashFlow.kindChosen || CrashFlow.incident?.t != t) return
        CrashFlow.setIncident { cur -> if (cur?.t == t) cur.copy(suggestWhy = g.why) else cur }
        CrashFlow.chooseKind(g.kind, byDriver = false)
    }

    /** 고속도로 판단: 도로 제한속도(실차는 NaviHelper, 게임은 브리지)가 80 이상. 모르면 최근 30초 평균 속도 80 이상 */
    private fun isHighway(t: Double): Boolean {
        val limit = CarState.roadLimitKph
        if (limit > 0) return limit >= 80
        return recorder.between(t - 30, t).map { it.speedKph }.average().let { !it.isNaN() && it >= 80 }
    }

    /** 진행 중인 사고를 없던 일로: 저장 전 대기를 멈추고, 기록을 비우고, 사고 화면·휴대폰 안내를 닫는다. 이미 저장된 기록은 둔다 */
    fun cancel() {
        pendingSince = null
        latestT = 0.0
        recorder.clear()
        CrashFlow.incident?.let { CarSync.close(it.t, notAccident = true) }
        CrashFlow.setIncident { null }
        CrashFlow.closed++
    }

    private fun dirFor(t: Double) =
        File(ctx.filesDir, "crashes/" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date((t * 1000).toLong())))

    /** 마지막으로 기록을 시작한 사고 시각(초). 없으면 0. 진행 중이면 그 사고 */
    private fun lastIncidentT(): Double = latestT.takeIf { it > 0 }
        ?: File(ctx.filesDir, "crashes").list()?.maxOrNull()?.let { runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(it)!!.time / 1000.0 }.getOrNull() } ?: 0.0

    private fun saveImpact(dir: File, t: Double, peakG: Double, deg: Double?) =
        File(dir, "impact.json").writeText(JSONObject().put("t", t).put("peakG", peakG).put("deg", deg ?: JSONObject.NULL).toString())

    private fun finish(t: Double) {
        pendingSince = null
        fun current() = latestT == t
        val log = recorder.between(t - BEFORE_SEC, t + AFTER_SEC)
        val dir = dirFor(t)
        if (CrashFlow.dir != dir) return // 기다리는 사이 "모두 지우기"·동의 철회로 지워진 사고: 다시 저장하지 않는다
        dir.mkdirs()
        val logFile = File(dir, "log.json").apply { writeAtomic(toJson(log)) }
        File(dir, "log.sha256").writeText(sha256(logFile)) // 잠금: 나중에 바뀌었는지 확인용
        val (peakG, peakDeg) = peakAtCrash
        val impactDeg = peakDeg.takeIf { peakG >= 1.0 }
        saveImpact(dir, t, peakG, impactDeg)
        if (current()) { CrashFlow.progress = 0.25f; CrashFlow.status = "기록 저장 완료. 경위서 만드는 중…" }

        // 만드는 동안 기록이 지워질 수 있다(모두 지우기·동의 철회): 파일 쓰기 예외로 앱이 죽지 않게
        thread(name = "report", isDaemon = true) { runCatching {
            val facts = FactTimeline.build(log, impactT = t, peakG = peakG, impactDeg = impactDeg, manual = File(dir, MANUAL).exists())
            fun notAccident() = File(dir, "false_alarm").exists() // "사고가 아니에요": 기록·사진은 남기고 외부 AI 로 보내지 않는다
            fun publish(report: String, vision: List<String>, seen: Sighting?) {
                if (current() && dir.exists()) { CrashFlow.record = CrashRecord(dir, log, report, facts, vision, impactDeg, seen); CarSync.publish() }
            }
            // 사실 목록 경위서를 먼저 저장·표시하고, 다듬기(relay)와 사진 분석은 함께 돌려 끝나면 바꾼다
            File(dir, "report.txt").writeText(facts.joinToString("\n"))
            publish(facts.joinToString("\n"), emptyList(), null)
            var polished: String? = null
            val polishing = thread(name = "polish", isDaemon = true) { runCatching { if (!notAccident()) polished = Relay.polish(facts) }.onFailure { Log.w("AskTheCar", "경위서 다듬기 실패", it) } }
            val filming = thread(name = "cameras", isDaemon = true) { runCatching {
                val video = File(dir, CameraVideo.FILE)
                Sim.link?.cameraVideo(video)?.let { end ->
                    File(dir, CameraVideo.END_FILE).writeText(end.toString())
                    File(dir, "cameras.sha256").writeText(sha256(video))
                }
            }.onFailure { Log.w("AskTheCar", "카메라 영상 저장 실패", it) } }

            // 사고 순간 차 카메라 사진(있을 때만)
            val photo = File(dir, "camera.jpg")
            // 수정 시각 유지: PDF 가 이 값을 촬영 시각으로 쓴다(복사하면 약 10초 늦어짐)
            CrashFlow.snapshot?.takeIf { it.exists() }?.let { it.copyTo(photo, overwrite = true).setLastModified(it.lastModified()); it.delete(); File(dir, "camera.sha256").writeText(sha256(photo)) }
            if (current()) CrashFlow.progress = 0.45f
            val bmp = if (photo.exists()) android.graphics.BitmapFactory.decodeFile(photo.path) else null
            val looked = bmp?.let { VehicleFinder.nearest(ctx, PHOTO_LABEL, it) }
            val seen = looked?.getOrNull()?.also {
                File(dir, "vision_detect.json").writeText(JSONObject().put("frame", it.frameLabel).put("fwd", it.fwd).put("right", it.right)
                    .put("score", it.score.toDouble()).put("label", it.label).toString())
            }
            suggest(t, inferKind(log, t, impactDeg, seen))
            if (current()) CrashFlow.progress = 0.65f
            val vision = listOfNotNull(seen?.let(VehicleFinder::sentence)) + bmp?.takeUnless { notAccident() }?.let(::describePhoto).orEmpty()
            if (current()) CrashFlow.progress = 0.8f
            polishing.join()
            filming.join()
            val report = Validator.choose(polished, facts)
            File(dir, "report.txt").writeText((listOf(report) + vision).joinToString("\n\n"))
            publish(report, vision, seen)
            if (current()) { CrashFlow.progress = 1f; CrashFlow.status = "경위서 완성" }
        }.onFailure { Log.w("AskTheCar", "경위서 만들기 중단", it) } }
    }

    /** 차 카메라 사진을 중계 서버(Claude)에 보내 보이는 것만 적게 한다. 검증에 걸린 문장은 버린다. */
    private fun describePhoto(bmp: Bitmap): List<String> {
        val jpeg = Base64.encodeToString(ByteArrayOutputStream().also { bmp.compress(Bitmap.CompressFormat.JPEG, 70, it) }.toByteArray(), Base64.NO_WRAP)
        return Relay.describe(listOf(PHOTO_LABEL to jpeg)).orEmpty().lines().map { it.trim().removePrefix("-").trim() }
            .filter { it.isNotBlank() && Validator.problems(it, listOf(PHOTO_LABEL)).isEmpty() }
            .map { "[영상 분석 추정] $it" }
    }

    companion object {
        const val BEFORE_SEC = 30.0
        const val AFTER_SEC = 10.0
        const val SUDDEN_DROP_KPH = 15.0
        /** 이 시간 안에 다시 온 에어백 신호는 같은 사고(앱 재시작 뒤 중복 사고 화면 방지) */
        const val REPEAT_SEC = 60.0
        const val MANUAL = "manual"
        private fun halted(s: Sample) = s.speedKph < 1 || s.gear == "P"
        /** 사진에 붙이는 시각 표시. 중계 서버가 허용하는 표시만 쓸 수 있음(relay FRAME_LABEL) */
        private const val PHOTO_LABEL = "충돌 직후"

        fun toJson(log: List<Sample>): String = JSONArray(log.map { s ->
            JSONObject().put("t", s.t).put("speed", s.speedKph).put("steer", s.steerDeg).put("brake", s.brake)
                .put("signal", s.signal).put("gear", s.gear).put("cruise", s.cruise).put("airbag", s.airbag)
                .put("lat", s.lat ?: JSONObject.NULL).put("lon", s.lon ?: JSONObject.NULL).put("lamps", s.lamps).put("yawRate", s.yawRate ?: JSONObject.NULL)
        }).toString()

        fun sha256(f: File): String {
            val md = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { i -> // 통째로 읽지 않고 64KB 씩
                val buf = ByteArray(64 * 1024)
                while (true) { val n = i.read(buf); if (n < 0) break; md.update(buf, 0, n) }
            }
            return md.digest().joinToString("") { "%02x".format(it) }
        }

        fun fromJson(s: String): List<Sample> = JSONArray(s).let { a -> List(a.length()) { i ->
            val o = a.getJSONObject(i)
            Sample(o.getDouble("t"), o.optDouble("speed", 0.0), o.optDouble("steer", 0.0), o.optBoolean("brake"), o.optString("signal", "none"),
                o.optString("gear", "D"), o.optBoolean("cruise"), if (o.isNull("lat")) null else o.optDouble("lat"),
                if (o.isNull("lon")) null else o.optDouble("lon"), o.optBoolean("airbag"), o.optString("lamps", ""),
                if (o.isNull("yawRate")) null else o.optDouble("yawRate").takeUnless { it.isNaN() })
        } }

        /**
         * 앱이 다시 켜진 뒤 "최근 사고 기록"용: 가장 최근 사고 폴더에서 기록을 다시 만든다(메모리의 CrashRecord 는 재시작하면 사라짐).
         * report.txt 가 없으면(경위서 전에 전원이 꺼짐) 사실 목록으로 다시 쓴다(relay 없이).
         */
        fun loadLatest(ctx: Context): CrashRecord? = File(ctx.filesDir, "crashes").listFiles()
            // 연습·"사고 아니에요" 기록은 최근 사고 기록으로 다시 띄우지 않음
            ?.filter { File(it, "log.json").exists() && !File(it, "practice").exists() && !File(it, "false_alarm").exists() }
            ?.maxByOrNull { it.name }?.let(::load)

        /** impact.json 의 충돌 시각. 없으면 폴더 이름으로 */
        fun crashTime(dir: File): Double? = runCatching { JSONObject(File(dir, "impact.json").readText()).getDouble("t") }.getOrNull()
            ?: runCatching { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(dir.name)!!.time / 1000.0 }.getOrNull()

        /** 사고 폴더 하나를 다시 읽는다 */
        fun load(dir: File): CrashRecord? = runCatching {
            fun json(name: String) = File(dir, name).takeIf { it.exists() }?.let { JSONObject(it.readText()) }
            val log = fromJson(File(dir, "log.json").readText())
            val impact = json("impact.json")
            val impactDeg = impact?.takeUnless { it.isNull("deg") }?.optDouble("deg")
            val facts = FactTimeline.build(log, impactT = impact?.takeIf { it.has("t") }?.getDouble("t"),
                peakG = impact?.optDouble("peakG", 0.0) ?: 0.0, impactDeg = impactDeg, manual = File(dir, MANUAL).exists())
            val reportFile = File(dir, "report.txt")
            if (!reportFile.exists()) reportFile.writeText(facts.joinToString("\n"))
            // report.txt = 경위서 + "\n\n" + [영상 분석 추정] 문장들
            val (vision, rest) = reportFile.readText().split("\n\n").partition { it.startsWith("[영상 분석 추정]") }
            val seen = json("vision_detect.json")?.let {
                Sighting(it.getString("frame"), it.getDouble("fwd"), it.getDouble("right"), it.getDouble("score").toFloat(), it.optString("label", "car"))
            }
            val (kind, chosen) = readKind(dir)
            CrashRecord(dir, log, rest.joinToString("\n\n"), facts, vision, impactDeg, seen, kind, chosen)
        }.onFailure { Log.w("AskTheCar", "지난 사고 기록 읽기 실패", it) }.getOrNull()

        /** kind.txt: "VEHICLE" 또는 "VEHICLE guess"(기록으로 추정) */
        fun readKind(dir: File): Pair<CrashKind?, Boolean> = File(dir, "kind.txt").takeIf { it.exists() }?.readText()?.trim()?.split(" ")
            ?.let { w -> CrashKind.entries.find { it.name == w[0] } to (w.getOrNull(1) != "guess") } ?: (null to false)
    }
}

/** 중계 서버 호출. 실패하면 null → 경위서는 사실 목록을 그대로 쓰고, [영상 분석 추정]은 비워 둔다. */
object Relay {
    fun polish(facts: List<String>): String? = post(JSONObject().put("facts", JSONArray(facts)))

    /** frames = (시각 표시, JPEG base64) */
    fun describe(frames: List<Pair<String, String>>): String? =
        post(JSONObject().put("frames", JSONArray(frames.map { (t, jpeg) -> JSONObject().put("t", t).put("jpeg", jpeg) })))

    private fun post(body: JSONObject): String? = runCatching {
        if (BuildConfig.RELAY_URL.isBlank() || !Consent.allowed(Consent.Item.CLOUD)) return null // 외부 AI 전송 동의가 있을 때만
        val bytes = body.toString().toByteArray()
        val c = URL(BuildConfig.RELAY_URL).openConnection() as HttpURLConnection
        c.requestMethod = "POST"
        c.doOutput = true
        c.connectTimeout = 5000
        c.readTimeout = 25000
        c.setFixedLengthStreamingMode(bytes.size) // 중계 서버는 Content-Length 없는 요청을 거부함
        c.setRequestProperty("content-type", "application/json")
        c.setRequestProperty("x-app-token", BuildConfig.RELAY_TOKEN)
        try {
            c.outputStream.use { it.write(bytes) }
            if (c.responseCode == 200) JSONObject(c.inputStream.bufferedReader().readText()).getString("text")
            else { Log.w("AskTheCar", "relay HTTP ${c.responseCode}: ${c.errorStream?.bufferedReader()?.readText()?.take(300)}"); null }
        } finally { c.disconnect() }
    }.onFailure { Log.w("AskTheCar", "relay 실패", it) }.getOrNull()
}
