package com.askthecar.phone

import android.app.Activity
import android.app.KeyguardManager
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.askthecar.AssetImages
import com.askthecar.ButtonKind
import com.askthecar.ClaimOwner
import com.askthecar.HandoffData
import com.askthecar.Pleos
import com.askthecar.PleosTheme
import com.askthecar.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** 시안 B안(design/redesign/flow2/proto.src.html)의 값. 412x915 기준 */
internal object W {
    val night get() = if (Pleos.dark) Color(0xFF25272D) else Color(0xFF17181C)
    val nightSub = Color(0xFFA4A8AE)
    val nightBody = Color(0xFFC7C9CE)
    val hit = Color(0xFFFF4B39)
    private val full = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None)
    private fun st(size: Int, line: Int, w: FontWeight = FontWeight.Normal) = T.body.copy(fontSize = size.sp, lineHeight = line.sp, fontWeight = w, lineHeightStyle = full, fontFeatureSettings = "tnum")
    val h30 = st(30, 38, FontWeight.ExtraBold).copy(letterSpacing = (-0.4).sp)
    val h24 = st(24, 32, FontWeight.ExtraBold)
    val t18 = st(18, 26, FontWeight.Bold)
    val t17 = st(17, 24, FontWeight.Bold)
    val t16 = st(16, 24, FontWeight.Bold)
    val b16 = st(16, 24)
    val b15 = st(15, 22)
    val c14 = st(14, 20)
    val c13 = st(13, 18)
    val logo = st(21, 28, FontWeight.ExtraBold).copy(letterSpacing = (-0.3).sp)
    val btn = st(17, 24, FontWeight.Bold)
    val pick = st(15, 22, FontWeight.Bold)
    val tog = st(14, 20, FontWeight.Bold)
    val shape14 = RoundedCornerShape(14.dp)
    val shape16 = RoundedCornerShape(16.dp)
    val shape18 = RoundedCornerShape(18.dp)
    val shape32 = RoundedCornerShape(32.dp)
    val sheet = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)
}

/** 시안의 24 격자 선 아이콘. 원·사각형은 경로로 옮김 */
internal object Ics {
    const val BACK = "M15 5l-7 7 7 7"
    const val GEAR = "M12 8.8a3.2 3.2 0 1 0 0 6.4a3.2 3.2 0 1 0 0-6.4zM12 2.5v2.6M12 18.9v2.6M2.5 12h2.6M18.9 12h2.6M5.3 5.3l1.8 1.8M16.9 16.9l1.8 1.8M5.3 18.7l1.8-1.8M16.9 7.1l1.8-1.8"
    const val CHECK = "M5 12.5l4.5 4.5L19 7.5"
    const val CHEV = "M9 5l7 7-7 7"
    const val PLUS = "M12 5v14M5 12h14"
    const val LOCK = "M7.5 10.5h9a2 2 0 0 1 2 2v6a2 2 0 0 1-2 2h-9a2 2 0 0 1-2-2v-6a2 2 0 0 1 2-2zM8.5 10.5V7.5a3.5 3.5 0 0 1 7 0v3"
    const val ASK = "M4 5.5h16v10H10l-4 3.5v-3.5H4z"
    const val DOC = "M7 3.5h7l4 4v13H7zM14 3.5v4h4M9.5 12h5M9.5 15.5h5"
    const val PHOTO = "M5 5h14a1.5 1.5 0 0 1 1.5 1.5v11a1.5 1.5 0 0 1-1.5 1.5H5a1.5 1.5 0 0 1-1.5-1.5v-11A1.5 1.5 0 0 1 5 5zM9 8.3a1.7 1.7 0 1 0 0 3.4a1.7 1.7 0 1 0 0-3.4zM20.5 15.5l-5-4.5-8 8"
    const val SEND = "M4 12l16-8-6 16-3-6.5z"
    const val PLAY = "M8 5.5v13l10-6.5z"
    const val MIC = "M12 3a3 3 0 0 1 3 3v5a3 3 0 0 1-6 0V6a3 3 0 0 1 3-3zM5.5 11.5a6.5 6.5 0 0 0 13 0M12 18v3"
    const val PHONE = "M5 4h3.5l1.7 4.3-2.2 1.5a11 11 0 0 0 6.2 6.2l1.5-2.2L20 15.5V19a1.5 1.5 0 0 1-1.6 1.5C10.5 20 4 13.5 3.5 5.6A1.5 1.5 0 0 1 5 4z"
    const val FAMILY = "M12 4.5a3.5 3.5 0 1 0 0 7a3.5 3.5 0 1 0 0-7zM5 20a7 7 0 0 1 14 0"
    const val LINK = "M10 14a4 4 0 0 0 5.7 0l3-3a4 4 0 0 0-5.7-5.7l-1 1M14 10a4 4 0 0 0-5.7 0l-3 3a4 4 0 0 0 5.7 5.7l1-1"
    const val HELP = "M12 3.5a8.5 8.5 0 1 0 0 17a8.5 8.5 0 1 0 0-17zM9.5 9.5a2.5 2.5 0 1 1 3.6 2.2c-.8.4-1.1.9-1.1 1.8M12 16.6v.1"
}

