package com.askthecar

import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawOutline
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import android.os.SystemClock
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.vector.PathParser
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.intl.LocaleList
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.ExperimentalTextApi
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Pleos Connect 디자인 토큰. 출처: 프로젝트 `design/Hyundai design rule/` (Colors·Typography·Buttons·Popup·System Notification PDF,
 * 2026-10-29 PyMuPDF로 값 추출). 라이트·다크는 시스템(차의 낮/밤 설정)을 따른다.
 * 글꼴 Asta Sans(42dot, OFL 1.1, res/font/asta_sans.ttf, 굵기 300~800).
 */
object Pleos {
    /** 지금 다크 테마인지. PleosTheme 이 시스템 설정으로 정한다(테마가 바뀌면 화면이 다시 만들어짐) */
    var dark = true
    private fun c(light: Long, dark: Long) = Color(if (this.dark) dark else light)

    // basic scale — 라이트와 다크가 서로 뒤집힌다 (Colors.pdf Grayscale)
    val basic00 get() = c(0xFFFFFFFF, 0xFF131417)
    val basic50 get() = c(0xFFF7F8FA, 0xFF1D1E21)
    val basic100 get() = c(0xFFEDEEF2, 0xFF313236)
    val basic200 get() = c(0xFFE0E1E6, 0xFF44464E)
    val basic300 get() = c(0xFFC7C9CE, 0xFF686A72)
    val basic400 get() = Color(0xFFA4A8AE)
    val basic500 get() = c(0xFF686A72, 0xFFC7C9CE)
    val basic600 get() = c(0xFF44464E, 0xFFE0E1E6)
    val basic700 get() = c(0xFF313236, 0xFFEDEEF2)

    // 표면 3겹(docs/reports/시각 스타일 확정): 0층 stage·rail, 1층 sheet, 2층 유리·popup
    val backgroundPrimary get() = c(0xFFF7F8FA, 0xFF1D1E21) // window: 세로 창·휴대폰 바닥 (Pleos background_primary)
    val backgroundSecondary get() = c(0xFFFFFFFF, 0xFF131417) // sheet: 기록지, 내용 판 (Pleos background_secondary)
    val stageTop get() = c(0xFFFFFFFF, 0xFF1D1E21) // 왼쪽 패널 바닥 표본: 위 흰색
    val stageMiddle get() = c(0xFFF1F1F1, 0xFF1D1E21) // 창 y 290dp부터
    val stageBottom get() = c(0xFFEEEEEE, 0xFF131417) // 지평선 바로 위
    val rail get() = c(0xFFFFFFFF, 0xFF131417) // 지평선 아래 줄 (왼쪽 패널 미디어 줄과 같음)
    val horizon get() = if (dark) Color.White.copy(alpha = 0.12f) else Color(0xFFE0E0E0)
    val carStageTop get() = stageTop
    val carStageBottom get() = stageBottom
    val backgroundPopup get() = if (dark) basic100 else basic00

    // 글자: 불투명 값이라 어느 바닥에서도 대비가 같다. 대비 계산은 시각 스타일 확정 문서
    private val ink get() = if (dark) Color.White else Color(0xFF131417)
    val textPrimary get() = ink
    val textSecondary get() = c(0xFF44464E, 0xFFE0E1E6)
    val textTertiary get() = c(0xFF686A72, 0xFFC7C9CE) // 가장 옅은 읽는 글자, Body M 이상만
    val textQuaternary get() = ink.copy(alpha = 0.32f) // 비활성 전용

    // button: filled = basic_600, basic = 글자색 5% (Buttons·Popup PDF)
    val buttonFilled get() = basic600 // 가이드 Filled(#44464E, 다크는 #E0E1E6): 주요 버튼·선택 상태
    val buttonBasic get() = ink.copy(alpha = 0.05f) // 가이드 Basic: 글자색 5% 판, 테두리 없음
    val outline get() = ink.copy(alpha = 0.64f) // 보조 버튼 테두리
    val divider get() = if (dark) Color.White.copy(alpha = 0.12f) else ink.copy(alpha = 0.10f)

