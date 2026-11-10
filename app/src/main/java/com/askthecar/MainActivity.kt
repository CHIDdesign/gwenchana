package com.askthecar

import androidx.compose.ui.draw.clip
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.material3.Surface
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import kotlinx.coroutines.delay
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 앱을 열면 나오는 홈(CarHome.kt, 사고 카드)과 설정. 평소 기록은 CarService 가 뒤에서 하고, 사고 때 CrashActivity 가 뜬다.
 */
class MainActivity : ComponentActivity() {
    private val askPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        if (Consent.allowed(Consent.Item.DRIVE)) CarService.start(this)
    }

    /** "다른 앱 위에 표시"가 꺼져 있으면 설정을 열 때 먼저 알린다(없으면 사고 화면이 저절로 뜨지 않음) */
    private var overlayNotice by mutableStateOf(false)
    /** 권한 설정 화면에서 돌아오면 나머지 권한을 묻는다 */
    private var askOnResume = false

    /** 설정을 바로 열어 온 경우(동의 직후·시연 프로필). 닫으면 홈이 아니라 앱을 닫는다 */
    private var settingsOnly = false
    private var settings by mutableStateOf(false)
    private var resumes by mutableIntStateOf(0)

    override fun onResume() {
        super.onResume()
        resumes++ // 돌아오면 사고 목록을 다시 읽는다
        if (askOnResume) { askOnResume = false; askPermissions.launch(permissions()) }
    }

    @Composable
    private fun OverlayNotice() = Dialog(onDismissRequest = {}, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PleosPopup("사고 화면을 띄우는 권한이 필요해요", "'다른 앱 위에 표시'를 켜 주세요. 켜지 않으면 사고가 나도 안내 화면이 저절로 뜨지 않고 알림과 음성으로만 알려 드려요.") {
            PleosButton("권한 설정 열기", ButtonKind.PRIMARY, modifier = Modifier.weight(1f)) {
                overlayNotice = false
                val opened = runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) }.isSuccess
                if (opened) askOnResume = true else askPermissions.launch(permissions())
            }
            PleosButton("나중에", ButtonKind.SECONDARY, modifier = Modifier.weight(1f)) { overlayNotice = false; askPermissions.launch(permissions()) }
        }
    }

    /** 권한 최소: 동의한 항목에 필요한 권한만 묻는다 */
    private fun permissions() = listOfNotNull(
        "android.car.permission.CAR_SPEED", "android.permission.POST_NOTIFICATIONS", "android.permission.READ_PHONE_STATE",
        CarService.LOCATION.takeIf { Consent.allowed(Consent.Item.LOCATION) },
        "android.permission.RECORD_AUDIO".takeIf { Consent.allowed(Consent.Item.VOICE) },
        "android.permission.CAMERA".takeIf { Consent.allowed(Consent.Item.CAMERA) },
    ).toTypedArray()

    /** 사고 때 바로 전화하고 안내 방식을 맞추는 데 쓰는 정보. 일반 앱은 Pleos 프로필을 읽을 수 없어 한 번 입력받는다. 바꾸면 바로 저장 */
    @Composable
    private fun MyInfoSection() {
        var i by remember { mutableStateOf(Personas.info(this)) }
        fun update(n: MyInfo) { i = n; Personas.saveInfo(this, n) }
        Intro("한 번만 입력하면 돼요. 사고가 나면 바로 전화할 수 있게 안내에 넣어 드려요. 비워 두면 그 단계는 나오지 않아요.")
        // 왼쪽: 고르는 것(안내 방식·차량), 오른쪽: 입력하는 것(연락처)
        Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                SettingCard("안내 방식", "사고가 났을 때 안내를 어떻게 보여 드릴지 골라요.") {
                    for ((m, t, d) in listOf(Triple(GuideMode.DETAIL, "상세", "용어 풀이까지 자세히"), Triple(GuideMode.BRIEF, "간결", "핵심만 짧게"), Triple(GuideMode.VOICE, "음성", "큰 글자와 음성 위주")))
                        Choice(t, d, i.mode == m, Modifier.fillMaxWidth()) { update(i.copy(mode = m)) }
                }
                SettingCard("차량", "렌터카면 렌터카 회사에 알리는 단계가 더해져요.") {
                    Choice("내 차", null, !i.rental, Modifier.fillMaxWidth()) { update(i.copy(rental = false)) }
                    Choice("렌터카·카셰어링", null, i.rental, Modifier.fillMaxWidth()) { update(i.copy(rental = true)) }
                }
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ContactCard("보험사", "사고 접수를 안내할 때 이 번호로 전화해요.", "예: 한결손해보험", i.insurerName, i.insurerPhone, { update(i.copy(insurerName = it)) }) { update(i.copy(insurerPhone = it)) }
                if (i.rental) ContactCard("렌터카 회사", "계약서에 적힌 사고 접수 번호를 넣어 주세요.", "계약서의 회사 이름", i.rentalName, i.rentalPhone, { update(i.copy(rentalName = it)) }) { update(i.copy(rentalPhone = it)) }
                ContactCard("보호자", "사고 소식을 알릴 가족이나 지인이에요.", "예: 어머니", i.guardianName, i.guardianPhone, { update(i.copy(guardianName = it)) }) { update(i.copy(guardianPhone = it)) }
            }
        }
    }

    /** 설정 묶음 하나: 제목 + 짧은 도움말 + 내용 */
    @Composable
    private fun SettingCard(title: String, help: String?, content: @Composable ColumnScope.() -> Unit) =
        PleosCard(Modifier.fillMaxWidth(), gap = 20.dp) {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(title, style = Pleos.titleS.copy(fontWeight = FontWeight.Bold))
                help?.let { Text(it, style = Pleos.bodyS, color = Pleos.textTertiary) }
            }
            content()
        }

    /** 카드 위쪽 한 줄 설명 */
    @Composable
    private fun Intro(text: String) = Text(text, style = Pleos.bodyM, color = Pleos.textSecondary)

    /** 고르는 칸: 고르면 진하게(주요 버튼과 같은 색), 아니면 흰 칸 + 얇은 테두리 */
    @Composable
    private fun Choice(title: String, detail: String?, selected: Boolean, modifier: Modifier, onClick: () -> Unit) {
        val fg = if (selected) Pleos.basic00 else Pleos.textPrimary
        Row(
            modifier.pressable(role = Role.RadioButton, onClick = onClick).defaultMinSize(minHeight = 88.dp)
                .clip(Pleos.shapeM).background(if (selected) Pleos.buttonFilled else Pleos.backgroundPrimary, Pleos.shapeM)
                .border(1.dp, if (selected) Pleos.buttonFilled else Pleos.divider, Pleos.shapeM).padding(horizontal = 28.dp, vertical = 14.dp),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp),
        ) {
            Text(title, style = Pleos.bodyL, color = fg)
            detail?.let { Text(it, style = Pleos.bodyS, color = fg.copy(alpha = 0.64f), modifier = Modifier.weight(1f)) }
        }
    }

    @Composable
    private fun ContactCard(title: String, help: String, hint: String, name: String, phone: String, onName: (String) -> Unit, onPhone: (String) -> Unit) =
        SettingCard(title, help) {
            InfoField(hint, name, false, Modifier.fillMaxWidth(), onName)
            InfoField("전화번호", phone, true, Modifier.fillMaxWidth(), onPhone)
        }

    @Composable
    private fun InfoField(hint: String, value: String, number: Boolean, modifier: Modifier, onChange: (String) -> Unit) {
        BasicTextField(
            value, onChange, modifier.background(Pleos.backgroundPrimary, Pleos.shapeS).border(1.dp, Pleos.outline, Pleos.shapeS).padding(horizontal = 24.dp, vertical = 22.dp), singleLine = true,
            textStyle = Pleos.bodyL.copy(color = Pleos.textPrimary), cursorBrush = SolidColor(Pleos.textPrimary),
            keyboardOptions = KeyboardOptions(keyboardType = if (number) KeyboardType.Phone else KeyboardType.Text),
            decorationBox = { inner -> Box { if (value.isEmpty()) Text(hint, style = Pleos.bodyL, color = Pleos.textQuaternary); inner() } },
        )
    }

    /** 동의 철회·기록 삭제 (개인정보 보호법·앱마켓 심사에 필요). 끄면 확인창 뒤 사고 기록 전체 삭제, 필수를 끄면 감지도 멈춤 */
    @Composable
    private fun ConsentSection() {
        val state = remember { mutableStateMapOf<Consent.Bundle, Boolean>().apply { Consent.Bundle.entries.forEach { put(it, it.allowed()) } } }
        var withdraw by remember { mutableStateOf<Consent.Bundle?>(null) }
        var wipe by remember { mutableStateOf(false) }
        Intro("동의를 끄거나 저장된 사고 기록을 지울 수 있어요.")
        SettingCard("동의 항목", "끄면 저장된 사고 기록이 모두 지워져요.") {
            Consent.Bundle.entries.forEachIndexed { k, b ->
                if (k > 0) Box(Modifier.fillMaxWidth().height(1.dp).background(Pleos.divider))
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("${b.title} (${if (b.required) "필수" else "선택"})", style = Pleos.bodyL)
                        Text(b.summary, style = Pleos.bodyS, color = Pleos.textTertiary)
                    }
                    Switch(checked = state[b] == true, onCheckedChange = { on ->
                        if (!on) withdraw = b
                        else { Consent.set(this@MainActivity, b, true); state[b] = true; askPermissions.launch(permissions()) }
                    })
                }
            }
        }
        SettingCard("사고 기록 지우기", "저장된 사고 기록·사진·진술을 한 번에 지워요.") {
            PleosButton("사고 기록 모두 지우기", ButtonKind.SECONDARY, large = false) { wipe = true }
        }
        withdraw?.let { b ->
            Confirm("${b.title} 동의를 철회할까요?",
                "저장된 사고 기록이 모두 바로 삭제돼요." + if (b.required) " 주행 기록과 사고 감지도 멈춰요." else "",
                onDismiss = { withdraw = null }) {
                Consent.set(this@MainActivity, b, false); state[b] = false; withdraw = null
                if (b.required) Personas.clearInfo(this@MainActivity) // 연락처도 함께 지운다
            }
        }
        if (wipe) Confirm("사고 기록을 모두 지울까요?", "저장된 사고 기록·사진·진술이 모두 삭제되고 되돌릴 수 없어요.", onDismiss = { wipe = false }) {
            Consent.deleteRecords(this@MainActivity); wipe = false
        }
    }

    @Composable
    private fun Confirm(title: String, text: String, onDismiss: () -> Unit, onOk: () -> Unit) = Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        PleosPopup(title, text) {
            PleosButton("지우기", ButtonKind.EMERGENCY, modifier = Modifier.weight(1f), onClick = onOk)
            PleosButton("취소", ButtonKind.SECONDARY, modifier = Modifier.weight(1f), onClick = onDismiss)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Consent.init(this)
        if (!Consent.allowed(Consent.Item.DRIVE)) { ConsentActivity.show(this); return finish() }
        // 앱 아이콘: 사고 안내 중이면 그 화면(하던 단계)으로, 게임 연동 중이면 내비로. 홈은 내비의 톱니(EXTRA_HOME)
        settingsOnly = intent.getBooleanExtra(EXTRA_SETTINGS, false)
        settings = settingsOnly
        if (!settingsOnly && !intent.getBooleanExtra(EXTRA_HOME, false) && CarService.running != null) {
            if (CrashFlow.screenOpen) {
                startActivity(Intent(this, CrashActivity::class.java).putExtra(CrashActivity.EXTRA_RESUME, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                return finish()
            }
            if (Sim.connected) { Sim.link?.openHome(this); return finish() }
        }
        overlayNotice = !Settings.canDrawOverlays(this)
        if (!overlayNotice) askPermissions.launch(permissions())
        setContent { PleosTheme { Surface(Modifier.fillMaxSize(), color = Pleos.backgroundPrimary) {
            val moving by produceState(false) { while (true) { value = isMoving(); delay(300) } }
            BackHandler(settings && !settingsOnly) { settings = false }
            when {
                moving -> DrivingHome { finish() }
                settings -> Screen()
                else -> CarHome(resumes, onSettings = { settings = true }, onExit = { finish() })
            }
            if (overlayNotice) OverlayNotice()
        } } }
    }

    /** 속도 1 이상 또는 P 아님. 서비스가 꺼져 있으면 차 값이 없어(기본값 D) 주행으로 보지 않는다 */
    private fun isMoving() = CarService.running != null && CarState.now.let { it.speedKph >= 1 || it.gear != "P" }

    @Composable
    private fun Screen() {
        val tabs = listOf("내 정보", "사고 기록", "개인정보 보호", "정보")
        var tab by remember { mutableStateOf(0) }
        Row(Modifier.fillMaxSize()) {
            Column(Modifier.width(400.dp).fillMaxHeight().padding(vertical = 24.dp)) {
                tabs.forEachIndexed { i, t -> MenuRow(t, i == tab, Role.Tab) { tab = i } }
                Spacer(Modifier.weight(1f))
                Box(Modifier.padding(horizontal = 32.dp).fillMaxWidth().height(1.dp).background(Pleos.divider))
                // 뒤로 가기 버튼이 없는 화면이라 닫을 곳이 필요하다. 하단 내비 버튼은 Pleos 기본 내비로 가 버린다
                MenuRow("닫기", false, Role.Button) { if (settingsOnly) finish() else settings = false }
            }
            Box(Modifier.width(1.dp).fillMaxHeight().background(Pleos.divider))
            Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(horizontal = 56.dp, vertical = 48.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(tabs[tab], style = Pleos.headlineS)
                when (tab) {
                    0 -> MyInfoSection()
                    1 -> {
                        Intro("사고가 감지되면 기록이 자동으로 저장돼요.")
                        SettingCard("최근 사고 기록", "재현·경위 설명문·사진을 보고 PDF 기록서로 공유할 수 있어요.") {
                            PleosButton("최근 사고 기록 보기", ButtonKind.PRIMARY, large = false) {
                                startActivity(Intent(this@MainActivity, CrashActivity::class.java).putExtra(CrashActivity.EXTRA_RECORD, true))
                            }
                        }
                    }
                    2 -> {
                        if (!Settings.canDrawOverlays(this@MainActivity)) SettingCard("사고 화면 띄우기", "사고 화면을 자동으로 띄우려면 '다른 앱 위에 표시' 권한이 필요해요.") {
                            PleosButton("권한 설정 열기", ButtonKind.PRIMARY, large = false) {
                                runCatching { startActivity(Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:$packageName"))) } // 그 설정 화면이 없는 차에서 앱이 꺼지지 않게
                            }
                        }
                        ConsentSection()
                    }
                    else -> SettingCard("오픈소스 라이선스", "원문은 앱 안 assets/licenses 에 있어요.") {
                        listOf("Asta Sans 글꼴 · OFL 1.1", "three.js · MIT", "MediaPipe · EfficientDet-Lite0 · AndroidX · Apache 2.0", "사람 모델 Kenney · CC0").forEach { Text(it, style = Pleos.bodyM, color = Pleos.textSecondary) }
                    }
                }
            }
        }
    }

    /** 왼쪽 메뉴 한 줄. 고른 탭은 진하게(설정 화면의 고른 칸과 같은 모양) */
    @Composable
    private fun MenuRow(text: String, selected: Boolean, role: Role, onClick: () -> Unit) =
        Box(Modifier.fillMaxWidth().height(100.dp).pressable(role = role, onClick = onClick).background(if (selected) Pleos.buttonFilled else Color.Transparent).padding(horizontal = 32.dp), contentAlignment = Alignment.CenterStart) {
            Text(text, style = Pleos.bodyL, color = if (selected) Pleos.basic00 else Pleos.textPrimary)
        }

    companion object {
        const val EXTRA_SETTINGS = "settings"
        /** 앱 아이콘과 달리 사고 화면·내비로 돌리지 않고 홈을 연다 */
        const val EXTRA_HOME = "home"
    }
}