@Composable
internal fun Ic(d: String, size: Dp = 24.dp, color: Color = Pleos.textPrimary) {
    val path = remember(d) { PathParser().parsePathString(d).toPath() }
    Canvas(Modifier.size(size)) {
        val k = this.size.minDimension / 24f
        scale(k, k, Offset.Zero) { drawPath(path, color, style = Stroke(1.8f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

/** 시안의 box-shadow 그대로 */
internal fun Modifier.drop(y: Dp, blur: Dp, alpha: Float, shape: Shape) = drawBehind {
    drawIntoCanvas { c ->
        val p = Paint().apply { color = Color.Transparent; asFrameworkPaint().setShadowLayer(blur.toPx() * 0.7f, 0f, y.toPx(), Color.Black.copy(alpha = alpha).toArgb()) }
        c.drawOutline(shape.createOutline(size, layoutDirection, this), p)
    }
}

internal enum class BS { PRI, SEC, WHITE, GLASS, RED, SOLID }

/** 시안 .btn. 글자가 길면 칸이 위아래로 늘어난다(글자 크게 쓰는 사람) */
@Composable
internal fun PBtn(text: String, style: BS = BS.PRI, icon: String? = null, modifier: Modifier = Modifier.fillMaxWidth(), height: Dp = 56.dp, size: TextUnit = 17.sp, iconSize: Dp = 20.dp, onClick: () -> Unit) {
    val (bg, fg) = when (style) {
        BS.PRI -> Pleos.buttonFilled to Pleos.basic00
        BS.SEC -> Pleos.buttonBasic to Pleos.textPrimary
        BS.WHITE -> Color(0xFFE0E1E6) to Color(0xFF131417)
        BS.GLASS -> Color.White.copy(alpha = 0.12f) to Color.White
        BS.RED -> Pleos.negative to Pleos.onNegative
        BS.SOLID -> (if (Pleos.dark) Color(0xFF313236) else Color(0xFFE7E8EC)) to Pleos.textPrimary
    }
    Box(modifier.heightIn(min = height).clip(W.shape16).pressable(onClick = onClick).background(bg, W.shape16).padding(horizontal = 12.dp, vertical = 4.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { Ic(it, iconSize, fg) }
            Text(text, style = W.btn.copy(fontSize = size), color = fg, textAlign = TextAlign.Center)
        }
    }
}

@Composable
internal fun IconBtn(d: String, label: String, bg: Color = Pleos.buttonBasic, fg: Color = Pleos.textPrimary, onClick: () -> Unit) =
    Box(Modifier.size(44.dp).clip(W.shape14).pressable(onClick = onClick).background(bg, W.shape14).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Ic(d, 24.dp, fg) }

@Composable
private fun assetImage(path: String): ImageBitmap? {
    val ctx = LocalContext.current
    var img by remember(path) { mutableStateOf(AssetImages.cached(path)) }
    LaunchedEffect(path) { if (img == null) img = withContext(Dispatchers.IO) { AssetImages.load(ctx, path) } }
    return img
}

/** 그림의 투명 여백을 뺀 영역(x, y, 폭, 높이). 시안이 여백을 잘라 쓴 크기에 맞춘다. assets/car 를 다시 렌더하면 다시 잴 것 */
private val carCrop = mapOf("rear" to listOf(31, 34, 1106, 679), "front" to listOf(36, 35, 1104, 717), "top" to listOf(36, 49, 653, 1257))

private fun cropped(img: ImageBitmap, view: String) = carCrop.getValue(view).let { (x, y, w, h) -> BitmapPainter(img, IntOffset(x, y), IntSize(w, h)) }

private class CarPts(val ratio: Float, val points: Map<String, Offset>)
private val carPts = ConcurrentHashMap<String, CarPts>()

private fun carMeta(ctx: Context, view: String) = carPts.getOrPut(view) {
    val meta = ctx.assets.open("car/car_figures.json").use { JSONObject(it.reader().readText()) }.getJSONObject(view)
    val p = meta.getJSONObject("points")
    val (bx, by, bw, bh) = carCrop.getValue(view)
    val w = meta.getInt("w").toFloat(); val h = meta.getInt("h").toFloat()
    CarPts(bw.toFloat() / bh, p.keys().asSequence().associateWith { k -> p.getJSONArray(k).let { Offset((it.getDouble(0).toFloat() * w - bx) / bw, (it.getDouble(1).toFloat() * h - by) / bh) } })
}

/**
 * 어두운 카드 위의 3/4 차(시안 rearCar) + 충격 면 바깥 꺾쇠. 뒤·오른쪽 충격은 뒤에서 본 그림, 앞·왼쪽 충격은 앞에서 본 그림(그쪽 면이 보이는 쪽).
 * CarFigure 는 꺾쇠 색이 테마 빨강이라 어두운 카드에서 시안의 #FF4B39 가 안 나와 따로 그린다
 */
@Composable
internal fun CaseCar(width: Dp, deg: Double?, modifier: Modifier = Modifier, alpha: Float = 1f) {
    val ctx = LocalContext.current
    val norm = deg?.let { ((it % 360) + 360) % 360 }
    val view = if (norm != null && (norm < 45 || norm >= 202.5)) "front" else "rear"
    val img = assetImage("car/car_$view.png")
    var meta by remember(view) { mutableStateOf<CarPts?>(null) }
    LaunchedEffect(view) { meta = withContext(Dispatchers.IO) { runCatching { carMeta(ctx, view) }.getOrNull() } }
    val appear = remember(norm) { Animatable(0f) }
    LaunchedEffect(norm) { appear.animateTo(1f, tween(900, easing = Pleos.easing)) }
    val m = meta
    if (img == null || m == null) { Spacer(modifier.size(width, width * 0.647f)); return }
    Box(modifier.size(width, width / m.ratio)) {
        Image(remember(img, view) { cropped(img, view) }, null, Modifier.fillMaxSize().graphicsLayer { this.alpha = alpha }, contentScale = ContentScale.Fit)
        if (norm != null) Canvas(Modifier.fillMaxSize()) {
            val a = (norm / 45).toInt() * 45
            val pa = m.points["impact_$a"] ?: return@Canvas
            val pb = m.points["impact_${(a + 45) % 360}"] ?: pa
            val pt = pa + (pb - pa) * ((norm - a) / 45).toFloat()
            val center = m.points["center"] ?: return@Canvas
            val w = this.size.width
            val p = Offset(pt.x * w, pt.y * this.size.height)
            val c = Offset(center.x * w, center.y * this.size.height)
            val dir = (p - c).let { it / hypot(it.x, it.y).coerceAtLeast(1f) }
            val t = appear.value
            val tip = p + dir * (w * 0.08f + w * 0.07f * (1f - t))
            val ang = atan2(dir.y, dir.x); val arm = w * 0.069f
            val chev = Path().apply {
                moveTo(tip.x + arm * cos(ang + 0.8f), tip.y + arm * sin(ang + 0.8f))
                lineTo(tip.x, tip.y)
                lineTo(tip.x + arm * cos(ang - 0.8f), tip.y + arm * sin(ang - 0.8f))
            }
            drawPath(chev, W.hit.copy(alpha = (t / 0.6f).coerceAtMost(1f)), style = Stroke(w * 0.0144f, cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun MiniTop(h: Dp) { assetImage("car/car_top.png")?.let { Image(remember(it) { cropped(it, "top") }, null, Modifier.height(h).width(h * 0.5195f), contentScale = ContentScale.Fit) } }

private fun whereWord(d: HandoffData) = if (Logic.manual(d)) "직접 기록" else if (d.highway) "고속도로" else "시내"

private fun notifEnabled(ctx: Context) = ctx.getSystemService(NotificationManager::class.java).areNotificationsEnabled()
private fun openNotifSettings(ctx: Context) { runCatching { ctx.startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)) } }

/** 설정 화면에 다녀오면 다시 읽는다 */
@Composable
private fun rememberNotifEnabled(): Boolean {
    val ctx = LocalContext.current
    val owner = LocalLifecycleOwner.current
    var on by remember { mutableStateOf(notifEnabled(ctx)) }
    LaunchedEffect(owner) { owner.lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) { on = notifEnabled(ctx); awaitCancellation() } }
    return on
}

/** 홈. 어두운 카드 쌓기(시안 pHomeB) */
@Composable
internal fun WalletHome(account: String, cases: List<HandoffData>, latestT: Double?, connected: Boolean,
                        onOpen: (HandoffData) -> Unit, onTodo: (HandoffData, Todo) -> Unit, onSecond: (HandoffData, Todo) -> Unit,
                        onSettings: () -> Unit, onManual: () -> Unit, onList: () -> Unit) {
    val ctx = LocalContext.current
    val helper = account == Logic.FAMILY
    val rev by ClaimStore.rev.collectAsState()
    val notif = rememberNotifEnabled()
    var lost by remember { mutableStateOf(false) }
    LaunchedEffect(connected) { lost = false; if (!connected) { delay(60_000); lost = true } } // 잠깐 끊긴 것은 알리지 않는다

    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 20.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("괜차나", style = W.logo, modifier = Modifier.weight(1f))
            IconBtn(Ics.GEAR, "설정", onClick = onSettings)
        }
        if (!notif) RedLine("알림이 꺼져 있어 사고를 못 알려요", "켜기") { openNotifSettings(ctx) }
        else if (lost) RedLine("차와 연결이 끊겼어요")

        if (cases.isEmpty()) {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                Box(Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth().height(330.dp).background(W.night, W.shape32)) {
                    Text("사고가 나면\n먼저 알려 드려요", style = W.h30, color = Color.White, modifier = Modifier.padding(24.dp))
                    CaseCar(300.dp, null, Modifier.offset(x = 40.dp, y = 128.dp), alpha = 0.95f)
                }
                Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 8.dp)) {
                    Ready("보험사 번호", ClaimStore.insurerContact(ctx).also { rev.hashCode() } != null, "넣기", onSettings)
                    Ready("가족 연락처", ClaimStore.familyContact(ctx) != null, "넣기", onSettings)
                    Ready("알림 허용", notif, "켜기") { openNotifSettings(ctx) }
                    Ready("차와 연결", connected, "확인", onSettings)
                }
            }
        } else {
            val todos = remember(cases, rev, latestT) { cases.associate { it.t to ClaimStore.todo(ctx, it, it.t == latestT, helper) } }
            var front by remember(cases.first().t) { mutableStateOf(cases.first().t) }
            val top = cases.take(3)
            val order = top.sortedBy { if (it.t == front) 0 else 1 } // sortedBy 는 나머지 순서를 그대로 둔다
            BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                val cardH = (maxHeight - 4.dp - 74.dp * (order.size - 1)).coerceIn(340.dp, 470.dp)
                order.drop(1).withIndex().reversed().forEach { (k, c) ->
                    val inset = 16.dp + 8.dp * k
                    Strip(c, todos.getValue(c.t), k, Modifier.offset(y = 4.dp + cardH - 30.dp + 74.dp * k).padding(horizontal = inset)) { front = c.t }
                }
                AnimatedContent(order.first(), Modifier.padding(start = 16.dp, end = 16.dp, top = 4.dp).fillMaxWidth().height(cardH), label = "front", contentKey = { it.t }, transitionSpec = {
                    (fadeIn(tween(240)) + scaleIn(tween(320, easing = Pleos.easing), initialScale = 0.96f)) togetherWith fadeOut(tween(120)) using SizeTransform(clip = false)
                }) { c ->
                    FrontCard(c, todos.getValue(c.t), c.t == cases.first().t, { onOpen(c) }, { t -> onTodo(c, t) }, { t -> onSecond(c, t) })
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!helper) PBtn("차가 못 본 사고 기록하기", BS.SOLID, Ics.PLUS, Modifier.weight(1f), onClick = onManual)
            if (cases.size > 3) PBtn("지난 사고 전체", BS.SOLID, modifier = if (helper) Modifier.fillMaxWidth() else Modifier.width(124.dp), size = 15.sp, onClick = onList)
        }
    }
}

@Composable
private fun RedLine(text: String, button: String? = null, onClick: () -> Unit = {}) = Row(
    Modifier.fillMaxWidth().padding(start = 20.dp, end = 16.dp, bottom = 4.dp).heightIn(min = 36.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
) {
    Text(text, style = W.t16.copy(fontSize = 15.sp), color = Pleos.negative, modifier = Modifier.weight(1f))
    button?.let { PBtn(it, BS.RED, modifier = Modifier.width(64.dp), height = 36.dp, size = 14.sp, onClick = onClick) }
}

@Composable
private fun Ready(name: String, ok: Boolean, action: String, onClick: () -> Unit) = Row(
    Modifier.fillMaxWidth().heightIn(min = 56.dp).pressable(onClick = onClick), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp),
) {
    Text(name, style = W.t16, modifier = Modifier.weight(1f))
    if (ok) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) { Ic(Ics.CHECK, 18.dp, Pleos.textTertiary); Text("됐어요", style = W.b15, color = Pleos.textTertiary) }
    else Text(action, style = W.t16.copy(fontSize = 15.sp), color = Pleos.negative)
    Ic(Ics.CHEV, 18.dp, Pleos.textPrimary)
}

internal fun taskIcon(t: Task) = when (t) {
    Task.CHECK_119, Task.CLAIM -> Ics.PHONE
    Task.SEND -> Ics.SEND
    Task.WAIT, Task.SCENE -> Ics.ASK
    Task.CARD -> Ics.CHECK
    Task.NOT_ACCIDENT -> Ics.DOC
    Task.REST -> null
}

@Composable
private fun FrontCard(d: HandoffData, todo: Todo, live: Boolean, onOpen: () -> Unit, onTodo: (Todo) -> Unit, onSecond: (Todo) -> Unit) {
    val gray = todo.task == Task.NOT_ACCIDENT
    val bg = if (!gray) W.night else if (Pleos.dark) Color(0xFF44464E) else Color(0xFFE0E1E6)
    val fg = if (gray && !Pleos.dark) Color(0xFF131417) else Color.White
    val sub = if (gray && !Pleos.dark) Color(0xFF686A72) else W.nightSub
    // 지난 사고가 앞으로 오면 할 일 대신 무슨 사고였는지(시안 pHomeB)
    val shown = if (live || gray) todo else Todo(todo.task, Logic.caseWord(d), "", "기록 보기")
    Box(Modifier.fillMaxSize().drop(14.dp, 30.dp, 0.28f, W.shape32).background(bg, W.shape32).clip(W.shape32).pressable(pressedTint = Color.White.copy(alpha = 0.04f), onClick = onOpen)) {
        Column(Modifier.padding(24.dp)) {
            Text(Logic.dayTime(d.t) + " · " + whereWord(d), style = W.c14, color = sub)
            Spacer(Modifier.height(8.dp))
            Text(shown.title, style = W.h30, color = fg)
            if (live) { Spacer(Modifier.height(6.dp)); Text(shown.line, style = W.b15, color = if (gray && !Pleos.dark) Color(0xFF44464E) else W.nightBody) }
        }
        CaseCar(296.dp, d.impactDeg.takeUnless { gray }, Modifier.align(Alignment.BottomStart).padding(start = 42.dp, bottom = 112.dp), alpha = if (gray) 0.6f else 1f)
        Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (!live && !gray) PBtn(shown.button, BS.WHITE, Ics.DOC, Modifier.weight(1f), onClick = onOpen)
            else {
                PBtn(shown.button, if (gray && !Pleos.dark) BS.PRI else BS.WHITE, taskIcon(todo.task), Modifier.weight(1f)) { onTodo(todo) }
                todo.second?.let { PBtn(it, BS.GLASS, Ics.ASK.takeIf { _ -> it == "묻기" }, Modifier.width(132.dp), size = 16.sp, iconSize = 18.dp) { onSecond(todo) } }
            }
        }
    }
}

