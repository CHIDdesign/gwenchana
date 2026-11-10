package com.askthecar.phone

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PersistableBundle
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import com.askthecar.ClaimExtra
import com.askthecar.ClaimOwner
import com.askthecar.ClaimPdf
import com.askthecar.ClaimText
import com.askthecar.Contact
import com.askthecar.HandoffData
import com.askthecar.Pleos
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/** 기록서에 넣을 휴대폰 주인 정보와 사고별 표시·연락 상대·일지. 이 휴대폰(SharedPreferences)에만 둔다 */
object ClaimStore {
    /** 사고별 값이 바뀔 때마다 오른다. 화면이 이걸 읽어 다시 그린다 */
    val rev = MutableStateFlow(0)
    private fun bump() = rev.update { it + 1 }
    private fun prefs(ctx: Context) = ctx.getSharedPreferences("claim_owner", Context.MODE_PRIVATE)

    fun owner(ctx: Context) = prefs(ctx).run {
        fun s(k: String) = getString(k, null)?.trim()?.takeIf { it.isNotEmpty() }
        ClaimOwner(s("name"), s("phone"), s("plate"), s("insurer"), s("policyNo"))
    }

    fun save(ctx: Context, o: ClaimOwner) { prefs(ctx).edit().putString("name", o.name).putString("phone", o.phone)
        .putString("plate", o.plate).putString("insurer", o.insurer).putString("policyNo", o.policyNo).apply(); bump() }

    /** 설정에 넣은 값(차에서 받은 번호가 없을 때 쓴다) */
    fun setting(ctx: Context, key: String) = prefs(ctx).getString(key, "")!!
    fun setSetting(ctx: Context, key: String, v: String) { prefs(ctx).edit().putString(key, v).apply(); bump() }
    fun insurerContact(ctx: Context) = setting(ctx, "insurer_phone").trim().takeIf { it.isNotEmpty() }?.let { Contact(owner(ctx).insurer ?: "내 보험사", it) }
    fun familyContact(ctx: Context) = setting(ctx, "family_phone").trim().takeIf { it.isNotEmpty() }?.let { Contact(setting(ctx, "family_name").trim().ifEmpty { "가족" }, it) }
    fun guardian(ctx: Context, d: HandoffData) = d.guardian ?: familyContact(ctx)

    fun clear(ctx: Context) { prefs(ctx).edit().clear().apply(); bump() }

    fun flag(ctx: Context, name: String, t: Double) = prefs(ctx).getBoolean("case_${name}_$t", false)
    fun setFlag(ctx: Context, name: String, t: Double, on: Boolean = true) { if (flag(ctx, name, t) != on) { prefs(ctx).edit().putBoolean("case_${name}_$t", on).apply(); bump() } }
    fun note(ctx: Context, name: String, t: Double) = prefs(ctx).getString("case_${name}_$t", "")!!
    fun setNote(ctx: Context, name: String, t: Double, v: String) { prefs(ctx).edit().putString("case_${name}_$t", v).apply(); bump() }
    fun caseNo(ctx: Context, t: Double) = note(ctx, "no", t)
    fun setCaseNo(ctx: Context, t: Double, no: String) = setNote(ctx, "no", t, no)
    fun clearCase(ctx: Context) { prefs(ctx).edit().apply { prefs(ctx).all.keys.filter { it.startsWith("case_") }.forEach { remove(it) } }.apply(); bump() }

    /** 한 번 넣으면 저장된 목록, 아직 안 건드렸으면 받은 번호로 만든 1장 */
    fun contacts(ctx: Context, d: HandoffData) = note(ctx, "contacts", d.t).takeIf { it.isNotEmpty() }?.let(Logic::decodeContacts) ?: Logic.seedContacts(d, insurerContact(ctx))
    fun saveContacts(ctx: Context, t: Double, l: List<CaseContact>) = setNote(ctx, "contacts", t, Logic.encodeContacts(l))

    fun log(ctx: Context, t: Double) = Logic.decodeLog(note(ctx, "log", t).ifEmpty { "[]" }).sortedByDescending { it.at }
    @Synchronized fun addLog(ctx: Context, t: Double, text: String, at: Double = System.currentTimeMillis() / 1000.0) =
        setNote(ctx, "log", t, Logic.encodeLog(Logic.decodeLog(note(ctx, "log", t).ifEmpty { "[]" }) + LogEntry(at, text)))

    /** 사람이 표시한 일 하나: 표시 + 일지 한 줄. 두 번 눌러도 한 줄 */
    fun check(ctx: Context, t: Double, name: String, log: String) { if (!flag(ctx, name, t)) { setFlag(ctx, name, t); addLog(ctx, t, log) } }

