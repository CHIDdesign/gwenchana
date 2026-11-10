package com.askthecar

import android.content.Context
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.ui.graphics.lerp
import kotlin.math.abs
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 미리 렌더한 3D 차 그림 (design/car/render_figures.py → assets/car/) */
enum class CarView(val key: String) { TOP("top"), FRONT("front"), REAR("rear") }

/** 차 위에 얹는 하얀 동그라미 표시. anchor = car_figures.json 기준점 이름(trunk, center, impact_0..315) */
data class CarPin(val anchor: String, val art: StepArt? = null, val number: Int? = null)

private class Figure(val img: androidx.compose.ui.graphics.ImageBitmap, val ratio: Float, val points: Map<String, Offset>)

/** 비율·기준점(JSON). 글자 몇 KB라 계속 들고, 그림만 공용 캐시(AssetImages)에 맡긴다 */
private val figureMeta = ConcurrentHashMap<CarView, Pair<Float, Map<String, Offset>>>()

private fun cachedFigure(view: CarView): Figure? {
    val (ratio, pts) = figureMeta[view] ?: return null
    return AssetImages.cached("car/car_${view.key}.png")?.let { Figure(it, ratio, pts) }
}

/** 막히는 호출 → IO 스레드에서. 파일이 없으면 null */
private fun loadFigure(ctx: Context, view: CarView): Figure? = runCatching {
    val (ratio, pts) = figureMeta.getOrPut(view) {
        val meta = ctx.assets.open("car/car_figures.json").use { JSONObject(it.reader().readText()) }.getJSONObject(view.key)
        val points = meta.getJSONObject("points").let { p -> p.keys().asSequence().associateWith { k -> p.getJSONArray(k).let { Offset(it.getDouble(0).toFloat(), it.getDouble(1).toFloat()) } } }
        meta.getInt("w").toFloat() / meta.getInt("h") to points
    }
    AssetImages.load(ctx, "car/car_${view.key}.png")?.let { Figure(it, ratio, pts) }
}.getOrNull()

@Composable
private fun figure(view: CarView): Figure? {
    val ctx = LocalContext.current
    // produceState 는 키가 바뀌어도 이전 값을 들고 있어 잠깐 다른 시점 그림이 보인다
    val state = remember(view) { mutableStateOf(cachedFigure(view)) }
    LaunchedEffect(view) { if (state.value == null) state.value = withContext(Dispatchers.IO) { loadFigure(ctx, view) } }
    return state.value
}

/** 충격 방향(앞 0°, 오른쪽 90°)의 차 표면 위치: 45° 기준점 둘 사이를 화면에서 이어 붙인다 */
private fun impactPoint(points: Map<String, Offset>, deg: Double): Offset? {
    val d = ((deg % 360) + 360) % 360
    val a = (d / 45).toInt() * 45; val b = (a + 45) % 360
    val pa = points["impact_$a"] ?: return null; val pb = points["impact_$b"] ?: return pa
    val t = ((d - a) / 45).toFloat()
    return pa + (pb - pa) * t
}

/** 충격 난 면이 보이는 3/4 렌더: 오른쪽·뒤 = 뒷모습, 왼쪽·앞 = 앞모습(모르면 뒷모습) */
fun carViewFor(deg: Double?): CarView {
    val d = deg?.let { ((it % 360) + 360) % 360 } ?: return CarView.REAR
    return if (d in 22.5..202.5) CarView.REAR else CarView.FRONT
}

/** 3/4 렌더에서 impact_0..315 지점부터 차 바깥까지의 거리(그림 폭 비율). shortcut: car_*.png 알파에서 잰 값, 렌더를 바꾸면 다시 재야 함 */
private val EDGE = mapOf(
    CarView.REAR to floatArrayOf(.093f, .041f, .033f, .042f, .027f, .175f, .341f, .323f),
    CarView.FRONT to floatArrayOf(.044f, .17f, .366f, .283f, .1f, .055f, .038f, .045f),
)

/** 시안 rearCar(w): 그림의 투명 여백을 뺀 차체가 폭 w 를 채우게 CarFigure 를 놓는다(x, y = 시안의 차체 왼쪽 위). 여백은 car_*.png 알파에서 잼 */
fun Modifier.carBox(view: CarView, x: Dp, y: Dp, w: Dp): Modifier {
    val (l, t, k) = if (view == CarView.FRONT) Triple(.0326f, .0317f, 1.058f) else Triple(.028f, .0307f, 1.057f)
    return offset(x - w * l, y - w * t).width(w * k)
}

/** 충격 지점의 차 바깥 거리: 45° 값 사이를 잇는다 */
private fun edgeAt(view: CarView, deg: Double): Float {
    val e = EDGE[view] ?: return 0f
    val d = ((deg % 360) + 360) % 360
    val i = (d / 45).toInt(); val f = ((d - i * 45) / 45).toFloat()
    return e[i] + (e[(i + 1) % 8] - e[i]) * f
}

/**
 * 3D 차 그림 + 2D 표시 (Pleos 차량 제어 화면처럼: 3D 차 위에 하얀 동그라미 아이콘).
 * impactDeg 가 있으면 충격 면 바깥에 증언색 꺾쇠 하나(앱 전체에서 같은 모양).
 * 그림 파일이 없으면 아무것도 그리지 않는다.
 */
