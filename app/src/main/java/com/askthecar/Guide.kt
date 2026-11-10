package com.askthecar

import android.content.Context

/** brief = 간결 모드에서도 보여 주는 핵심 단계, tip = 사회초년생에게만 보여 주는 용어 풀이 */
data class GuideStep(
    val art: StepArt, val title: String, val body: String, val voice: String = "$title. $body",
    val call: String? = null, val brief: Boolean = false, val tip: String? = null,
    /** 전화 단계의 두 번째 버튼 글자(예: "없어요"). call 과 같이 쓴다 */
    val decline: String? = null,
    /** 전화 버튼에 같이 적을 상대 이름(119·112는 없음) */
    val callName: String? = null,
    /** 아래 진행 버튼 글자(없으면 "다음"). 상황에 맞는 말로 */
    val next: String? = null,
    /** 두 번째 전화번호(고속도로 신고의 1588-2504) */
    val call2: String? = null,
)

/** 안내 방식 */
enum class GuideMode(val label: String) { DETAIL("상세"), BRIEF("간결"), VOICE("음성") }

/** 사용자 유형 (기획서 "사용자 유형과 맞춤화") */
enum class Group(val label: String) { NEWBIE("사회초년생"), WORKER("사회인"), SENIOR("고령자") }

// Contact 는 shared/HandoffData.kt, StepArt·CrashKind·PhotoGuide 는 shared/CrashTypes.kt (휴대폰 앱과 같이 씀)

/**
 * 운전자 페르소나 = 앱 안의 "로그인". Pleos 로그인 프로필은 일반 앱이 읽을 수 없어(권한 signature|privileged) 앱이 직접 고르게 한다.
 * 선택한 id 하나만 차 안(SharedPreferences)에 저장. 회사 이름·전화번호는 모두 가상(실제 회사 아님).
 */
data class Persona(
    val id: String, val name: String, val age: Int, val job: String, val group: Group,
    val rental: Boolean, val insurance: String, val mode: GuideMode,
    val insurer: Contact? = null, val rentalCompany: Contact? = null, val guardian: Contact? = null,
) {
    /** 화면·PDF 에 쓰는 한 줄 소개 */
    val summary get() = listOfNotNull(name, "${age}세".takeIf { age > 0 }, job.takeIf { it != Personas.guest.job }, if (rental) "렌터카" else "자차").joinToString(" · ")
}

/** 설정의 "내 정보". 차 안(SharedPreferences)에만 저장하고, 사고가 나면 연락처만 같은 계정 휴대폰 앱으로 보낸다 */
data class MyInfo(
    val mode: GuideMode = GuideMode.DETAIL, val rental: Boolean = false,
    val insurerName: String = "", val insurerPhone: String = "",
    val rentalName: String = "", val rentalPhone: String = "",
    val guardianName: String = "", val guardianPhone: String = "",
) {
    /** 번호가 하나라도 있거나 안내 방식을 바꿨으면 입력한 것으로 본다 */
    val filled get() = mode != GuideMode.DETAIL || rental || listOf(insurerPhone, rentalPhone, guardianPhone).any { it.isNotBlank() }
}

object Personas {
    /** 입력한 내 정보로 안내 대상을 만든다. 안내 방식이 곧 안내 깊이: 상세 = 용어 풀이, 음성 = 큰 글자·음성 위주 */
    fun fromInfo(i: MyInfo): Persona {
        fun contact(name: String, phone: String, fallback: String) = phone.trim().takeIf { it.isNotEmpty() }?.let { Contact(name.trim().ifEmpty { fallback }, it) }
        val group = when (i.mode) { GuideMode.DETAIL -> Group.NEWBIE; GuideMode.BRIEF -> Group.WORKER; GuideMode.VOICE -> Group.SENIOR }
        return guest.copy(
            group = group, mode = i.mode, rental = i.rental, insurance = i.insurerName.trim().ifEmpty { "미등록" },
            insurer = contact(i.insurerName, i.insurerPhone, "보험사"),
            rentalCompany = if (i.rental) contact(i.rentalName, i.rentalPhone, "렌터카 회사") else null, // 내 차로 바꿔도 입력값은 남아 있으므로
            guardian = contact(i.guardianName, i.guardianPhone, "보호자"),
        )
    }

