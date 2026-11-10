package com.askthecar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.abs

/** 차에게 묻기의 질문. shortcut: 정해진 질문만, 말로 묻기는 12/3 전에 추가하기로 함(사용자 결정 2026-11-09) */
enum class Ask(val chip: String, val quoted: String) {
    HAZARD("비상등", "비상등은 언제 켜셨어요?"),
    BRAKE("브레이크", "브레이크는 언제 밟으셨어요?"),
    SPEED("속도", "그때 속도는 얼마였어요?"),
    SIGNAL("방향지시등", "방향지시등은 켜셨어요?"),
    STEER("핸들", "핸들은 어느 쪽이었어요?"),
    AIRBAG("에어백", "에어백은 터졌어요?"),
    JUDGE("누구 잘못이에요?", "누구 잘못이에요?"),
}

/** text 의 *글자* = 굵게. at = 띠에 찍을 시각(없으면 점 없음). source = 출처 줄, null 이면 기록이 없어 띠를 비운다 */
class Answer(val text: String, val at: Double?, val source: String?) {
    val plain get() = text.replace("*", "")
}

/** 답은 AI 없이 기록에서 바로 계산한다(증인 규칙: 숫자는 기록 그대로, 판단 안 함) */
object CarAnswer {
    private val none = Answer("그건 기록에 없어요.", null, null)

    private fun src(t: Double) = "차 기록 · " + ClaimText.time(t, "HH:mm:ss.S")

    private fun rel(t: Double, crash: Double) = (crash - t).let { dt ->
        when { abs(dt) < 0.05 -> "충돌 순간"; dt > 0 -> "충돌 *%.1f초 전*".format(dt); else -> "충돌 *%.1f초 뒤*".format(-dt) }
    }

    /** 충돌에 가장 가까운 "켜짐"의 시작. 충돌 전에 없으면 충돌 뒤 첫 켜짐 */
    private fun onset(s: List<Sample>, crash: Double, on: (Sample) -> Boolean): Sample? {
        var i = s.indexOfLast { it.t <= crash && on(it) }
        if (i < 0) return s.firstOrNull { it.t > crash && on(it) }
        while (i > 0 && on(s[i - 1])) i--
        return s[i]
    }

    private val partial = Answer("충돌 뒤 기록은 아직 받지 못했어요.", null, null)

    private val FACT_TIME = Regex("""^(\d{2}):(\d{2}):(\d{2})(?:\.(\d))?\s""")

    /**
     * 사실 목록(FactTimeline, "HH:mm:ss.S 내용")에서 marker 가 든 줄의 시각(epoch 초). 충돌 전 마지막, 없으면 충돌 뒤 첫 줄.
     * 휴대폰은 충돌 전후 15초 샘플만 받아서 그 전에 켠 비상등은 "처음부터"로 보였다(차와 답이 달랐음) → 두 기기가 같은 사실 줄로 답한다
     */
    fun factTime(facts: List<String>, crash: Double, marker: String): Double? {
        val day = java.time.Instant.ofEpochSecond(crash.toLong()).atZone(ClaimText.KST).toLocalDate()
        val ts = facts.filter { marker in it }.mapNotNull { line ->
            val m = FACT_TIME.find(line) ?: return@mapNotNull null
            val (h, mi, sec, d) = m.destructured
            var t = day.atTime(h.toInt(), mi.toInt(), sec.toInt()).atZone(ClaimText.KST).toEpochSecond() + (d.ifEmpty { "0" }).toInt() / 10.0
            if (t - crash > 43200) t -= 86400 else if (crash - t > 43200) t += 86400 // 자정을 넘긴 기록
            t
        }
        return ts.lastOrNull { it <= crash + 0.05 } ?: ts.firstOrNull()
    }