    // 유채색은 증언색 하나. Pleos #FE3D16 은 흰 글자 대비 3.56 이라 Pleos 자신의 4.5 기준을 못 넘어 같은 계열로 어둡게 함
    // 다크는 같은 빨강을 밝게만(주황으로 넘어가지 않게, 검정 글자 대비 6.4). 3D 페이지(crash3d/index.html)·휴대폰 알림 아이콘도 같은 값
    val negative get() = c(0xFFD32016, 0xFFF2483A)
    val onNegative get() = c(0xFFFFFFFF, 0xFF131417)
    val emergency get() = Color(0xFFAE251F) // 창 전체를 칠하는 119·2차 사고 경고 화면 전용
    val positive get() = c(0xFF0064FF, 0xFF4AA3FF) // 글자 버튼 전용. stage 위 금지(#F1F1F1 위 4.36)
    val active get() = ink // 초록은 쓰지 않는다(전화·연결됨 포함)
    val textActive get() = ink
    /** 주황·노랑은 쓰지 않는다(승인된 보드: 의미 있는 곳에만 빨강 하나). 옛 이름은 증언색으로 모은다 */
    val textOrange get() = negative

    // typography (Typography.pdf, 단위 sp)
    @OptIn(ExperimentalTextApi::class) // 가변 글꼴 굵기 지정 API가 이 Compose 버전에선 실험 표시
    private fun asta(w: Int) = Font(R.font.asta_sans, FontWeight(w), variationSettings = FontVariation.Settings(FontVariation.weight(w)))
    val font = FontFamily(asta(400), asta(500), asta(700), asta(800))

    // 한국어를 글자 중간이 아니라 어절(띄어쓰기) 단위로 줄바꿈 (에뮬레이터에서 "조/각"처럼 끊기던 문제, API 33+). ko 고정: 휴대폰 언어가 영어여도 같게
    private val korean = LineBreak(LineBreak.Strategy.HighQuality, LineBreak.Strictness.Strict, LineBreak.WordBreak.Phrase)
    private val ko = LocaleList("ko-KR")
    // tnum: 숫자 폭을 같게 해 시각·속도 열이 흔들리지 않게 (Asta Sans GSUB 에 있음)
    private fun style(size: Int, line: Int, weight: FontWeight = FontWeight.Normal) =
        TextStyle(lineBreak = korean, localeList = ko, fontFamily = font, fontSize = size.sp, lineHeight = line.sp, fontWeight = weight, fontFeatureSettings = "tnum")
    val headlineL = style(60, 80, FontWeight.ExtraBold)
    val headlineM = style(56, 72, FontWeight.Bold)
    val headlineS = style(48, 64, FontWeight.Bold)
    val titleL = style(40, 52, FontWeight.Bold)
    val titleM = style(36, 44)
    val titleS = style(32, 40, FontWeight.Bold)
    val bodyL = style(30, 38)
    val bodyM = style(28, 36)
    val bodyS = style(26, 34)
    val labelM = style(24, 28)
    val labelS = style(20, 24)

    // motion
    val easing = CubicBezierEasing(0.16f, 1f, 0.3f, 1f)
    const val durationShort = 150
    const val durationMedium = 300

    // 모서리는 48(팝업), 40(기록지), 32(Large 버튼), 24(Medium), 20(80 아이콘 버튼), 16(Small·칩)만 쓴다
    val shapeXL: Shape = SquircleShape(48.dp)
    val shapeSheet: Shape = SquircleShape(40.dp, 0.dp, 0.dp, 40.dp) // 왼쪽 모서리만 둥글고 오른쪽은 창 끝까지 나간다
    val shapeL = RoundedCornerShape(32.dp)
    val shapeM = RoundedCornerShape(24.dp)
    val shapeIcon = RoundedCornerShape(20.dp)
    val shapeS = RoundedCornerShape(16.dp)
    val shapeXS = shapeS

    /** 앱 창 1700dp(가로 1앱) / 840dp(2앱 분할·세로). 이보다 좁으면 세로 배치 */
    val wideMinWidth = 1200.dp
}

@Composable
fun PleosTheme(content: @Composable () -> Unit) {
    val dark = isSystemInDarkTheme()
    Pleos.dark = dark
    // 낮/밤이 바뀌면(차는 해 질 녘 자동) 화면을 다시 만들지 않고(configChanges=uiMode, 안내 단계 유지) 색만 새로 그림:
    // Pleos 색은 상태가 아니라서 key 로 아래 전체를 다시 구성한다
    key(dark) {
        MaterialTheme(
            colorScheme = if (dark) darkColorScheme(background = Pleos.backgroundPrimary, surface = Pleos.backgroundPrimary, primary = Pleos.buttonFilled)
            else lightColorScheme(background = Pleos.backgroundPrimary, surface = Pleos.backgroundPrimary, primary = Pleos.buttonFilled),
        ) {
            // style= 만 주고 색을 안 준 Text 는 LocalContentColor(기본 검정)를 따름 → 다크에서 검은 글자가 묻혔음
            CompositionLocalProvider(
                LocalContentColor provides Pleos.textPrimary,
                LocalTextStyle provides LocalTextStyle.current.copy(fontFamily = Pleos.font, color = Pleos.textPrimary),
                content = content,
            )
        }
    }
}

