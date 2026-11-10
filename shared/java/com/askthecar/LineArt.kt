package com.askthecar

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.vector.PathParser
import kotlin.math.abs

enum class Tone { INK, SOFT, SIGNAL }

/** 시안 CSS 기본 곡선(class d) / class ease = cubic-bezier(.4,0,.2,1) = FastOutSlowIn */
val EASE_D: Easing = CubicBezierEasing(.25f, .1f, .25f, 1f)
val EASE_FOLD: Easing = CubicBezierEasing(.16f, 1f, .3f, 1f)

/** 묶음 이동·축소(시안 fly·card): from~to 동안 (dx,dy)만큼 옮기고 (cx,cy) 기준으로 scale 배가 된다 */
class Move(val from: Int, val to: Int, val dx: Float = 0f, val dy: Float = 0f, val scale: Float = 1f, val cx: Float = 0f, val cy: Float = 0f, val ease: Easing = FastOutSlowInEasing)

/**
 * 획 하나. from~to(ms) 동안 앞에서부터 그려진다(to<=from 이면 from 에 바로 나타남).
 * change 동안 morph(경로 숫자 보간)·toTone(색 바뀜), move 로 이동·축소, fadeIn/fadeOut 으로 투명도, dash 로 점선→실선, rotate 는 pivot 기준 회전
 */
class Seg(
    val d: String, val tone: Tone, val w: Float, val from: Int = 0, val to: Int = 0, val ease: Easing = EASE_D,
    val morph: String? = null, val toTone: Tone? = null, val change: Pair<Int, Int>? = null, val changeEase: Easing = FastOutSlowInEasing,
    val move: Move? = null, val fadeIn: Pair<Int, Int>? = null, val fadeOut: Pair<Int, Int>? = null,
    val dash: Pair<FloatArray, FloatArray>? = null, val rotate: Float = 0f,
) {
    val end = maxOf(from, to, change?.second ?: 0, move?.to ?: 0, fadeIn?.second ?: 0, fadeOut?.second ?: 0)
}

/** 전체가 살짝 커졌다 돌아옴(시안 pop) */
class Pop(val from: Int, val peak: Int, val to: Int, val scale: Float)

/** box = 격자 크기(시안 48), dy = 전체를 아래로 옮기는 칸 수(대상 칸에서 가운데 맞춤), pivot = rotate 기준점 */
class LineDrawing(val box: Float, val segs: List<Seg>, val pop: Pop? = null, val dy: Float = 0f, val pivot: Offset = Offset(24f, 24f)) {
    val end = maxOf(segs.maxOf { it.end }, pop?.to ?: 0)
}

/** 재생 횟수를 정한 진행값 0~1. 무한 반복 금지(시각 스타일 확정) */
@Composable
fun rememberPlays(ms: Int, times: Int, key: Any? = Unit): State<Float> {
    val a = remember(key) { Animatable(0f) }
    LaunchedEffect(key) { repeat(times) { a.snapTo(0f); a.animateTo(1f, tween(ms, easing = LinearEasing)) } }
    return a.asState()
}

private fun progress(now: Float, from: Int, to: Int) = if (to <= from) (if (now >= from) 1f else 0f) else ((now - from) / (to - from)).coerceIn(0f, 1f)

/**
 * 선 그림을 한 번 그린다. animate=false(주행 중·작은 아이콘)면 until 시각의 모습으로 멈춤.
 * until = 어디까지 재생할지(ms). 서버 응답·차 신호·저장을 기다리는 그림은 until 을 그 앞 시각에 두었다가 신호가 오면 art.end 로 올린다.
 * ink = 잉크색(선택된 검은 칸에서는 흰색), stroke = 선 굵기 배율(휴대폰 48dp 칸은 2)
 */