    /** 전화 원고 끝: 접수번호를 내 쪽 연락 상대에도 적는다 */
    fun markClaimed(ctx: Context, d: HandoffData, no: String) {
        val l = contacts(ctx, d)
        val i = l.indexOfFirst { it.type == ContactType.MINE || it.type == ContactType.RENT }
        if (i >= 0 && no.isNotBlank()) saveContacts(ctx, d.t, l.mapIndexed { k, c -> if (k == i) c.copy(no = no.trim()) else c })
        check(ctx, d.t, "claimed", (if (i >= 0) l[i].type.label else if (d.rental) "렌터카" else "내 보험사") + " 접수" + no.trim().takeIf { it.isNotEmpty() }?.let { " · $it" }.orEmpty())
    }

    /** 이 사고의 다음 할 일 */
    fun todo(ctx: Context, d: HandoffData, latest: Boolean, helper: Boolean): Todo = Logic.nextTodo(d, Marks(
        called119 = flag(ctx, "called119", d.t),
        sceneOpen = latest && !flag(ctx, "closed", d.t) && d.steps.isNotEmpty() && Logic.fresh(d),
        myself = flag(ctx, "self", d.t), card = flag(ctx, "card", d.t),
        claimed = flag(ctx, "claimed", d.t) || caseNo(ctx, d.t).isNotBlank() || contacts(ctx, d).any { it.no.isNotBlank() && (it.type == ContactType.MINE || it.type == ContactType.RENT) },
        sent = flag(ctx, "sent", d.t), canHandOver = !helper && guardian(ctx, d) != null,
    ), flag(ctx, "notAccident", d.t))

    /** 기록서 PDF 임시 파일(상대 기록이 들어 있을 수 있음)을 지운다. 보내는 앱이 읽는 동안에도 필요해서 공유 직후에는 부르지 않는다 */
    fun clearFiles(ctx: Context) { File(ctx.cacheDir, "claims").deleteRecursively() }
}

/** 문자 접수 문장 복사. 이름·번호·위치가 들어 있어 민감 표시(붙여 넣기 알림에 글자가 안 보임) */
internal fun copyClaim(ctx: Context, d: HandoffData, owner: ClaimOwner = ClaimStore.owner(ctx), noPlace: Boolean = false): Boolean {
    val cm = ctx.getSystemService(ClipboardManager::class.java) ?: return false
    cm.setPrimaryClip(ClipData.newPlainText("사고 접수", ClaimText.smsText(d, owner, noPlace)).apply {
        description.extras = PersistableBundle().apply { putBoolean("android.content.extra.IS_SENSITIVE", true) }
    })
    return true
}

/** 이 사고에 이 휴대폰으로 찍은 사진(장면마다 가장 최근 것, PhoneActivity 의 사진 안내와 같은 규칙) */
private fun photosFor(ctx: Context, d: HandoffData): List<File> {
    val dir = File(ctx.filesDir, "photos")
    val names = dir.list()?.toList().orEmpty()
    return d.shots.mapIndexedNotNull { i, s -> Logic.latestPhoto(names, i, s.art, (d.t * 1000).toLong(), Sync.photoUntil(d))?.let { File(dir, it) } }
}

internal fun claimPhotoCount(ctx: Context, d: HandoffData) = photosFor(ctx, d).size

/** 보내기 화면에서 고른 것. 차 기록 값은 항상 들어간다 */
internal data class SendChoice(val photos: Boolean, val statement: Boolean, val place: Boolean, val contact: Boolean, val policy: Boolean, val otherPlate: String?, val injured: String, val recipient: String)

private fun extraOf(c: SendChoice, o: ClaimOwner) = ClaimExtra(c.otherPlate, c.injured, noPlace = !c.place, noStatement = !c.statement,
    noContact = !c.contact && o.phone != null, noPolicy = !c.policy && o.policyNo != null, recipient = c.recipient) // 원래 없는 값을 "제외함"이라 적지 않는다

private fun chosen(d: HandoffData, c: SendChoice) = d.copy(statement = d.statement.takeIf { c.statement }, samples = if (c.place) d.samples else d.samples.map { it.copy(lat = null, lon = null) })
private fun chosen(o: ClaimOwner, c: SendChoice) = o.copy(phone = o.phone.takeIf { c.contact }, policyNo = o.policyNo.takeIf { c.policy })

private fun uriOf(ctx: Context, f: File) = FileProvider.getUriForFile(ctx, ctx.packageName + ".files", f)

