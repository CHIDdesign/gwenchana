package com.askthecar

import android.content.Context
import android.content.Intent
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 시안 B(design/redesign/flow2/proto.src.html)의 공통 조각과 차 홈. 숫자는 시안 값 */
object BIcon { // 시안 P: 24 격자, 선 1.8
    const val FLAME = "M12 3c1 3.5 5 5 5 10a5 5 0 0 1-10 0c0-2.2 1-3.5 2-4.6.3 1.4 1 2.3 2 2.6-.5-2.3.3-5.5 1-8z"
    const val GEAR = "M12 8.8a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0-6.4M12 2.5v2.6M12 18.9v2.6M2.5 12h2.6M18.9 12h2.6M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"
    const val X = "M6 6l12 12M18 6L6 18"
    const val LOCK = "M7.5 10.5h9a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val PLUS = "M12 5v14M5 12h14"
    const val UNDO = "M8 6L4 10l4 4M4 10h10a5.5 5.5 0 0 1 0 11h-3"
}

@Composable
fun BIc(d: String, color: Color, size: Dp, modifier: Modifier = Modifier) {
    val path = remember(d) { PathParser().parsePathString(d).toPath() }
    Canvas(modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, k, Offset.Zero) { drawPath(path, color, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** 시안 --night 계열. 다크 모드 바닥(#1D1E21)에 묻히지 않게 한 칸 밝게 */
val NIGHT get() = Color(if (Pleos.dark) 0xFF25272D else 0xFF17181C)
private val NIGHT2 get() = Color(if (Pleos.dark) 0xFF30323A else 0xFF25272D)
private val NIGHT3 get() = Color(if (Pleos.dark) 0xFF3A3C44 else 0xFF30323A)
private val GREY = Color(0xFFA4A8AE)

val H44 get() = Pleos.headlineS.copy(fontSize = 44.sp, lineHeight = 58.sp, fontWeight = FontWeight.ExtraBold)
val B30 get() = Pleos.bodyL.copy(lineHeight = 40.sp)
private val B26 get() = Pleos.bodyS.copy(lineHeight = 36.sp)

/** box-shadow 0 y blur (alpha). Pleos 3단계에 없는 시안 카드 그림자 */
fun Modifier.softShadow(y: Dp, blur: Dp, alpha: Float, shape: Shape): Modifier = drawBehind {
    drawIntoCanvas { canvas ->
        val paint = Paint().apply { color = Color.Transparent; asFrameworkPaint().setShadowLayer(blur.toPx(), 0f, y.toPx(), Color.Black.copy(alpha = alpha).toArgb()) }
        canvas.drawOutline(shape.createOutline(size, layoutDirection, this), paint)
    }
}

/** 시안 carin: 16dp 아래에서 0.7초에 떠오름 */
@Composable
fun Modifier.riseIn(): Modifier {
    val a = remember { Animatable(0f) }
    LaunchedEffect(Unit) { a.animateTo(1f, tween(700, easing = Pleos.easing)) }
    return graphicsLayer { alpha = a.value; translationY = (1f - a.value) * 16.dp.toPx() }
}

enum class FloorKind { RED, DARK, BASIC, PALE, GLASS }

/** 시안 .cb: 높이 100·모서리 32·글자 32 굵게·양옆 36·아이콘 36. small = 되돌리기(80·24·28·24·32) */
@Composable
fun FloorButton(text: String, kind: FloorKind, modifier: Modifier = Modifier, icon: String? = null, small: Boolean = false, onClick: () -> Unit) {
    val (bg, fg) = when (kind) {
        FloorKind.RED -> Pleos.negative to Pleos.onNegative
        FloorKind.DARK -> Pleos.buttonFilled to Pleos.basic00
        FloorKind.BASIC -> Pleos.buttonBasic to Pleos.textPrimary
        FloorKind.PALE -> Color(0xFFE0E1E6) to Color(0xFF131417)
        FloorKind.GLASS -> Color.White.copy(alpha = 0.12f) to Color.White
    }
    val shape = RoundedCornerShape(if (small) 24.dp else 32.dp)
    Row(
        modifier.height(if (small) 80.dp else 100.dp).clip(shape).pressable { onClick() }.background(bg, shape).padding(horizontal = if (small) 24.dp else 36.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterHorizontally),
    ) {
        icon?.let { BIc(it, fg, if (small) 32.dp else 36.dp) }
        Text(text, style = Pleos.titleS.copy(fontSize = if (small) 28.sp else 32.sp, fontWeight = FontWeight.Bold), color = fg, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip)
    }
}

/** 시안 .cicb: 100×100 아이콘 버튼 */
@Composable
private fun SquareButton(icon: String, label: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(32.dp)
    Box(modifier.size(100.dp).clip(shape).pressable { onClick() }.background(Pleos.buttonBasic, shape).semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        BIc(icon, Pleos.textPrimary, 44.dp)
    }
}

// ── 기록 중 띠 (기획 §6, §5-8)

enum class RecStage { RECORDING, SAVING, SAVED, UPLOADED, ARRIVED, FAILED }

/** 신호를 띠 상태로. 0~10초는 기록 중, 그 뒤엔 저장·서버·휴대폰 중 실제로 끝난 가장 먼 단계 */
fun recStage(elapsed: Double, saved: Boolean, acked: Boolean, arrived: Boolean, failed: Boolean) = when {
    failed -> RecStage.FAILED
    !saved -> if (elapsed < CrashWatcher.AFTER_SEC) RecStage.RECORDING else RecStage.SAVING
    !acked -> RecStage.SAVED
    !arrived -> RecStage.UPLOADED
    else -> RecStage.ARRIVED
}

@Composable
private fun recStageOf(inc: Incident): Pair<RecStage, Double> {
    val elapsed by produceState(System.currentTimeMillis() / 1000.0 - inc.t, inc.t) {
        while (value < CrashWatcher.AFTER_SEC) { delay(200); value = System.currentTimeMillis() / 1000.0 - inc.t }
    }
    val saved = CrashFlow.dir != null && CrashFlow.record?.dir == CrashFlow.dir
    return recStage(elapsed, saved, CarSync.recordAcked == inc.t, CarSync.arrived(inc.t), CrashFlow.saveFailed == inc.t) to elapsed
}

/** 창 맨 아래 5dp 막대: 충돌 뒤 기록 10초 동안 차오르고 저장되면 사라진다 */
@Composable
fun RecordBar(inc: Incident, modifier: Modifier = Modifier) {
    val (stage) = recStageOf(inc)
    if (stage != RecStage.RECORDING && stage != RecStage.SAVING) return
    val fill = remember(inc.t) { Animatable(((System.currentTimeMillis() / 1000.0 - inc.t) / CrashWatcher.AFTER_SEC).coerceIn(0.0, 1.0).toFloat()) }
    LaunchedEffect(inc.t) { fill.animateTo(1f, tween(((1f - fill.value) * CrashWatcher.AFTER_SEC * 1000).toInt(), easing = LinearEasing)) }
    Box(modifier.fillMaxWidth(fill.value).height(5.dp).background(Pleos.textPrimary))
}

/** 시안 "기록 중 0:07": 점(기록·저장 중) 또는 자물쇠·체크(끝난 단계) + 글자 */
@Composable
fun RecordLabel(inc: Incident, modifier: Modifier = Modifier) {
    val (stage, elapsed) = recStageOf(inc)
    // 저장 시각: 사고 +10초가 아니라 실제 파일 시각을 적는다
    val savedAt by produceState<String?>(null, CrashFlow.record?.dir) {
        value = CrashFlow.record?.dir?.let { d -> withContext(Dispatchers.IO) { File(d, "log.sha256").takeIf { it.exists() }?.let { FactTimeline.ts(it.lastModified() / 1000.0).take(8) } } }
    }
    val text = when (stage) {
        RecStage.RECORDING -> "기록 중 0:%02d".format(elapsed.toInt().coerceIn(0, 9))
        RecStage.SAVING -> "저장 중"
        RecStage.SAVED -> "차에 저장됨" + savedAt?.let { " $it" }.orEmpty()
        RecStage.UPLOADED -> "서버에 올라감"
        RecStage.ARRIVED -> "휴대폰에 도착"
        RecStage.FAILED -> "기록 저장에 실패했어요"
    }
    val failed = stage == RecStage.FAILED
    val ink = if (failed) Pleos.negative else Pleos.textTertiary
    Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        when (stage) {
            RecStage.RECORDING, RecStage.SAVING -> {
                val blink by rememberInfiniteTransition(label = "rec").animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
                Box(Modifier.size(14.dp).alpha(blink).background(if (stage == RecStage.RECORDING) Pleos.negative else Pleos.textTertiary, CircleShape))
            }
            RecStage.FAILED -> Box(Modifier.size(14.dp).background(Pleos.negative, CircleShape))
            else -> BIc(if (stage == RecStage.ARRIVED) BIcon.CHECK else BIcon.LOCK, ink, 26.dp)
        }
        Text(text, style = Pleos.bodyS, color = ink)
    }
}

// ── 대상 고르기 (시안 cKind B)

@Composable
fun KindCard(kind: CrashKind, modifier: Modifier, onClick: () -> Unit) {
    val shape = RoundedCornerShape(48.dp)
    Column(
        modifier.softShadow(12.dp, 40.dp, 0.22f, shape).clip(shape).pressable(role = Role.Button, pressedTint = Color.White.copy(alpha = 0.12f), onClick = onClick).background(NIGHT, shape),
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(40.dp, Alignment.CenterVertically),
    ) {
        LineArt(LineArts.kind(kind), Modifier.size(320.dp), animate = false, ink = Color.White, stroke = 1.25f) // 시안 선 1.5 = 1.2 × 1.25
        Text(kind.label, style = Pleos.headlineS.copy(fontSize = 44.sp, lineHeight = 56.sp, fontWeight = FontWeight.Bold), color = Color.White)
    }
}

// ── 차 홈 (시안 cHomeB)

/** name = 폴더 이름(yyyyMMdd_HHmmss). kind 는 운전자가 고른 것만(기록으로 추정한 대상은 카드에 사실처럼 적지 않음) */
class PastCrash(val name: String, val kind: CrashKind?, val deg: Double?, val falseAlarm: Boolean) {
    val title get() = when {
        kind != null && deg != null -> "${kind.label} · ${sideText(deg)}"
        kind != null -> kind.label
        deg != null -> sideText(deg)
        else -> "충격 기록"
    }
    val note get() = if (deg != null) "[센서 추정]" else "기록 보관"
    private fun sideText(d: Double) = PhotoGuide.part(d).removeSuffix("쪽") + "쪽 충격"
}

/** 기록이 남은 사고 폴더 전부, 최신순 */
fun pastCrashes(root: File): List<PastCrash> = root.listFiles().orEmpty().filter { File(it, "log.json").exists() }.sortedByDescending { it.name }.map { d ->
    val (kind, chosen) = CrashWatcher.readKind(d)
    val deg = runCatching { JSONObject(File(d, "impact.json").readText()).takeUnless { it.isNull("deg") }?.optDouble("deg") }.getOrNull()
    PastCrash(d.name, kind.takeIf { chosen && it != CrashKind.UNKNOWN }, deg, File(d, "false_alarm").exists())
}

private val NAME = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US)

/** 폴더 이름 → "오늘 14:02" / "9월 3일 18:12" (time = 시각만) */
fun crashDate(name: String, today: Date = Date()): String = runCatching {
    val at = synchronized(NAME) { NAME.parse(name)!! }
    fun f(p: String) = SimpleDateFormat(p, Locale.KOREA).format(at)
    if (f("yyyyMMdd") == SimpleDateFormat("yyyyMMdd", Locale.KOREA).format(today)) "오늘 ${f("HH:mm")}" else f("M월 d일 HH:mm")
}.getOrDefault(name)

private fun crashTimeOnly(name: String) = name.substringAfter('_', "").take(4).let { if (it.length == 4) "${it.take(2)}:${it.drop(2)}" else name }

private fun open(ctx: Context, extras: Intent.() -> Unit) = ctx.startActivity(Intent(ctx, CrashActivity::class.java).apply(extras).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

/** 주행 중: 시안 §5-2 한 줄만 */
@Composable
fun DrivingHome(onExit: () -> Unit) = StageFloor { _ ->
    Text("주행 중엔 뒤에서 기록만 해요", style = H44, modifier = Modifier.offset(x = 80.dp, y = 300.dp))
    SquareButton(BIcon.X, "앱 나가기", Modifier.align(Alignment.TopEnd).offset(x = (-64).dp, y = 48.dp), onExit)
}

/**
 * 홈: 왼쪽 큰 카드 = 진행 중 사고(없으면 안내 글), 오른쪽 = 지난 사고 가로 목록, 사고 아님 줄.
 * tick 이 바뀌면(화면으로 돌아옴) 폴더를 다시 읽는다.
 */
@Composable
fun CarHome(tick: Int, onSettings: () -> Unit, onExit: () -> Unit) {
    val ctx = LocalContext.current
    val inc = CrashFlow.incident
    val activeDir = CrashFlow.dir?.name?.takeIf { inc != null }
    val all by produceState(emptyList<PastCrash>(), tick, activeDir) {
        value = withContext(Dispatchers.IO) { pastCrashes(File(ctx.filesDir, "crashes")) }
    }
    LaunchedEffect(Unit) { CarSync.followSeen() }
    val past = all.filter { !it.falseAlarm && it.name != activeDir }
    val marked = all.firstOrNull { it.falseAlarm }
    StageFloor { horizon ->
        Text("사고 기록", style = H44, modifier = Modifier.offset(x = 80.dp, y = 68.dp))
        SquareButton(BIcon.GEAR, "설정", Modifier.align(Alignment.TopEnd).offset(x = (-188).dp, y = 48.dp), onSettings)
        SquareButton(BIcon.X, "앱 나가기", Modifier.align(Alignment.TopEnd).offset(x = (-64).dp, y = 48.dp), onExit)
        if (inc != null) HeroCard(inc, activeDir.orEmpty(), Modifier.offset(x = 80.dp, y = 180.dp))
        else Column(Modifier.offset(x = 80.dp, y = 300.dp).width(800.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text("진행 중인 사고가 없어요", style = H44)
            Text("사고가 나면 이 화면이 먼저 열려요.", style = B30, color = Pleos.textSecondary)
        }
        marked?.let { m ->
            Row(
                Modifier.offset(x = 980.dp, y = 290.dp).size(640.dp, 112.dp).riseIn().background(Pleos.textPrimary.copy(alpha = 0.06f), RoundedCornerShape(28.dp)).padding(start = 32.dp, end = 16.dp),
                verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Text("${crashTimeOnly(m.name)} 사고 아님으로 표시 · 7일 보관", style = B26, color = Pleos.textSecondary, modifier = Modifier.weight(1f))
                // 진행 중인 사고가 있으면 되돌리지 않는다(그 사고를 덮어씀)
                if (inc == null) FloorButton("되돌리기", FloorKind.BASIC, icon = BIcon.UNDO, small = true) {
                    if (CrashFlow.reopen(ctx, File(ctx.filesDir, "crashes/${m.name}"))) open(ctx) {}
                }
            }
        }
        if (past.isNotEmpty()) LazyRow(
            Modifier.offset(x = 980.dp, y = 430.dp).width(maxWidth - 980.dp).height(570.dp),
            contentPadding = PaddingValues(end = 60.dp), horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            itemsIndexed(past, key = { _, c -> c.name }) { i, c ->
                PastCard(c, if (i == 0) NIGHT2 else NIGHT3) { open(ctx) { putExtra(CrashActivity.EXTRA_RECORD, true); putExtra(CrashActivity.EXTRA_DIR, c.name) } }
            }
        }
        // 진행 중 사고를 덮어쓰지 않게
        if (inc == null) FloorButton("차가 못 본 사고 기록하기", FloorKind.BASIC, Modifier.offset(x = 80.dp, y = horizon + 28.dp), icon = BIcon.PLUS) {
            ctx.startForegroundService(Intent(ctx, CarService::class.java).setAction(CarService.ACTION_MANUAL))
        }
    }
}

@Composable
private fun HeroCard(inc: Incident, dirName: String, modifier: Modifier) {
    val ctx = LocalContext.current
    val shape = RoundedCornerShape(56.dp)
    val saved = CrashFlow.dir != null && CrashFlow.record?.dir == CrashFlow.dir
    val arrived = CarSync.arrived(inc.t)
    val title = when { arrived -> "휴대폰에서 이어서 하세요"; saved -> "기록을 차에 저장했어요"; else -> "기록하고 있어요" } // 신호가 없으면 휴대폰에서 하라고 하지 않는다
    Box(modifier.size(870.dp, 820.dp).riseIn().softShadow(22.dp, 50.dp, 0.3f, shape).clip(shape).background(NIGHT, shape)) {
        Text(crashDate(dirName) + if (inc.roadKnown) " · " + (if (inc.highway) "고속도로" else "시내") else "", style = B26, color = GREY, modifier = Modifier.offset(x = 56.dp, y = 52.dp))
        Text(title, style = Pleos.headlineM.copy(fontWeight = FontWeight.ExtraBold), color = Color.White, modifier = Modifier.offset(x = 56.dp, y = 100.dp))
        Row(Modifier.offset(x = 56.dp, y = 190.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            if (saved) Chip(BIcon.LOCK, "차에 저장됨")
            if (arrived) Chip(BIcon.CHECK, "휴대폰 도착")
        }
        CarFigure(carViewFor(inc.impactDeg), Modifier.carBox(carViewFor(inc.impactDeg), 150.dp, 278.dp, 600.dp), inc.impactDeg)
        Row(Modifier.align(Alignment.BottomStart).padding(40.dp).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            FloorButton("기록 보기", FloorKind.PALE, Modifier.weight(1f)) { open(ctx) { putExtra(CrashActivity.EXTRA_RECORD, true) } }
            FloorButton("차에게 묻기", FloorKind.GLASS) { open(ctx) { putExtra(CrashActivity.EXTRA_RECORD, true); putExtra(CrashActivity.EXTRA_ASK, true) } }
        }
    }
}

@Composable
private fun Chip(icon: String, text: String) = Row(
    Modifier.height(52.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(16.dp)).padding(horizontal = 20.dp),
    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    BIc(icon, Color(0xFFD7D9DF), 28.dp)
    Text(text, style = Pleos.bodyS, color = Color(0xFFD7D9DF))
}

@Composable
private fun PastCard(c: PastCrash, color: Color, onClick: () -> Unit) {
    val shape = RoundedCornerShape(40.dp)
    Box(Modifier.width(310.dp).fillMaxHeight().clip(shape).pressable(pressedTint = Color.White.copy(alpha = 0.12f), onClick = onClick).background(color, shape)) {
        Column(Modifier.padding(horizontal = 28.dp, vertical = 32.dp)) {
            Text(crashDate(c.name), style = Pleos.titleS.copy(lineHeight = 42.sp, fontWeight = FontWeight.Bold), color = Color.White)
            Text(c.title, style = B26, color = GREY)
            Text(c.note, style = B26, color = GREY)
        }
        CarFigure(CarView.REAR, Modifier.carBox(CarView.REAR, 20.dp, 360.dp, 270.dp))
    }
}