    fun info(ctx: Context) = prefs(ctx).run {
        MyInfo(
            GuideMode.entries.find { it.name == getString("mode", null) } ?: GuideMode.DETAIL, getBoolean("rental", false),
            getString("insurerName", "")!!, getString("insurerPhone", "")!!, getString("rentalName", "")!!, getString("rentalPhone", "")!!,
            getString("guardianName", "")!!, getString("guardianPhone", "")!!,
        )
    }

    fun saveInfo(ctx: Context, i: MyInfo) = prefs(ctx).edit().putString("mode", i.mode.name).putBoolean("rental", i.rental)
        .putString("insurerName", i.insurerName).putString("insurerPhone", i.insurerPhone).putString("rentalName", i.rentalName).putString("rentalPhone", i.rentalPhone)
        .putString("guardianName", i.guardianName).putString("guardianPhone", i.guardianPhone).apply()

    fun clearInfo(ctx: Context) = saveInfo(ctx, MyInfo())

    val all = listOf(
        Persona(
            "haneul", "김하늘", 26, "신입 디자이너", Group.NEWBIE, rental = true,
            insurance = "렌터카 자차손해면책 가입", mode = GuideMode.DETAIL,
            rentalCompany = Contact("바로카셰어 사고접수 (가상)", "02-000-2002"), guardian = Contact("어머니", "010-0000-1111"),
        ),
        Persona(
            "junho", "박준호", 41, "영업팀장", Group.WORKER, rental = false,
            insurance = "개인용 자동차보험 + 긴급출동 특약", mode = GuideMode.BRIEF,
            insurer = Contact("한결손해보험 사고접수 (가상)", "02-000-1001"),
        ),
        Persona(
            "sunja", "이순자", 72, "은퇴", Group.SENIOR, rental = false,
            insurance = "개인용 자동차보험", mode = GuideMode.VOICE,
            insurer = Contact("한결손해보험 사고접수 (가상)", "02-000-1001"), guardian = Contact("딸 박지은", "010-0000-2222"),
        ),
    )

    /** 아직 내 정보를 입력하지 않았을 때. 설정의 기본값(상세)과 같게 안심 단계·용어 풀이를 보여 준다 */
    val guest = Persona("guest", "운전자", 0, "미등록", Group.NEWBIE, rental = false, insurance = "미등록", mode = GuideMode.DETAIL)

    private fun prefs(ctx: Context) = ctx.getSharedPreferences("profile", Context.MODE_PRIVATE)

    /** 게임 시연에서 고른 사람 > 설정의 내 정보 > null(아직 입력 안 함). 실차엔 고르는 화면이 없어 내 정보만 쓰인다 */
    fun load(ctx: Context): Persona? = prefs(ctx).getString("persona", null)?.let { id -> all.firstOrNull { it.id == id } }
        ?: info(ctx).takeIf { it.filled }?.let(::fromInfo)

    /** 손님은 저장하지 않는다(지난 운전자 기록을 지움) */
    fun save(ctx: Context, p: Persona) = prefs(ctx).edit().apply { if (p.id == guest.id) remove("persona") else putString("persona", p.id) }.apply()
}

/** 실차 데이터로 추정한 사고 대상과 근거 한 줄 */
data class KindGuess(val kind: CrashKind, val why: String)

/**
 * 실차에서 받는 값만으로 무엇과 부딪혔는지 추정.
 * 쓰는 값: 속도·기어(AAOS 차량 속성), 충격 방향(안드로이드 가속도 센서), 카메라 사진 인식.
 * Pleos SDK 에 주변 물체·충돌 대상 정보는 없음(2.3.2 확인). FCA 경고등은 장치 꺼짐·고장 표시라 근거로 안 씀.
 * 기준값(3km/h, 15m, 뒤 135~225°)은 추정 → 게임 사고로 조정. 모르면 null(운전자가 고름).
 */