private val StripShape = RoundedCornerShape(bottomStart = 28.dp, bottomEnd = 28.dp)

@Composable
private fun Strip(d: HandoffData, todo: Todo, depth: Int, modifier: Modifier, onClick: () -> Unit) {
    val shade = if (Pleos.dark) (if (depth == 0) Color(0xFF313236) else Color(0xFF292A2F)) else (if (depth == 0) Color.White else Color(0xFFE9EAEF))
    Box(modifier.fillMaxWidth().height(104.dp).drop(8.dp, 18.dp, 0.10f, StripShape).background(shade, StripShape).clip(StripShape).pressable(onClick = onClick).padding(start = 18.dp, end = 18.dp, top = 30.dp), contentAlignment = Alignment.CenterStart) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MiniTop(50.dp)
            Column(Modifier.weight(1f)) {
                Text(Logic.dayTime(d.t), style = W.t17, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(Logic.caseWord(d) + " · " + Logic.statusWord(todo.task), style = W.c14, color = Pleos.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** 3건이 넘을 때 전체 목록 */
@Composable
internal fun CaseList(cases: List<HandoffData>, latestT: Double?, helper: Boolean, onOpen: (HandoffData) -> Unit, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val rev by ClaimStore.rev.collectAsState()
    val todos = remember(cases, rev, latestT) { cases.associate { it.t to ClaimStore.todo(ctx, it, it.t == latestT, helper) } }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().height(56.dp).padding(start = 12.dp, end = 16.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            IconBtn(Ics.BACK, "뒤로", onClick = onBack)
            Text("지난 사고", style = W.t18)
        }
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(horizontal = 16.dp, vertical = 4.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            cases.forEach { c ->
                val s = RoundedCornerShape(20.dp)
                Row(Modifier.fillMaxWidth().heightIn(min = 76.dp).drop(2.dp, 10.dp, 0.05f, s).background(Pleos.backgroundSecondary, s).clip(s).pressable { onOpen(c) }.padding(horizontal = 14.dp),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    MiniTop(56.dp)
                    Column(Modifier.weight(1f)) {
                        Text(Logic.dayTime(c.t), style = W.t17)
                        Text(Logic.caseWord(c) + " · " + Logic.statusWord(todos.getValue(c.t).task), style = W.c14, color = Pleos.textTertiary)
                    }
                    Ic(Ics.CHEV, 20.dp)
                }
            }
        }
    }
}

/** 아래에서 올라오는 시트(시안 .sheet). onDismiss = null 이면 바깥을 눌러도 안 닫힘 */
@Composable
internal fun BoxScope.BottomSheet(onDismiss: (() -> Unit)?, content: @Composable ColumnScope.() -> Unit) {
    val src = remember { MutableInteractionSource() }
    val shown = remember { MutableTransitionState(false).apply { targetState = true } }
    Box(Modifier.matchParentSize().background(Color(0xFF131417).copy(alpha = 0.42f)).clickable(src, null) { onDismiss?.invoke() })
    BackHandler(onDismiss != null) { onDismiss?.invoke() }
    AnimatedVisibility(shown, Modifier.align(Alignment.BottomCenter), enter = slideInVertically(tween(350, easing = Pleos.easing)) { it / 3 } + fadeIn(tween(250))) {
        Column(Modifier.fillMaxWidth().background(Pleos.backgroundPopup, W.sheet).clickable(src, null) {}.navigationBarsPadding().imePadding().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content)
    }
}

internal enum class Go { GUIDE, CALL, SEND, ASK, RECORD, PHOTOS, LINK, FAMILY, FAULT, SETTINGS }

private val TileShape = W.shape18

/** 사고 카드(시안 pDetailB): 어두운 윗부분 + 할 일/일지/기록 */
@Composable
internal fun CaseScreen(d: HandoffData, latest: Boolean, helper: Boolean, tab: Int, setTab: (Int) -> Unit, go: (Go) -> Unit, onBack: () -> Unit,
                        onTodo: (Todo) -> Unit, onSecond: (Todo) -> Unit, onContact: (Int?) -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(Unit) { // 어두운 윗부분 위 상태 막대 글자는 밝게
        val c = WindowCompat.getInsetsController((view.context as Activity).window, view)
        c.isAppearanceLightStatusBars = false
        onDispose { c.isAppearanceLightStatusBars = !Pleos.dark }
    }
    val rev by ClaimStore.rev.collectAsState()
    val todo = remember(d, rev, latest) { ClaimStore.todo(ctx, d, latest, helper) }
    val contacts = remember(d, rev) { ClaimStore.contacts(ctx, d) }
    val log = remember(d, rev) { ClaimStore.log(ctx, d.t) }
    var sheet by remember { mutableStateOf<String?>(null) } // "statement" | "help"
    val grow = remember(d.t) { Animatable(0f) }
    LaunchedEffect(d.t) { grow.animateTo(1f, tween(420, easing = Pleos.easing)) }
    val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val impact = Logic.impactWord(d)

    Box(Modifier.fillMaxSize().graphicsLayer { alpha = grow.value.coerceIn(0f, 1f); scaleX = 0.92f + 0.08f * grow.value; scaleY = scaleX; transformOrigin = TransformOrigin(0.5f, 0.3f) }.background(Pleos.backgroundPrimary)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxWidth().height(286.dp).background(W.night, RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp)).clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))) {
                CaseCar(270.dp, d.impactDeg.takeUnless { todo.task == Task.NOT_ACCIDENT }, Modifier.offset(x = 80.dp, y = 50.dp))
                Box(Modifier.padding(start = 12.dp, top = top + 4.dp)) { IconBtn(Ics.BACK, "뒤로", Color.White.copy(alpha = 0.12f), Color.White, onBack) }
                Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().padding(start = 20.dp, end = 20.dp, bottom = 14.dp), verticalAlignment = Alignment.Bottom) {
                    Column(Modifier.weight(1f)) {
                        Text(Logic.dayTime(d.t) + " · " + (impact ?: if (Logic.manual(d)) "직접 기록" else "충격"), style = W.t18, color = Color.White)
                        Text((if (impact != null) "[센서 추정] · " else "") + (if (Logic.manual(d)) "차가 못 본 사고" else whereWord(d)), style = W.c13, color = W.nightSub)
                    }
                    Row(Modifier.height(28.dp).background(Color.White.copy(alpha = 0.12f), RoundedCornerShape(9.dp)).padding(horizontal = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        Ic(Ics.LOCK, 14.dp, Color(0xFFD7D9DF))
                        Text(if (Logic.manual(d)) "기록 없음" else if (d.hash != null) "기록 저장" else if (Logic.recordLost(d)) "못 받음" else "저장 중", style = W.c13, color = Color(0xFFD7D9DF))
                    }
                }
            }
            Row(Modifier.padding(start = 16.dp, end = 16.dp, top = 12.dp).fillMaxWidth().background(Pleos.buttonBasic, W.shape14).padding(4.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf("할 일", "일지", "기록").forEachIndexed { i, n ->
                    val on = i == tab
                    val s = RoundedCornerShape(11.dp)
                    Box(Modifier.weight(1f).height(44.dp).then(if (on) Modifier.drop(2.dp, 8.dp, 0.10f, s).background(Pleos.backgroundPopup, s) else Modifier).clip(s).pressable { setTab(i) }, contentAlignment = Alignment.Center) {
                        Text(n, style = W.t16, color = if (on) Pleos.textPrimary else Pleos.textTertiary)
                    }
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                when (tab) {
                    0 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 96.dp)) { TodoTab(todo, contacts, onContact) }
                    1 -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 12.dp)) { LogTab(log) }
                    else -> Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).navigationBarsPadding().padding(start = 20.dp, end = 20.dp, top = 14.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        RecordTab(d, latest, helper, go) { sheet = it }
                    }
                }
                if (tab == 0) Row(Modifier.align(Alignment.BottomCenter).fillMaxWidth().background(Pleos.backgroundPrimary.copy(alpha = 0.92f)).navigationBarsPadding().padding(horizontal = 16.dp, vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PBtn(todo.button, BS.PRI, taskIcon(todo.task), Modifier.weight(1f)) { onTodo(todo) }
                    todo.second?.let { s -> PBtn(s, BS.SEC, modifier = Modifier.width(112.dp)) { onSecond(todo) } }
                }
            }
        }
        when (sheet) {
            "statement" -> BottomSheet({ sheet = null }) {
                Text("내 진술", style = W.h24)
                Text(d.statement?.let { "“$it”" } ?: "아직 남긴 진술이 없어요. 차 화면의 사고 기록에서 말로 남길 수 있어요.", style = W.b16, color = Pleos.textSecondary)
                Spacer(Modifier.height(6.dp))
                PBtn("닫기", BS.SEC) { sheet = null }
            }
            "help" -> BottomSheet({ sheet = null }) {
                Text("도움말", style = W.h24)
                KNOW.forEach { Text(it, style = W.b15, color = Pleos.textSecondary) }
                Spacer(Modifier.height(6.dp))
                if (latest && d.steps.isNotEmpty()) PBtn("현장 안내 다시 보기", BS.SEC) { sheet = null; go(Go.GUIDE) }
                if (!helper) PBtn("전화 원고 보기", BS.SEC) { sheet = null; go(Go.CALL) } // 접수 뒤에도 다시 읽을 수 있게
                PBtn("담당자가 과실을 말했을 때", BS.SEC) { sheet = null; go(Go.FAULT) }
                PBtn("닫기", BS.SEC) { sheet = null }
            }
        }
    }
}

