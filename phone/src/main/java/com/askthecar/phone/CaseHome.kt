package com.askthecar.phone

import android.content.Context
import android.speech.tts.TextToSpeech
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.LineHeightStyle
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.askthecar.Ask
import com.askthecar.AskStrip
import com.askthecar.ButtonKind
import com.askthecar.CarAnswer
import com.askthecar.ClaimText
import com.askthecar.HandoffData
import com.askthecar.Pleos
import com.askthecar.PleosButton
import com.askthecar.PleosIcon
import com.askthecar.PleosIcons
import com.askthecar.LineArt
import com.askthecar.LineArts
import com.askthecar.boldMarks
import com.askthecar.pleosShadow
import com.askthecar.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

private const val SEP = " · "

/** 시안 3A 글자 크기. 휴대폰 412x915 기준 */
internal object S {
    private val full = LineHeightStyle(LineHeightStyle.Alignment.Center, LineHeightStyle.Trim.None) // 시안(CSS)처럼 첫 줄 위·끝 줄 아래 여백도 줄 높이에 넣는다
    val display = T.head.copy(fontSize = 34.sp, lineHeight = 44.sp, fontWeight = FontWeight.ExtraBold, lineHeightStyle = full)
    val huge = T.head.copy(fontSize = 40.sp, lineHeight = 52.sp, fontWeight = FontWeight.ExtraBold, lineHeightStyle = full)
    val title = T.head.copy(lineHeightStyle = full)
    val date = T.small.copy(fontSize = 15.sp, lineHeight = 22.sp, lineHeightStyle = full)
    val body = T.body.copy(lineHeight = 26.sp, lineHeightStyle = full)
    val big = T.body.copy(fontSize = 20.sp, lineHeight = 30.sp, lineHeightStyle = full)
    val note = T.small.copy(fontSize = 13.sp, lineHeight = 18.sp, lineHeightStyle = full)
    val line = T.body.copy(fontSize = 22.sp, lineHeight = 30.sp, lineHeightStyle = full) // 전화 원고의 앞뒤 문장
    val reading = T.body.copy(fontSize = 34.sp, lineHeight = 44.sp, lineHeightStyle = full) // 전화 원고의 지금 문장
}

/** 현장 뒤에 알아 둘 것(문구: docs/reports/사고 수칙 공식 원문 재확인.md 17번) */
internal val KNOW = listOf(
    "현장에서 큰돈을 요구하면 거절해도 돼요. 보험사에 알리지 않고 합의하지 마세요.",
    "가볍게 다쳤어도 병원에서 검사받고 의사 조언을 따라 주세요. 며칠 뒤 아프면 보험사에도 알려 주세요.",
    "염좌·타박상 치료가 8주를 넘을 것 같으면 보험사에 절차를 물어 주세요. 8주까지만 치료받을 수 있다는 뜻은 아니에요.",
)

/** 문자 접수 문장을 복사해 두고 전화 앱에 번호를 띄운다 */
private fun copyAndDial(ctx: Context, d: HandoffData, phone: String) { copyClaim(ctx, d); dial(ctx, phone) }

internal fun topGap(y: Dp, statusBar: Dp) = (y - statusBar).coerceAtLeast(4.dp)

/** 가족에게 맡기기 화면 3장 */
@Composable
internal fun HandOverScreen(d: HandoffData, family: com.askthecar.Contact, onMyself: () -> Unit) {
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val screen = maxHeight
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            Column(Modifier.fillMaxWidth().heightIn(min = screen)) { HandOver(d, family.name, family.phone, statusBar, onMyself) }
        }
    }
}

