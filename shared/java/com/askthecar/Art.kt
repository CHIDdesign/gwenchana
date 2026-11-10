package com.askthecar

import androidx.compose.ui.graphics.Shape
import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.util.LruCache
import android.view.Surface
import android.view.TextureView
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.key
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * 가이드 그림. 디자인 규칙(Iconography.pdf): 24 그리드 기준 선 굵기 1.5(48px 에서 3.6), 둥근 끝·둥근 이음, Filled/Outlined.
 * 여기선 아이콘을 크게 키운 "일러스트" — 선은 그림 크기의 2.6%, 강조색 하나 + 기본 글자색.
 * 교체: assets/guide/<이름>.jpg(또는 .png)가 있으면 그 그림을 쓴다. 이름 = StepArt 이름 소문자(예: trunk.jpg). 차 안내 화면은 StepHero(16:9 틀·영상)를 쓴다
 */
@Composable
fun StepArtView(art: StepArt, modifier: Modifier, animate: Boolean, iconOnly: Boolean = false, until: Int? = null) {
    lineArtOf(art)?.let { LineArt(it, modifier, animate, until = until ?: it.end); return } // 시안의 선 그림이 있으면 그것으로 통일
    val custom = if (iconOnly) null else assetImage("guide/${art.asset}.jpg") ?: assetImage("guide/${art.asset}.png")
    if (custom != null) Image(custom, null, modifier) // 없으면 아무것도 안 그림: 옛 Canvas 그림(회색 원판·파랑·주황·코드로 그린 사람)은 지웠다
}

/**
 * 그림을 보여 줄 단계: 운전자가 직접 찾거나 조작해야 하는 것(비상등 버튼, 트렁크, 사진 찍을 곳)만.
 * 나머지(안심·전화·대피·기록)는 작은 아이콘 + 글. 근거: docs/reports/사고 앱 그림 사용 방식.md
 * (충돌 감지·보험 앱은 장식 그림 없음, 장식 그림은 무시되거나 광고처럼 보임 — 사용자 지적 "광고 같아" 2026-11-07)
 */
/** 넓은 차 화면에서 그림이 바닥 전체를 채우고 글이 그 안에 들어가는 단계(시안 A안, docs/reports/차량 화면 그림과 글 통합.md) */
val StepArt.fillsStage get() = hasPicture && this != StepArt.TRUNK

/** assets/guide 의 그림 이름 */
val StepArt.asset get() = if (this == StepArt.DONT_MOVE) "person" else name.lowercase()

/** 그림 왼쪽 52%(글 칸)에 잉크가 처음 나타나는 세로 위치(그림 높이 비율). 정지 그림과 영상 전체 프레임에서 잰 값 */
/** 그림 잉크가 오른쪽에서 끝나는 위치(그림 폭 비율). 차가 거기서 세로로 잘린 그림은 그 면을 화면 끝에 맞춘다. null = 잘린 면 없음(가운데 배치) */
val StepArt.rightInk get(): Float? = when (this) {
    StepArt.HAZARD -> 0.969f
    StepArt.CARD -> 0.968f
    StepArt.GUARDIAN -> 0.967f
    StepArt.HURT_CALL -> 0.989f
    StepArt.WALK, StepArt.CAMERA -> 1f
    else -> null
}

/** 그림 왼쪽 52%(글 칸)에 잉크가 처음 나타나는 세로 위치(그림 높이 비율). 그림을 바꾸면 다시 잴 것 */
val StepArt.textClear get() = when (this) {
    StepArt.WALK -> 0.612f
    StepArt.INSURER -> 0.483f
    StepArt.HAZARD -> 0.125f
    StepArt.CAMERA -> 0.268f
    StepArt.CARD -> 0.09f
    StepArt.GUARDIAN -> 0.439f
    StepArt.PERSON, StepArt.DONT_MOVE -> 0.24f
    StepArt.HURT_CALL -> 0.315f
    else -> 0f
}

