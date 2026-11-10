package com.askthecar

import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/** 차 → 휴대폰 앱으로 동기화하는 사고 정보 한 묶음 (차 앱·휴대폰 앱이 같은 파일을 씀). 사진·영상·운전자 이름은 넣지 않는다. 위치는 재현용 samples 에만(동의 문구에 적음) */
data class HandoffData(
    /** 충돌 시각(epoch 초). 사고 하나를 구분하는 id 로도 쓴다 */
    val t: Double,
    val kind: String, val kindChosen: Boolean,
    /** 충격 방향(앞 0°, 오른쪽 90°), 모르면 null */
    val impactDeg: Double?, val highway: Boolean,
    /** 기록 지문(log.sha256 앞 16자), 기록 저장 전이면 null */
    val hash: String?,
    val facts: List<String>,
    /** 차 화면과 같은 대처 단계 전체(같은 순서). 단계 번호는 이 목록의 index */
    val steps: List<Step>,
    /** 이 번호의 단계부터는 차 밖 = 휴대폰 앱에서 진행 (차 화면은 "휴대폰 앱에서 이어서") */
    val phoneFrom: Int,
    val shots: List<ShotInfo>, val contacts: List<Contact>,
    /** 3D 재현용 충돌 전후 기록(sceneJson 이 쓰는 범위만). 기록 저장 전이면 비어 있음 */
    val samples: List<Sample> = emptyList(),
    /** 카메라 인식 결과. frameLabel·score 는 안 보냄 */
    val seen: Sighting? = null,
    /** 경위 설명문(다듬은 문장 또는 사실 목록), 기록 저장 전이면 null */
    val report: String? = null,
    /** "[영상 분석 추정]" 문장들 */
    val vision: List<String> = emptyList(),
    /** 운전자 진술(글자로 받은 것), 없으면 null */
    val statement: String? = null,
    /** false = 브레이크·지시등·에어백이 기록되지 않음(옛·상대 차의 6칸 기록). Sample 의 값은 기본값일 뿐이라 사실로 쓰면 안 됨 */
    val controlsRecorded: Boolean = true,
    /** false = 조향이 기록되지 않음. Sample.steerDeg 는 0.0 일 뿐 */
    val steerRecorded: Boolean = true,
    /** 안내 방식(차의 GuideMode 이름). 휴대폰 사건 홈의 구도를 고른다 */
    val mode: String = "DETAIL",
    /** 미리 등록한 가족(contacts 에도 들어 있음). 없으면 맡기기를 묻지 않는다 */
    val guardian: Contact? = null,
    /** 렌터카면 보험사 대신 렌터카 회사에 접수 */
    val rental: Boolean = false,
    /** false = 이 차가 경고등 값을 보낸 적 없음. 비상등이 안 켜졌다는 뜻이 아니다 */
    val lampsRecorded: Boolean = false,
    /** 차에서 답한 것: 다른 사람이 다쳐 119를 눌렀음 / 차를 이미 옮겼음. 건너뛸 단계를 정한다(skipped) */
    val near: Boolean = false, val moved: Boolean = false,
    /** 운전자가 "다쳤어요"를 골랐음 / 에어백 사고. 이 사고는 7일 동안 서류 할 일을 내밀지 않는다 */
    val hurt: Boolean = false, val severe: Boolean = false,
    /** 차가 멈췄음(속도<1 또는 P). 달리는 중엔 휴대폰을 울리지 않는다. 옛 형식은 true */
    val stopped: Boolean = true,
) {
    /** art = StepArt 이름, call = 전화번호, decline = 두 번째 버튼 글자, next = 진행 버튼 글자, call2 = 두 번째 전화번호 */
    data class Step(val art: String, val title: String, val body: String, val call: String?, val callName: String?, val decline: String?, val next: String? = null, val call2: String? = null)
    /** 촬영 한 장면. fwd/right = 내 차 중심 기준 찍는 사람 위치(앞+·오른쪽+ 미터), art = ShotArt 이름 */
    data class ShotInfo(val art: String, val title: String, val line: String, val fwd: Double, val right: Double, val suggestion: Boolean)
}

data class Contact(val name: String, val phone: String)

object HandoffCodec {
    private fun JSONObject.str(k: String) = if (isNull(k)) null else getString(k)
    private fun Any?.orNull(): Any = this ?: JSONObject.NULL
    private fun round(v: Double, scale: Double) = Math.round(v * scale) / scale

