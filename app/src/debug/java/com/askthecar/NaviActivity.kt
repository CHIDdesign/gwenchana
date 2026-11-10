package com.askthecar

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.json.JSONObject
import java.net.URL
import kotlin.concurrent.thread
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * 게임 지도 기반 내비 화면 (Pleos 내비에는 게임 지도를 넣을 수 없어 우리 앱이 그린다).
 * 지도 = 모드가 내보낸 게임 도로(브리지 /map/roads.json), 위치 = 게임 속 내 차 위치(GameState.pose). 사고 판단엔 안 쓰임.
 * 진행 방향이 위, 내 차는 아래쪽 가운데. 경로 안내(목적지)는 아직 없음.
 * 새 위치가 올 때(최대 20Hz)만 다시 그린다 — 값은 그리기 단계에서만 읽어 화면 전체를 다시 구성하지 않는다.
 */
class NaviActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { PleosTheme { Box(Modifier.fillMaxSize().background(Pleos.backgroundPrimary)) { Screen() } } }
    }

    @Composable
    private fun Screen() {
        val persona by androidx.compose.runtime.produceState(Personas.load(this) ?: Personas.guest) { while (true) { value = Personas.load(this@NaviActivity) ?: Personas.guest; kotlinx.coroutines.delay(1_000) } }
        val pose = remember { mutableStateOf<GamePose?>(null) }
        val map = remember { mutableStateOf<GameMap.Roads?>(null) }
        var speed by remember { mutableIntStateOf(0) } // 속도 숫자는 왼쪽 주행 화면에 있다. 여기선 설정 버튼을 숨길 때만 쓴다
        var limit by remember { mutableIntStateOf(0) }
        var recording by remember { mutableStateOf(CarService.running != null) }
        LaunchedEffect(Unit) {
            val smooth = PoseSmoother()
            var drawn = 0L
            while (true) {
                withFrameNanos { now ->
                    if (now - drawn >= FRAME_NANOS) { // 위치는 초당 20번 오지만 화면은 30번 그린다: 사이는 움직임을 이어 그린다
                        drawn = now
                        GameMap.ensure()
                        pose.value = smooth.at(now / 1e9, GameState.pose)
                        map.value = GameMap.current
                        speed = CarState.now.speedKph.roundToInt(); limit = CarState.roadLimitKph
                        recording = CarService.running != null
                    }
                }
            }
        }
        val waiting by remember { derivedStateOf { pose.value == null || map.value == null } }
        Box(Modifier.fillMaxSize()) {
            if (waiting) {
                Text(if (!Sim.connected) "게임과 연결을 기다리는 중이에요" else "게임 지도를 불러오는 중이에요",
                    style = Pleos.titleM, color = Pleos.textTertiary, modifier = Modifier.align(Alignment.Center))
            } else MapCanvas(map, pose)
            LimitSign(limit, Modifier.align(Alignment.TopStart).padding(32.dp))
            SearchCard(Modifier.align(Alignment.BottomStart).padding(24.dp))
            RecordingPill(persona, recording, Modifier.align(Alignment.BottomCenter).padding(bottom = 24.dp))
            MapButton(Modifier.align(Alignment.TopEnd).padding(24.dp)) { Text("2D", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Pleos.textPrimary) }
            if (speed < 5) // 달리는 동안엔 설정 화면으로 못 가게
                MapButton(Modifier.align(Alignment.BottomEnd).padding(24.dp).clickable { startActivity(Intent(this@NaviActivity, MainActivity::class.java).putExtra(MainActivity.EXTRA_HOME, true)) }) {
                    Icon(Icons.Filled.Settings, "설정", tint = Pleos.textPrimary, modifier = Modifier.size(40.dp))
                }
        }
    }

    /** 실제 내비의 네모 버튼(에뮬레이터 캡처: 흰 바탕, 1px 테두리, 약 78dp) */
    @Composable
    private fun MapButton(modifier: Modifier, content: @Composable () -> Unit) {
        Box(modifier.size(78.dp).background(Pleos.backgroundPopup).border(1.dp, LINE), contentAlignment = Alignment.Center) { content() }
    }

    /** 모양 기준 = 에뮬레이터의 실제 내비 첫 화면(2026-11-05 캡처). shortcut: 게임 지도엔 검색할 장소가 없어 눌러도 동작 안 함, 목적지 안내를 만들 때 연결 */
    @Composable
    private fun SearchCard(modifier: Modifier) {
        Column(modifier.width(452.dp).background(Pleos.backgroundPopup).border(1.dp, LINE)) {
            Row(Modifier.fillMaxWidth().background(Color.Black).padding(horizontal = 24.dp, vertical = 22.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Search, null, tint = Color.White, modifier = Modifier.size(38.dp))
                Spacer(Modifier.width(18.dp))
                Text("어디로 갈까요?", style = Pleos.bodyM, color = Color(0xFFBDBDBD))
            }
            Row(Modifier.fillMaxWidth().padding(vertical = 22.dp), horizontalArrangement = Arrangement.SpaceEvenly, verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Home, "집", tint = Pleos.textTertiary, modifier = Modifier.size(40.dp))
                Icon(Icons.Filled.Star, "즐겨찾기", tint = Pleos.textPrimary, modifier = Modifier.size(40.dp))
                Text("P", fontSize = 32.sp, fontWeight = FontWeight.Bold, color = Pleos.textPrimary)
            }
        }
    }

    /** 실제 내비의 위치 이름 자리(네모 칸). 게임 도로엔 이름이 없어 기록 상태를 보여 준다 */
    @Composable
    private fun RecordingPill(p: Persona, recording: Boolean, modifier: Modifier) {
        Row(modifier.background(Pleos.backgroundPopup).border(1.dp, LINE).padding(horizontal = 20.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            // 꺼졌을 땐 색만이 아니라 "!" 표시와 문구로도 알린다
            if (recording) Box(Modifier.size(12.dp).background(Pleos.active, CircleShape))
            else Box(Modifier.size(28.dp).background(Pleos.negative, CircleShape), contentAlignment = Alignment.Center) { Text("!", style = Pleos.labelM, color = Color.White) }
            Spacer(Modifier.width(12.dp))
            Text(if (recording) "${p.name} · 사고 대비 기록 중" else "기록 꺼짐 — 앱을 다시 시작하세요", style = Pleos.bodyM, color = Pleos.textPrimary)
        }
    }

    @Composable
    private fun LimitSign(kph: Int, modifier: Modifier) {
        if (kph <= 0) return
        // 제한속도 표지는 도로 표지판 색(흰 바탕·검은 글자)을 그대로 쓰고, 테두리만 토큰
        Box(modifier.size(96.dp).background(Color.White, CircleShape).border(10.dp, Pleos.negative, CircleShape), contentAlignment = Alignment.Center) {
            Text("$kph", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color(0xFF131417))
        }
    }

    @Composable
    private fun MapCanvas(map: State<GameMap.Roads?>, pose: State<GamePose?>) {
        // 색은 Pleos Connect 보도 사진에서 읽음. 도로는 청회색, 바탕은 거의 흰색
        val bg = if (Pleos.dark) Color(0xFF14171B) else Color(0xFFF6F7FA)
        val road = if (Pleos.dark) Color(0xFF3A414B) else Color(0xFFAAB3C9)
        val blue = Color(0xFF1E88E5)
        Canvas(Modifier.fillMaxSize().background(bg).drawWithCache {
            val r = 18f * density
            val c = Offset(size.width / 2, size.height * 0.68f)
            val arrow = Path().apply { moveTo(c.x, c.y - r); lineTo(c.x + r * 0.8f, c.y + r * 0.8f); lineTo(c.x, c.y + r * 0.35f); lineTo(c.x - r * 0.8f, c.y + r * 0.8f); close() }
            onDrawWithContent { drawContent(); drawCircle(blue, 34f * density, c); drawCircle(Color.White, 27f * density, c); drawPath(arrow, blue) }
        }) {
            val roads = map.value ?: return@Canvas
            val me = pose.value ?: return@Canvas
            val cx = size.width / 2; val cy = size.height * 0.68f
            // 1m 당 픽셀: 빠를수록 넓게 (정차 9 → 화면 폭 약 200m, 100km/h 4 → 약 460m)
            val s = (max(4.0, 9.0 - CarState.now.speedKph / 20.0) * density).toFloat()
            val reach = max(size.width, size.height) / s * 0.75
            val so = sin(Math.toRadians(me.h)); val co = cos(Math.toRadians(me.h))
            // 게임 좌표 → 화면(내 차 기준 앞이 위)
            fun sx(x: Double, y: Double) = (cx + ((x - me.x) * co - (y - me.y) * so) * s).toFloat()
            fun sy(x: Double, y: Double) = (cy - ((x - me.x) * so + (y - me.y) * co) * s).toFloat()
            val visible = roads.near(me.x, me.y, reach)
            for (i in visible) {
                val e = roads.edges[i]; val a = roads.nodes[e.a]; val b = roads.nodes[e.b]
                // 실제 내비처럼 도로 폭보다 가늘게 그린다
                drawLine(road, Offset(sx(a.x, a.y), sy(a.x, a.y)), Offset(sx(b.x, b.y), sy(b.x, b.y)), strokeWidth = max(3.0 * density, (a.r + b.r) * s * 0.4).toFloat(), cap = StrokeCap.Round)
            }
        }
    }

    companion object {
        private val LINE = Color(0x33131417)
        private const val FRAME_NANOS = 30_000_000L
        fun open(ctx: Context) {
            if (CrashFlow.screenOpen) return // 시동 화면이 끝나는 순간 사고가 나면 내비가 사고 안내 위로 올라왔음
            ctx.startActivity(Intent(ctx, NaviActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        }
    }
}

/**
 * 듬성듬성 오는 게임 위치를 매 프레임 부드럽게: 받은 값들로 속도를 재서 지금 위치를 예측하고, 화면 값은 그쪽으로 끌려가게 한다.
 * 30m 넘게 튀면(리셋·순간이동) 바로 옮긴다. 화면 표시 전용이고 사고 기록에는 안 쓴다.
 */
class PoseSmoother {
    private var last: GamePose? = null
    private var lastAt = 0.0
    private var vx = 0.0; private var vy = 0.0; private var vh = 0.0
    private var shown: GamePose? = null
    private var shownAt = 0.0

    fun at(now: Double, target: GamePose?): GamePose? {
        if (target == null) return null
        if (target !== last) {
            val l = last; val dt = now - lastAt
            if (l != null && dt in 0.005..0.5 && hypot(target.x - l.x, target.y - l.y) < 30) {
                vx = (target.x - l.x) / dt; vy = (target.y - l.y) / dt; vh = wrap(target.h - l.h) / dt
            } else { vx = 0.0; vy = 0.0; vh = 0.0; shown = null }
            last = target; lastAt = now
        }
        val age = min(now - lastAt, MAX_PREDICT)
        val px = target.x + vx * age; val py = target.y + vy * age; val ph = target.h + vh * age
        val s = shown
        val next = if (s == null || hypot(px - s.x, py - s.y) > 30) GamePose(px, py, ph) else {
            val k = 1 - exp(-(now - shownAt) / FOLLOW_SEC)
            GamePose(s.x + (px - s.x) * k, s.y + (py - s.y) * k, s.h + wrap(ph - s.h) * k)
        }
        shown = next; shownAt = now
        return next
    }

    private fun wrap(deg: Double) = ((deg + 540) % 360 + 360) % 360 - 180

    private companion object {
        const val MAX_PREDICT = 0.15 // 이보다 오래 소식 없으면 더 나아가지 않는다
        const val FOLLOW_SEC = 0.07
    }
}

/** 게임 도로 지도. 모드가 맵마다 쓴 roads.json 을 브리지(/map/roads.json)에서 받는다. mapVersion 이 바뀌면 다시 받음 */
object GameMap {
    class Node(val x: Double, val y: Double, val r: Double)
    class Edge(val a: Int, val b: Int, val limit: Double)

    /**
     * 도로를 100m 칸에 미리 나눠 담는다(지도마다 한 번). 긴 도로는 지나가는 칸마다 모두 담아서,
     * 끝점이 화면 밖이어도 도로가 화면을 지나가면 보인다. near() 는 그리기 스레드(메인)에서만 부른다.
     */
    class Roads(val nodes: List<Node>, val edges: List<Edge>) {
        private val cells = HashMap<Long, IntArray>()
        private val seen = IntArray(edges.size)
        private var epoch = 0

        init {
            val tmp = HashMap<Long, MutableList<Int>>()
            edges.forEachIndexed { i, e ->
                val a = nodes[e.a]; val b = nodes[e.b]
                val n = max(1, (hypot(b.x - a.x, b.y - a.y) / (CELL / 2)).toInt() + 1)
                for (k in 0..n) {
                    val key = key(cell(a.x + (b.x - a.x) * k / n), cell(a.y + (b.y - a.y) * k / n))
                    val l = tmp.getOrPut(key) { mutableListOf() }
                    if (l.lastOrNull() != i) l.add(i)
                }
            }
            tmp.forEach { (k, v) -> cells[k] = v.toIntArray() }
        }

        /** (x, y) 에서 reach(m) 안쪽 사각형과 겹치는 칸의 도로 번호들 */
        fun near(x: Double, y: Double, reach: Double): IntArray {
            epoch++
            val out = ArrayList<Int>()
            for (cx in cell(x - reach)..cell(x + reach)) for (cy in cell(y - reach)..cell(y + reach))
                cells[key(cx, cy)]?.forEach { if (seen[it] != epoch) { seen[it] = epoch; out.add(it) } }
            return out.toIntArray()
        }

        private fun cell(v: Double) = floor(v / CELL).toInt()
        private fun key(cx: Int, cy: Int) = (cx.toLong() shl 32) xor (cy.toLong() and 0xffffffffL)
    }

    private const val CELL = 100.0

    @Volatile var current: Roads? = null
        private set
    @Volatile private var loaded = -1L
    @Volatile private var loading = false

    fun ensure(host: String = "10.0.2.2") {
        val v = GameState.mapVersion
        if (v <= 0 || v == loaded || loading) return
        loading = true
        thread(name = "game-map", isDaemon = true) {
            runCatching {
                val o = JSONObject(URL("http://$host:8766/map/roads.json").readText())
                val n = o.getJSONArray("nodes"); val e = o.getJSONArray("edges")
                // 모드가 1부터 셈(Lua) → 0부터로
                current = Roads(List(n.length()) { i -> n.getJSONArray(i).let { Node(it.getDouble(0), it.getDouble(1), it.getDouble(2)) } },
                    List(e.length()) { i -> e.getJSONArray(i).let { Edge(it.getInt(0) - 1, it.getInt(1) - 1, it.getDouble(2)) } })
                loaded = v
                Log.i("AskTheCar", "게임 지도: 지점 ${n.length()}개, 도로 ${e.length()}개")
            }.onFailure { Log.w("AskTheCar", "게임 지도 받기 실패", it) }
            loading = false
        }
    }
}
