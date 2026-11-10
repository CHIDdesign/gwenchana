package com.askthecar.phone

import com.askthecar.ClaimOwner
import com.askthecar.ClaimText
import com.askthecar.Contact
import com.askthecar.CrashKind
import com.askthecar.HandoffData
import com.askthecar.Shot
import com.askthecar.ShotArt
import com.askthecar.StepArt
import com.askthecar.SyncState
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** 화면과 떨어진 계산(단위 테스트 대상) */
object Logic {
    /** epoch 초 → "10월 30일 14:05" */
    fun time(t: Double, zone: ZoneId = ZoneId.systemDefault()): String =
        DateTimeFormatter.ofPattern("M월 d일 HH:mm", Locale.KOREAN).withZone(zone).format(Instant.ofEpochMilli((t * 1000).toLong()))

    /** 서버에서 받은 이름 → 차 앱과 같은 종류. 모르는 이름(새 차 앱)이면 안전한 값으로 */
    fun kindOf(d: HandoffData) = CrashKind.entries.find { it.label == d.kind } ?: CrashKind.UNKNOWN
    fun artOf(name: String) = StepArt.entries.find { it.name == name }

    /** 차 화면에 맞춰 쓴 단계를 휴대폰에서 읽을 말로: 이미 휴대폰을 보고 있는 사람에게 "휴대폰으로 이어 볼게요", "차에서 계속 볼게요"는 맞지 않다 */
    fun forPhone(s: HandoffData.Step): HandoffData.Step = when {
        artOf(s.art) == StepArt.HANDOFF -> s.copy(title = "이제 여기서 이어서 안내할게요", body = "차로 돌아가지 않아도 같은 안내와 사고 기록을 볼 수 있어요.", next = null, decline = null)
        s.next?.contains("휴대폰") == true -> s.copy(body = s.body.replace(" 다음 안내는 휴대폰에서 이어져요.", ""), next = "대피했어요")
        else -> s
    }
    fun shotOf(s: HandoffData.ShotInfo) = Shot(ShotArt.entries.find { it.name == s.art } ?: ShotArt.OVERVIEW, s.title, s.line, s.fwd, s.right, s.suggestion)

    /** 사진 이름: <순서>_<종류>_<찍은 시각ms>.jpg */
    fun photoName(index: Int, art: String, ms: Long) = "${index}_${art}_$ms.jpg"

    /** 이 사고(sinceMs 이후, 다음 사고 untilMs 이전)에 이 장면으로 찍은 사진 중 가장 최근 것. 다른 사고 사진은 지우지 않고 고르지만 않는다 */
    fun latestPhoto(names: List<String>, index: Int, art: String, sinceMs: Long, untilMs: Long = Long.MAX_VALUE): String? {
        val p = "${index}_${art}_"
        return names.filter { it.startsWith(p) && it.endsWith(".jpg") }
            .mapNotNull { n -> n.removePrefix(p).removeSuffix(".jpg").toLongOrNull()?.let { n to it } }
            .filter { it.second in sinceMs until untilMs }.maxByOrNull { it.second }?.first
    }

