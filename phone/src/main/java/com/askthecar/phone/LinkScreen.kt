package com.askthecar.phone

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.askthecar.ButtonKind
import com.askthecar.LineArt
import com.askthecar.LineArts
import androidx.compose.foundation.layout.size
import com.askthecar.HandoffData
import com.askthecar.LinkClient
import com.askthecar.LinkHint
import com.askthecar.LinkState
import com.askthecar.Pleos
import com.askthecar.StepArt
import com.askthecar.StepHero
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** 이 화면들의 위 막대 제목 (단계마다 큰 제목은 따로) */
private const val TITLE = "두 차 기록 연결"

/**
 * 두 차 기록 연결: 상대 차도 이 앱을 쓰면, 두 사람 모두 코드로 확인하고 동의한 뒤 두 기록을 한 시간 축에 나란히 본다.
 * 앱은 증인: 비교·판단 문구 없이 각 차가 기록한 값만 보여 준다. 상대 기록은 화면과 보낼 기록서 PDF 임시 파일에만 있고, 철회·연결 끊김이면 사라진다.
 */
@Composable
fun LinkScreen(mine: HandoffData, onBack: () -> Unit) {
    val ctx = LocalContext.current
    val client = remember { Sync.account(ctx)?.let { LinkClient(Sync.server(ctx), it) } }
    val scope = rememberCoroutineScope()
    var st by remember { mutableStateOf<LinkState?>(null) }
    var hints by remember { mutableStateOf<List<LinkHint>>(emptyList()) }
    var entering by remember { mutableStateOf(false) }
    var msg by remember { mutableStateOf<String?>(null) }
    var wasLinked by remember { mutableStateOf(false) }
    var tick by remember { mutableStateOf(0) }
    var misses by remember { mutableStateOf(0) } // 연속으로 상태를 못 받은 횟수
    val lifecycle = LocalLifecycleOwner.current.lifecycle

    // 화면이 보이는 동안 2초마다 상태를 새로 받음(상대가 코드를 넣거나 동의·철회하면 바로 보이게)
    LaunchedEffect(client, tick) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (client != null) {
                val s = withContext(Dispatchers.IO) { client.status() }
                if (s != null) {
                    misses = 0
                    if (st?.state == LinkState.LINKED && s.state == LinkState.NONE) wasLinked = true
                    st = s
                    if (s.state == LinkState.NONE) hints = withContext(Dispatchers.IO) { client.candidates() }.orEmpty()
                } else misses++
                delay(2000)
            }
        }
    }
    // 철회됐는지 확인할 수 없는 동안에는 옛 상대 기록을 보여 주지 않는다
    val lost = misses >= 3
    val partnerShown = st?.partner != null && !lost
    // 철회가 확인됐을 때만 지운다. 통신이 잠깐 끊겼다고 지우면 공유 창에서 보내는 중인 파일이 사라진다
    LaunchedEffect(st?.partner != null) { if (st?.partner == null) withContext(Dispatchers.IO) { ClaimStore.clearFiles(ctx) } }

    fun act(block: (LinkClient) -> Boolean) = scope.launch {
        client ?: return@launch
        msg = if (withContext(Dispatchers.IO) { block(client) }) null else "처리하지 못했어요. 서버 연결을 확인하고 다시 해 주세요."
        tick++ // 바로 다시 받기
    }

    val s = st
    if (client == null || s == null) {
        Page(TITLE, onBack = onBack) {
            Text(when {
                client == null -> "먼저 로그인해 주세요."
                lost -> "서버에 연결하지 못했어요. 연결되면 바로 이어서 보여 드려요."
                else -> "연동 서버에 연결하는 중이에요…"
            }, style = T.body, color = Pleos.textSecondary)
        }
        return
    }
    when {
        lost && s.state == LinkState.LINKED -> Page(TITLE, onBack = onBack) {
            Heading("서버에 연결하지 못했어요", "연결되는 동안에는 상대 기록을 보여 주지 않아요. 다시 연결되면 이어서 보여 드려요.")
        }
        s.state == LinkState.CODE -> Page(TITLE, onBack = onBack, bottom = { QuietLink("코드 지우기") { act { it.revoke() } } }) {
            Heading("상대 휴대폰에 이 코드를 넣어 주세요",
                "상대 운전자가 자기 휴대폰의 괜차나 앱에서 이 코드를 넣으면 연결돼요. 연결된 뒤에도 두 분이 각자 동의해야 기록이 보여요.")
            Card("내 연결 코드") {
                Text(s.code.orEmpty().chunked(3).joinToString(" "), style = Pleos.headlineM.copy(fontSize = 48.sp, lineHeight = 60.sp, fontFamily = FontFamily.Monospace),
                    textAlign = TextAlign.Center, modifier = Modifier.fillMaxWidth().background(Pleos.backgroundPrimary, Pleos.shapeS).padding(vertical = 24.dp))
                Text("약 ${(s.expiresIn + 59) / 60}분 동안 쓸 수 있어요", style = T.small, color = Pleos.textTertiary)
            }
            Failed(msg)
        }
        s.state == LinkState.LINKED && !s.meAgreed -> Consent(msg, onBack, onAgree = { act { it.consent() } }, onDecline = { act { it.revoke() } })
        s.state == LinkState.LINKED && s.partner == null -> Page(TITLE, onBack = onBack, bottom = { QuietLink("연결 끊기 (동의 철회)") { act { it.revoke() } } }) {
            LineArt(LineArts.FAMILY, Modifier.size(72.dp), animate = false, until = 0) // 점선 그대로 = 상대 동의 대기
            Heading("상대의 동의를 기다리고 있어요", "상대 운전자도 동의하면 두 기록이 나란히 보여요.")
            Failed(msg)
        }
        s.state == LinkState.LINKED -> Merged(mine, s.partner!!, msg, onRevoke = { act { it.revoke() } }, onBack = onBack)
        entering -> EnterCode(msg, onCancel = { entering = false; msg = null }) { code ->
            scope.launch {
                val r = withContext(Dispatchers.IO) { client.claim(code) }
                msg = when (r) {
                    200 -> null
                    404 -> "코드가 맞지 않거나 시간이 지났어요. 상대에게 새 코드를 받아 주세요."
                    409 -> "이 휴대폰에 받은 사고가 없거나, 두 사고 시각이 너무 떨어져 있어요."
                    429 -> "너무 많이 틀렸어요. 5분 뒤에 다시 해 주세요."
                    400 -> "내 코드예요. 상대 휴대폰에 나온 코드를 넣어 주세요."
                    else -> "서버에 연결하지 못했어요. 잠시 뒤 다시 해 주세요."
                }
                if (r == 200) entering = false
                tick++
            }
        }
        else -> Page(TITLE, onBack = onBack, bottom = {
            Btn("내 코드 보여 주기") { act { it.makeCode() != null } }
            Btn("상대 코드 입력하기", ButtonKind.SECONDARY) { entering = true; msg = null }
        }) {
            LineArt(LineArts.FAMILY, Modifier.size(72.dp), animate = false, until = 0)
            Heading("상대 차도 이 앱을 쓰나요?", "두 차가 각자 남긴 주행 기록을 같은 시각에 맞춰 나란히 볼 수 있어요. 보험 처리 때 함께 보여 줄 수 있어요.")
            Failed(msg)
            if (wasLinked) Card { Text("연결이 끊겼어요. 상대 기록은 이제 보이지 않아요.", style = T.body, color = Pleos.textOrange) }
            Card("이렇게 연결돼요", gap = 8.dp) {
                Text("두 분이 코드로 서로 확인하고, 각자 동의해야만 연결돼요. 판단하지 않고 기록만 보여 줘요.", style = T.body)
                if (hints.isNotEmpty()) Text("비슷한 시각·장소(${hints.maxOf { it.withinM }}m 안)에 기록된 사고가 ${hints.size}건 있어요. 연결은 코드를 주고받아야만 돼요.",
                    style = T.small, color = Pleos.textTertiary)
            }
        }
    }
}

