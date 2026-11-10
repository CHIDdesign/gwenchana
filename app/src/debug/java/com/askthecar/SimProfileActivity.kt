package com.askthecar

import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color as AColor
import android.graphics.Paint
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 게임 시연 전용: 상단 동그라미를 누르면 뜨는 프로필 메뉴. 실제 메뉴(에뮬레이터 캡처, 양산차 시승 영상)와 같은 자리·모양에 시연 운전자 3명을 넣은 것.
 * 일반 앱은 Pleos 프로필을 못 읽고 못 바꾸므로 흉내다. src/debug 에만 있어 APK 에 안 들어간다.
 */
class SimProfileActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setFinishOnTouchOutside(true)
        // 앱 창 기준 위치. 값은 실제 메뉴 캡처에서 잼(폭 518, 오른쪽 25, 위 22). 이 에뮬레이터는 밀도 1
        window.setLayout(518, WindowManager.LayoutParams.WRAP_CONTENT)
        window.setGravity(Gravity.TOP or Gravity.END)
        window.attributes = window.attributes.apply { x = 25; y = 22 }
        val now = Personas.load(this) ?: Personas.guest
        setContent {
            PleosTheme {
                Column(Modifier.fillMaxWidth().background(Color.White).border(1.dp, LINE)) {
                    (Personas.all + Personas.guest).forEach { p -> Person(p, p.id == now.id) }
                    Box(Modifier.fillMaxWidth().height(1.dp).background(LINE))
                    if (CarState.now.speedKph < 5) // 달리는 동안엔 설정 화면으로 못 가게
                        Row(Modifier.fillMaxWidth().height(106.dp).clickable {
                            startActivity(Intent(this@SimProfileActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_SETTINGS, true)); finish()
                        }.padding(horizontal = 30.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("프로필 설정", style = Pleos.bodyM, color = INK)
                            Spacer(Modifier.weight(1f))
                            Text("›", style = Pleos.titleM, color = INK)
                        }
                }
            }
        }
    }

    @androidx.compose.runtime.Composable
    private fun Person(p: Persona, selected: Boolean) {
        Row(Modifier.fillMaxWidth().height(118.dp).background(if (selected) Color(0xFFF5F5F6) else Color.White).clickable {
            Personas.save(this, p); (Sim.link as? BeamNgLink)?.refreshBadge(); finish()
        }.padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Box(Modifier.size(52.dp).background(Color.White, CircleShape).border(1.dp, LINE, CircleShape), contentAlignment = Alignment.Center) {
                Text(p.name.take(1), style = Pleos.bodyS, color = Color(0xFF9C27B0))
            }
            Text(p.name, style = Pleos.bodyM, color = INK)
            Spacer(Modifier.weight(1f))
            if (selected) Text("✓", style = Pleos.titleS, color = INK)
        }
    }

    companion object {
        private val INK = Color(0xFF131417)
        private val LINE = Color(0x26131417)
    }
}

/** 상단 바의 실제 프로필 동그라미 자리에 얹는 동그라미. 시연 운전자 첫 글자를 보이고, 누르면 위 메뉴를 연다 */
class ProfileBadge(ctx: Context) : View(ctx) {
    private val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.WHITE }
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.argb(38, 19, 20, 23); style = Paint.Style.STROKE; strokeWidth = 1.5f }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = AColor.rgb(156, 39, 176); textAlign = Paint.Align.CENTER }

    init {
        contentDescription = "프로필"
        setOnClickListener {
            ctx.startActivity(Intent(ctx, SimProfileActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }

    /** 왼쪽 칸은 상단 바 색으로 덮는다: R 기어에 후방 카메라 아이콘이 생기면 실제 동그라미가 그 칸으로 밀려 두 개로 보였다 */
    override fun onDraw(c: Canvas) {
        c.drawColor(if (resources.configuration.isNightModeActive) AColor.rgb(27, 28, 31) else AColor.rgb(247, 247, 247))
        val r = height / 2f - 1
        val cx = width - height / 2f
        c.drawCircle(cx, height / 2f, r, fill)
        c.drawCircle(cx, height / 2f, r, line)
        text.textSize = height * 0.42f
        c.drawText((Personas.load(context) ?: Personas.guest).name.take(1), cx, height / 2f + text.textSize * 0.36f, text)
    }
}
