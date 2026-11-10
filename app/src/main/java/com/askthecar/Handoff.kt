package com.askthecar

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * 휴대폰으로 넘기기: 차 화면과 같은 대처 단계 전체 + 사실 기록·촬영 순서·연락처·기록 지문 + 재현·경위서를 HandoffData 하나로 (형식은 shared/HandoffData.kt).
 * 사진·운전자 이름은 넣지 않는다. 위치는 재현에 쓰는 15초만. 판단 문구도 없다.
 */
object Handoff {
    /** facts·hash 는 기록 저장(사고 +10초) 전이면 비어 있음/null */
    fun data(t: Double, facts: List<String>, hash: String?, kind: CrashKind, chosen: Boolean, impactDeg: Double?, highway: Boolean,
             severe: Boolean, p: Persona, record: CrashRecord? = null, statement: String? = null, pre: List<Sample> = emptyList()): HandoffData {
        val steps = Guides.steps(highway, p, severe, kind, CrashFlow.hurt, CrashFlow.incident?.roadKnown == false, CrashFlow.near)
        return HandoffData(
            t, kind.label, chosen, impactDeg, highway, hash?.take(16), facts,
            steps.map { HandoffData.Step(it.art.name, it.title, it.body, it.call, it.callName, it.decline, it.next, it.call2) },
            steps.indexOfFirst { it.art == StepArt.HANDOFF || (highway && it.art == StepArt.WALK) }.let { if (it < 0) steps.size else it }, // 없으면 휴대폰 단계 없음
            PhotoGuide.shots(impactDeg, kind, highway).map { HandoffData.ShotInfo(it.art.name, it.title, it.line, it.fwd, it.right, it.suggestion) },
            listOfNotNull(p.insurer, p.rentalCompany, p.guardian),
            (record?.log ?: pre).filter { it.t in (t - 10)..(t + 5) }, // sceneJson 의 재현 범위와 같음: 재현이 달라지지 않고 위치를 덜 보낸다
            record?.seen, record?.report, record?.vision.orEmpty(), statement,
            mode = p.mode.name, guardian = p.guardian, rental = p.rental && p.rentalCompany != null, // 번호가 없으면 차 안내처럼 보험사로
            lampsRecorded = CarState.telltalesSeen, near = CrashFlow.near, moved = CrashFlow.moved,
            hurt = CrashFlow.hurt, severe = severe, stopped = CrashFlow.halted,
        )
    }
}

/**
 * 차 → 동기화 서버(game/bridge/sync_server.py) → 같은 계정의 휴대폰 앱. 보내기는 한 줄로 세운 스레드에서(순서 유지, 화면을 막지 않음).
 * 주행 기록 동의(DRIVE)가 있을 때만 — 동의 문구에 휴대폰 연동을 적어 둠(Consent.kt).
 * shortcut: 계정 = 시연용 운전자 id(손님은 "guest"). 실제 서비스는 현대차 계정으로 — Pleos 프로필 API 는 signature 권한이라 일반 앱이 못 읽음
 */
object CarSync {
    /** 마지막 서버 호출이 성공했는지 ("휴대폰 연결됨/연결 확인 중") */
    var ok by mutableStateOf(false)
    /** 서버가 "기록(지문 포함)이 올라갔다"고 200 으로 답한 사고 시각. 시안 "기록 전송"의 체크는 이것이 있을 때만 */
    var recordAcked by mutableStateOf<Double?>(null)
    /** 휴대폰이 사고를 받아 저장했다고 서버에 쓴 사고 시각(SyncState.seen). "휴대폰에 도착"은 이 값이 지금 사고와 같을 때만 */
    var seen by mutableStateOf<Double?>(null)

    fun arrived(t: Double) = seen?.let { kotlin.math.abs(it - t) < 0.001 } == true
    private lateinit var app: Context
    private val send = Executors.newSingleThreadScheduledExecutor { Thread(it, "car-sync").apply { isDaemon = true } }
    private var retrying = false // send 스레드에서만 씀

    fun init(ctx: Context) { app = ctx.applicationContext }

    /** 서버 주소가 없는 빌드(실차 release, secrets 에 sync.url 없음)는 휴대폰 연동 자체를 안 한다 */
    fun allowed() = ::app.isInitialized && BuildConfig.SYNC_URL.isNotEmpty() && Consent.allowed(Consent.Item.DRIVE)

    /** IO 스레드에서 부를 것 (poll 은 최대 약 25초 기다림) */
    fun client() = SyncClient(BuildConfig.SYNC_URL, (Personas.load(app) ?: Personas.guest).id)