/** 곡률이 서서히 변하는 모서리(기록지·팝업·유리 막대 전용). 코너마다 p = r*1.6, 제어점 0.155p */
class SquircleShape(private val tl: Dp, private val tr: Dp = tl, private val br: Dp = tl, private val bl: Dp = tl) : Shape {
    override fun createOutline(size: Size, layoutDirection: LayoutDirection, density: Density): Outline {
        fun span(r: Dp) = with(density) { minOf(r.toPx() * 1.6f, size.minDimension / 2) }
        val k = 0.155f
        val a = span(tl); val b = span(tr); val c = span(br); val d = span(bl)
        val w = size.width; val h = size.height
        return Outline.Generic(Path().apply {
            moveTo(a, 0f)
            lineTo(w - b, 0f); cubicTo(w - k * b, 0f, w, k * b, w, b)
            lineTo(w, h - c); cubicTo(w, h - k * c, w - k * c, h, w - c, h)
            lineTo(d, h); cubicTo(k * d, h, 0f, h - k * d, 0f, h - d)
            lineTo(0f, a); cubicTo(0f, k * a, k * a, 0f, a, 0f)
            close()
        })
    }
}

/** 왼쪽 패널의 수평선(화면 y). shortcut: 에뮬레이터 캡처 표본, 실차 패널 위치를 확인하면 바꾼다 */
private val PANEL_HORIZON = 1120.dp

/**
 * 왼쪽 패널과 한 바닥으로 이어지는 차 화면 바닥. 위는 패널과 같은 밝기, 지평선 아래는 흰 줄(rail).
 * content 는 지평선 높이(창 위에서부터)를 받아 그 기준으로 배치한다.
 */
@Composable
fun StageFloor(content: @Composable BoxWithConstraintsScope.(horizon: Dp) -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    var top by remember { mutableStateOf(90.dp) } // 앱 창이 화면에서 시작하는 위치
    BoxWithConstraints(Modifier.fillMaxSize().onGloballyPositioned {
        val at = IntArray(2); view.getLocationOnScreen(at)
        top = with(density) { at[1].toDp() }
    }) {
        val horizon = PANEL_HORIZON - top
        Box(Modifier.matchParentSize().drawBehind {
            val h = horizon.toPx().coerceIn(1f, size.height)
            val solid = (190.dp.toPx() / h).coerceAtMost(0.5f)
            val soft = (290.dp.toPx() / h).coerceIn(solid, 0.99f)
            drawRect(Pleos.rail)
            drawRect(Brush.verticalGradient(0f to Pleos.stageTop, solid to Pleos.stageTop, soft to Pleos.stageMiddle, 1f to Pleos.stageBottom, endY = h), size = Size(size.width, h))
            drawRect(Pleos.horizon, Offset(0f, h), Size(size.width, 2.dp.toPx()))
        })
        content(horizon)
    }
}

/** PRIMARY = 진한 filled(CTA), SECONDARY = 연한 basic(Sub action), EMERGENCY = 증언색, SAFE = 전화 걸기(초록을 쓰지 않아 filled 와 같다) */
enum class ButtonKind { PRIMARY, SECONDARY, EMERGENCY, SAFE }

/** 마지막으로 눌린 시각(앱 전체 하나): 화면이 바뀌어 같은 자리에 새 버튼이 와도 두 번 눌림으로 단계(119 등)를 건너뛰지 않게 */
private var lastTap = 0L

/**
 * 가이드 interaction: **모든 터치 요소**에 95% 축소 + 눌림 색, 손을 뗄 때 실행(영역 밖으로 나가면 취소), 햅틱.
 * pressedTint 는 요소 위에 덮는 색(글자까지 살짝 덮임 — 눌린 동안만).
 */
@Composable
fun Modifier.pressable(role: Role = Role.Button, pressedTint: Color = Pleos.textPrimary.copy(alpha = 0.08f), onClick: () -> Unit): Modifier {
    val source = remember { MutableInteractionSource() }
    val pressed by source.collectIsPressedAsState()
    val haptic = LocalHapticFeedback.current // 가이드: 터치하면 햅틱 또는 음성 피드백
    val scale by animateFloatAsState(if (pressed) 0.95f else 1f, tween(Pleos.durationShort, easing = Pleos.easing), label = "press")
    return this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .drawWithContent { drawContent(); if (pressed) drawRect(pressedTint) }
        .clickable(source, indication = null, role = role) {
            val now = SystemClock.uptimeMillis()
            if (now - lastTap < 300) return@clickable
            lastTap = now
            haptic.performHapticFeedback(HapticFeedbackType.LongPress); onClick()
        }
}