/** 고령자: 먼저 가족에게 맡길지부터. 맡기기 → 무엇이 가는지 확인 → 보냄 (CasePages.kt) */
@Composable
private fun ColumnScope.HandOver(d: HandoffData, name: String, phone: String, statusBar: Dp, onMyself: () -> Unit) {
    val ctx = LocalContext.current
    val (who, person) = Logic.family(name)
    val scope = rememberCoroutineScope()
    var page by rememberSaveable(d.t) { mutableIntStateOf(if (ClaimStore.flag(ctx, "handed", d.t)) 2 else 0) }
    var busy by remember { mutableStateOf(false) }
    var failed by remember { mutableStateOf(false) }
    fun send(on: Boolean, done: () -> Unit) {
        if (busy) return
        busy = true; failed = false
        ClaimStore.setFlag(ctx, "handed", d.t, on) // 보내는 중에 화면을 벗어나도 취소 화면이 남게, 실패하면 되돌린다
        val c = Sync.client(ctx)
        scope.launch {
            val ok = withContext(Dispatchers.IO) { runCatching { c?.handover(d.t, on) == true }.getOrDefault(false) }
            busy = false
            if (ok) done() else { ClaimStore.setFlag(ctx, "handed", d.t, !on); failed = true }
        }
    }
    if (page == 1) { HandOverConfirm(person, statusBar, busy, failed, { send(true) { page = 2 } }, { page = 0 }); return }
    if (page == 2) { HandOverDone(person, statusBar, failed, { dial(ctx, phone) }, { send(false) { page = 0 } }); return }
    // 시안 "어르신용 안심": 체크와 함께 음성 한 번("탄성 없이 천천히, 음성과 함께"). 한국어 음성이 없는 휴대폰이면 조용히 넘어간다
    if (d.hash != null) DisposableEffect(d.t) {
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx) { ok -> if (ok == TextToSpeech.SUCCESS && (t?.setLanguage(Locale.KOREAN) ?: -1) >= TextToSpeech.LANG_AVAILABLE) t?.speak("기록은 저장했어요", TextToSpeech.QUEUE_FLUSH, null, "calm") }
        onDispose { t.shutdown() }
    }
    Spacer(Modifier.height(topGap(64.dp, statusBar)))
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("${who}에게\n맡길까요?", style = S.huge)
        Spacer(Modifier.height(24.dp))
        Text((if (d.hash != null) "기록은 저장했어요." else if (Logic.recordLost(d)) "차에서 기록을 받지 못했어요." else "기록을 저장하고 있어요.") + "\n연락과 자료 전달은 $person${Logic.subject(person)} 도와줄 수 있어요.", style = S.big, color = Pleos.textSecondary)
    }
    Box(Modifier.padding(top = 66.dp, bottom = 62.dp).fillMaxWidth().height(160.dp), contentAlignment = Alignment.Center) {
        if (d.hash != null) LineArt(LineArts.CALM, Modifier.size(160.dp), animate = true)
    }
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        BigBtn("${person}께 맡기기", true, 88.dp, 24.sp) { page = 1 }
        BigBtn("제가 직접 할게요", false, 88.dp, 24.sp, onClick = onMyself)
        Text("보험금 청구 권한은 넘어가지 않아요", style = S.big, modifier = Modifier.padding(top = 12.dp, bottom = 24.dp))
    }
}

/** 고령자용 큰 버튼(PleosButton 규격 밖의 72·88dp) */
@Composable
internal fun BigBtn(text: String, filled: Boolean, height: Dp, size: androidx.compose.ui.unit.TextUnit, icon: String? = null, onClick: () -> Unit) {
    val fg = if (filled) Pleos.basic00 else Pleos.textPrimary
    Box(Modifier.fillMaxWidth().heightIn(min = height).clip(Pleos.shapeM).pressable(onClick = onClick).background(if (filled) Pleos.buttonFilled else Pleos.buttonBasic, Pleos.shapeM).padding(horizontal = 16.dp, vertical = 8.dp), contentAlignment = Alignment.Center) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            icon?.let { PleosIcon(it, fg, Modifier.size(24.dp)) }
            Text(text, style = T.title.copy(fontSize = size, lineHeight = size * 1.3f), color = fg)
        }
    }
}

/** 아래쪽 유리 도크. 유리 ③: 흐림 없이 92% + 반짝임 테두리 + L2 (시각 스타일 확정) */
@Composable
internal fun GlassDock(content: @Composable RowScope.() -> Unit) =
    Row(dockStyle().height(72.dp).padding(8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp), content = content)