    private fun submit(f: (SyncClient) -> Boolean) {
        if (!allowed()) return
        send.execute { tryPending(); ok = runCatching { f(client()) }.getOrDefault(false) } // 밀린 삭제가 먼저: 늦은 삭제가 새 사고를 지우지 않게
    }

    /** 지금 사고 상태(사고 직후·기록 저장·종류 변경 때) */
    fun publish() = submit { c ->
        val inc = CrashFlow.incident ?: return@submit ok
        val r = CrashFlow.record?.takeIf { it.dir == CrashFlow.dir } // 옛 사고 기록이 섞이지 않게
        val hash = r?.let { File(it.dir, "log.sha256").takeIf { f -> f.exists() }?.readText()?.trim() }
        val p = Personas.load(app) ?: Personas.guest
        val statement = r?.let { File(it.dir, "statement.txt").takeIf { f -> f.exists() }?.readText() }
        val sent = c.putIncident(Handoff.data(inc.t, r?.facts.orEmpty(), hash, CrashFlow.kind, CrashFlow.kindChosen, inc.impactDeg, inc.highway, inc.severe, p, r, statement, inc.preLog)) // 사고 +10초 저장 전에 차가 꺼져도 충돌 전 기록은 휴대폰에 남게
        if (sent && hash != null) recordAcked = inc.t
        sent
    }

    /** "사고 아님"을 되돌림. publish 와 같은 줄에 서서 순서대로 간다 */
    fun reopen(t: Double) {
        if (::app.isInitialized) closes().edit().remove(account()).commit() // 못 보낸 "사고 아님"이 되돌린 뒤에 도착하지 않게
        submit { it.reopen(t) }
    }

    /** 사고 화면 없이 홈만 열려 있을 때도 도착 신호를 받는다(사고 화면은 followPhone 이 받음) */
    suspend fun followSeen() {
        var since = 0L
        while (true) {
            if (!allowed() || CrashFlow.incident == null) { delay(2_000); continue }
            val s = withContext(Dispatchers.IO) { runCatching { client().poll(since) }.getOrNull() }
            ok = s != null
            if (s == null) { delay(2_000); continue }
            since = s.version
            seen = s.seen
        }
    }

    fun step(t: Double, index: Int) = submit { it.putStep(t, index, "car") }

    /** 터널 등에서 끊긴 순간 닫아도 휴대폰이 안내·알림에 남지 않게, 200을 받을 때까지 다시 보낸다 */
    fun close(t: Double, notAccident: Boolean = false) {
        if (!allowed()) return
        closes().edit().putString(account(), "$t $notAccident").commit() // 앱이 꺼져도 남게 바로 저장
        flushPending()
    }

    /** 동의 철회·"모두 지우기": 서버의 이 계정 사고·연결도 지운다. 동의를 먼저 끈 뒤 불리므로 allowed() 를 보지 않는다 */
    fun wipe() {
        if (!::app.isInitialized) return
        closes().edit().clear().commit() // 동의를 철회했으니 못 보낸 닫힘도 더 보내지 않는다
        pending().edit().putBoolean(account(), true).commit() // 계정은 지금 값으로. 앱이 꺼져도 남게 바로 저장
        flushPending()
    }

    /** 터널·오프라인에서 지워도 서버·휴대폰 사본이 남지 않게, 200을 받을 때까지 다시 보낸다. 앱이 켜질 때도(Consent.init) */
    fun flushPending() {
        if (::app.isInitialized) send.execute { tryPending() }
    }

    private fun account() = (Personas.load(app) ?: Personas.guest).id
    private fun pending() = app.getSharedPreferences("sync_wipe", Context.MODE_PRIVATE)
    private fun closes() = app.getSharedPreferences("sync_close", Context.MODE_PRIVATE)

    private fun tryPending() {
        pending().all.keys.forEach { a ->
            if (runCatching { SyncClient(BuildConfig.SYNC_URL, a).wipe() }.getOrDefault(false)) pending().edit().remove(a).apply()
        }
        closes().all.forEach { (a, v) ->
            val (t, notAccident) = v.toString().split(" ")
            if (runCatching { SyncClient(BuildConfig.SYNC_URL, a).close(t.toDouble(), notAccident.toBoolean()) }.getOrDefault(false)) closes().edit().remove(a).apply()
        }
        if ((pending().all.isNotEmpty() || closes().all.isNotEmpty()) && !retrying) {
            retrying = true
            send.schedule({ retrying = false; tryPending() }, 30, java.util.concurrent.TimeUnit.SECONDS)
        }
    }
}
