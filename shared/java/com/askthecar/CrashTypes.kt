package com.askthecar

/** 대처 단계 그림 종류 (StepArtView 가 종류별 움직이는 그림을 그린다) */
enum class StepArt {
    CALM, HAZARD, PERSON, TRUNK, WALK, PHONE, CAMERA, RECORD, GUARDIAN, INSURER, FACILITY, REPLAY, HANDOFF, CARD, HURT_CALL,
    /** 다친 분 곁에 있기(그림은 PERSON 과 같음). 119를 눌렀거나 사람과 부딪혔을 때만 보인다 */
    DONT_MOVE,
    /** 고속도로의 사진·나중 블랙박스: 사람이 차 옆에 있는 그림과 "가까이 가지 마세요"가 어긋나서 선 그림만 */
    CAMERA_FAR, CARD_LATER,
    ROAD, WAIT, FIRE, POLICE, EXCHANGE, MOVE_RISK, MOVE_LATER,
}

val StepArt.isPhoto get() = this == StepArt.CAMERA || this == StepArt.CAMERA_FAR

/** 답에 따라 건너뛰는 단계. near = 다른 사람이 다쳐 119를 눌렀음, moved = 차를 이미 옮겼음, person = 사람과 부딪힘 */
fun StepArt.skipped(near: Boolean, moved: Boolean, person: Boolean) = when (this) {
    StepArt.DONT_MOVE -> !near && !person
    StepArt.MOVE_RISK, StepArt.POLICE -> near
    StepArt.MOVE_LATER -> near || moved
    else -> false
}

/** from 에서 dir 쪽으로 건너뛸 단계를 지나 닿는 번호. 목록 밖이면 -1 또는 size */
fun guideTarget(arts: List<StepArt?>, from: Int, dir: Int, skip: (StepArt) -> Boolean): Int {
    var i = from + dir
    while (i in arts.indices && arts[i]?.let(skip) == true) i += dir
    return i
}

/** 불·연기: 모든 안내 화면에서 여는 단계. 차·휴대폰·음성이 같은 문구를 쓰도록 공용 */
object FireGuide {
    const val LINK = "불이나 연기가 나면" // "보여요"는 앱이 불을 봤다는 말로 읽힌다
    const val TITLE = "바로 차에서 멀어져 주세요"
    const val BODY = "시동을 끄고 사람부터 멀리 떨어져 주세요. 고속도로면 가드레일 밖으로요. 터널이면 연기 반대쪽으로 유도등을 따라 나가 주세요. 피한 뒤 119에 전화해 전기차인지도 말해 주세요. 다른 사람이 차에 다가오지 못하게 하고, 새는 액체는 만지지 말고, 담배나 불은 쓰지 마세요."
}

/** 무엇과 부딪혔나. 실차 데이터 추정(inferKind)으로 미리 고르고 운전자가 확인·변경 */
enum class CrashKind(val label: String) { VEHICLE("차"), OBJECT("벽·시설물"), PERSON("사람·자전거"), UNKNOWN("확인 안 됨") }

/** 사진에서 찾은 상대 물체(대략). fwd/right = 내 차 중심 기준 앞(+)·오른쪽(+) 미터 */
data class Sighting(val frameLabel: String, val fwd: Double, val right: Double, val score: Float, val label: String = "car") {
    val isPerson get() = label == "person" || label == "bicycle" || label == "motorcycle"
}

/** 각도 제안 가이드 한 장면의 그림 종류 */
enum class ShotArt { SAFE, OVERVIEW, LANES, DAMAGE, WHEEL, PLATE, FACILITY, MARKS }

/** 촬영 한 장면. fwd/right = 충돌 순간 내 차 중심 기준 찍는 사람 위치(앞+·오른쪽+ 미터), suggestion = 출처 없는 앱 제안 */
data class Shot(val art: ShotArt, val title: String, val line: String, val fwd: Double, val right: Double, val suggestion: Boolean = false)