/** 버튼 글자가 길어 나란히 못 놓을 때 */
@Composable
internal fun GlassDockStack(content: @Composable ColumnScope.() -> Unit) =
    Column(dockStyle().padding(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), content = content)

@Composable
private fun dockStyle(): Modifier {
    val navBar = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    return Modifier.padding(start = 20.dp, end = 20.dp, bottom = topGap(60.dp, navBar)).fillMaxWidth()
        .pleosShadow(2, Pleos.shapeM).background(Pleos.backgroundPopup.copy(alpha = 0.92f), Pleos.shapeM)
        .border(1.dp, Brush.verticalGradient(listOf(Color.White.copy(alpha = if (Pleos.dark) 0.12f else 0.9f), Color.Transparent)), Pleos.shapeM)
}

/**
 * 시안 4A: 접수 전화에서 지금 읽을 문장 하나. 앱은 통화 음성을 들을 수 없어 사용자가 눌러 넘긴다.
 * 일반 앱은 전화를 끊을 수 없고 통화 상태도 권한 없이는 모르므로, 시안의 끊기 자리는 전화 앱을 여는 버튼, "통화 중" 자리는 "전화 원고"
 */
@Composable
internal fun CallScript(d: HandoffData, onDone: () -> Unit) {
    val ctx = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view) { view.keepScreenOn = true; onDispose { view.keepScreenOn = false } } // 읽는 동안 화면이 꺼지지 않게
    val lines = remember(d) { Logic.script(d, ClaimStore.owner(ctx)) } // 원고를 연 뒤 도착한 기록도 반영
    val who = Logic.callee(d) ?: ClaimStore.insurerContact(ctx) // 차에서 받은 번호가 없으면 휴대폰 설정의 번호
    val place = who?.name?.removeSuffix(" (가상)") ?: if (d.rental) "렌터카 회사" else "보험사"
    var at by rememberSaveable(d.t) { mutableIntStateOf(0) }
    var no by rememberSaveable(d.t) { mutableStateOf(ClaimStore.caseNo(ctx, d.t)) }
    var copied by remember(d.t) { mutableStateOf(false) }
    val last = at == lines.lastIndex
    val ink = Pleos.textPrimary
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()

    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(topGap(64.dp, statusBar)))
            Text("$place${SEP}전화 원고", style = S.body, color = Pleos.textSecondary, modifier = Modifier.padding(horizontal = 20.dp))
            Spacer(Modifier.height(146.dp))
            AnimatedContent(at, label = "line", transitionSpec = {
                (fadeIn(tween(250, delayMillis = 150)) + slideInVertically(tween(300, delayMillis = 100)) { it / 8 }) togetherWith fadeOut(tween(150))
            }) { i ->
                val line = lines.getOrNull(i) ?: return@AnimatedContent // 보는 중 더 짧은 원고의 새 사고가 오면 사라지는 쪽이 옛 번호를 든다
                Column(Modifier.fillMaxWidth().padding(horizontal = 20.dp)) {
                    Box(Modifier.fillMaxWidth().height(64.dp)) {
                        if (i > 0) Text(lines[i - 1].replace("*", ""), style = S.line, color = Pleos.textTertiary, maxLines = 2, overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.pressable { at = i - 1 }) // 잘못 넘겼을 때 돌아오기
                        else Text("연결되면 스피커를 켜고 돌아와요.", style = S.line, color = Pleos.textTertiary)
                    }
                    Text(boldMarks(line), style = S.reading)
                    Spacer(Modifier.height(12.dp))
                    val drawn = remember { Animatable(0f) }
                    LaunchedEffect(Unit) { drawn.animateTo(1f, tween(250, delayMillis = 150)) }
                    Box(Modifier.fillMaxWidth(drawn.value).height(3.dp).background(ink))
                    Spacer(Modifier.height(21.dp))
                    lines.getOrNull(i + 1)?.let { Text(it.replace("*", ""), style = S.line, color = Pleos.textTertiary, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                }
            }
        }
        AnimatedVisibility(last, enter = slideInVertically(tween(250)) { it } + fadeIn(tween(250))) {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Input("접수번호를 적어 두세요", no) { no = it; ClaimStore.setCaseNo(ctx, d.t, it) }
                if (ClaimText.place(d) != null) QuietLink(if (copied) "복사했어요. 문자 앱에 붙여 넣으세요." else "위치가 담긴 문자 문장 복사") { copied = copyClaim(ctx, d) }
            }
        }
        GlassDock {
            who?.let { c ->
                Box(
                    Modifier.size(56.dp).clip(Pleos.shapeS).pressable { copyAndDial(ctx, d, c.phone) }.background(Pleos.buttonBasic, Pleos.shapeS)
                        .semantics { contentDescription = "전화 앱 열기" },
                    contentAlignment = Alignment.Center,
                ) { PleosIcon(PleosIcons.PHONE, ink, Modifier.size(22.dp)) }
            }
            PleosButton(if (last) "접수 끝냈어요" else "다음 문장", ButtonKind.PRIMARY, large = false, Modifier.weight(1f), compact = true) {
                if (!last) at++ else { ClaimStore.markClaimed(ctx, d, no); onDone() }
            }
        }
    }
}