    private fun event(s: List<Sample>, crash: Double, complete: Boolean, facts: List<String>, marker: String, on: (Sample) -> Boolean, done: (Sample?) -> String, held: (Sample) -> String, absent: String): Answer {
        val x = onset(s, crash, on)
        val ft = factTime(facts, crash, marker)
        // 사실 줄이 있으면 그 시각으로(차·휴대폰이 같은 줄을 봄). 샘플 창이 늦게 시작해 "처음부터"로 보일 때도 사실 줄이 먼저
        if (ft != null && (x == null || x === s.first() || abs(ft - x.t) < 0.6)) return Answer("${rel(ft, crash)} ${done(x?.takeIf { abs(ft - it.t) < 0.6 })}", ft, src(ft))
        if (x == null) return if (complete) Answer("기록된 구간에는 $absent", null, src(crash)) else partial
        return if (x === s.first()) Answer("기록이 시작될 때부터 ${held(x)}", x.t, src(x.t))
        else Answer("${rel(x.t, crash)} ${done(x)}", x.t, src(x.t))
    }

    /** lamps = 이 차가 경고등 값을 보낸 적 있음. complete = 사고 뒤까지 저장된 기록임(저장 전에는 충돌 순간까지만 있다) */
    fun of(ask: Ask, s: List<Sample>, crash: Double, controls: Boolean, steer: Boolean, lamps: Boolean = true, complete: Boolean = true, facts: List<String> = emptyList()): Answer {
        if (s.isEmpty()) return none
        return when (ask) {
            Ask.HAZARD -> if (!controls || !lamps) none else event(s, crash, complete, facts, "경고등 켜짐: 비상등", { "비상등" in it.lamps }, { "비상등이 켜졌어요." }, { "비상등이 켜져 있었어요." }, "비상등이 켜진 표시가 없어요.")
            Ask.BRAKE -> if (!controls) none else event(s, crash, complete, facts, "브레이크 페달 밟음", { it.brake }, { "브레이크를 밟았어요." }, { "브레이크를 밟고 있었어요." }, "브레이크를 밟은 표시가 없어요.")
            Ask.SIGNAL -> if (!controls) none else {
                fun word(x: Sample?) = (x?.let { ClaimText.signal(it.signal) + " " } ?: "") + "방향지시등이"
                event(s, crash, complete, facts, "방향지시등 켜짐", { it.signal == "L" || it.signal == "R" }, { word(it) + " 켜졌어요." }, { word(it) + " 켜져 있었어요." }, "방향지시등이 켜진 표시가 없어요.")
            }
            Ask.SPEED -> ClaimText.near(s, crash)?.let { x ->
                val before = ClaimText.near(s, crash - 1.0)?.let { " 1초 전에는 시속 *%.0fkm*였어요.".format(it.speedKph) }.orEmpty()
                Answer("충돌 순간 시속 *%.0fkm*였어요.".format(x.speedKph) + before, x.t, src(x.t))
            } ?: none
            Ask.STEER -> ClaimText.near(s, crash)?.takeIf { steer }?.let { x -> Answer("충돌 순간 앞바퀴는 *${ClaimText.steer(x.steerDeg)}* 방향이었어요.", x.t, src(x.t)) } ?: none
            Ask.AIRBAG -> if (!controls) none else s.firstOrNull { it.airbag }?.let { x -> Answer("${rel(x.t, crash)} 에어백 전개 신호가 기록됐어요.", x.t, src(x.t)) }
                ?: if (complete) Answer("기록된 구간에는 에어백 전개 신호가 없어요.", null, src(crash)) else partial
            Ask.JUDGE -> Answer("판단은 드리지 않아요. 제가 기록한 것은 이거예요.", null, src(crash))
        }
    }
}

/** "*값*" 표시를 굵은 글자로 */
fun boldMarks(s: String) = buildAnnotatedString {
    s.split("*").forEachIndexed { k, part -> if (k % 2 == 1) withStyle(SpanStyle(fontWeight = FontWeight.ExtraBold)) { append(part) } else append(part) }
}

/**
 * 시간 띠: 속도선(끊긴 값은 끊긴 채), 그 순간의 점, 충돌선. 기록 없으면 빈 띠.
 * bottom = 캔버스 아래에서 기준선까지(충돌선은 기준선 아래까지 내려옴)
 */