val StepArt.hasPicture get() = this in setOf(StepArt.HURT_CALL, StepArt.HAZARD, StepArt.TRUNK, StepArt.CAMERA, StepArt.CARD, StepArt.WALK, StepArt.INSURER, StepArt.GUARDIAN, StepArt.PERSON, StepArt.DONT_MOVE)

/** 단계 아이콘(그림 없는 단계): 시안 선 그림(48 격자·1.2 선·바탕 없음). animate 면 한 번 그리고 멈춤, until 로 신호 전까지 대기 */
@Composable
fun StepIcon(art: StepArt, size: Dp, animate: Boolean = false, until: Int? = null) = StepArtView(art, Modifier.size(size), animate, iconOnly = true, until = until)

/**
 * 안내 단계의 큰 그림(16:9).
 * 1) assets/guide/<이름>.mp4 가 있고 [animate](정차 + 차가 주행 규제를 걸지 않음)면 소리 없이 한 번 재생하고 멈춤
 * 2) 아니면 guide/<이름>.jpg 정지 그림(영상 첫 화면이 뜨기 전에도 이 그림이 밑에 깔림)
 * 3) 둘 다 없으면 선 그림(있는 단계만)
 */
@Composable
fun StepHero(art: StepArt, modifier: Modifier, animate: Boolean, shape: Shape = Pleos.shapeL) {
    val base = art.asset
    val name = if (Pleos.dark && assetExists("guide/night/$base.jpg")) "guide/night/$base" else "guide/$base" // 야간판은 따로 만든 그림(design/people/nano/make_night.py)
    val still = assetImage("$name.jpg")
    val video = assetExists("$name.mp4")
    Box(modifier.aspectRatio(16f / 9f).clip(shape).background(Pleos.backgroundSecondary), contentAlignment = Alignment.Center) {
        if (still != null) { if (still !== AssetImages.loading) Image(still, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        else StepArtView(art, Modifier.fillMaxHeight(0.74f).aspectRatio(1f), animate)
        if (animate && video) key(name) { ClipOnce("$name.mp4", Modifier.fillMaxSize()) }
    }
}

/** assets 안 파일이 있는지(한 번만 확인) */
@Composable
private fun assetExists(path: String) = LocalContext.current.let { ctx ->
    remember(path) { runCatching { ctx.assets.open(path).close(); true }.getOrDefault(false) }
}

/**
 * assets 의 짧은 영상을 소리 없이 한 번 재생. 화면에서 빠지면(주행 시작 등) 바로 멈추고 해제한다.
 * mp4 는 aapt 가 압축하지 않아 openFd 로 바로 읽힌다. 실패하면 조용히 아무것도 안 그림(밑의 정지 그림이 보임).
 */
@Composable
private fun ClipOnce(path: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val player = remember(path) { MediaPlayer() }
    DisposableEffect(player) { onDispose { runCatching { player.release() } } }
    AndroidView({ c ->
        TextureView(c).apply {
            isOpaque = false // 첫 화면이 나오기 전엔 밑의 정지 그림이 보이게
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(st: SurfaceTexture, w: Int, h: Int) {
                    runCatching {
                        ctx.assets.openFd(path).use { player.setDataSource(it.fileDescriptor, it.startOffset, it.length) }
                        player.setSurface(Surface(st))
                        player.isLooping = false // 한 번 재생하고 마지막 장면에서 멈춘다(읽는 데 방해되지 않게)
                        player.setVolume(0f, 0f) // 안내 음성과 겹치지 않게 무음
                        player.setOnPreparedListener { it.start() }
                        player.prepareAsync()
                    }
                }
                override fun onSurfaceTextureSizeChanged(st: SurfaceTexture, w: Int, h: Int) = Unit
                override fun onSurfaceTextureDestroyed(st: SurfaceTexture): Boolean { runCatching { player.stop() }; return true }
                override fun onSurfaceTextureUpdated(st: SurfaceTexture) = Unit
            }
        }
    }, modifier)
}

/** assets 그림 공용 캐시. 그림 한 장이 약 4MB라 24MB = 최근 6장쯤 (object 안에 둠: 단위 테스트는 안드로이드 클래스를 못 만든다) */
internal object AssetImages {
    /** 읽는 중 표시(투명 1×1). null(파일 없음 → 선 그림)과 구분해 선 그림이 잠깐 번쩍이지 않게 한다 */
    val loading = ImageBitmap(1, 1)
    private val cache = object : LruCache<String, ImageBitmap>(24 * 1024 * 1024) {
        override fun sizeOf(key: String, value: ImageBitmap) = value.asAndroidBitmap().byteCount
    }

    private val missing = java.util.concurrent.ConcurrentHashMap.newKeySet<String>() // 없는 파일을 매번 찾지 않게
    fun isMissing(path: String) = path in missing

    fun cached(path: String): ImageBitmap? = cache.get(path)

    /** 막히는 호출 → IO 스레드에서. 파일이 없으면 null */
    fun load(ctx: Context, path: String): ImageBitmap? = cached(path)
        ?: runCatching { ctx.assets.open(path).use { BitmapFactory.decodeStream(it)?.asImageBitmap() } }.getOrNull()?.also { cache.put(path, it) }.also { if (it == null) missing.add(path) }
}

/** assets 안 그림 하나. 읽는 중이면 AssetImages.loading, 없으면 null */
@Composable
private fun assetImage(path: String): ImageBitmap? {
    val ctx = LocalContext.current
    // produceState 는 키가 바뀌어도 이전 값을 들고 있어 잠깐 앞 단계 그림이 보인다
    val state = remember(path) { mutableStateOf<ImageBitmap?>(if (AssetImages.isMissing(path)) null else AssetImages.cached(path) ?: AssetImages.loading) }
    LaunchedEffect(path) { if (state.value === AssetImages.loading) state.value = withContext(Dispatchers.IO) { AssetImages.load(ctx, path) } }
    return state.value
}

/** 보드 "충격 기록"의 차 윤곽(48 격자, 차 중심 24·21.3). 촬영 안내 그림의 내 차·상대 차 */
private val CAR_OUTLINE by lazy { listOf(LineArts.BODY_R, LineArts.BODY_L, LineArts.WINDSHIELD, LineArts.MIRRORS).map { PathParser().parsePathString(it).toPath() } }

/** 격자 차 윤곽을 center(px)에 yaw 만큼 돌려 그린다. k = 격자 한 칸의 px, sw = 화면 선 굵기(px) */
private fun DrawScope.carOutline(center: Offset, yawDeg: Float, k: Float, color: Color, sw: Float) = rotate(yawDeg, center) {
    translate(center.x - 24f * k, center.y - 21.3f * k) {
        scale(k, k, Offset.Zero) { CAR_OUTLINE.forEach { drawPath(it, color, style = Stroke(sw / k, cap = StrokeCap.Round, join = StrokeJoin.Round)) } }
    }
}

/**
 * 각도 제안 가이드 그림(보드 규칙: 가는 둥근 윤곽선, 유채색은 빨강 하나 = 찍는 위치와 촬영 범위).
 * 위에서 본 사고 현장(차선·내 차·상대/시설물/사람·충격 꺾쇠)에서 빨간 점(휴대폰)이 찍을 위치로 이동 → 촬영 범위 선. 번호판은 확대 그림.
 * 좌표: fwd = 화면 위, right = 화면 오른쪽 (미터). 위치는 충격 방향 추정 기반 대략값. 바탕을 칠하지 않아 바닥(stage) 위에 바로 얹힌다.
 */
@Composable
fun ShotSchematic(shot: Shot, kind: CrashKind, impactDeg: Double?, modifier: Modifier, animate: Boolean) {
    val custom = assetImage("guide/shot_${shot.art.name.lowercase()}.png")
    if (custom != null) { Image(custom, null, modifier); return }
    val anim = rememberPlays(3200, 1)
    val measurer = rememberTextMeasurer()
    val ink = Pleos.textPrimary; val soft = Pleos.textTertiary; val red = Pleos.negative
    val geo = remember(shot, impactDeg, kind) {
        val r = Math.toRadians(impactDeg ?: 0.0); val f = cos(r); val s = sin(r)
        val far = when (kind) { CrashKind.OBJECT -> 2.8; CrashKind.PERSON -> 3.0; else -> 5.4 } // 차 윤곽 두 개가 겹쳐 보이지 않게 한 칸 띄움
        val other = Offset((s * far).toFloat(), (f * far).toFloat()) // (right, fwd)
        val impact = Offset((s * 1.0).toFloat(), (f * 2.3).toFloat())
        val target = when (shot.art) {
            ShotArt.DAMAGE -> impact
            ShotArt.PLATE, ShotArt.FACILITY -> other
            ShotArt.LANES -> Offset(0f, 12f)
            ShotArt.MARKS -> Offset(impact.x + (s * 1.2).toFloat(), impact.y + (f * 1.2).toFloat())
            ShotArt.WHEEL -> Offset(0.9f, 1.4f)
            else -> Offset(other.x / 2, other.y / 2)
        }
        ShotGeo(other, impact, target, Offset(shot.right.toFloat(), shot.fwd.toFloat()))
    }
    Canvas(modifier) {
        val t = if (animate) anim.value else 0.6f
        val other = geo.other; val impact = geo.impact; val target = geo.target; val spot = geo.spot
        // 보이는 범위: 내 차·상대·찍는 위치가 다 들어오게
        val pts = listOf(Offset(0f, 0f), other, spot, target)
        val minX = pts.minOf { it.x } - 3; val maxX = pts.maxOf { it.x } + 3
        val minY = pts.minOf { it.y } - 4; val maxY = pts.maxOf { it.y } + 4
        val scale = min(size.width / (maxX - minX), size.height / (maxY - minY))
        fun P(p: Offset) = Offset(size.width / 2 + (p.x - (minX + maxX) / 2) * scale, size.height / 2 - (p.y - (minY + maxY) / 2) * scale)
        val sw = 2.5.dp.toPx() // 보드 1.2/48 을 화면에서 같은 굵기로
        val k = 4.5f * scale / 30.6f // 격자 한 칸 = 차 길이 4.5m 기준
        // 차선(점선, 참고 그림)
        for (lx in listOf(-5.25f, -1.75f, 1.75f, 5.25f)) drawLine(ink.copy(alpha = 0.18f), P(Offset(lx, maxY)), P(Offset(lx, minY)), sw * 0.6f,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(scale * 2f, scale * 1.5f)))
        if (shot.art == ShotArt.SAFE) drawLine(ink, P(Offset(4.2f, maxY)), P(Offset(4.2f, minY)), sw * 1.4f, StrokeCap.Round) // 가드레일
        carOutline(P(Offset(0f, 0f)), 0f, k, ink, sw)
        val face = Math.toDegrees(kotlin.math.atan2((0 - other.x).toDouble(), (0 - other.y).toDouble())).toFloat() // 내 차 쪽을 봄
        when (kind) {
            CrashKind.OBJECT -> rotate(-face + 90f, P(other)) { drawLine(soft, P(Offset(other.x - 2f, other.y)), P(Offset(other.x + 2f, other.y)), sw * 1.6f, StrokeCap.Round) }
            CrashKind.PERSON -> { drawCircle(soft, scale * 0.45f, P(other), style = Stroke(sw)); drawCircle(soft, scale * 0.12f, P(other)) }
            else -> carOutline(P(other), -face, k, soft, sw)
        }
        // 충격 자리: 보드와 같은 꺾쇠(차 밖에서 범퍼를 가리킴). 빨강은 찍는 위치에만 쓰므로 여기는 잉크
        run {
            val tip = P(impact); val out = (tip - P(Offset.Zero)).let { it / it.getDistance() }
            fun arm(deg: Float) = Math.toRadians(deg.toDouble()).let { a -> Offset((out.x * cos(a) - out.y * sin(a)).toFloat(), (out.x * sin(a) + out.y * cos(a)).toFloat()) }
            val len = scale * 0.6f
            drawPath(Path().apply { val l = tip + arm(35f) * len; val r2 = tip + arm(-35f) * len; moveTo(l.x, l.y); lineTo(tip.x, tip.y); lineTo(r2.x, r2.y) }, ink, style = Stroke(sw * 1.4f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }

        // 휴대폰 위치: 처음 40% 동안 이동 후 정지
        val start = Offset(1.4f, -0.5f) // 운전석 문 근처에서 출발
        val m = min(1f, t / 0.4f)
        val phone = start + (spot - start) * (1 - (1 - m) * (1 - m))
        if (t > 0.4f) { // 촬영 범위: 빨간 선 두 가닥(부채꼴 테두리만)
            val ang = Math.toDegrees(kotlin.math.atan2((P(target).y - P(phone).y).toDouble(), (P(target).x - P(phone).x).toDouble()))
            val reach = ((P(target) - P(phone)).getDistance() - scale * 0.6f).coerceAtLeast(scale * 0.7f) // 찍는 대상(꺾쇠·윤곽선) 앞에서 끝나 겹치지 않게
            val chev = P(impact); val rCh = scale * 0.65f // 꺾쇠 둘레: 선이 꺾쇠를 가로지르지 않고 그 앞에서 멈춘다
            for (d in listOf(-22.0, 22.0)) {
                val a = Math.toRadians(ang + d); val u = Offset(cos(a).toFloat(), sin(a).toFloat())
                val f = P(phone) - chev; val b = 2f * (f.x * u.x + f.y * u.y); val c = f.x * f.x + f.y * f.y - rCh * rCh; val disc = b * b - 4f * c
                val hit = if (disc >= 0f) (-b - kotlin.math.sqrt(disc)) / 2f else Float.MAX_VALUE
                val len = if (hit in 0f..reach) hit else reach
                drawLine(red, P(phone), P(phone) + u * len, sw, StrokeCap.Round)
            }
        }
        drawCircle(red, scale * 0.3f, P(phone)) // 찍는 사람(빨간 점). 가까이 찍는 장면에서 촬영 범위 선이 점에 가려지지 않는 크기
        if (shot.art == ShotArt.PLATE && t > 0.5f) plateInset(measurer, ink)
    }
}

/** 각도 제안 그림의 위치들(내 차 기준 오른쪽·앞 미터). 장면이 바뀔 때만 다시 계산 */
private class ShotGeo(val other: Offset, val impact: Offset, val target: Offset, val spot: Offset)

/** 번호판 확대 그림(예시 글자, 실제 번호 아님) */
private fun DrawScope.plateInset(measurer: TextMeasurer, ink: Color) {
    val w = size.width * 0.34f; val h = w * 0.3f
    val o = Offset(size.width - w - 24f, 24f)
    drawRoundRect(Color.White, o, Size(w, h), CornerRadius(h * 0.12f))
    drawRoundRect(ink, o, Size(w, h), CornerRadius(h * 0.12f), style = Stroke(h * 0.05f))
    drawRoundRect(ink.copy(alpha = 0.4f), o - Offset(8f, 8f), Size(w + 16f, h + 16f), CornerRadius(h * 0.16f), style = Stroke(h * 0.06f))
    val text = measurer.measure("12가 3456", TextStyle(fontSize = (h / 2.2f / density).sp, fontWeight = FontWeight.Bold, color = Color(0xFF131417)))
    drawText(text, topLeft = Offset(o.x + (w - text.size.width) / 2, o.y + (h - text.size.height) / 2))
}

/** 기획서의 "충돌 10초 전부터 추정 궤적" 미니맵(3D 를 못 쓸 때 대신): track() 결과를 화면에 맞춰 그림 */
@Composable
fun TrackView(log: List<Sample>, modifier: Modifier) {
    val end = log.firstOrNull { it.airbag }?.t ?: log.lastOrNull()?.t ?: 0.0
    val pts = track(log.filter { it.t in (end - 10)..end })
    val bg = Pleos.backgroundSecondary; val path0 = Pleos.positive; val first = Pleos.active; val last = Pleos.negative
    Canvas(modifier) {
        run {
            drawRoundRect(bg, Offset.Zero, size, CornerRadius(24f))
            if (pts.size < 2) return@Canvas
            val minX = pts.minOf { it.first }; val maxX = pts.maxOf { it.first }
            val minY = pts.minOf { it.second }; val maxY = pts.maxOf { it.second }
            val span = max(max(maxX - minX, maxY - minY), 10.0)
            val sc = (size.minDimension * 0.8f / span).toFloat()
            fun p(q: Pair<Double, Double>) = Offset( // 진행 방향(x)을 화면 위쪽으로
                size.width / 2 - ((q.second - (minY + maxY) / 2) * sc).toFloat(),
                size.height / 2 - ((q.first - (minX + maxX) / 2) * sc).toFloat(),
            )
            val path = Path().apply { pts.forEachIndexed { i, q -> p(q).let { if (i == 0) moveTo(it.x, it.y) else lineTo(it.x, it.y) } } }
            drawPath(path, path0, style = Stroke(8f, cap = StrokeCap.Round))
            drawCircle(first, 14f, p(pts.first()))
            drawCircle(last, 18f, p(pts.last()))
        }
    }
}

/** 부딪힌 대상 칸: 그림과 이름. 기본 크기 = 차 화면. onClick 이 없으면 보여 주기만(휴대폰: 대상은 차에서 고름) */
@Composable
fun KindTile(kind: CrashKind, selected: Boolean, modifier: Modifier = Modifier.width(272.dp), iconSize: Dp = 120.dp, style: TextStyle = Pleos.titleS, onClick: (() -> Unit)? = null) {
    val fg = if (selected) Pleos.basic00 else Pleos.textPrimary
    Column(
        modifier.clip(Pleos.shapeL).background(if (selected) Pleos.buttonFilled else Pleos.buttonBasic, Pleos.shapeL)
            .then(if (onClick != null) Modifier.pressable(role = Role.RadioButton, onClick = onClick) else Modifier).padding(vertical = iconSize / 5),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(iconSize / 10, Alignment.CenterVertically),
    ) {
        // 시안 Motion 보드와 같은 1.2 선(사용자 지시 2026-11-09), 휴대폰 작은 칸만 2배. 고른 칸(검정)은 흰 선
        LineArt(LineArts.kind(kind), Modifier.size(iconSize), animate = false, ink = fg, stroke = if (iconSize < 96.dp) 2f else 1f)
        Text(kind.label, style = style, color = fg)
    }
}

/** 경위서 한 줄씩: 앞에 시각이 있으면 시각과 내용을 나눠 표처럼. 기본 크기 = 차 화면 */
@Composable
fun ReportLines(report: String, timeStyle: TextStyle = Pleos.bodyS, textStyle: TextStyle = Pleos.bodyM, timeWidth: Dp = 170.dp, gap: Dp = 20.dp) {
    report.lines().filter { it.isNotBlank() }.forEach { line ->
        val m = Regex("""^(\d{2}:\d{2}:\d{2}(?:\.\d+)?)\s+(.*)$""").find(line)
        if (m == null) Text(line, style = textStyle)
        else Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
            Text(m.groupValues[1], style = timeStyle, color = Pleos.textTertiary, modifier = Modifier.width(timeWidth))
            Text(m.groupValues[2].replace("km/h", "km⁠/⁠h"), style = textStyle, modifier = Modifier.weight(1f)) // 단위가 "km/ h"로 끊기지 않게(word joiner)
        }
    }
}