/**
 * 가이드 Basic button. large = 높이 100dp·글자 32 (Popup 큰 버튼), 아니면 80dp·글자 30 (System Notification 버튼).
 * compact = 휴대폰용(높이 56dp·글자 17, 차 큰 화면 크기는 휴대폰에 넘침).
 */
@Composable
fun PleosButton(text: String, kind: ButtonKind = ButtonKind.PRIMARY, large: Boolean = true, modifier: Modifier = Modifier, compact: Boolean = false, icon: String? = null, onClick: () -> Unit) {
    val (bg, fg) = when (kind) {
        ButtonKind.PRIMARY, ButtonKind.SAFE -> Pleos.buttonFilled to Pleos.basic00
        ButtonKind.SECONDARY -> Pleos.buttonBasic to Pleos.textPrimary
        ButtonKind.EMERGENCY -> Pleos.negative to Pleos.onNegative
    }
    // 눌림 색: 진한 버튼은 바탕색 쪽으로, 빨강은 조금 어둡게, 빈 버튼은 글자색 8%
    val tint = when (kind) {
        ButtonKind.PRIMARY, ButtonKind.SAFE -> Pleos.basic00.copy(alpha = 0.2f)
        ButtonKind.SECONDARY -> Pleos.textPrimary.copy(alpha = 0.08f)
        else -> Color.Black.copy(alpha = 0.16f)
    }
    val shape = if (compact) Pleos.shapeS else if (large) Pleos.shapeL else Pleos.shapeM
    Box(
        modifier
            .clip(shape)
            .pressable(pressedTint = tint, onClick = onClick)
            .defaultMinSize(minHeight = if (compact) 56.dp else if (large) 100.dp else 80.dp)
            .background(bg, shape)
            .padding(horizontal = if (compact) 16.dp else if (large) 48.dp else 32.dp, vertical = if (compact) 8.dp else 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            icon?.let { PleosIcon(it, fg, Modifier.size(if (compact) 19.dp else if (large) 40.dp else 24.dp)) }
            Text(text, style = (if (compact) Pleos.bodyL.copy(fontSize = 17.sp, lineHeight = 24.sp) else if (large) Pleos.titleS else Pleos.bodyL).copy(fontWeight = FontWeight.Bold), color = fg) // 가이드: 버튼 글자 Bold
        }
    }
}

/** Pleos 아이콘 규격(48 격자, 선 3.6, 둥근 끝)의 선 아이콘. d = SVG 경로 */
@Composable
fun PleosIcon(d: String, color: Color, modifier: Modifier = Modifier) {
    val path = remember(d) { PathParser().parsePathString(d).toPath() }
    Canvas(modifier) {
        val k = size.minDimension / 48f
        scale(k, k, Offset.Zero) { if (d == PleosIcons.PHONE) drawPath(path, color) else drawPath(path, color, style = Stroke(if (d == PleosIcons.DONE) 2.4f else 3.6f, cap = StrokeCap.Round, join = StrokeJoin.Round)) }
    }
}

object PleosIcons {
    const val BACK = "M29.4 10.8L16.2 24L29.4 37.2"
    const val MIC = "M17.4 12.6A6.6 6.6 0 0 1 30.6 12.6V22.2A6.6 6.6 0 0 1 17.4 22.2ZM10.2 23.4C10.2 31.8 16.2 36 24 36S37.8 31.8 37.8 23.4M24 36V42"
    const val DOC = "M12.6 6H28.2L36 13.8V42H12.6ZM18 24H30M18 31.2H26.4"
    const val DONE = "M44 24A20 20 0 1 1 4 24A20 20 0 1 1 44 24ZM14.4 24.6L21 31.2L33.6 17.4"
    /** 스피커(읽어 주기): 48 격자 선 아이콘 규칙대로 직접 그림 */
    const val SPEAKER = "M8.4 18.6H15.6L25.2 10.8V37.2L15.6 29.4H8.4ZM31.2 18C33.6 20.4 33.6 27.6 31.2 30M36.6 13.2C41.4 18 41.4 30 36.6 34.8"
    /** Pleos 전화 아이콘(공식 에셋, 면으로 채움) */
    const val PHONE = "M13.3454 32.1181C17.87 36.6427 21.6151 39.5317 27.1791 42.436C29.0287 43.3991 30.97 44.2398 32.9878 44.9276C35.644 45.8358 38.6438 45.6875 40.6472 43.7211C42.8115 41.5966 44.6697 38.796 45.401 36.2698C45.6064 35.5603 45.2886 34.8247 44.6944 34.386L34.8468 27.1143C34.0439 26.5214 32.9269 26.6112 32.229 27.3246L27.9587 31.6901C24.6875 29.5653 22.7615 28.266 19.9795 25.484C17.1975 22.702 15.8982 20.7913 13.7734 17.5048L18.1389 13.2345C18.8523 12.5366 18.9421 11.4196 18.3492 10.6167L11.0775 0.769137C10.6388 0.174936 9.90326 -0.142923 9.19375 0.0624842C6.66749 0.79385 3.86693 2.65202 1.74245 4.81635C-0.223992 6.81968 -0.372311 9.81955 0.535875 12.4758C1.22374 14.4935 2.06446 16.4195 3.02747 18.2844C5.93179 23.8484 8.8361 27.6088 13.3454 32.1181Z"
}