/** 시안 5A 휴대폰판(사용자 선택 1안): 답과 시간 띠를 보며 아래 버튼으로 질문을 바꾼다. 통화 중일 수 있어 소리는 눌렀을 때만 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun AskPage(d: HandoffData, ask: Ask, onPick: (Ask) -> Unit, onRecord: () -> Unit) {
    val ctx = LocalContext.current
    val a = remember(d, ask) { CarAnswer.of(ask, d.samples, d.t, d.controlsRecorded, d.steerRecorded, d.lampsRecorded, d.hash != null, d.facts) }
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    var tts by remember { mutableStateOf<TextToSpeech?>(null) } // 한국어 음성이 없는 휴대폰이면 null 이라 듣기 버튼을 숨긴다
    DisposableEffect(Unit) {
        var t: TextToSpeech? = null
        t = TextToSpeech(ctx) { ok -> if (ok == TextToSpeech.SUCCESS && (t?.setLanguage(Locale.KOREAN) ?: -1) >= TextToSpeech.LANG_AVAILABLE) tts = t }
        onDispose { t.shutdown() }
    }
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(topGap(64.dp, statusBar)))
            Text("\"${ask.quoted}\"", style = S.body, color = Pleos.textTertiary)
            Spacer(Modifier.height(8.dp))
            Box(Modifier.heightIn(min = 176.dp)) { Text(boldMarks(a.text), style = S.reading) }
            Spacer(Modifier.height(16.dp))
            Text(a.source.orEmpty(), style = S.date.copy(fontFeatureSettings = "tnum"), color = Pleos.textSecondary)
            Spacer(Modifier.height(18.dp))
            AskStrip(d.samples, d.t, a.at, a.source == null, 3.dp, 12.dp, Modifier.fillMaxWidth().height(200.dp).semantics { contentDescription = "시간에 따른 속도와 그 순간" })
            Spacer(Modifier.height(26.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Ask.entries.forEach { q ->
                    val on = q == ask
                    Box(Modifier.height(44.dp).clip(Pleos.shapeS).pressable { onPick(q) }.background(if (on) Pleos.buttonFilled else Pleos.buttonBasic, Pleos.shapeS).padding(horizontal = 16.dp), contentAlignment = Alignment.Center) {
                        Text(q.chip, style = S.date.copy(fontWeight = FontWeight.Bold), color = if (on) Pleos.basic00 else Pleos.textPrimary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
        }
        GlassDock {
            tts?.let { t -> PleosButton("듣기", ButtonKind.SECONDARY, large = false, Modifier.weight(1f), compact = true) { t.speak(a.plain, TextToSpeech.QUEUE_FLUSH, null, "ask") } }
            PleosButton("기록에서 보기", ButtonKind.SECONDARY, large = false, Modifier.weight(1f), compact = true, onClick = onRecord)
        }
    }
}
