package com.askthecar.phone

import com.askthecar.HandoffData
import com.askthecar.PhotoGuide
import com.askthecar.Sample
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot

/**
 * 두 차 기록을 한 시간 축에 나란히 놓는 계산(단위 테스트 대상). 판단·비교 문구 없이 각 차가 기록한 값만 둔다.
 * 시간 축 = 내 차 충돌 시각 기준 초. 상대 기록은 상대 차 시계(epoch)대로 놓는다 → 두 시계가 다르면 그만큼 어긋난다(화면에 적음).
 */
object LinkLogic {
    /** 한 줄 = 한 시각. 그 시각 ±0.5초 안에 기록이 없으면 null(지어내지 않음) */
    data class Row(val sec: Int, val mine: Sample?, val other: Sample?, val gapM: Double?)

    fun nearest(samples: List<Sample>, t: Double, tol: Double = 0.5): Sample? =
        samples.minByOrNull { abs(it.t - t) }?.takeIf { abs(it.t - t) <= tol }

    fun rows(mine: HandoffData, other: HandoffData, from: Int = -10, to: Int = 5): List<Row> = (from..to).map { s ->
        val t = mine.t + s
        val a = nearest(mine.samples, t); val b = nearest(other.samples, t)
        Row(s, a, b, if (a != null && b != null) gapM(a, b) else null)
    }

    /** 두 기록 위치 사이 거리(m). 가까운 거리용 평면 근사, GPS 오차가 그대로 들어간다 */
    fun gapM(a: Sample, b: Sample): Double? {
        val la = a.lat ?: return null; val lo = a.lon ?: return null
        val lb = b.lat ?: return null; val ob = b.lon ?: return null
        return hypot((la - lb) * 110_950.0, (lo - ob) * 111_320.0 * cos(Math.toRadians(la)))
    }

    /** 상대 차가 충격을 기록한 시각 - 내 차 (초) */
    fun impactGap(mine: HandoffData, other: HandoffData) = other.t - mine.t

    /** "38km/h · 왼쪽으로 도는 중". 자이로가 있으면 자이로, 없으면 조향각. 둘 다 없으면 속도만(steerDeg 0 을 "곧게"로 지어내지 않음) */
    fun cell(s: Sample?, steerRecorded: Boolean = true): String {
        if (s == null) return "기록 없음"
        val turn = s.yawRate?.let { w -> // 라디안/초, 왼쪽 +
            val d = Math.toDegrees(w)
            if (abs(d) < 3) "곧게" else if (d > 0) "왼쪽으로 도는 중" else "오른쪽으로 도는 중"
        } ?: if (!steerRecorded) return "${s.speedKph.toInt()}km/h"
        else if (abs(s.steerDeg) < 2) "곧게" else if (s.steerDeg < 0) "핸들 왼쪽" else "핸들 오른쪽"
        return "${s.speedKph.toInt()}km/h · $turn"
    }

    fun impactWord(deg: Double?) = deg?.let { "${PhotoGuide.part(it)} (${it.toInt()}°)" } ?: "기록 없음"

    /** 입력에서 숫자만 6자리. 아니면 null */
    fun cleanCode(input: String): String? = input.filter(Char::isDigit).takeIf { it.length == 6 }
}
