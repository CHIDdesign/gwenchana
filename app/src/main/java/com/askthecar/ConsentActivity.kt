package com.askthecar

import androidx.compose.ui.draw.clip
import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.semantics.Role
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.unit.dp

/**
 * 첫 실행 동의 화면. 묶음마다 체크하고, 자세히 보기에서 항목별 수집 내용·목적·보관 기간 전문을 본다.
 * [동의하고 시작] 버튼을 눌렀을 때만 동의로 저장한다 (뒤로 가기·빈 곳 터치는 동의 아님).
 * 정차 중 첫 실행 화면이라 distractionOptimized 는 넣지 않음(주행 중엔 AAOS 가 막음).
 */
class ConsentActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Consent.init(this)
        setContent { PleosTheme { Surface(Modifier.fillMaxSize(), color = Pleos.backgroundPrimary) { Screen() } } }
    }

    @Composable
    private fun Screen() {
        val picks = remember { mutableStateMapOf<Consent.Bundle, Boolean>().apply { Consent.Bundle.entries.forEach { put(it, it.allowed()) } } }
        val open = remember { mutableStateMapOf<Consent.Bundle, Boolean>() }
        val ready = Consent.Bundle.entries.filter { it.required }.all { picks[it] == true }
        Column(Modifier.fillMaxSize().padding(horizontal = 56.dp, vertical = 40.dp), verticalArrangement = Arrangement.spacedBy(28.dp)) {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("개인정보 수집·이용 동의", style = Pleos.headlineM)
                Text("사고가 났을 때 사실을 기록하려면 아래 정보가 필요해요. 평소엔 최근 60초만 메모리에 두고 버리고, 사고가 감지됐을 때만 저장해요.",
                    style = Pleos.bodyL, color = Pleos.textSecondary)
            }
            val all = Consent.Bundle.entries.all { picks[it] == true }
            Column(Modifier.weight(1f).verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(16.dp)) {
                // 전체 동의는 테두리로 다른 묶음과 구분(맨 위 한 번에 고르는 칸)
                CheckRow("전체 동의", null, all, Modifier.clip(Pleos.shapeL).border(1.dp, Pleos.outline, Pleos.shapeL)) { on -> Consent.Bundle.entries.forEach { picks[it] = on } }
                Consent.Bundle.entries.forEach { b ->
                    PleosCard(Modifier.fillMaxWidth(), padding = 0.dp, gap = 0.dp) {
                        CheckRow(b.title, b, picks[b] == true, Modifier) { picks[b] = it }
                        Row(Modifier.fillMaxWidth().padding(start = 112.dp, end = 16.dp, bottom = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(b.summary, style = Pleos.bodyM, color = Pleos.textSecondary, modifier = Modifier.weight(1f))
                            Spacer(Modifier.width(16.dp))
                            PleosLink(if (open[b] == true) "접기" else "자세히 보기") { open[b] = open[b] != true }
                        }
                        if (open[b] == true) Column(
                            Modifier.padding(start = 112.dp, end = 32.dp, bottom = 32.dp).fillMaxWidth().background(Pleos.backgroundPrimary, Pleos.shapeM).padding(32.dp),
                            verticalArrangement = Arrangement.spacedBy(20.dp),
                        ) {
                            b.items.forEach { i ->
                                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                    Text(i.title, style = Pleos.titleS)
                                    Text("무엇을: ${i.what}\n왜: ${i.why}\n보관: ${i.keep}", style = Pleos.bodyS, color = Pleos.textSecondary)
                                }
                            }
                            Text(b.refuse, style = Pleos.bodyS)
                        }
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                Text(if (ready) "고른 항목은 설정 > 개인정보 보호에서 언제든 바꿀 수 있어요." else "필수 항목에 동의해야 시작할 수 있어요.",
                    style = Pleos.bodyM, color = if (ready) Pleos.textTertiary else Pleos.textOrange, modifier = Modifier.weight(1f))
                PleosButton("동의하지 않음", ButtonKind.SECONDARY) { finish() }
                PleosButton("동의하고 시작", if (ready) ButtonKind.PRIMARY else ButtonKind.SECONDARY, modifier = Modifier.width(480.dp)) {
                    if (!ready) return@PleosButton
                    picks.forEach { (b, on) -> Consent.set(this@ConsentActivity, b, on) }
                    next(this@ConsentActivity, intent.getStringExtra(EXTRA_NEXT))
                    finish()
                }
            }
        }
    }

    /** 체크 칸 + 제목(묶음이면 필수·선택 표시) */
    @Composable
    private fun CheckRow(title: String, bundle: Consent.Bundle?, checked: Boolean, modifier: Modifier, onChange: (Boolean) -> Unit) {
        Row(modifier.fillMaxWidth().pressable(role = Role.Checkbox) { onChange(!checked) }.padding(horizontal = 32.dp, vertical = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(32.dp)) {
            Box(Modifier.size(48.dp).background(if (checked) Pleos.buttonFilled else Color.Transparent, Pleos.shapeXS).border(1.dp, if (checked) Pleos.buttonFilled else Pleos.outline, Pleos.shapeXS), contentAlignment = Alignment.Center) {
                if (checked) Text("✓", style = Pleos.titleS, color = Pleos.basic00)
            }
            bundle?.let { Text(if (it.required) "필수" else "선택", style = Pleos.bodyM, color = if (it.required) Pleos.textOrange else Pleos.textTertiary) }
            Text(title, style = Pleos.titleM)
        }
    }

    companion object {
        const val EXTRA_NEXT = "next"
        const val NEXT_BOOT = "boot"

        fun show(ctx: Context, next: String? = null) = ctx.startActivity(
            Intent(ctx, ConsentActivity::class.java).putExtra(EXTRA_NEXT, next).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))

        /** 동의 뒤: 시동·부팅이면 바로 대기(기록 시작), 앱 아이콘이면 설정 화면(권한 요청 후 기록 시작). 권한을 물어야 하므로 실차 첫 실행은 설정 화면으로 간다 */
        private fun next(ctx: Context, next: String?) {
            if (next == NEXT_BOOT && Sim.link != null) { CarService.start(ctx); Sim.link?.onBoot(ctx) }
            else ctx.startActivity(Intent(ctx, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS, true).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        }
    }
}
