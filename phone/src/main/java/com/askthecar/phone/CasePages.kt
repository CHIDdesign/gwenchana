package com.askthecar.phone

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color as AColor
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.askthecar.ButtonKind
import com.askthecar.ClaimText
import com.askthecar.CrashKind
import com.askthecar.HandoffData
import com.askthecar.LineArt
import com.askthecar.LineArts
import com.askthecar.Pleos
import com.askthecar.PleosButton
import com.askthecar.PleosIcon
import com.askthecar.PleosIcons
import com.askthecar.pressable
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

private const val PORTAL_URL = "https://accident.knia.or.kr"
private const val UNSURE = "확인 못 함"
private val PageShape = RoundedCornerShape(8.dp)

/** 시안 "담당자가 과실을 말할 때". 앱은 판단하지 않고, 답을 미뤄도 된다는 것과 근거를 받는 방법만 알려 준다 */
@Composable
internal fun FaultPage(d: HandoffData, onAsk: () -> Unit) {
    val ctx = LocalContext.current
    val who = Logic.callee(d)
    val statusBar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    Column(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal = 20.dp)) {
            Spacer(Modifier.height(topGap(64.dp, statusBar)))
            // 시안: 들은 말을 먼저 짚어 준다. 앱은 실제 통화 내용을 모르므로 "들었다면"으로 예시임을 표시(기록 문서에는 들어가지 않는 화면 문구)
            Text("이런 말을 들었다면", style = S.body, color = Pleos.textSecondary)
            Text("\"주차선 밖이니 과실이 있을 수 있어요\"", style = T.head)
            Spacer(Modifier.height(40.dp))
            Text("지금 답하지\n않아도 돼요", style = S.display)
            Spacer(Modifier.height(8.dp))
            Text("과실 비율은 전화 한 통으로 정해지지 않아요. 이렇게 말해 보세요.", style = S.body, color = Pleos.textSecondary)
            Spacer(Modifier.height(24.dp))
            Box(Modifier.fillMaxWidth().height(3.dp).background(Pleos.textPrimary))
            Spacer(Modifier.height(18.dp))
            Text("\"근거를 문자로 보내 주세요. 제 보험사 담당자와 확인하고 답할게요.\"", style = S.line)
            Spacer(Modifier.height(28.dp))
            Line()
            Spacer(Modifier.height(12.dp))
            QuietLink("과실비율 공식 자료 보기") { runCatching { ctx.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(PORTAL_URL))) } }
            if (d.samples.isNotEmpty()) { Spacer(Modifier.height(8.dp)); QuietLink("차 기록에서 그 순간 보기", onClick = onAsk) } // 차 기록이 없는 사고엔 없다
            Spacer(Modifier.height(16.dp))
        }
        who?.let { c -> GlassDock { PleosButton(if (d.rental) "렌터카 회사에 전화" else "내 보험사에 전화", ButtonKind.PRIMARY, large = false, Modifier.weight(1f), compact = true, PleosIcons.PHONE) { dial(ctx, c.phone) } } }
    }
}

private class Built(val choice: SendChoice, val pdf: File, val uris: List<Uri>, val count: Int)

private fun pageCount(pdf: File) = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd -> PdfRenderer(fd).use { it.pageCount } }