/** 고른 것만 넣어 기록서를 새로 만든다. IO 스레드에서 */
internal fun buildSend(ctx: Context, d: HandoffData, owner: ClaimOwner, c: SendChoice): Pair<File, List<Uri>> {
    ClaimStore.clearFiles(ctx)
    val photos = if (c.photos) photosFor(ctx, d) else emptyList()
    // 만드는 일마다 다른 폴더: 토글을 빨리 바꿔도 늦게 끝난 옛 작업이 고른 뒤의 파일을 덮지 못하게
    val pdf = ClaimPdf.write(ctx, File(ctx.cacheDir, "claims/${System.nanoTime()}/AskTheCar_${ClaimText.recordNo(d)}.pdf"), chosen(d, c), null, photos, chosen(owner, c), extraOf(c, owner))
    return pdf to (listOf(pdf) + photos).map { uriOf(ctx, it) }
}

internal fun shareIntent(d: HandoffData, owner: ClaimOwner, c: SendChoice, uris: List<Uri>): Intent =
    Intent.createChooser(sendIntent(uris, ClaimText.emailSubject(d, chosen(owner, c)), ClaimText.smsText(chosen(d, c), chosen(owner, c), !c.place)), "사고 기록서 보내기")

private fun sendIntent(uris: List<Uri>, subject: String, text: String) =
    Intent(if (uris.size > 1) Intent.ACTION_SEND_MULTIPLE else Intent.ACTION_SEND).apply {
        if (uris.size > 1) { type = "*/*"; putParcelableArrayListExtra(Intent.EXTRA_STREAM, ArrayList(uris)) }
        else { type = "application/pdf"; putExtra(Intent.EXTRA_STREAM, uris.first()) }
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }

/** 두 차가 연결됐을 때: 나란히 넣은 기록서를 공유 창으로. other = 상대 차 기록 */
@Composable
internal fun TwoCarShare(mine: HandoffData, other: HandoffData) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var note by remember { mutableStateOf<String?>(null) }
    Btn(if (busy) "만드는 중…" else "두 차 기록서 보내기") {
        if (busy) return@Btn
        busy = true; note = null
        scope.launch {
            // 상대 쪽으로 나가는 기록서: 보내기 화면의 "상대 보험사"와 같은 기본 제외(진술·위치·연락처·증권번호·사진)
            val c = Logic.includeFor(ContactType.OTHER).let { SendChoice(it.photo, it.stmt, it.loc, it.contact, it.policy, null, if (mine.hurt) "있음" else "확인 못 함", "상대 차 운전자") }
            val full = ClaimStore.owner(ctx)
            val owner = chosen(full, c)
            val sent = chosen(mine, c)
            val uris = withContext(Dispatchers.IO) {
                runCatching {
                    ClaimStore.clearFiles(ctx)
                    val photos = if (c.photos) photosFor(ctx, mine) else emptyList()
                    (listOf(ClaimPdf.write(ctx, File(ctx.cacheDir, "claims/AskTheCar_${ClaimText.recordNo(mine)}.pdf"), sent, other, photos, owner, extraOf(c, full))) + photos).map { uriOf(ctx, it) }
                }.getOrNull().also { if (!isActive) ClaimStore.clearFiles(ctx) } // 만드는 사이 화면이 닫혔으면(연결 끊김 포함) 남기지 않는다
            }
            busy = false
            if (uris == null) note = "기록서를 만들지 못했어요. 다시 눌러 주세요."
            else runCatching { ctx.startActivity(Intent.createChooser(sendIntent(uris, ClaimText.emailSubject(sent, owner), ClaimText.smsText(sent, owner, !c.place)), "사고 기록서 보내기")) }.onFailure { note = "보낼 앱을 찾지 못했어요." }
        }
    }
    note?.let { Text(it, style = T.body, color = Pleos.textSecondary) }
}

@Composable
internal fun Input(label: String, value: String, type: KeyboardType = KeyboardType.Text, onChange: (String) -> Unit) = Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
    Text(label, style = T.small, color = Pleos.textTertiary)
    BasicTextField(value, onChange, singleLine = true, textStyle = T.body.copy(color = Pleos.textPrimary), cursorBrush = SolidColor(Pleos.textPrimary),
        keyboardOptions = KeyboardOptions(keyboardType = type),
        modifier = Modifier.fillMaxWidth().background(Pleos.backgroundPrimary, Pleos.shapeXS).border(1.dp, Pleos.divider, Pleos.shapeXS).padding(16.dp))
}