@Composable
fun AskStrip(s: List<Sample>, crash: Double, at: Double?, empty: Boolean, line: Dp, bottom: Dp, modifier: Modifier = Modifier) {
    val ink = Pleos.textPrimary; val red = Pleos.negative
    val measurer = rememberTextMeasurer()
    val labelStyle = Pleos.labelS.copy(color = Pleos.textTertiary)
    // 10Hz 샘플을 그대로 이으면 계단처럼 보여서(검수 2026-11-09) 그릴 때만 0.5초 이동평균으로 다듬는다. 답의 숫자는 원본 기록 그대로
    val smooth = remember(s) { s.map { p -> p.copy(speedKph = s.filter { abs(it.t - p.t) <= 0.25 }.map { it.speedKph }.average()) } }
    // 시안 "시간 띠": 속도선 0~960ms(cubic-bezier .4,0,.2,1), 점은 펜이 지나갈 때, 충돌선은 960~1140~1260ms 위에서 아래로(1→1.04→1). 첫 진입에 한 번, 질문을 바꿀 때는 점만 바뀜
    val clock = remember { Animatable(0f) }
    LaunchedEffect(Unit) { clock.animateTo(1260f, tween(1260, easing = LinearEasing)) }
    Canvas(modifier) {
        val now = clock.value
        val pen = FastOutSlowInEasing.transform((now / 960f).coerceAtMost(1f))
        val crashH = when { now < 960f -> 0f; now < 1140f -> 1.04f * (now - 960f) / 180f; else -> 1.04f - 0.04f * (now - 1140f) / 120f }
        val base = size.height - bottom.toPx()
        val w = size.width
        val px = line.toPx()
        drawLine(ink.copy(alpha = 0.2f), Offset(0f, base), Offset(w, base), (px / 3).coerceAtLeast(1f))
        if (empty || s.size < 2) return@Canvas
        val t0 = s.first().t; val span = (s.last().t - t0).coerceAtLeast(1.0)
        val top = maxOf(s.maxOf { it.speedKph }, 40.0)
        val room = base - size.height * 0.16f
        fun x(t: Double) = ((t - t0) / span).toFloat() * w
        fun y(v: Double) = base - (v / top).toFloat() * room
        fun path(part: List<Sample>) = Path().apply {
            part.forEachIndexed { i, p -> if (i == 0 || p.t - part[i - 1].t > 1.5) moveTo(x(p.t), y(p.speedKph)) else lineTo(x(p.t), y(p.speedKph)) }
        }
        val (pre, post) = smooth.partition { it.t <= crash }
        clipRect(right = w * pen + px) {
            drawPath(path(pre), ink, style = Stroke(px, cap = StrokeCap.Round))
            drawPath(path(pre.takeLast(1) + post), ink.copy(alpha = 0.3f), style = Stroke(px, cap = StrokeCap.Round))
        }
        at?.let { t ->
            if (pen * w < x(t)) return@let // 점은 펜이 지나간 뒤에 찍힌다
            val v = ClaimText.near(s, t, 2.0)?.speedKph ?: return@let
            val dash = px * 1.2f
            drawLine(ink, Offset(x(t), y(v)), Offset(x(t), base), px / 3, pathEffect = PathEffect.dashPathEffect(floatArrayOf(dash / 2, dash)))
            drawCircle(ink, px * 2, Offset(x(t), y(v)))
        }
        if (crashH > 0f) drawRect(red, Offset(x(crash) - px / 3, 0f), Size(px * 2 / 3, size.height * crashH))
        // 축 표기 셋만 작게: 왼쪽 끝 시각, 충돌선 아래 "충돌", 점 위 속도
        val start = measurer.measure("%.0f초".format(t0 - crash), labelStyle)
        drawText(start, topLeft = Offset(0f, base + px))
        // "충돌"은 선 오른쪽 12dp(선이 글자를 관통하지 않게), 속도는 점 위 16dp(점 반지름 위로)
        if (crashH > 0f) { val c = measurer.measure("충돌", labelStyle); drawText(c, topLeft = Offset((x(crash) + px + 12.dp.toPx()).coerceAtMost(w - c.size.width), base + px)) }
        at?.let { t -> ClaimText.near(s, t, 2.0)?.let { v -> if (pen * w >= x(t)) { val l = measurer.measure("%.0f km/h".format(v.speedKph), labelStyle); drawText(l, topLeft = Offset((x(t) - l.size.width / 2).coerceIn(0f, w - l.size.width), y(v.speedKph) - px * 2 - 16.dp.toPx() - l.size.height)) } } }
    }
}