/**
 * 현장 촬영 가이드(상황별). 근거는 docs/reports/사고 수칙 공식 원문 재확인.md (2026-11-10):
 * 촬영 항목(앞바퀴, 근접과 원거리, 기름·냉각수 흔적)은 도로교통공단 교통안전수칙(2025)과 문제은행 493번 기준 [공식].
 * 고속도로에서 사진을 대피보다 앞세우지 않기는 문제은행 449번 기준. 가드레일 밖에서 보이는 것만 찍기와 고속도로 앞바퀴·근접 포기는 [추론].
 * 숫자(10~20m)는 차도로 걸어 나가게 할 수 있어 뺐다. 위치는 충격 방향 추정으로 정한 대략값.
 */
object PhotoGuide {
    fun part(deg: Double) = listOf("앞", "앞 오른쪽", "오른쪽", "뒤 오른쪽", "뒤", "뒤 왼쪽", "왼쪽", "앞 왼쪽")[(((deg + 22.5) % 360) / 45).toInt()]

    fun shots(impactDeg: Double?, kind: CrashKind, highway: Boolean): List<Shot> {
        val r = Math.toRadians(impactDeg ?: 0.0)
        val f = kotlin.math.cos(r); val s = kotlin.math.sin(r) // 충격 방향 (앞, 오른쪽)
        val side = impactDeg?.let { part(it) + " " } ?: ""
        val far = "차도로 나가지 않고 멀리서, 앞뒤 좌우 방향으로요."
        val overview = Shot(ShotArt.OVERVIEW, "먼저 사고 현장 전체를 한 장 남겨 주세요",
            if (kind == CrashKind.PERSON) "$far 다친 분의 얼굴은 찍지 마세요." else far,
            f * 2.3 - s * 14, s * 2.3 + f * 14)
        val damage = Shot(ShotArt.DAMAGE, "${side}부딪힌 곳을 가까이서 찍어 주세요", "1m쯤 떨어져 2~3장, 긁힌 자국이 잘 보이게요.", f * 3.6, s * 2.6)
        if (highway) return listOf(
            Shot(ShotArt.SAFE, "가드레일 밖 안전한 곳에서만 찍으세요", "차도에는 들어가지 마세요. 위험하면 찍지 않아도 돼요.", -12.0, 6.0),
            overview.copy(line = "가드레일 밖에서 사고 차가 다 나오게 찍어 주세요."),
        ) + (if (kind == CrashKind.VEHICLE) listOf(Shot(ShotArt.PLATE, "상대 차 번호판을 찍어 주세요", "안전할 때만, 가드레일 밖에서 확대해서요.", f * 9.0, s * 9.0 + 4)) else emptyList())
        val lanes = Shot(ShotArt.LANES, "차선과 신호등이 함께 나오게 찍어 주세요", "교차로나 차선을 바꾸던 중이었다면 특히 중요해요.", -10.0, 0.0)
        val wheel = Shot(ShotArt.WHEEL, "앞바퀴 방향을 찍어 주세요", "핸들을 돌리기 전에요.", 3.4, 3.2)
        val marks = Shot(ShotArt.MARKS, "타이어 자국, 부서진 조각, 기름·냉각수 흔적", "차도에 들어가지 않고 보이는 것만요.", f * 2.0 - s * 2.5, s * 2.0 + f * 2.5)
        return when (kind) {
            CrashKind.OBJECT -> listOf(overview.copy(line = "차와 부딪힌 시설물이 한 화면에 나오게, 차도로 나가지 않고 멀리서요."), damage, wheel,
                Shot(ShotArt.FACILITY, "부서진 시설물을 찍어 주세요", "가드레일·벽이 부서진 곳과 주변 표지판이 나오게요.", f * 6.0, s * 6.0 + 2), marks)
            CrashKind.PERSON -> listOf(overview, lanes, damage, wheel, marks)
            else -> listOf(overview, lanes, damage, wheel,
                Shot(ShotArt.PLATE, "상대 차 번호판을 찍어 주세요", "부딪힌 곳과 함께 나오면 더 좋아요.", f * 8.5, s * 8.5), marks)
        }
    }
}