@Composable
private fun EnterCode(msg: String?, onCancel: () -> Unit, onSubmit: (String) -> Unit) {
    var text by remember { mutableStateOf("") }
    Page(TITLE, onBack = onCancel, bottom = {
        Btn("연결하기") { LinkLogic.cleanCode(text)?.let(onSubmit) }
        QuietLink("취소", onClick = onCancel)
    }) {
        Heading("상대 코드 입력", "상대 휴대폰에 나온 6자리 숫자를 넣어 주세요.")
        BasicTextField(text, { text = it.filter(Char::isDigit).take(6) }, singleLine = true,
            textStyle = Pleos.titleL.copy(fontFamily = FontFamily.Monospace, color = Pleos.textPrimary, textAlign = TextAlign.Center), cursorBrush = SolidColor(Pleos.textPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
            modifier = Modifier.fillMaxWidth().border(1.dp, Pleos.outline, Pleos.shapeS).padding(vertical = 24.dp, horizontal = 16.dp))
        msg?.let { Text(it, style = T.body, color = Pleos.textOrange) }
    }
}

/** 개인정보보호법 제17조(제3자 제공)에 맞춰 알릴 것: 받는 사람·목적·항목·보관 기간·거부할 권리. 위치 관련 세부 요건은 미확인(변호사 확인 필요) */
@Composable
private fun Consent(msg: String?, onBack: () -> Unit, onAgree: () -> Unit, onDecline: () -> Unit) = Page(TITLE, onBack = onBack, bottom = {
    Btn("동의하고 보여 주기", onClick = onAgree)
    Btn("동의하지 않음 (연결 끊기)", ButtonKind.SECONDARY, onClick = onDecline)
}) {
    Heading("상대에게 내 기록을 보여 줄까요?", "상대와 연결됐어요. 동의해야 내 기록이 상대에게 보여요. 상대도 따로 동의해야 상대 기록이 보여요.")
    Failed(msg)
    Card(gap = 0.dp) {
        listOf(
            "받는 사람" to "이번 사고의 상대 운전자 (코드로 연결한 분). 상대가 보험사에 보내는 기록서 PDF에 함께 들어갈 수 있어요.",
            "목적" to "보험 처리 때 두 차 기록을 함께 보기",
            "보여 주는 것" to "사고 시각, 부딪힌 대상(운전자가 골랐는지 포함), 충격 방향, 고속도로 여부, 기록 지문, 충돌 전후 시각별 주행 기록(속도·조향·차 회전·브레이크·방향지시등·비상등·에어백 상태), 위치",
            "보여 주지 않는 것" to "진술, 연락처, 사진, 경위 설명문, 안내 단계",
            "보관 기간" to "연결을 끊을 때까지. 지금 시제품은 PC 서버 메모리에만 있고 서버를 끄면 사라져요. 상대 휴대폰에는 기록서 PDF를 보낼 때만 임시 파일로 남고, 연결이 끊긴 것을 확인하거나 앱을 새로 열 때 지워져요. 이미 보낸 기록서는 되돌릴 수 없어요.",
            "거부·철회" to "동의하지 않아도 앱의 다른 기능은 그대로 쓸 수 있어요. 동의한 뒤에도 언제든 '연결 끊기'로 철회할 수 있어요.",
        ).forEachIndexed { k, (key, v) ->
            if (k > 0) Line()
            Column(Modifier.padding(top = if (k > 0) 16.dp else 0.dp, bottom = if (k < 5) 16.dp else 0.dp)) {
                Text(key, style = T.small, color = Pleos.textTertiary)
                Text(v, style = T.body)
            }
        }
    }
}

/** 두 기록 나란히: 요약(시각·충격 방향·대상) + 1초마다 한 줄(내 차 / 상대 차 / 두 차 거리) */
@Composable
private fun Merged(mine: HandoffData, other: HandoffData, msg: String?, onRevoke: () -> Unit, onBack: () -> Unit) = Page(TITLE, "나란히 보기", onBack) {
    Text("이 화면은 판단하지 않아요. 두 차가 각자 기록한 값을 같은 시각에 맞춰 놓았어요.", style = T.body, color = Pleos.textSecondary)

    @Composable
    fun line(a: String, b: String, c: String, d: String? = null, head: Boolean = false) = Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        val st = T.small.copy(color = if (head) Pleos.textTertiary else Pleos.textPrimary)
        Text(a, style = T.small, color = Pleos.textTertiary, modifier = Modifier.width(64.dp))
        Text(b, style = st, modifier = Modifier.weight(1f))
        Text(c, style = st, modifier = Modifier.weight(1f))
        if (d != null) Text(d, style = st, modifier = Modifier.width(48.dp))
    }

    Card("요약", gap = 8.dp) {
        line("", "내 차", "상대 차", head = true)
        Line()
        line("충격 기록", Logic.time(mine.t), Logic.time(other.t))
        line("충격 방향", LinkLogic.impactWord(mine.impactDeg), LinkLogic.impactWord(other.impactDeg))
        line("부딪힌 대상", mine.kind, other.kind)
        line("기록 지문", mine.hash ?: "저장 중", other.hash ?: "저장 중")
        val gap = LinkLogic.impactGap(mine, other)
        Text("상대 차는 내 차보다 %.1f초 %s 충격을 기록했어요. 두 차 시계 차이가 섞여 있을 수 있어요.".format(kotlin.math.abs(gap), if (gap >= 0) "뒤에" else "먼저"),
            style = T.small, color = Pleos.textTertiary, modifier = Modifier.padding(top = 8.dp))
    }

    Card("시각별 기록 (내 차 충돌 기준)", gap = 8.dp) {
        if (mine.samples.isEmpty() || other.samples.isEmpty()) Text("한쪽 기록이 아직 저장 중이에요. 사고 후 약 10초 뒤에 나와요.", style = T.body, color = Pleos.textSecondary)
        else {
            line("시각", "내 차", "상대 차", "거리", head = true)
            Line()
            LinkLogic.rows(mine, other).forEach { r ->
                line(if (r.sec == 0) "충돌" else "%+d초".format(r.sec), LinkLogic.cell(r.mine, mine.steerRecorded), LinkLogic.cell(r.other, other.steerRecorded), r.gapM?.let { "${it.toInt()}m" } ?: "-")
            }
        }
        Text("거리는 두 차 GPS 위치로 잰 값이라 몇 미터 어긋날 수 있어요. 상대 기록은 휴대폰에 따로 저장하지 않아요. 기록서 PDF를 보낼 때만 임시 파일로 남고, 연결이 끊긴 것을 확인하거나 앱을 새로 열 때 지워요.",
            style = T.small, color = Pleos.textTertiary, modifier = Modifier.padding(top = 8.dp))
    }

    TwoCarShare(mine, other) // 이 안의 기록서에 두 차 기록이 나란히 들어감 (제목은 ClaimActions의 "보험 접수" 하나만)
    Failed(msg)
    QuietLink("연결 끊기 (동의 철회)", onClick = onRevoke)
}

/** 버튼을 눌렀는데 서버가 처리하지 못했을 때 한 줄 */
@Composable
private fun Failed(msg: String?) { msg?.let { Text(it, style = T.body, color = Pleos.textOrange) } }