fun inferKind(log: List<Sample>, crashT: Double, impactDeg: Double?, seen: Sighting? = null): KindGuess? {
    if (seen != null && kotlin.math.hypot(seen.fwd, seen.right) < 15) {
        if (!seen.isPerson) return KindGuess(CrashKind.VEHICLE, "카메라에 차가 가까이 보여요")
        if (seen.score >= 0.6f) return KindGuess(CrashKind.PERSON, "카메라에 사람·자전거가 가까이 보여요") // 낮은 점수는 차 뒷모습을 오토바이로 보는 일이 있어(시험에서 확인) 믿지 않음
    }
    val before = log.lastOrNull { it.t <= crashT - 0.3 } ?: return null
    val rear = impactDeg != null && impactDeg in 135.0..225.0
    return when {
        before.speedKph < 3 || before.gear == "P" -> KindGuess(CrashKind.VEHICLE, "멈춰 있을 때 충격이 기록됐어요")
        rear && before.gear == "R" -> null // 후진 충돌은 차·기둥 모두 흔함: 운전자에게 묻는다
        rear -> KindGuess(CrashKind.VEHICLE, "앞으로 가는 중 뒤쪽 충격이 기록됐어요")
        // 사진에 차·사람이 안 보여도 벽으로 단정하지 않는다: 인식이 놓치는 일이 많아(시험에서 확인) 운전자에게 묻는다
        else -> null
    }
}

/**
 * 사고 대처 가이드. 순서와 문구의 기준: docs/reports/사고 수칙 공식 원문 재확인.md 의 "최종 변경 목록" (어느 기관의 공식 순서도 아님 [추론]).
 * 고속도로: [다쳤어요 119] → 비상등·갓길 → 트렁크 → 휴대폰 들고 대피 → 다친 사람 → 신고 → [시설물] → 기다리기 → [보호자] → 보험사 → 사진(선택) → 블랙박스(나중에) → 연락처.
 * 시내: [다쳤어요 119] → [에어백 전화] → [안심] → 비상등 → 다친 사람 → [위험하면 옮기기] → 차 밖으로 → 휴대폰 → [시설물/경찰 판단] → 연락처 → [보호자] → 보험사 → 블랙박스 → 사진 → [아직이면 옮기기].
 * 앱은 119에 자동으로 걸지 않음(차의 긴급 구난 센터·폰과 중복 신고 방지). 판단 단어 금지.
 * shortcut: 문구가 코드에 있음. 비개발자가 문구를 자주 고치게 되면 assets/guides.json 으로 옮긴다.
 */