    fun sha256(f: File): String {
        val md = MessageDigest.getInstance("SHA-256")
        f.inputStream().use { s -> val b = ByteArray(65536); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    /** 서버에서 온 상태 → 화면에 둘 상태. 서버가 껐다 켜져 빈 것뿐이면(철회로 지운 것이 아니면) 받아 둔 사고를 그대로 둔다 */
    fun keepIncident(old: SyncState?, new: SyncState): SyncState =
        if (new.incident == null && !new.wiped && old?.incident != null) old.copy(version = new.version) else new

    /** 사고 뒤 1분이 지나도 기록이 안 오면 차 전원·연결이 끊긴 것으로 본다 */
    fun recordLost(d: HandoffData, now: Double = System.currentTimeMillis() / 1000.0) = d.hash == null && now - d.t > 60

    /** 현장 안내를 먼저 띄울 만큼 최근 사고인가. 차가 꺼져 안내가 안 닫힌 사고가 앱을 열 때마다 안내로 가지 않게 */
    fun fresh(d: HandoffData, now: Double = System.currentTimeMillis() / 1000.0) = now - d.t < 3600

    /** 다음 할 일 하나(기획 §4-8 우선순위). 앞선 일이 끝나야 다음 일이 나온다 */
    fun nextTodo(d: HandoffData, m: Marks, notAccident: Boolean, now: Double = System.currentTimeMillis() / 1000.0): Todo {
        if (notAccident) return Todo(Task.NOT_ACCIDENT, "사고 아님으로\n표시했어요", "차에서 되돌리면 다시 사고로 돌아와요", "기록 보기")
        val ask = "묻기".takeIf { d.samples.isNotEmpty() }
        val place = if (d.rental) "렌터카 회사에" else "보험사에"
        return when {
            d.hurt && !m.called119 && !d.near -> Todo(Task.CHECK_119, "119에\n연락했나요?", "다친 사람이 있으면 먼저 전화하세요", "119 전화", "했어요")
            m.sceneOpen -> Todo(Task.SCENE, "현장 안내를\n이어서 보세요", "차에서 하던 안내가 여기 있어요", "안내 이어 보기", ask)
            (d.hurt || d.severe) && !m.myself && now - d.t < 7 * 86400 -> // 이 경로는 7일 동안 서류 할 일을 내밀지 않는다
                Todo(Task.REST, "회복이\n먼저예요", "병원 검사를 받으세요. 서류 할 일은 7일 뒤에 보여 드려요.",
                    if (m.canHandOver) "가족에게 맡기기" else "제가 할게요", "제가 할게요".takeIf { m.canHandOver })
            !m.card -> Todo(Task.CARD, "블랙박스 카드를\n챙기세요", "차를 맡기기 전에요. 폐차되면 찾기 어려워요.", "카드 챙겼어요", ask)
            !m.claimed -> Todo(Task.CLAIM, "$place\n사고를 알리세요", "읽을 문장을 준비해 뒀어요", "전화 원고 보기", ask)
            !m.sent -> Todo(Task.SEND, "담당자에게\n기록을 보내세요", "받는 사람에 따라 넣을 것을 골라요", "기록 보내기", ask)
            d.samples.isNotEmpty() -> Todo(Task.WAIT, "기다리는 중이에요", "전화가 오면 차 기록으로 답하세요", "차에게 묻기")
            else -> Todo(Task.WAIT, "기다리는 중이에요", "담당자가 또 달라고 하면 다시 보낼 수 있어요", "기록 다시 보내기")
        }
    }

    /** 받는 사람별 기본값: 내 쪽(보험사·렌터카)은 전부, 상대 쪽·경찰은 사진만 */
    fun includeFor(to: ContactType) = (to == ContactType.MINE || to == ContactType.RENT).let { Include(true, it, it, it, it) }

    /** 공유 창은 결과를 알려 주지 않아서 "보냈다"가 아니라 "보냈다고 표시함" */
    fun sentLog(to: ContactType) = "${to.person}에게 ${to.edition}을 보냈다고 표시함"

    /** 보내기 칩: 이 사고의 연락 상대 종류(가족은 앱 안에서 맡기므로 뺌). 연락 상대가 없으면 내 쪽 하나 */
    fun recipients(contacts: List<CaseContact>, rental: Boolean): List<ContactType> =
        listOf(ContactType.OTHER, ContactType.MINE, ContactType.RENT, ContactType.POLICE).filter { t -> contacts.any { it.type == t } }
            .ifEmpty { listOf(if (rental) ContactType.RENT else ContactType.MINE) }

    /** 처음 보이는 연락 상대 1장: 차에서 온 보험사·렌터카 번호, 없으면 휴대폰 설정의 보험사 번호 */
    fun seedContacts(d: HandoffData, setting: Contact?): List<CaseContact> =
        (callee(d) ?: setting)?.let { listOf(CaseContact(if (d.rental && callee(d) != null) ContactType.RENT else ContactType.MINE, it.name.removeSuffix(" (가상)"), it.phone)) }.orEmpty()

    fun encodeContacts(l: List<CaseContact>) = JSONArray(l.map { JSONObject().put("type", it.type.name).put("name", it.name).put("phone", it.phone).put("no", it.no) }).toString()
    fun decodeContacts(s: String): List<CaseContact> = runCatching { JSONArray(s).let { a -> List(a.length()) { a.getJSONObject(it) } }.mapNotNull { o ->
        ContactType.entries.find { it.name == o.getString("type") }?.let { CaseContact(it, o.getString("name"), o.getString("phone"), o.optString("no")) } } }.getOrDefault(emptyList())
    fun encodeLog(l: List<LogEntry>) = JSONArray(l.map { JSONObject().put("at", it.at).put("text", it.text) }).toString()
    fun decodeLog(s: String): List<LogEntry> = runCatching { JSONArray(s).let { a -> List(a.length()) { a.getJSONObject(it).run { LogEntry(getDouble("at"), getString("text")) } } } }.getOrDefault(emptyList())

    fun lockLog(hash: String) = "기록 잠김 · 지문 ${hash.take(6)}…"

    /** "오늘 14:02", "어제 09:30", "9월 3일 18:12" */
    fun dayTime(t: Double, now: Double = System.currentTimeMillis() / 1000.0, zone: ZoneId = ZoneId.systemDefault()): String {
        fun date(x: Double) = Instant.ofEpochMilli((x * 1000).toLong()).atZone(zone).toLocalDate()
        val hm = DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli((t * 1000).toLong()))
        return when (date(t)) { date(now) -> "오늘 $hm"; date(now).minusDays(1) -> "어제 $hm"; else -> time(t, zone) }
    }

    /** 일지 왼쪽 칸 */
    fun logTime(at: Double, now: Double = System.currentTimeMillis() / 1000.0, zone: ZoneId = ZoneId.systemDefault()): String {
        fun fmt(x: Double, p: String) = DateTimeFormatter.ofPattern(p).withZone(zone).format(Instant.ofEpochMilli((x * 1000).toLong()))
        return if (now - at < 60) "방금" else if (fmt(at, "yyyyMMdd") == fmt(now, "yyyyMMdd")) fmt(at, "HH:mm") else fmt(at, "M/d")
    }

    /** "뒤쪽 충격". 방향을 모르면 null */
    fun impactWord(d: HandoffData) = d.impactDeg?.let { ClaimText.side(it).let { s -> if (s.endsWith("쪽")) "$s 충격" else "${s}쪽 충격" } }

    /** 카드 띠의 한 줄: "차 · 앞쪽 충격", 아무것도 모르면 "충격" */
    fun caseWord(d: HandoffData) = if (manual(d)) "직접 기록" else listOfNotNull(d.kind.takeIf { d.kindChosen && it != CrashKind.UNKNOWN.label }, impactWord(d)).joinToString(" · ").ifEmpty { "충격" }

    fun statusWord(t: Task) = when (t) { Task.NOT_ACCIDENT -> "사고 아님으로 표시"; Task.WAIT -> "기다리는 중"; else -> "할 일 있음" }

    /** 접수 전화를 받을 곳: 렌터카면 렌터카 회사(contacts 는 보험사, 렌터카, 가족 순) */
    fun callee(d: HandoffData) = d.contacts.filter { it != d.guardian }.let { if (d.rental) it.lastOrNull() else it.firstOrNull() }

    /**
     * 접수 전화에서 읽을 문장. *글자* = 굵게(시각·값). 차 기록과 운전자가 넣은 값만 쓴다.
     * 추정한 대상·도로 종류(d.highway)는 운전자 입으로 말하게 하지 않고, 속도는 접수에 필요 없어 넣지 않는다. 렌터카면 내 차 번호·증권번호는 틀린 정보라 뺀다
     */
    fun script(d: HandoffData, o: ClaimOwner, now: Double = System.currentTimeMillis() / 1000.0): List<String> {
        fun day(t: Double) = ClaimText.time(t, "yyyyMMdd")
        fun value(v: String?) = v?.replace("*", "")?.trim()?.takeIf { it.isNotEmpty() }
        fun be(v: String) = if (subject(v) == "이") "이에요" else "예요"
        val date = when (day(d.t)) { day(now) -> "오늘 "; day(now - 86400) -> "어제 "; else -> ClaimText.time(d.t, "M월 d일 ") }
        val what = when (kindOf(d).takeIf { d.kindChosen }) {
            CrashKind.VEHICLE -> "다른 차와 부딪혔어요"
            CrashKind.OBJECT -> "벽이나 시설물에 부딪혔어요"
            CrashKind.PERSON -> "사람이나 자전거와 부딪혔어요"
            else -> "사고가 났어요"
        }
        return listOfNotNull(
            "사고 접수하려고 전화드렸어요.",
            date + ClaimText.time(d.t, "a") + " *" + ClaimText.time(d.t, "h시 m분") + "*에 " + (if (manual(d)) "사고가 난 것을 확인했어요." else "$what."),
            d.impactDeg?.let { "차 센서 추정으로는 *${ClaimText.side(it)}* 부분에 충격이 있었어요." },
            "차 기록으로는 에어백이 터졌어요.".takeIf { d.controlsRecorded && d.samples.any { it.airbag } },
            value(o.name)?.let { "운전자는 *$it*${be(it)}." },
            value(o.plate.takeUnless { d.rental })?.let { "차 번호는 *$it*${be(it)}." },
            value(o.policyNo.takeUnless { d.rental })?.let { "증권번호는 *$it*${be(it)}." },
            "정확한 위치는 문자로 보내 드릴게요.".takeIf { ClaimText.place(d) != null },
            "접수번호를 알려 주세요.",
        )
    }

    /** "딸 박지은" → ("따님", "지은 님"). 이름 없이 "어머니"면 ("어머니", "어머니") */
    fun family(name: String): Pair<String, String> {
        val parts = name.split(" ").filter { it.isNotEmpty() }
        val who = when (val r = parts.firstOrNull() ?: "가족") { "딸" -> "따님"; "아들" -> "아드님"; else -> r }
        return who to if (parts.size > 1) parts.last().let { if (it.length == 3) it.drop(1) else it } + " 님" else who
    }

    /** 앞 글자에 받침이 있으면 "이", 없으면 "가". 숫자는 읽는 소리 기준(2·4·5·9만 받침 없음), 영문은 "이" */
    fun subject(word: String) = word.lastOrNull()?.let { c ->
        when (c) { in '가'..'힣' -> if ((c - '가') % 28 != 0) "이" else "가"; '2', '4', '5', '9' -> "가"; else -> "이" }
    } ?: "이"

    /** 맡겨진 사고를 보는 보호자 계정(시연용 하나, 서버 handover) */
    const val FAMILY = "family"

    /** 운전자가 휴대폰에서 직접 시작한 사고: 차가 보낸 안내 단계가 없다 */
    fun manual(d: HandoffData) = ClaimText.manual(d)

    /** 시연용 계정 = 차 앱 운전자 id. 차 앱 Guide.kt 의 Personas(id·이름)와 반드시 같아야 함 (:app 에 의존할 수 없어 복사) */
    val drivers = listOf("haneul" to "김하늘", "junho" to "박준호", "sunja" to "이순자", "guest" to "손님")
    val logins = drivers + (FAMILY to "가족 (맡은 사고 보기)")
    fun driverName(id: String?) = logins.firstOrNull { it.first == id }?.second
}

enum class ContactType(val label: String, val person: String) {
    OTHER("상대 보험사", "상대 보험사 담당자"), MINE("내 보험사", "내 보험사 담당자"), RENT("렌터카", "렌터카 회사 담당자"), POLICE("경찰", "경찰 조사관"), FAMILY("가족", "가족");
    val edition get() = if (this == MINE || this == RENT) "전체판" else "기록판"
}
data class CaseContact(val type: ContactType, val name: String, val phone: String, val no: String = "")
data class LogEntry(val at: Double, val text: String)
/** 사고마다 사람이 표시한 것. sceneOpen = 최신 사고의 현장 안내가 안 끝났고 1시간 안 */
data class Marks(val called119: Boolean = false, val sceneOpen: Boolean = false, val myself: Boolean = false, val card: Boolean = false, val claimed: Boolean = false, val sent: Boolean = false, val canHandOver: Boolean = false)
enum class Task { CHECK_119, SCENE, REST, CARD, CLAIM, SEND, WAIT, NOT_ACCIDENT }
data class Todo(val task: Task, val title: String, val line: String, val button: String, val second: String? = null)
/** 보낼 때 넣을 항목(차 기록은 항상 들어가 여기 없음) */
data class Include(val photo: Boolean, val stmt: Boolean, val loc: Boolean, val contact: Boolean, val policy: Boolean)

/**
 * 휴대폰이 보여 줄 단계. seenStep/seenBy = 마지막으로 본 서버 단계(바뀌었을 때만 따라감)
 * 차가 옮긴 단계("car")만 따라가고, 휴대폰이 보낸 단계가 되돌아온 것(메아리)은 무시한다
 */
data class Follow(val t: Double? = null, val step: Int = 0, val seenStep: Int? = null, val seenBy: String? = null) {
    fun onServer(s: SyncState): Follow {
        val d = s.incident ?: return this // 서버가 껐다 켜져 비었으면 받아 둔 사고를 지우지 않는다
        val fresh = d.t != t
        val changed = fresh || s.step != seenStep || s.stepBy != seenBy
        val to = when {
            fresh -> s.step ?: 0 // 새 사고(또는 앱을 새로 연 경우): 서버의 지금 단계부터
            changed && s.stepBy == "car" && s.step != null -> s.step
            else -> step
        }
        return Follow(d.t, to.coerceIn(0, maxOf(0, d.steps.size - 1)), s.step, s.stepBy)
    }

    /** 휴대폰에서 옮김. seen 은 그대로: 그 전 차 단계가 다른 이유(기록 갱신)로 다시 와도 되돌아가지 않음, (to, "phone") 메아리는 "phone" 이라 무시 */
    fun move(to: Int) = copy(step = to)
}