@Composable
fun CarFigure(view: CarView, modifier: Modifier = Modifier, impactDeg: Double? = null, pins: List<CarPin> = emptyList(),
              pinSize: Dp = 56.dp, animate: Boolean = false) {
    val fig = figure(view) ?: return
    val hit = impactDeg?.let { impactPoint(fig.points, it) }
    val center = fig.points["center"]
    val signal = Pleos.negative; val ink = Pleos.textPrimary
    // 시안 "충격 기록" 꺾쇠 동작: 498~648ms 바깥에서 차 쪽으로 올라오며 나타나고, 648~660ms 잉크→증언색. animate=false 면 끝 모습
    // 방향은 사고 1초 뒤에 들어올 수 있어(CrashWatcher) 그때 꺾쇠가 움직이며 나타나게 키로 둔다. 3/4 렌더는 시안 hit 900ms
    val markEnd = if (view == CarView.TOP) 660f else 900f
    val clock = remember(animate, hit != null) { Animatable(if (animate) 0f else markEnd) }
    if (animate) LaunchedEffect(hit != null) { clock.animateTo(markEnd, tween(markEnd.toInt(), easing = LinearEasing)) }
    BoxWithConstraints(modifier.aspectRatio(fig.ratio)) {
        Image(fig.img, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit)
        if (hit != null && center != null && view != CarView.TOP) Canvas(Modifier.fillMaxSize()) {
            // 시안 rearCar(폭 = 차체 폭): 꺾쇠 폭 13%·굵기 1.44%. 여기 w 는 여백까지 포함한 폭이라 나눈 값. 차 윤곽 바깥에서 충격 지점을 가리킴, hit 키프레임 그대로
            val w = size.width
            val p = Offset(hit.x * w, hit.y * size.height)
            val dir = (p - Offset(center.x * w, center.y * size.height)).let { it / hypot(it.x, it.y).coerceAtLeast(1f) }
            val tip = p + dir * (w * (edgeAt(view, impactDeg!!) + .0545f)) + Offset(w * .011f, w * .008f) // 시안의 뒤쪽 꺾쇠 자리에 맞춘 값
            val a = atan2(dir.y, dir.x); val arm = w * .0653f
            val chevron = Path().apply {
                moveTo(tip.x + arm * cos(a + 0.82f), tip.y + arm * sin(a + 0.82f))
                lineTo(tip.x, tip.y)
                lineTo(tip.x + arm * cos(a - 0.82f), tip.y + arm * sin(a - 0.82f))
            }
            val f = clock.value / 900f
            val e = EASE_FOLD.transform((f / .6f).coerceAtMost(1f))
            val dy = when { f < .6f -> 22f * (1f - e); f < .75f -> -3f * EASE_FOLD.transform((f - .6f) / .15f); else -> -3f * (1f - EASE_FOLD.transform((f - .75f) / .25f)) }
            val grow = 1.3f - .3f * e
            translate(0f, dy.dp.toPx()) {
                scale(grow, grow, tip) { drawPath(chevron, Color(0xFFFF4B39).copy(alpha = (f / .6f).coerceAtMost(1f)), style = Stroke(w * .01366f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
            }
        } else if (hit != null) Canvas(Modifier.fillMaxSize()) {
            val now = clock.value
            if (center != null && now >= 498f) {
                val rise = FastOutSlowInEasing.transform(((now - 498f) / 150f).coerceIn(0f, 1f))
                val p = Offset(hit.x * size.width, hit.y * size.height)
                val c = Offset(center.x * size.width, center.y * size.height)
                val dir = (p - c).let { it / hypot(it.x, it.y).coerceAtLeast(1f) }
                val u = size.minDimension
                val tip = p + dir * (u * (0.25f - 0.07f * abs(dir.x)) + u * 0.25f * (1f - rise)) // 꺾쇠 끝이 차를 가리킨다(옆은 거울 폭만큼 가깝게)
                val a = atan2(dir.y, dir.x); val arm = u * 0.12f
                val chevron = Path().apply {
                    moveTo(tip.x + arm * cos(a + 0.8f), tip.y + arm * sin(a + 0.8f))
                    lineTo(tip.x, tip.y)
                    lineTo(tip.x + arm * cos(a - 0.8f), tip.y + arm * sin(a - 0.8f))
                }
                val color = lerp(ink, signal, ((now - 648f) / 12f).coerceIn(0f, 1f)).copy(alpha = rise)
                drawPath(chevron, color, style = Stroke(u * 0.024f, cap = StrokeCap.Round, join = StrokeJoin.Round))
            }
        }
        pins.forEach { pin ->
            val at = (if (pin.anchor == "impact") impactDeg?.let { impactPoint(fig.points, it) } else fig.points[pin.anchor]) ?: return@forEach
            val box = Modifier.offset(maxWidth * at.x - pinSize / 2, maxHeight * at.y - pinSize / 2).size(pinSize)
                .pleosShadow(1, CircleShape)
            if (pin.number != null) Box(box.background(Pleos.basic700, CircleShape).border(3.dp, Color.White, CircleShape), contentAlignment = Alignment.Center) {
                Text("${pin.number}", style = Pleos.bodyM.copy(fontWeight = FontWeight.Bold, fontSize = (pinSize.value * 0.42f).sp, lineHeight = (pinSize.value * 0.5f).sp), color = Color.White)
            } else Box(box.background(Color.White, CircleShape), contentAlignment = Alignment.Center) {
                pin.art?.let { StepArtView(it, Modifier.size(pinSize * 0.86f), animate = false, iconOnly = true) }
            }
        }
    }
}

/** 3D 차 그림 바탕: 왼쪽 주행 화면처럼 위는 밝고 아래로 옅은 회색 */
@Composable
fun Modifier.carStage(shape: androidx.compose.ui.graphics.Shape = Pleos.shapeL) =
    this.background(Brush.verticalGradient(listOf(Pleos.carStageTop, Pleos.carStageBottom)), shape)