object Guides {
    // 말투(2026-11-07): "괜찮아요. 지금부터는 차가 함께할게요." — 짧은 존댓말, 한 화면 한 행동, 명령보다 "~해 주세요". 숫자·번호는 그대로
    private val operatorCall = GuideStep(StepArt.PHONE, "차로 오는 전화를 받아 주세요",
        "에어백이 터지면 차의 긴급 구난 센터에서 전화가 올 수 있어요. 안 오면 기다리지 말고 휴대폰으로 119에 전화해 주세요. 흰 가루는 에어백에서 나온 거예요. 창문을 열고, 운전대 안쪽은 뜨거우니 만지지 마세요.", brief = true)
    private val calm = GuideStep(StepArt.CALM, "괜찮아요. 지금부터는 차가 함께할게요", "해야 할 일을 하나씩, 순서대로 알려 드릴게요.",
        voice = "괜찮아요. 지금부터는 차가 함께할게요. 해야 할 일을 하나씩, 순서대로 알려 드릴게요.", next = "시작할게요")
    /** 팝업에서 "다쳤어요"를 고른 경우. 다시 묻지 않고 바로 119 */
    private fun hurtCall(highway: Boolean) = GuideStep(StepArt.HURT_CALL, "119에 먼저 전화해 주세요",
        if (highway) "움직일 수 있으면 가드레일 밖으로 나가서 전화해 주세요. 못 나가면 '고속도로 차 안인데 못 나간다'고 먼저 말해 주세요. 말하기 어려우면 119로 문자를 보내 주세요."
        else "위치와 다친 곳을 말하고 전화를 끊지 말아 주세요. 주변이 위험하지 않으면 움직이지 마세요. 말하기 어려우면 119로 문자를 보내 주세요.",
        call = "119", decline = "전화했어요", brief = true)
    /** 제한속도 값이 없어 고속도로인지 모를 때만. 답하면 목록에서 빠진다 */
    private val roadAsk = GuideStep(StepArt.ROAD, "지금 고속도로나 자동차전용도로인가요?", "", voice = "지금 고속도로나 자동차전용도로인가요?", next = "네", decline = "아니요", brief = true)
    private fun hazard(body: String) = GuideStep(StepArt.HAZARD, "비상등을 켜 주세요", body, brief = true,
        tip = "비상등은 보통 운전대 근처의 빨간 삼각형 버튼이에요.", next = "켰어요")
    private val cityHazard = hazard("뒤에서 오는 차가 이 차를 알아볼 수 있어요.")
    private val highwayHazard = hazard("뒤에서 오는 차가 이 차를 알아볼 수 있어요. 차가 움직이면 갓길로 옮겨 세워 주세요.")
    private val trunk = GuideStep(StepArt.TRUNK, "트렁크를 열어 두세요", "뒤차가 사고 차임을 알아보기 쉬워져요. 버튼을 누르면 차가 열어 드려요.", brief = true)
    /** 고속도로는 대피와 휴대폰 이어 보기가 한 단계. 이 단계 뒤로 차 화면은 고정(CrashActivity) */
    private val highwayWalk = GuideStep(StepArt.WALK, "휴대폰을 들고 가드레일 밖으로 대피해 주세요",
        "동승자도 함께요. 차 안, 차 앞뒤, 갓길은 위험해요. 가드레일이 없으면 차도에서 최대한 떨어져 주세요. 다음 안내는 휴대폰에서 이어져요.", brief = true, next = "휴대폰으로 이어 볼게요")
    private fun cityWalk(kind: CrashKind) = GuideStep(StepArt.WALK, "도로 밖 안전한 곳으로 나와 주세요",
        "차도에는 서 있지 마세요. 현장을 떠나지는 말아 주세요." + if (kind == CrashKind.OBJECT) " 끊어진 전선이 차에 닿아 있으면 내리지 말고 119에 전화해 주세요." else "", brief = true, next = "나왔어요")
    /** 차에서 내린 뒤엔 차 화면을 볼 수 없으니, 여기부터는 같은 계정의 휴대폰 앱이 이어서 안내한다(CarSync) */
    private val handoff = GuideStep(StepArt.HANDOFF, "이제 휴대폰으로 이어서 볼게요", "차에서 내리면 휴대폰에 같은 안내와 사고 기록이 보여요.", brief = true, next = "차에서 계속 볼게요")
    private fun report(severe: Boolean) = GuideStep(StepArt.PHONE, "가드레일 밖에서 신고해 주세요",
        "이정표, 출구 번호, 도로 번호로 위치를 말해 주세요. 사람이 다쳤으면 112에 꼭 알려 주세요. 긴급견인은 1588-2504에 부탁할 수 있어요. 삼각대는 위험 없이 세울 수 있을 때만 세우고, 못 세웠으면 신고할 때 말해 주세요." +
            if (severe) " 차로 오는 전화를 받으러 돌아가지 마세요." else "",
        call = "112", call2 = "1588-2504", decline = "신고했어요", brief = true)
    private val wait = GuideStep(StepArt.WAIT, "차로 돌아가지 말고 기다려 주세요",
        "경찰이나 견인 기사, 구조대가 올 때까지 가드레일 밖에 있어 주세요. 차에 못 나온 사람이 있으면 119에 바로 말하고 안내를 따라 주세요.", brief = true, next = "알겠어요")
    private val injured = GuideStep(StepArt.PERSON, "다친 사람이 있나요?", "119에 위치, 다친 사람 수, 의식과 호흡, 심한 출혈, 갇힌 사람이 있는지 말해 주세요. 전화를 끊지 말고 안내를 따라 주세요.", call = "119", decline = "없어요", brief = true)
    private val dontMove = GuideStep(StepArt.DONT_MOVE, "다친 분은 옮기지 마세요", "불이 나거나 2차 사고 위험이 없으면 그대로 두고 곁에 있어 주세요. 피가 많이 나면 깨끗한 천으로 세게 눌러 주세요. 상대가 괜찮다고 해도 떠나지 말고 112에 알려 주세요.",
        call = "112", decline = "알렸어요", brief = true)
    private val moveRisk = GuideStep(StepArt.MOVE_RISK, "차가 위험한 자리에 있나요?", "뒤차에 부딪힐 것 같으면 사진보다 먼저 길 가장자리로 옮겨 주세요.", next = "옮겼어요", decline = "위험하지 않아요", brief = true)
    private val moveLater = GuideStep(StepArt.MOVE_LATER, "차를 길 가장자리로 옮겨 주세요", "차가 움직일 때만요. 경찰이 기다리라고 했으면 따라 주세요.", next = "옮겼어요", decline = "옮길 수 없어요", brief = true)
    private val facility = GuideStep(StepArt.FACILITY, "부서진 시설물이 있나요?", "혼자 부딪혔어도 가드레일·벽·신호등이 부서졌다면 112에 알려 주세요.",
        call = "112", decline = "없어요", brief = true)
    private val police = GuideStep(StepArt.POLICE, "경찰에 알릴지 확인해 주세요",
        "신고하지 않아도 되는 건 차만 부서진 게 분명하고 차를 안전하게 정리했을 때뿐이에요. 조금이라도 다쳤거나, 시설물이 부서졌거나, 잘 모르겠으면 112에 알려 주세요.", call = "112", decline = "해당 없어요", brief = true)
    private const val EXCHANGE_BODY = "이름, 전화번호, 주소를 알려 주세요. 상대 차 번호와 보험사도 적어 두세요. 본 사람이 있으면 연락처를 받아 두세요. 누구 잘못인지는 지금 정하지 않아도 돼요. 경찰이 아닌 사람이 면허증을 달라거나 잘못을 인정하는 글을 쓰라고 하면 거절해도 돼요."
    private fun exchange(highway: Boolean) = GuideStep(StepArt.EXCHANGE, "상대방과 연락처를 주고받아 주세요", (if (highway) "경찰이나 견인 기사가 온 뒤 안전한 곳에서 해 주세요. " else "") + EXCHANGE_BODY, brief = true)
    private fun guardianStep(c: Contact) =
        GuideStep(StepArt.GUARDIAN, "${c.name}에게 알려 주세요", "사고가 났다는 것과 지금 있는 곳만 말해도 충분해요.", call = c.phone, decline = "나중에 할게요", callName = c.name)
    private const val NO_NUMBER = "번호는 보험 증권이나 대여 계약서, 앱에서 찾아 주세요."
    private const val RENTAL_BODY = "사고가 나면 바로 알려 주세요. 합의나 견인, 수리할 곳은 회사와 먼저 상의해 주세요."
    private const val INSURER_BODY = "견인이 필요하면 긴급출동도 함께 요청하세요. 낯선 견인차나 렌터카는 현장에서 바로 정하지 않아도 돼요. 보험사에 알리지 않고 합의하지 마세요."
    /** 번호가 없어도 단계는 숨기지 않는다 */
    private fun contactStep(p: Persona): GuideStep {
        if (p.rental) return GuideStep(StepArt.INSURER, "렌터카 회사에 알려 주세요", RENTAL_BODY + " " + if (p.rentalCompany != null) "대여 계약서에 있는 사고 접수 번호예요." else NO_NUMBER,
            call = p.rentalCompany?.phone, decline = "이미 알렸어요".takeIf { p.rentalCompany != null }, brief = true, callName = p.rentalCompany?.name,
            tip = "자차손해면책은 사고 때 내가 낼 수리비를 줄여 주는 렌터카 계약이에요.")
        return GuideStep(StepArt.INSURER, "보험사에 사고를 접수해 주세요", if (p.insurer != null) "${p.insurer.name} · $INSURER_BODY" else "$INSURER_BODY $NO_NUMBER",
            call = p.insurer?.phone, decline = "이미 접수했어요".takeIf { p.insurer != null }, brief = true, callName = p.insurer?.name)
    }
    private val cityPhoto = GuideStep(StepArt.CAMERA, "차를 옮기기 전에 사진을 남겨 주세요", "차도에 들어가야 하거나 위험하면 찍지 않아도 돼요. 블랙박스가 있어도 사진은 따로 남겨 두면 좋아요.", brief = true, next = "찍을 곳 알려 주세요", decline = "건너뛰기")
    private val highwayPhoto = GuideStep(StepArt.CAMERA_FAR, "사진은 꼭 찍지 않아도 돼요", "지금은 안전이 먼저예요. 가드레일 밖에서 차도에 들어가지 않고 보이는 것만 찍어 주세요. 가까이 가서 찍지 마세요. 주행 기록은 저장되어 있지만 사진을 대신하지는 않아요.", brief = true, next = "가드레일 밖에서 찍기", decline = "찍지 않을게요")
    private val cityBlackbox = GuideStep(StepArt.CARD, "블랙박스가 녹화됐는지 확인해 주세요", "차 옆이 안전할 때만요. 영상은 지우지 말고, 차를 맡기기 전에 카드를 챙겨 주세요.", brief = true, next = "확인했어요", decline = "나중에 할게요")
    private val laterBlackbox = GuideStep(StepArt.CARD_LATER, "블랙박스 카드는 나중에 챙겨 주세요", "지금은 차로 돌아가지 마세요. 차가 안전한 곳으로 옮겨진 뒤, 수리나 폐차로 맡기기 전에 챙겨 주세요.", brief = true, next = "알겠어요")
    private val replay = GuideStep(StepArt.REPLAY, "기록으로 다시 그린 사고 장면이에요", "영상이 아니라 주행 기록으로 그린 [추정] 장면이에요.", brief = true)
    private val record = GuideStep(StepArt.RECORD, "기록은 안전하게 저장해 두었어요", "경위 설명문과 기록서를 언제든 다시 볼 수 있어요.", brief = true)