/** 누르는 것은 버튼으로 보인다(글자만 있는 링크 금지, 사용자 지적 2026-11-10). 터치 영역 80dp */
@Composable
fun PleosLink(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) = PleosButton(text, ButtonKind.SECONDARY, large = false, modifier = modifier, onClick = onClick)

/**
 * 가이드 System popup: 머리 아이콘(선택) → 제목 → 설명 → 버튼(최대 2개, 가로). large = 1200dp, 아니면 760dp
 * (폭 숫자는 docs/DESIGN_NOTES.md 기록, .fig 로 재확인 못 함). 아래 [below] 는 조용한 세 번째 선택(링크) 자리.
 */
@Composable
fun PleosPopup(
    title: String, description: String?, modifier: Modifier = Modifier, large: Boolean = false,
    icon: (@Composable () -> Unit)? = null, below: (@Composable () -> Unit)? = null, buttons: @Composable RowScope.() -> Unit,
) {
    Column(
        modifier.widthIn(max = if (large) 1200.dp else 760.dp).fillMaxWidth().pleosShadow(2, Pleos.shapeXL).background(Pleos.backgroundPopup, Pleos.shapeXL)
            .padding(start = 56.dp, end = 56.dp, top = 56.dp, bottom = if (below != null) 24.dp else 56.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        icon?.let { it(); Spacer(Modifier.height(28.dp)) }
        Text(title, style = Pleos.titleL.copy(fontWeight = FontWeight.Bold), textAlign = TextAlign.Center)
        description?.let { Spacer(Modifier.height(16.dp)); Text(it, style = Pleos.bodyL, color = Pleos.textSecondary, textAlign = TextAlign.Center) }
        Spacer(Modifier.height(48.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp), content = buttons)
        below?.let { Spacer(Modifier.height(8.dp)); it() }
    }
}

/**
 * 내용 묶음 하나 = 카드 하나(테두리·그림자·흐림 없음).
 * [label] 은 카드 맨 위 작은 머리글(예: "경위 설명문").
 */
@Composable
fun PleosCard(modifier: Modifier = Modifier, label: String? = null, padding: Dp = 32.dp, gap: Dp = 16.dp, shape: Shape = Pleos.shapeL, content: @Composable ColumnScope.() -> Unit) {
    Column(modifier.clip(shape).background(Pleos.backgroundSecondary, shape).padding(padding), verticalArrangement = Arrangement.spacedBy(gap)) {
        label?.let { Text(it, style = Pleos.labelM, color = Pleos.textTertiary) }
        content()
    }
}

/** Pleos 그림자 3단계. 다크에선 거의 안 보이는 게 정상 */
fun Modifier.pleosShadow(level: Int, shape: Shape): Modifier = drawBehind {
    fun layer(y: Dp, blur: Dp, alpha: Float) = drawIntoCanvas { canvas ->
        val paint = Paint().apply { color = Color.Transparent; asFrameworkPaint().setShadowLayer(blur.toPx(), 0f, y.toPx(), Color.Black.copy(alpha = alpha).toArgb()) }
        canvas.drawOutline(shape.createOutline(size, layoutDirection, this), paint)
    }
    when (level) {
        1 -> layer(6.dp, 24.dp, 0.08f)
        2 -> { layer(8.dp, 32.dp, 0.16f); layer(2.dp, 2.dp, 0.08f) }
        else -> { layer(20.dp, 40.dp, 0.24f); layer(2.dp, 4.dp, 0.20f) }
    }
}