/** PDF 한 쪽을 그림으로 (시안 폭 364dp를 또렷하게) */
private fun render(pdf: File, i: Int): ImageBitmap = ParcelFileDescriptor.open(pdf, ParcelFileDescriptor.MODE_READ_ONLY).use { fd ->
    PdfRenderer(fd).use { r ->
        r.openPage(i.coerceIn(0, r.pageCount - 1)).use { p ->
            val k = 1.6f
            val bmp = Bitmap.createBitmap((p.width * k).toInt(), (p.height * k).toInt(), Bitmap.Config.ARGB_8888)
            bmp.eraseColor(AColor.WHITE)
            p.render(bmp, null, Matrix().apply { setScale(k, k) }, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
            bmp.asImageBitmap()
        }
    }
}

/**
 * 기록 보내기(시안 pSendB). 지금 만든 PDF 실물을 보며 받는 사람과 넣을 것을 고른다. 차 기록 값은 뺄 수 없다.
 * 공유 창은 결과를 알려 주지 않아서 돌아오면 사용자에게 묻는다
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SendScreen(d: HandoffData, onBack: () -> Unit, onSent: () -> Unit, onAddContact: () -> Unit) {
    val ctx = LocalContext.current
    val rev by ClaimStore.rev.collectAsState()
    val owner = remember(rev) { ClaimStore.owner(ctx) }
    val types = remember(d, rev) { Logic.recipients(ClaimStore.contacts(ctx, d), d.rental) }
    val mineFirst = types.firstOrNull { it == ContactType.MINE || it == ContactType.RENT } ?: types.first() // 처음엔 내 쪽 담당자
    var to by rememberSaveable(d.t) { mutableStateOf(mineFirst) }
    if (to !in types) to = mineFirst
    var inc by remember(to) { mutableStateOf(Logic.includeFor(to)) }
    val askPlate = !d.kindChosen || d.kind == CrashKind.VEHICLE.label // 벽·사람과 부딪혔으면 상대 차 번호를 묻지 않는다
    var plate by rememberSaveable(d.t) { mutableStateOf(ClaimStore.note(ctx, "plate", d.t)) }
    var hurt by rememberSaveable(d.t) { mutableStateOf(ClaimStore.note(ctx, "hurt", d.t)) }
    val photoCount = remember(d.t) { claimPhotoCount(ctx, d) }
    val hasPlace = ClaimText.place(d) != null
    val choice = SendChoice(inc.photo, inc.stmt, inc.loc, inc.contact, inc.policy, if (askPlate) plate.ifBlank { UNSURE } else null, hurt.ifBlank { if (d.hurt) "있음" else UNSURE }, "${to.person} (${to.edition})")
    var page by rememberSaveable(d.t) { mutableIntStateOf(0) }
    var failed by remember { mutableStateOf(false) }
    val built by produceState<Built?>(null, choice, owner) { // 새 PDF 가 나올 때까지 옛 그림을 그대로 둔다
        failed = false
        val made = withContext(Dispatchers.IO) { runCatching { val (pdf, uris) = buildSend(ctx, d, owner, choice); Built(choice, pdf, uris, pageCount(pdf)) }.getOrNull() }
        if (made != null) value = made else if (isActive) failed = true
    }
    val b = built
    val count = b?.count ?: 1
    val at = page.coerceIn(0, count - 1)
    val image by produceState<ImageBitmap?>(null, b?.pdf, at) {
        val f = b?.pdf ?: return@produceState
        withContext(Dispatchers.IO) { runCatching { render(f, at) }.getOrNull() }?.let { value = it }
    }
    var fill by remember { mutableStateOf(false) }
    var asking by rememberSaveable { mutableStateOf(false) }
    val share = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { asking = true }
    var sheetH by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    val blanks = listOf(askPlate && plate.isBlank(), hurt.isBlank()).count { it }

    BoxWithConstraints(Modifier.fillMaxSize().background(if (Pleos.dark) Pleos.basic00 else Color(0xFFE4E5EA))) {
        val screenH = maxHeight
        Column(Modifier.fillMaxSize().statusBarsPadding()) {
            Row(Modifier.fillMaxWidth().height(48.dp).padding(start = 12.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Ics.BACK, "뒤로", onClick = onBack)
                Text("기록 보내기", style = W.t18, modifier = Modifier.weight(1f), textAlign = TextAlign.Center)
                Spacer(Modifier.width(44.dp))
            }
            val bar = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
            val room = screenH - bar - 48.dp - 6.dp - with(density) { sheetH.toDp() } - 8.dp
            val pageW = minOf(364.dp, (room * 560f / 792f).coerceAtLeast(120.dp))
            Box(Modifier.padding(top = 6.dp).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                Box(Modifier.size(pageW, pageW * 792f / 560f).drop(2.dp, 14.dp, 0.16f, PageShape).background(Color.White, PageShape).clip(PageShape)) {
                    image?.let { Image(it, "기록서 ${at + 1}쪽", Modifier.fillMaxSize(), contentScale = ContentScale.FillWidth, alignment = Alignment.TopCenter) }
                }
                Row(Modifier.padding(top = (pageW * 792f / 560f) * 0.42f).fillMaxWidth().padding(horizontal = 2.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                    if (at > 0) Arrow(Ics.BACK, "이전 쪽") { page = at - 1 } else Spacer(Modifier.size(44.dp))
                    if (at < count - 1) Arrow(Ics.CHEV, "다음 쪽") { page = at + 1 } else Spacer(Modifier.size(44.dp))
                }
            }
        }
        Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged { sheetH = it.height }.drop(0.dp, 30.dp, 0.14f, W.sheet).background(Pleos.backgroundPopup, W.sheet).navigationBarsPadding().padding(start = 16.dp, end = 16.dp, top = 18.dp, bottom = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("누구에게", style = W.t18, modifier = Modifier.weight(1f))
                Text("${at + 1} / ${count}쪽 · ${to.edition}", style = W.c14, color = Pleos.textTertiary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                types.forEach { t -> PickChip(t.label, t == to) { to = t; page = 0 } }
                PickChip("", false, Ics.PLUS, onClick = onAddContact)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                TogButton("차 기록", Ics.LOCK, true, enabled = false) {}
                if (photoCount > 0) Inc("사진 ${photoCount}장", inc.photo) { inc = inc.copy(photo = !inc.photo) }
                if (d.statement != null) Inc("내 진술", inc.stmt) { inc = inc.copy(stmt = !inc.stmt) }
                if (hasPlace) Inc("위치", inc.loc) { inc = inc.copy(loc = !inc.loc) }
                if (owner.phone != null) Inc("내 연락처", inc.contact) { inc = inc.copy(contact = !inc.contact) }
                if (owner.policyNo != null) Inc("증권번호", inc.policy) { inc = inc.copy(policy = !inc.policy) }
                TogButton(if (blanks > 0) "빈 칸 $blanks" else "빈 칸 고치기", null, false) { fill = true }
            }
            if (failed) Text("기록서를 만들지 못했어요. 항목을 다시 눌러 주세요.", style = W.c14, color = Pleos.negative)
            PBtn("${to.person}에게 보내기", BS.PRI, Ics.SEND) {
                val ready = b?.takeIf { it.choice == choice } ?: return@PBtn // 방금 바꾼 항목이 아직 PDF 에 안 들어갔으면 옛 파일을 보내지 않는다
                runCatching { share.launch(shareIntent(d, owner, choice, ready.uris)) }.onFailure { failed = true }
            }
        }
        if (fill) BottomSheet({ fill = false }) {
            Text("비어 있는 칸", style = W.h24)
            Text("모르면 '$UNSURE'으로 표시해 보낼 수 있어요.", style = W.b15, color = Pleos.textSecondary)
            if (askPlate) Input("상대 차 번호", plate) { plate = it; ClaimStore.setNote(ctx, "plate", d.t, it) }
            Text("다친 사람", style = T.small, color = Pleos.textTertiary)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                listOf("있음" to "있어요", "없음" to "없어요").forEach { (v, label) ->
                    PBtn(label, if (hurt == v) BS.PRI else BS.SEC, modifier = Modifier.weight(1f)) { hurt = v; ClaimStore.setNote(ctx, "hurt", d.t, v) }
                }
            }
            Spacer(Modifier.height(6.dp))
            PBtn("닫기", BS.SEC) { fill = false }
        }
        if (asking) BottomSheet(null) {
            Text("${to.person}에게 보냈나요?", style = W.h24)
            Text("공유 창은 결과를 알려 주지 않아서 직접 표시해요.", style = W.b15, color = Pleos.textSecondary)
            Spacer(Modifier.height(6.dp))
            PBtn("보냈어요", BS.PRI) { asking = false; ClaimStore.setFlag(ctx, "sent", d.t); ClaimStore.addLog(ctx, d.t, Logic.sentLog(to)); onSent() }
            PBtn("안 보냈어요", BS.SEC) { asking = false }
        }
    }
}

@Composable
private fun Inc(label: String, on: Boolean, onClick: () -> Unit) = TogButton(label, Ics.CHECK.takeIf { on }, on, onClick = onClick)

@Composable
private fun Arrow(d: String, label: String, onClick: () -> Unit) {
    val s = W.shape14
    Box(Modifier.size(44.dp).drop(2.dp, 8.dp, 0.2f, s).background(Color.White.copy(alpha = 0.92f), s).clip(s).pressable(onClick = onClick).semantics { contentDescription = label }, contentAlignment = Alignment.Center) { Ic(d, 24.dp, Color(0xFF131417)) }
}

/** 맡기기 2장째: 무엇이 가고 무엇이 안 가는지 보여 주고 사용자가 눌러야 보낸다(가족은 미리 등록한 사람만) */
@Composable
internal fun ColumnScope.HandOverConfirm(person: String, statusBar: androidx.compose.ui.unit.Dp, busy: Boolean, failed: Boolean, onSend: () -> Unit, onBack: () -> Unit) {
    Spacer(Modifier.height(topGap(64.dp, statusBar)))
    Column(Modifier.padding(horizontal = 20.dp)) {
        Text("이렇게\n맡겨요", style = S.huge)
        Spacer(Modifier.height(28.dp))
        listOf("사고 시각·위치, 차 기록, 경위서, 진술, 연락처를 $person 휴대폰으로 보내요", "보험사 연락과 자료 전달을 도와줄 수 있어요", "보험금 청구 권한은 넘어가지 않아요", "언제든 맡기기를 취소할 수 있어요").forEachIndexed { i, s ->
            if (i > 0) Line()
            Row(Modifier.fillMaxWidth().padding(vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                PleosIcon(PleosIcons.DONE, Pleos.textPrimary, Modifier.size(28.dp))
                Text(s, style = S.big)
            }
        }
    }
    Spacer(Modifier.weight(1f))
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (failed) Text("보내지 못했어요. 연결을 확인하고 다시 눌러 주세요.", style = S.big, color = Pleos.negative)
        BigBtn(if (busy) "보내는 중" else "보내기", true, 88.dp, 24.sp, onClick = onSend)
        BigBtn("돌아가기", false, 88.dp, 24.sp, onClick = onBack)
        Spacer(Modifier.height(24.dp))
    }
}

/** 맡기기 3장째 */
@Composable
internal fun ColumnScope.HandOverDone(person: String, statusBar: androidx.compose.ui.unit.Dp, failed: Boolean, onCall: () -> Unit, onCancel: () -> Unit) {
    Spacer(Modifier.height(topGap(64.dp, statusBar)))
    Column(Modifier.padding(horizontal = 20.dp)) {
        LineArt(LineArts.FAMILY, Modifier.size(120.dp), animate = true)
        Spacer(Modifier.height(16.dp))
        Text("${person}에게\n보냈어요", style = S.huge)
        Spacer(Modifier.height(24.dp))
        Text("이제 연락과 자료 전달을 도와줄 수 있어요.\n보험금 청구 권한은 넘어가지 않아요.", style = S.big, color = Pleos.textSecondary)
    }
    Spacer(Modifier.weight(1f))
    Column(Modifier.padding(horizontal = 20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        if (failed) Text("취소하지 못했어요. 연결을 확인하고 다시 눌러 주세요.", style = S.big, color = Pleos.negative)
        BigBtn("${person}께 전화하기", true, 88.dp, 24.sp, PleosIcons.PHONE, onClick = onCall)
        BigBtn("맡기기 취소", false, 88.dp, 24.sp, onClick = onCancel)
        Spacer(Modifier.height(24.dp))
    }
}