    fun encode(d: HandoffData): String = JSONObject().put("t", d.t).put("kind", d.kind).put("chosen", d.kindChosen)
        .put("deg", d.impactDeg.orNull()).put("hw", d.highway).put("hash", d.hash.orNull())
        .put("facts", JSONArray(d.facts)).put("phoneFrom", d.phoneFrom)
        .put("steps", JSONArray(d.steps.map { JSONObject().put("art", it.art).put("title", it.title).put("body", it.body)
            .put("call", it.call.orNull()).put("name", it.callName.orNull()).put("decline", it.decline.orNull()).put("next", it.next.orNull()).put("call2", it.call2.orNull()) }))
        .put("shots", JSONArray(d.shots.map { JSONObject().put("art", it.art).put("title", it.title).put("line", it.line).put("f", it.fwd).put("r", it.right).put("s", it.suggestion) }))
        .put("contacts", JSONArray(d.contacts.map { JSONObject().put("name", it.name).put("phone", it.phone) }))
        // 한 장면 = [충돌 기준 초, 속도, 조향, 자이로|null, 위도|null, 경도|null, 브레이크 0/1, 지시등 "L"|"R"|"", 에어백 0/1]. 키를 반복하지 않고 소수를 줄여 본문을 작게
        // 뒤 세 칸은 기록서(ClaimPdf)용으로 나중에 붙음: 옛 휴대폰 앱은 위치로 읽어 무시. 기록 안 된 값은 칸을 비워(6칸, 조향 null) 다시 저장해도 "기록 없음"이 유지되게
        .put("samples", JSONArray(d.samples.map { s -> JSONArray(listOf<Any?>(round(s.t - d.t, 1e2), round(s.speedKph, 1e2), if (d.steerRecorded) round(s.steerDeg, 1e2) else null,
            s.yawRate?.let { round(it, 1e4) }, s.lat?.let { round(it, 1e6) }, s.lon?.let { round(it, 1e6) }) +
            (if (d.controlsRecorded) listOf(if (s.brake) 1 else 0, s.signal.takeIf { it == "L" || it == "R" } ?: "", if (s.airbag) 1 else 0, if ("비상등" in s.lamps) 1 else 0) else emptyList())) }))
        .put("seen", d.seen?.let { JSONObject().put("f", round(it.fwd, 1e2)).put("r", round(it.right, 1e2)).put("label", it.label) }.orNull())
        .put("report", d.report.orNull()).put("vision", JSONArray(d.vision)).put("statement", d.statement.orNull())
        .put("mode", d.mode).put("rental", d.rental).put("lamps", d.lampsRecorded).put("near", d.near).put("moved", d.moved).put("hurt", d.hurt).put("severe", d.severe).put("stopped", d.stopped).put("guardian", d.guardian?.let { JSONObject().put("name", it.name).put("phone", it.phone) }.orNull())
        .toString()

    /** 깨졌으면 null. samples 부터 뒤의 값은 옛 형식(받아 둔 사고·옛 차 앱)에 없을 수 있다 */
    fun decode(s: String): HandoffData? = runCatching {
        val o = JSONObject(s)
        val t = o.getDouble("t")
        fun <T> list(k: String, f: (JSONArray, Int) -> T) = o.getJSONArray(k).let { a -> List(a.length()) { f(a, it) } }
        val rows = if (o.has("samples")) list("samples") { a, i -> a.getJSONArray(i) } else emptyList()
        HandoffData(
            t, o.getString("kind"), o.getBoolean("chosen"),
            if (o.isNull("deg")) null else o.getDouble("deg"), o.getBoolean("hw"), o.str("hash"),
            list("facts") { a, i -> a.getString(i) },
            list("steps") { a, i -> a.getJSONObject(i).let { HandoffData.Step(it.getString("art"), it.getString("title"), it.getString("body"), it.str("call"), it.str("name"), it.str("decline"), it.str("next"), it.str("call2")) } },
            o.getInt("phoneFrom"),
            list("shots") { a, i -> a.getJSONObject(i).let { HandoffData.ShotInfo(it.getString("art"), it.getString("title"), it.getString("line"), it.getDouble("f"), it.getDouble("r"), it.getBoolean("s")) } },
            list("contacts") { a, i -> a.getJSONObject(i).let { Contact(it.getString("name"), it.getString("phone")) } },
            rows.map { v ->
                fun opt(k: Int) = if (v.isNull(k)) null else v.getDouble(k)
                val more = v.length() > 8
                Sample(t + v.getDouble(0), v.getDouble(1), opt(2) ?: 0.0, lat = opt(4), lon = opt(5), yawRate = opt(3),
                    brake = more && v.getInt(6) == 1, signal = if (more) v.getString(7).ifEmpty { "none" } else "none", airbag = more && v.getInt(8) == 1, lamps = if (v.length() > 9 && v.getInt(9) == 1) "비상등" else "")
            },
            if (o.isNull("seen")) null else o.getJSONObject("seen").let { Sighting("", it.getDouble("f"), it.getDouble("r"), 0f, it.getString("label")) },
            o.str("report"),
            if (o.has("vision")) list("vision") { a, i -> a.getString(i) } else emptyList(),
            o.str("statement"),
            rows.all { it.length() > 8 }, rows.none { it.isNull(2) },
            o.optString("mode", "DETAIL"),
            if (!o.has("guardian") || o.isNull("guardian")) null else o.getJSONObject("guardian").let { Contact(it.getString("name"), it.getString("phone")) },
            o.optBoolean("rental"),
            o.optBoolean("lamps"),
            o.optBoolean("near"), o.optBoolean("moved"),
            o.optBoolean("hurt"), o.optBoolean("severe"), o.optBoolean("stopped", true),
        )
    }.getOrNull()
}