@Composable
private fun TodoTab(todo: Todo, contacts: List<CaseContact>, onContact: (Int?) -> Unit) {
    Text("다음 할 일", style = W.c14, color = Pleos.textTertiary)
    Spacer(Modifier.height(2.dp))
    Text(todo.title.replace('\n', ' '), style = W.h24)
    Spacer(Modifier.height(4.dp))
    Text(todo.line, style = W.b15, color = Pleos.textSecondary)
    Row(Modifier.fillMaxWidth().padding(top = 18.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text("연락 상대", style = W.t18, modifier = Modifier.weight(1f))
        TogButton("추가", Ics.PLUS, false, 36.dp) { onContact(null) }
    }
    if (contacts.isEmpty()) Text("아직 없어요. 담당자 연락을 받으면 넣어 두세요.", style = W.b15, color = Pleos.textTertiary)
    contacts.chunked(2).forEachIndexed { r, row ->
        Row(Modifier.fillMaxWidth().padding(top = if (r > 0) 8.dp else 0.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            row.forEachIndexed { k, c -> ContactCard(c, Modifier.weight(1f)) { onContact(r * 2 + k) } }
            if (row.size == 1) Spacer(Modifier.weight(1f))
        }
    }
}

@Composable
private fun ContactCard(c: CaseContact, modifier: Modifier, onClick: () -> Unit) =
    Column(modifier.drop(2.dp, 10.dp, 0.05f, TileShape).background(Pleos.backgroundSecondary, TileShape).clip(TileShape).pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp)) {
        Text(c.type.label, style = W.c13, color = Pleos.textTertiary)
        Text(c.name.ifBlank { "이름 없음" }, style = W.t16, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(if (c.no.isNotBlank()) c.no else c.phone.ifBlank { "번호 없음" }, style = W.c13, color = Pleos.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }

@Composable
private fun LogTab(log: List<LogEntry>) {
    if (log.isEmpty()) Text("아직 표시한 일이 없어요.", style = W.b15, color = Pleos.textTertiary)
    val line = Pleos.textPrimary.copy(alpha = 0.18f)
    val first = Pleos.buttonFilled
    log.forEachIndexed { i, e ->
        Row(Modifier.fillMaxWidth().padding(bottom = 18.dp).drawBehind {
            if (i < log.lastIndex) drawLine(line, Offset(5.5.dp.toPx(), 20.dp.toPx()), Offset(5.5.dp.toPx(), size.height + 14.dp.toPx()), 1.dp.toPx())
        }) {
            Box(Modifier.padding(top = 6.dp).size(11.dp).background(if (i == 0) first else Color(0xFFA4A8AE), CircleShape))
            Spacer(Modifier.width(13.dp))
            Text(Logic.logTime(e.at), style = W.c13.copy(lineHeight = 24.sp), color = Pleos.textTertiary, modifier = Modifier.width(58.dp))
            Spacer(Modifier.width(8.dp))
            Text(e.text, style = W.b16, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun ColumnScope.RecordTab(d: HandoffData, latest: Boolean, helper: Boolean, go: (Go) -> Unit, openSheet: (String) -> Unit) {
    val ctx = LocalContext.current
    val photos = remember(d.t) { claimPhotoCount(ctx, d) }
    val family = ClaimStore.guardian(ctx, d)
    val handed = ClaimStore.flag(ctx, "handed", d.t)
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Ics.PLAY, "사고 재현", if (d.samples.isEmpty()) "기록 없음" else "3D", Modifier.weight(1f)) { go(Go.RECORD) }
        Tile(Ics.DOC, "기록서", "PDF로 보기", Modifier.weight(1f)) { go(Go.SEND) }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        // shortcut: 사진은 찍은 시각으로 사고를 가려서 다음 사고가 난 뒤엔 옛 사고에 새로 못 넣음, 12/03 사고별 폴더로 바꾼다
        Tile(Ics.PHOTO, "사진", if (photos > 0) "${photos}장" else if (d.shots.isEmpty() || !latest) "찍을 장면 없음" else "찍기", Modifier.weight(1f), enabled = d.shots.isNotEmpty() && (latest || photos > 0)) { go(Go.PHOTOS) }
        Tile(Ics.MIC, "내 진술", if (d.statement != null) "있음" else "없음", Modifier.weight(1f)) { openSheet("statement") }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile(Ics.LINK, "두 차 연결", if (latest) "상대 기록과" else "가장 최근 사고만", Modifier.weight(1f), enabled = !helper && latest) { go(Go.LINK) } // shortcut: 서버가 최신 사고만 들고 있음, 12/03 실제 서버 때 푼다
        if (helper) Tile(Ics.HELP, "도움말", "알아 둘 것", Modifier.weight(1f)) { openSheet("help") }
        else Tile(Ics.FAMILY, "가족에게 맡기기", if (!latest) "가장 최근 사고만" else if (handed) "맡김" else family?.name ?: "등록 안 됨", Modifier.weight(1f), enabled = latest) { go(if (family != null) Go.FAMILY else Go.SETTINGS) }
    }
    if (!helper) PBtn("도움말", BS.SEC, Ics.HELP) { openSheet("help") }
}

@Composable
private fun Tile(icon: String, title: String, sub: String, modifier: Modifier, enabled: Boolean = true, onClick: () -> Unit) {
    val mod = modifier.heightIn(min = 122.dp).drop(2.dp, 10.dp, 0.05f, TileShape).background(Pleos.backgroundSecondary, TileShape).clip(TileShape)
    Column(if (enabled) mod.pressable(onClick = onClick).padding(horizontal = 14.dp, vertical = 12.dp) else mod.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.SpaceBetween) {
        Ic(icon, 26.dp, if (enabled) Pleos.textPrimary else Pleos.textTertiary)
        Spacer(Modifier.height(14.dp))
        Column {
            Text(title, style = W.t17, color = if (enabled) Pleos.textPrimary else Pleos.textTertiary)
            Text(sub, style = W.c13, color = Pleos.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** 시안 .tog: 켜고 끄는 칩·작은 버튼 */
@Composable
internal fun TogButton(text: String, icon: String?, on: Boolean, height: Dp = 40.dp, enabled: Boolean = true, onClick: () -> Unit) {
    val s = RoundedCornerShape(12.dp)
    val fg = if (on) Pleos.textPrimary else Pleos.textTertiary
    val base = Modifier.height(height).background(if (on) Color(0xFF44464E).copy(alpha = 0.12f) else Color.Transparent, s)
        .border(1.5.dp, if (on) Pleos.buttonFilled else Pleos.textPrimary.copy(alpha = 0.16f), s).clip(s)
    Row((if (enabled) base.pressable(onClick = onClick) else base).padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { Ic(it, if (height > 36.dp) 14.dp else 16.dp, fg) }
        Text(text, style = W.tog, color = fg)
    }
}

/** 시안 .pick: 받는 사람 칩 */
@Composable
internal fun PickChip(text: String, on: Boolean, icon: String? = null, onClick: () -> Unit) {
    val s = RoundedCornerShape(13.dp)
    Row(Modifier.height(44.dp).background(if (on) Pleos.buttonFilled else Pleos.buttonBasic, s).clip(s).pressable(onClick = onClick).padding(horizontal = 14.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        icon?.let { Ic(it, 18.dp, Pleos.textPrimary) }
        if (text.isNotEmpty()) Text(text, style = W.pick, color = if (on) Pleos.basic00 else Pleos.textPrimary)
    }
}

/** 연락 상대 추가·고치기 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ContactPage(d: HandoffData, index: Int?, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val list = remember(d.t) { ClaimStore.contacts(ctx, d) }
    val old = index?.let { list.getOrNull(it) }
    var type by rememberSaveable { mutableStateOf(old?.type ?: ContactType.OTHER) }
    var name by rememberSaveable { mutableStateOf(old?.name.orEmpty()) }
    var phone by rememberSaveable { mutableStateOf(old?.phone.orEmpty()) }
    var no by rememberSaveable { mutableStateOf(old?.no.orEmpty()) }
    fun save() {
        if (name.isBlank() && phone.isBlank()) return
        val c = CaseContact(type, name.trim(), phone.trim(), no.trim())
        ClaimStore.saveContacts(ctx, d.t, if (old != null) list.map { if (it === old) c else it } else list + c)
        if (old == null) ClaimStore.addLog(ctx, d.t, "${type.label} ${c.name.ifEmpty { c.phone }} 연락처를 넣었어요")
        if (c.no.isNotEmpty() && (type == ContactType.MINE || type == ContactType.RENT)) ClaimStore.check(ctx, d.t, "claimed", "${type.label} 접수 · ${c.no}")
        onDone()
    }
    Page(if (old == null) "연락 상대 추가" else "연락 상대", onBack = onDone, bottom = {
        Btn("저장", onClick = ::save)
        if (old != null) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (old.phone.isNotBlank()) Btn("전화 걸기", ButtonKind.SECONDARY, Modifier.weight(1f)) { dial(ctx, old.phone) }
            Btn("지우기", ButtonKind.SECONDARY, Modifier.weight(1f)) { ClaimStore.saveContacts(ctx, d.t, list.filter { it !== old }); onDone() }
        }
    }) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ContactType.entries.forEach { t -> PickChip(t.label, t == type) { type = t } }
        }
        Input("이름", name) { name = it }
        Input("전화번호", phone, KeyboardType.Phone) { phone = it }
        Input("접수번호 (있으면)", no) { no = it }
    }
}

/** 설정: 기록서에 넣을 내 정보, 연락처, 서버, 로그아웃 */
@Composable
internal fun SettingsPage(helper: Boolean, onBack: () -> Unit, onLogout: () -> Unit) {
    val ctx = LocalContext.current
    var owner by remember { mutableStateOf(ClaimStore.owner(ctx)) }
    fun set(o: ClaimOwner) { owner = o; ClaimStore.save(ctx, o) }
    fun v(s: String) = s.takeIf { it.isNotBlank() }
    var insurer by remember { mutableStateOf(ClaimStore.setting(ctx, "insurer_phone")) }
    var famName by remember { mutableStateOf(ClaimStore.setting(ctx, "family_name")) }
    var famPhone by remember { mutableStateOf(ClaimStore.setting(ctx, "family_phone")) }
    val notif = rememberNotifEnabled()
    Page("설정", onBack = onBack) {
        Card("미리 넣어 두면 빨라요", gap = 8.dp) {
            Input("보험사 번호", insurer, KeyboardType.Phone) { insurer = it; ClaimStore.setSetting(ctx, "insurer_phone", it) }
            if (!helper) { Input("가족 이름", famName) { famName = it; ClaimStore.setSetting(ctx, "family_name", it) }
                Input("가족 전화번호", famPhone, KeyboardType.Phone) { famPhone = it; ClaimStore.setSetting(ctx, "family_phone", it) } }
        }
        Card("기록서에 넣을 내 정보", gap = 8.dp) {
            Text("모두 선택이에요. 비워 두면 기록서에 손으로 적을 칸이 생겨요. 이 휴대폰에만 저장돼요.", style = T.small, color = Pleos.textSecondary)
            Input("이름", owner.name.orEmpty()) { set(owner.copy(name = v(it))) }
            Input("연락처", owner.phone.orEmpty(), KeyboardType.Phone) { set(owner.copy(phone = v(it))) }
            Input("차량번호", owner.plate.orEmpty()) { set(owner.copy(plate = v(it))) }
            Input("보험사 이름", owner.insurer.orEmpty()) { set(owner.copy(insurer = v(it))) }
            Input("증권번호", owner.policyNo.orEmpty()) { set(owner.copy(policyNo = v(it))) }
        }
        Card("알림", gap = 8.dp) {
            Text(if (notif) "사고 알림이 켜져 있어요." else "알림이 꺼져 있어 사고를 못 알려요.", style = T.body, color = if (notif) Pleos.textSecondary else Pleos.negative)
            QuietLink("알림 설정 열기") { openNotifSettings(ctx) }
        }
        ServerCard()
        QuietLink("로그아웃", onClick = onLogout)
    }
}

/** 사고 알림 전체 화면(시안 pAlert B안). 잠금 화면 위에 뜬다 */
class AlertActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(SystemBarStyle.dark(android.graphics.Color.TRANSPARENT), SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        Sync.load(this)
        setContent { PleosTheme { AlertScreen(onClose = ::finish, onOpen = ::openGuide) } }
    }

    private fun openGuide() {
        Notify.cancelAlert(this)
        val go = {
            startActivity(Intent(this, PhoneActivity::class.java).putExtra(Notify.EXTRA_GUIDE, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP))
            finish()
        }
        val km = getSystemService(KeyguardManager::class.java)
        if (km.isKeyguardLocked) km.requestDismissKeyguard(this, object : KeyguardManager.KeyguardDismissCallback() { override fun onDismissSucceeded() { go() } }) else go()
    }
}

@Composable
private fun AlertScreen(onClose: () -> Unit, onOpen: () -> Unit) {
    val ctx = LocalContext.current
    val s by Sync.state.collectAsState()
    val d = s?.incident
    val gone = d == null || s?.notAccident == true || (s?.closed == true && Sync.account(ctx) != Logic.FAMILY) // 그사이 차에서 "사고 아니에요"를 눌렀으면 닫는다
    LaunchedEffect(gone) { if (gone) onClose() }
    if (d == null) return
    val blink by rememberInfiniteTransition(label = "dot").animateFloat(1f, 0.25f, infiniteRepeatable(tween(600), RepeatMode.Reverse), label = "dot")
    val rise = remember { Animatable(0f) }
    LaunchedEffect(Unit) { rise.animateTo(1f, tween(700, easing = Pleos.easing)) }
    Column(Modifier.fillMaxSize().background(Color(0xFF17181C)).safeDrawingPadding()) {
        Column(Modifier.weight(1f).fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = topGap(88.dp, WindowInsets.statusBars.asPaddingValues().calculateTopPadding())), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).graphicsLayer { alpha = blink }.background(Color(0xFFD32016), CircleShape))
                Text("차에서 온 알림", style = W.t17, color = Color(0xFFD32016))
            }
            Spacer(Modifier.height(14.dp))
            Text("사고가 감지됐어요", style = W.h30.copy(fontSize = 36.sp, lineHeight = 46.sp), color = Color.White, textAlign = TextAlign.Center,
                modifier = Modifier.graphicsLayer { alpha = rise.value; translationY = (1 - rise.value) * 16.dp.toPx() })
            Spacer(Modifier.height(8.dp))
            Text(Notify.alertLine(d), style = W.b16.copy(fontSize = 17.sp, lineHeight = 26.sp), color = W.nightSub, textAlign = TextAlign.Center)
            Spacer(Modifier.height(44.dp))
            CaseCar(340.dp, d.impactDeg)
        }
        Box(Modifier.padding(start = 16.dp, end = 16.dp, bottom = 16.dp)) { PBtn("안내 보기", BS.WHITE, height = 64.dp, size = 20.sp, onClick = onOpen) }
    }
}