@Composable
fun LineArt(art: LineDrawing, modifier: Modifier, animate: Boolean, until: Int = art.end, ink: Color = Pleos.textPrimary, stroke: Float = 1f, zoom: Float = 1f) {
    // PathMeasure 는 윤곽 하나만 재서 M 마다 나눈다
    val parts = remember(art) { art.segs.map { s -> parts(s.d) } }
    val clock = remember(art) { Animatable(if (animate) 0f else until.toFloat()) }
    LaunchedEffect(art, until, animate) {
        val target = until.toFloat()
        if (animate && target > clock.value) clock.animateTo(target, tween((target - clock.value).toInt(), easing = LinearEasing)) else clock.snapTo(target)
    }
    val soft = Pleos.textTertiary; val signal = Pleos.negative
    fun toneColor(t: Tone) = when (t) { Tone.INK -> ink; Tone.SOFT -> soft; Tone.SIGNAL -> signal }
    Canvas(modifier) {
        val now = clock.value
        val k = minOf(size.width, size.height) / art.box * zoom
        val pop = art.pop?.let { p -> when { now < p.from -> 1f; now < p.peak -> 1f + (p.scale - 1f) * (now - p.from) / (p.peak - p.from); now < p.to -> p.scale - (p.scale - 1f) * (now - p.peak) / (p.to - p.peak); else -> 1f } } ?: 1f
        translate((size.width - art.box * k) / 2, (size.height - art.box * k) / 2) {
            scale(k, k, Offset.Zero) {
                scale(pop, pop, Offset(art.box / 2, art.box / 2)) {
                    translate(0f, art.dy) {
                        art.segs.forEachIndexed { i, s ->
                            val f = progress(now, s.from, s.to)
                            if (f <= 0f && s.dash == null) return@forEachIndexed // 점선 획은 처음부터 보인다(점선→실선)
                            val alpha = (s.fadeIn?.let { progress(now, it.first, it.second) } ?: 1f) * (1f - (s.fadeOut?.let { progress(now, it.first, it.second) } ?: 0f))
                            if (alpha <= 0f) return@forEachIndexed
                            val p = s.ease.transform(f)
                            val c = s.change?.let { s.changeEase.transform(progress(now, it.first, it.second)) } ?: 0f
                            val color = (s.toTone?.let { lerp(toneColor(s.tone), toneColor(it), c) } ?: toneColor(s.tone)).copy(alpha = alpha)
                            val effect = s.dash?.let { (a, b) -> PathEffect.dashPathEffect(floatArrayOf(a[0] + (b[0] - a[0]) * p, a[1] + (b[1] - a[1]) * p)) }
                            val style = Stroke(s.w * stroke, cap = StrokeCap.Round, join = StrokeJoin.Round, pathEffect = effect)
                            val shapes = if (s.morph != null && c > 0f) parts(morph(s.d, s.morph, c)) else parts[i]
                            val m = s.move?.let { it to it.ease.transform(progress(now, it.from, it.to)) }
                            rotate(s.rotate, art.pivot) {
                                translate((m?.first?.dx ?: 0f) * (m?.second ?: 0f), (m?.first?.dy ?: 0f) * (m?.second ?: 0f)) {
                                    val sc = m?.let { 1f + (it.first.scale - 1f) * it.second } ?: 1f
                                    scale(sc, sc, Offset(m?.first?.cx ?: 0f, m?.first?.cy ?: 0f)) {
                                        shapes.forEach { whole ->
                                            drawPath(if (p >= 0.999f || s.dash != null) whole else reveal(whole, p) ?: return@forEach, color, style = style)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun parts(d: String) = d.split(Regex("(?=M)")).filter { it.isNotBlank() }.map { PathParser().parsePathString(it).toPath() }

private val NUM = Regex("-?\\d*\\.?\\d+")

/** 두 경로의 숫자만 보간(명령 순서는 시안에서 이미 같음) */
fun morph(a: String, b: String, f: Float): String {
    val to = NUM.findAll(b).map { it.value.toFloat() }.toList()
    var i = 0
    return NUM.replace(a) { m -> val x = m.value.toFloat(); val y = to.getOrElse(i++) { x }; (x + (y - x) * f).toString() }
}

private fun reveal(whole: Path, f: Float): Path? {
    val m = PathMeasure().apply { setPath(whole, false) }
    if (m.length * f < 0.5f) return null // 시작점의 둥근 끝만 찍히는 것 방지
    return Path().also { m.getSegment(0f, m.length * f, it, true) }
}

private fun circle(cx: Float, cy: Float, r: Float) = "M$cx ${cy - r}A$r $r 0 1 1 $cx ${cy + r}A$r $r 0 1 1 $cx ${cy - r}" // 12시에서 시계 방향

/** 시안 "직접 그린 SVG 모션"(Motion.dc.html) 원본 경로·시각. 사람 장면은 잉크 일러스트(assets/guide), 사람은 코드로 그리지 않음 */
object LineArts {
    const val BODY_R = "M24 6C27 6 29.4 6.6 30.6 8.4C31.5 9.6 31.8 12 31.8 15V31.2C31.8 34.8 30 36.6 27 36.6L24 36.6"
    const val BODY_L = "M24 6C21 6 18.6 6.6 17.4 8.4C16.5 9.6 16.2 12 16.2 15V31.2C16.2 34.8 18 36.6 21 36.6L24 36.6"
    private const val HIT_R = "M24 6C27 6 29.4 6.6 30.6 8.4C31.5 9.6 31.8 12 31.8 15V31.2C31.8 33.6 30.6 35.1 28.2 35.4L24 34.2"
    private const val HIT_L = "M24 6C21 6 18.6 6.6 17.4 8.4C16.5 9.6 16.2 12 16.2 15V31.2C16.2 33.6 17.4 35.1 19.8 35.4L24 34.2"
    const val WINDSHIELD = "M16.2 18C20.4 16.2 27.6 16.2 31.8 18"
    const val MIRRORS = "M31.8 15.6L35.4 14.4M16.2 15.6L12.6 14.4"
    private val CAR_PIVOT = Offset(24f, 21.3f)

    /**
     * 충격 기록: 위에서 본 차 + 꺾쇠가 아래서 올라와 범퍼가 눌리는 순간 빨개지고 불꽃이 튄다.
     * 시안은 뒤쪽 충격이라 꺾쇠·불꽃은 impactDeg(앞 0·오른쪽 90)만큼 차 중심 기준으로 돌리고, 범퍼 눌림은 뒤(135~225도)일 때만. deg 가 없으면 차만
     */
    fun impact(deg: Double?): LineDrawing {
        val rot = deg?.let { (it - 180).toFloat() } ?: 0f
        val rear = deg != null && deg in 135.0..225.0
        val hit = if (rear) 648 to 750 else null
        return LineDrawing(48f, listOfNotNull(
            Seg(BODY_R, Tone.INK, 1.2f, 0, 450, FastOutSlowInEasing, morph = HIT_R.takeIf { rear }, change = hit),
            Seg(BODY_L, Tone.INK, 1.2f, 0, 450, FastOutSlowInEasing, morph = HIT_L.takeIf { rear }, change = hit),
            Seg(WINDSHIELD, Tone.INK, 1.2f, 150, 450, FastOutSlowInEasing), Seg(MIRRORS, Tone.INK, 1.2f, 348, 498, FastOutSlowInEasing),
        ) + if (deg == null) emptyList() else listOf(
            Seg("M19.8 54.6L24 50.4L28.2 54.6", Tone.INK, 1.8f, 498, 498, move = Move(498, 648, dy = -12f), fadeIn = 498 to 648, toTone = Tone.SIGNAL, change = 648 to 660, rotate = rot),
            Seg("M36 31.2L42 31.2M12 31.2L6 31.2M35.4 37.8L39.6 42M12.6 37.8L8.4 42", Tone.SIGNAL, 1.2f, 648, 840, fadeOut = 840 to 960, rotate = rot),
        ), pivot = CAR_PIVOT)
    }

    /** 기록 전송: 문서가 그려진 뒤 휴대폰 속으로 작아지며 날아가고, 체크는 서버 응답 뒤(until = end)에만 */
    val EVIDENCE = run {
        val fly = Move(1200, 1800, dx = 22.8f, scale = 0.4f, cx = 15f, cy = 22.5f) // 문서 중심 → 휴대폰 중심(37.8, 22.5)
        val gone = 1680 to 1800
        LineDrawing(48f, listOf(
            Seg("M34.2 9H41.4A3 3 0 0 1 44.4 12V33A3 3 0 0 1 41.4 36H34.2A3 3 0 0 1 31.2 33V12A3 3 0 0 1 34.2 9Z", Tone.INK, 1.2f),
            Seg("M6 9H17.4L24 15.6V36H6Z", Tone.INK, 1.2f, 0, 450, move = fly, fadeOut = gone),
            Seg("M10.2 21H19.8M10.2 25.8H19.8", Tone.INK, 1.2f, 150, 450, move = fly, fadeOut = gone),
            Seg("M10.2 30.6H15.6", Tone.INK, 1.2f, 348, 498, move = fly, fadeOut = gone),
            Seg("M34.8 22.8L37.2 25.2L41.4 19.8", Tone.INK, 1.2f, 1800, 1800, fadeIn = 1800 to 1980),
        ))
    }
    /** 기록 전송에서 문서만 그려진 상태(응답 대기) */
    const val EVIDENCE_SENT = 1200

    private const val HEAD_L = "M11.4 15A4.8 4.8 0 1 1 11.4 24.6A4.8 4.8 0 1 1 11.4 15"
    private const val HEAD_R = "M36.6 15A4.8 4.8 0 1 1 36.6 24.6A4.8 4.8 0 1 1 36.6 15"
    /** 가족에게 넘기기: 두 사람 사이 점선이 실선으로 채워지면 맡긴 것. until = 0 이면 점선 그대로(동의 대기) */
    val FAMILY = LineDrawing(48f, listOf(
        Seg(HEAD_L, Tone.INK, 1.2f), Seg("M3.6 37.8C3.6 31.8 6.6 28.8 11.4 28.8S19.2 31.8 19.2 37.8", Tone.INK, 1.2f),
        Seg(HEAD_R, Tone.INK, 1.2f), Seg("M28.8 37.8C28.8 31.8 31.8 28.8 36.6 28.8S44.4 31.8 44.4 37.8", Tone.INK, 1.2f),
        Seg("M30 19.8H18", Tone.INK, 1.2f, 720, 960, dash = floatArrayOf(2f, 3f) to floatArrayOf(12f, 0f)),
        Seg("M21.6 16.2L18 19.8L21.6 23.4", Tone.INK, 1.2f, 1800, 1800, fadeIn = 1800 to 1980),
    ))
    /** 어르신용 안심(저장 완료): 원이 12시에서 시계 방향, 체크는 저장된 뒤(until = end). 탄성 없이 천천히 */
    val CALM = LineDrawing(48f, listOf(Seg(circle(24f, 24f, 20f), Tone.INK, 2.4f, 0, 960), Seg("M14.4 24.6L21 31.2L33.6 17.4", Tone.INK, 2.4f, 1200, 1620)))
    /** 안심에서 원만 그려진 상태(저장 전) */
    const val CALM_RING = 960
    /**
     * 비상등 확인: 빈 삼각형(켜 주세요 대기, until = 0) → 실제 신호가 오면 바깥에 빨강이 덧그려지고 전체가 살짝 커졌다 돌아오며
     * 안쪽 빨간 삼각형이 체크로 접힌다. 시안 시각 -1200ms(신호 순간이 0). 840ms 에 끝나 다음 단계 자동 진행(1200ms) 안에 들어간다
     */
    val HAZARD_OK = LineDrawing(48f, listOf(
        Seg("M24 8.4C25.5 8.4 27 9.3 27.7 10.6L41.4 35.8C42.9 38.6 40.9 42 37.8 42L24 42", Tone.INK, 1.2f), Seg("M24 8.4C22.5 8.4 21 9.3 20.3 10.6L6.6 35.8C5.1 38.6 7.1 42 10.2 42L24 42", Tone.INK, 1.2f),
        Seg("M24 8.4C25.5 8.4 27 9.3 27.7 10.6L41.4 35.8C42.9 38.6 40.9 42 37.8 42L24 42", Tone.SIGNAL, 1.2f, 0, 420, FastOutSlowInEasing), Seg("M24 8.4C22.5 8.4 21 9.3 20.3 10.6L6.6 35.8C5.1 38.6 7.1 42 10.2 42L24 42", Tone.SIGNAL, 1.2f, 0, 420, FastOutSlowInEasing),
        Seg("M14.4 35.4L33.6 35.4L24 18L14.4 35.4", Tone.SIGNAL, 1.2f, morph = "M17.4 31.2L22.2 36L30 25.8L30 25.8", toTone = Tone.INK, change = 480 to 840, changeEase = EASE_FOLD),
    ), pop = Pop(360, 540, 780, 1.04f))
    /** 블랙박스 카드: 빨간 누름 → 카드가 받침에서 13칸 올라온다(폐차 전에 꺼내는 동작) */
    val CARD = run {
        val up = Move(840, 1140, dy = -13f, ease = EASE_FOLD)
        LineDrawing(48f, listOf(
            Seg("M9 37.8H39", Tone.SOFT, 1.2f), Seg("M12.6 37.8V33.6H35.4V37.8", Tone.SOFT, 1.2f),
            Seg("M18 32.4V14.4H26.4L30 18V32.4", Tone.INK, 1.2f, move = up), Seg("M21 14.4V18.6M24 14.4V18.6", Tone.INK, 1.2f, move = up),
            Seg("M24 3.6V9", Tone.SIGNAL, 1.8f, 540, 540, fadeIn = 540 to 720, move = Move(720, 840, dy = 1.8f), fadeOut = 840 to 1020),
        ))
    }
    /** 불·연기: 불꽃 윤곽 하나 */
    val FIRE = LineDrawing(48f, listOf(Seg("M24 5.4C25.2 13.2 36 18.6 36 30A12 12 0 0 1 12 30C12 24 15.6 20.4 17.4 15.6C19.2 18.6 21 19.8 22.8 20.4C22.2 15 22.8 9.6 24 5.4Z", Tone.INK, 1.2f, 0, 600)))
    /** 기다리기: 시계 */
    val WAIT = LineDrawing(48f, listOf(Seg(circle(24f, 24f, 18f), Tone.INK, 1.2f, 0, 600), Seg("M24 13.2V24L31.2 28.2", Tone.INK, 1.2f, 480, 780)))
    /** 전화 단계: 시안이 쓰는 공식 수화기 경로를 선으로(새로 그리지 않음, 1.2/48 비율) */
    val CALL = LineDrawing(45.4635f, listOf(Seg(PleosIcons.PHONE, Tone.INK, 1.137f)))
    /** 벽·시설물: 벽돌 윤곽. 대상 칸과 시설물 단계가 같은 그림 (시안 KIND.object) */
    val WALL = LineDrawing(48f, listOf(Seg("M6 13.5h36V38H6zM6 21.7h36M6 29.8h36M15 13.5v8.2M27 13.5v8.2M39 13.5v8.2M9 21.7v8.1M21 21.7v8.1M33 21.7v8.1M15 29.8V38M27 29.8V38M39 29.8V38", Tone.INK, 1.2f)))
    /** 트렁크 위치 핀(3D 차 뒷모습 위): 뒷문 윤곽 + 보드 충격 꺾쇠와 같은 모양 */
    val TRUNK_PIN = LineDrawing(48f, listOf(Seg("M12 36V22.8C12 19.8 13.8 18 16.8 18H31.2C34.2 18 36 19.8 36 22.8V36M12 30H36", Tone.INK, 1.2f), Seg("M19.8 13.2L24 9L28.2 13.2", Tone.INK, 1.8f)))

    /** 대상 칸의 차: 옆모습(시안 KIND.vehicle). 위에서 본 모양은 사용자가 "딱정벌레"라고 거절함 */
    val KIND_VEHICLE = LineDrawing(48f, listOf(Seg("M9.2 32H4.6v-5.4c0-2 1.1-3.2 3-3.7l6-1.3 5.4-6.4c1-1.2 2.3-1.8 3.9-1.8h10.3c1.7 0 3.1.7 4.1 2.1l3.9 5.9c1.5.7 2.3 2.2 2.3 4V32h-3.1M17.8 32h13.3", Tone.INK, 1.2f), Seg(circle(13.5f, 32f, 4.3f), Tone.INK, 1.2f), Seg(circle(35.4f, 32f, 4.3f), Tone.INK, 1.2f), Seg("M15.8 21.5h23.6M27.2 13.6v7.9", Tone.INK, 1.2f)))
    /** 대상 칸의 사람(시안 KIND.person) */
    val KIND_PERSON = LineDrawing(48f, listOf(Seg(circle(24f, 10.5f, 5f), Tone.INK, 1.2f), Seg("M24 17v13M13.5 25.5L24 19.5l10.5 6M24 30l-7.5 13M24 30l7.5 13", Tone.INK, 1.2f)))

    fun kind(k: CrashKind) = when (k) { CrashKind.VEHICLE -> KIND_VEHICLE; CrashKind.OBJECT -> WALL; else -> KIND_PERSON }
}

/** 작은 아이콘 자리의 선 그림. 사람·행동 장면(잉크 그림이 있는 단계)은 없음 */
fun lineArtOf(art: StepArt): LineDrawing? = when (art) {
    StepArt.RECORD, StepArt.HANDOFF, StepArt.REPLAY -> LineArts.EVIDENCE
    StepArt.CALM -> LineArts.CALM
    StepArt.PHONE, StepArt.POLICE, StepArt.EXCHANGE -> LineArts.CALL
    StepArt.CARD_LATER -> LineArts.CARD
    StepArt.CAMERA_FAR, StepArt.ROAD, StepArt.MOVE_RISK, StepArt.MOVE_LATER -> LineArts.KIND_VEHICLE
    StepArt.FIRE -> LineArts.FIRE
    StepArt.WAIT -> LineArts.WAIT
    StepArt.FACILITY -> LineArts.WALL
    StepArt.TRUNK -> LineArts.TRUNK_PIN
    else -> null
}