    /** 다친 운전자에게는 대피를 강하게 말하지 않는다(119 단계와 같은 말) */
    private fun forHurt(s: GuideStep, highway: Boolean): GuideStep {
        val body = if (highway) "움직일 수 있으면 가드레일 밖으로 나와 주세요. 못 나오면 119에 그렇게 말하고 안내를 따라 주세요. 불이나 연기가 보이면 바로 차에서 멀어져 주세요."
        else "주변이 위험하지 않으면 움직이지 말고 119 안내를 따라 주세요. 불이나 연기가 보이면 바로 차에서 멀어져 주세요."
        return s.copy(body = body, voice = "${s.title}. $body", next = if (highway) s.next else "다음")
    }

    /**
     * askRoad = 제한속도 값이 없어 물어야 함, near = 다른 사람이 다쳐 119를 눌렀음.
     * 목록에는 답에 따라 건너뛸 단계(DONT_MOVE·MOVE_*·POLICE)도 들어 있다. 건너뛰기는 StepArt.skipped: 답한 기기에서 바로 정해지게
     */
    fun steps(highway: Boolean, p: Persona = Personas.guest, severe: Boolean = false, kind: CrashKind = CrashKind.UNKNOWN, hurt: Boolean = false, askRoad: Boolean = false, near: Boolean = false): List<GuideStep> {
        val person = kind == CrashKind.PERSON
        val walk = (if (highway) highwayWalk else cityWalk(kind)).let { if (hurt) forHurt(it, highway) else it }
        val injury = listOfNotNull(injured.takeUnless { hurt }, dontMove.takeIf { !hurt || person })
        val canMove = !hurt && !person
        val hasOther = kind != CrashKind.OBJECT // 벽·시설물에 혼자 부딪혔으면 연락처를 주고받을 상대가 없다
        val card = if (highway || hurt || severe || near) laterBlackbox else cityBlackbox
        val guardian = p.guardian?.let(::guardianStep)
        val all = listOfNotNull(hurtCall(highway).takeIf { hurt }, roadAsk.takeIf { askRoad }) + (if (highway) {
            listOf(highwayHazard, trunk, walk) + injury +
                listOfNotNull(report(severe), facility.takeIf { kind == CrashKind.OBJECT }, wait, guardian, contactStep(p), highwayPhoto, card, exchange(true).takeIf { hasOther })
        } else {
            listOfNotNull(operatorCall.takeIf { severe }, calm.takeIf { p.group == Group.NEWBIE && !hurt }, cityHazard) + injury +
                listOfNotNull(moveRisk.takeIf { canMove }, walk, handoff,
                    if (kind == CrashKind.OBJECT) facility else police.takeIf { canMove },
                    exchange(false).takeIf { hasOther }, guardian, contactStep(p), card, cityPhoto, moveLater.takeIf { canMove })
        }) + listOf(replay, record)
        return if (p.mode == GuideMode.BRIEF) all.filter { it.brief } else all
    }
}