/** 서버가 들고 있는 한 계정의 지금 상태. version 은 바뀔 때마다 1씩 오른다 */
data class SyncState(val version: Long, val incident: HandoffData?, val step: Int?, val stepBy: String?, val closed: Boolean, val wiped: Boolean = false, val notAccident: Boolean = false, val seen: Double? = null)

/**
 * 차·휴대폰이 같은 계정으로 사고 상태를 주고받는 통로 (서버: game/bridge/sync_server.py, 규칙은 그 파일 맨 위).
 * 모두 막히는(blocking) 호출 → IO 스레드에서 부를 것. 실패하면 false/null (다음에 다시 보내면 됨).
 * shortcut: 계정 = 시연용 운전자 id(진짜 현대차·Pleos 계정은 일반 앱이 못 읽음), 인증 없음 — 실제 서비스는 현대차 계정 토큰으로 바꿔야 함
 */
class SyncClient(private val base: String, account: String) {
    private val root = base.trimEnd('/') + "/s/" + URLEncoder.encode(account, "UTF-8")

    /** by = "car" | "phone" */
    fun putIncident(d: HandoffData) = send("PUT", "$root/incident", HandoffCodec.encode(d))
    fun putStep(t: Double, index: Int, by: String) = send("PUT", "$root/step", JSONObject().put("t", t).put("index", index).put("by", by).toString())
    /** 사고 아니에요·안내 끝: 휴대폰 알림·화면도 닫힌다 */
    /** 미리 등록한 가족에게 이 사고를 맡기기/취소 (서버: handover) */
    fun handover(t: Double, on: Boolean) = send("PUT", "$root/handover", JSONObject().put("t", t).put("on", on).toString())
    fun close(t: Double, notAccident: Boolean = false) = send("PUT", "$root/close", JSONObject().put("t", t).put("notAccident", notAccident).toString())
    fun wipe(): Boolean = send("PUT", "$root/wipe", "{}")
    /** 휴대폰이 이 사고를 받아 저장했다고 알림 (차의 "휴대폰 도착" 표시) */
    fun putSeen(t: Double) = send("PUT", "$root/seen", JSONObject().put("t", t).toString())
    /** 차에서 "사고 아님"을 되돌림 */
    fun reopen(t: Double) = send("PUT", "$root/reopen", JSONObject().put("t", t).toString())

    /** since 보다 새 상태가 생길 때까지 최대 약 25초 기다렸다 돌려준다. 바뀐 게 없으면 같은 version, 실패하면 null */
    fun poll(since: Long): SyncState? = runCatching {
        val c = URL("$root?since=$since").openConnection() as HttpURLConnection
        c.connectTimeout = 3000; c.readTimeout = 35000
        try {
            if (c.responseCode != 200) return null
            val o = JSONObject(c.inputStream.bufferedReader().readText())
            SyncState(o.getLong("version"), if (o.isNull("incident")) null else HandoffCodec.decode(o.getString("incident")),
                if (o.isNull("step")) null else o.getInt("step"), if (o.isNull("stepBy")) null else o.getString("stepBy"), o.getBoolean("closed"), o.optBoolean("wiped"), o.optBoolean("notAccident"), if (o.isNull("seen")) null else o.optDouble("seen"))
        } finally { c.disconnect() }
    }.getOrNull()

    private fun send(method: String, url: String, body: String): Boolean = runCatching {
        val c = URL(url).openConnection() as HttpURLConnection
        c.requestMethod = method; c.doOutput = true; c.connectTimeout = 3000; c.readTimeout = 5000
        c.setRequestProperty("Content-Type", "application/json; charset=utf-8")
        try { c.outputStream.use { it.write(body.toByteArray()) }; c.responseCode == 200 } finally { c.disconnect() }
    }.getOrDefault(false)
}