/** 참고 화면의 "비슷한 사고 유형" 후보 하나. facts = 이 후보가 나온 근거(기록된 사실, 시각 포함) */
data class TypeCandidate(val name: String, val facts: List<String>)

/**
 * 기록된 사실(충격 방향·충돌 직전 속도·5초 안 지시등·조향·후진·운전자가 고른 종류)로 "비슷한 사고 유형" 후보를 최대 3개.
 * 누구 잘못인지는 말하지 않는다 — 유형 이름과 근거 사실만. 기준값(충격 방향 90° 구간, 조향 2°·10°, 전진 5km/h)은 추정값.
 */
fun typeCandidates(log: List<Sample>, crashT: Double, impactDeg: Double?, kind: CrashKind, zone: java.time.ZoneId = java.time.ZoneId.systemDefault(), chosen: Boolean = true): List<TypeCandidate> {
    fun ts(t: Double) = FactTimeline.ts(t, zone)
    val recent = log.filter { it.t in (crashT - 5)..crashT }
    val last = recent.lastOrNull()
    val fast = log.filter { it.t in (crashT - 2)..crashT }.maxByOrNull { it.speedKph } // 충돌 뒤엔 속도가 떨어지므로 직전 2초의 최대
    val signal = recent.firstOrNull { it.signal != "none" }
    val steer = recent.maxByOrNull { kotlin.math.abs(it.steerDeg) }?.takeIf { kotlin.math.abs(it.steerDeg) >= 2.0 }
    val bigSteer = steer?.takeIf { kotlin.math.abs(it.steerDeg) >= 10.0 }
    val deg = impactDeg?.let { (it % 360 + 360) % 360 }
    val front = deg != null && (deg <= 45 || deg >= 315)
    val rear = deg != null && deg in 135.0..225.0
    val side = deg != null && !front && !rear
    val reverse = last?.gear == "R"
    val forward = !reverse && (fast?.speedKph ?: 0.0) >= 5.0

    val impactFact = deg?.let { "${ts(crashT)} 충격 ${FactTimeline.side(it)} 방향 [추정]" }
    val speedFact = fast?.let { "${ts(it.t)} 내 차 속도 ${"%.0f".format(it.speedKph)} km/h" }
    val kindFact = "사고 종류: ${kind.label} (${if (chosen) "운전자 선택" else "기록으로 추정"})".takeIf { kind != CrashKind.UNKNOWN }
    val steerFact = steer?.let { "${ts(it.t)} 앞바퀴 ${if (it.steerDeg < 0) "좌측" else "우측"} ${"%.1f".format(kotlin.math.abs(it.steerDeg))}°" }
    val signalFact = signal?.let { "${ts(it.t)} 방향지시등 ${if (it.signal == "L") "좌측" else "우측"} 켜짐" }
    fun c(name: String, vararg f: String?) = TypeCandidate(name, f.filterNotNull())

    return listOfNotNull(
        c("차와 보행자 사고 유형", kindFact, impactFact).takeIf { kind == CrashKind.PERSON },
        c("후진 중 사고 유형", last?.let { "${ts(it.t)} 기어 R단" }, impactFact, speedFact).takeIf { reverse },
        c("교차로 회전 중 사고 유형", steerFact, impactFact, speedFact).takeIf { bigSteer != null && (front || side) && !reverse && kind != CrashKind.PERSON },
        c("진로 변경 중 접촉 유형", signalFact, steerFact, impactFact).takeIf { side && (signal != null || steer != null) && bigSteer == null && !reverse && kind != CrashKind.PERSON },
        c("앞차와의 추돌 유형", impactFact, speedFact, kindFact).takeIf { front && forward && kind != CrashKind.OBJECT && kind != CrashKind.PERSON },
        c("뒤차에 추돌된 유형", impactFact, speedFact, kindFact).takeIf { rear && !reverse && kind != CrashKind.OBJECT && kind != CrashKind.PERSON },
        c("벽·시설물과 부딪힌 유형", kindFact, impactFact, speedFact).takeIf { kind == CrashKind.OBJECT && deg != null },
    ).take(3)
}
