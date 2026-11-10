package com.askthecar

import org.json.JSONObject
import java.net.InetSocketAddress
import java.net.Socket
import kotlin.concurrent.thread

/** 게임 지도 내비 전용 값. 사고 판단·기록엔 안 씀 */
object GameState {
    /** 게임 도로 지도 버전(브리지 roads.json 시각, 0 = 없음). 바뀌면 GameMap 이 다시 받음 */
    @Volatile var mapVersion = 0L
    @Volatile var pose: GamePose? = null
}

/** 게임 좌표 한 점: x 동, y 북(미터), h 북쪽 기준 시계 방향(도) */
data class GamePose(val x: Double, val y: Double, val h: Double)

/**
 * 테스트 모드: PC 브리지(game/bridge/fake_drive.py 또는 BeamNG 브리지)에서 값을 받는다.
 * 에뮬레이터에서 10.0.2.2 = 호스트 PC. 받은 JSON에 있는 필드만 덮어쓴다.
 */
class TestModeSource(private val host: String = "10.0.2.2", private val port: Int = 8765) {
    @Volatile private var running = false
    val isRunning get() = running
    @Volatile var connected = false
        private set

    @Volatile private var socket: Socket? = null
    /** 껐다 바로 켜면 옛 읽기 스레드가 아직 살아 있을 수 있어, 세대가 바뀌면 옛 스레드는 끝낸다 */
    @Volatile private var generation = 0

    fun start() {
        running = true
        val gen = ++generation
        thread(name = "test-mode", isDaemon = true) {
            while (running && gen == generation) {
                runCatching {
                    Socket().also { socket = it }.use { s ->
                        s.connect(InetSocketAddress(host, port), 2000)
                        s.soTimeout = 3000 // 반쯤 끊긴 연결(3초 무소식)은 끊긴 것으로: 안 그러면 옛 값이 영원히 덮어씀
                        connected = true
                        s.getInputStream().bufferedReader().forEachLine { line -> if (running) apply(JSONObject(line)) }
                    }
                }
                if (gen == generation) { connected = false; CarState.overridden = emptySet() }
                if (running && gen == generation) Thread.sleep(2000) // 브리지가 아직 안 켜졌으면 재시도
            }
        }
    }

    fun stop() {
        running = false
        runCatching { socket?.close() }
        connected = false
        CarState.overridden = emptySet(); CarState.roadLimitKph = 0; GameState.pose = null
    }

    /** 게임 속 시동(브리지가 보냄). 바뀌면 onIgnition (화면 전원 흉내). null = 아직 모름 */
    @Volatile var ignition: Boolean? = null
    var onIgnition: ((Boolean) -> Unit)? = null

    private fun apply(o: JSONObject) {
        if (o.has("ignition")) {
            val on = o.getBoolean("ignition")
            if (on != ignition) onIgnition?.invoke(on)
            ignition = on
        }
        CarState.overridden = KEYS.filterTo(mutableSetOf()) { o.has(it) }
        if (o.has("roadLimit")) CarState.roadLimitKph = o.getInt("roadLimit")
        if (o.has("mapVersion")) GameState.mapVersion = o.getLong("mapVersion")
        o.optJSONObject("gamePos")?.let { g -> GameState.pose = GamePose(g.getDouble("x"), g.getDouble("y"), g.getDouble("h")) }
        CarState.update {
        copy(
            speedKph = o.optDouble("speed", speedKph),
            steerDeg = o.optDouble("steer", steerDeg),
            brake = o.optBoolean("brake", brake),
            signal = o.optString("signal", signal),
            gear = o.optString("gear", gear),
            cruise = o.optBoolean("cruise", cruise),
            airbag = o.optBoolean("airbag", airbag),
            lat = if (!Consent.allowed(Consent.Item.LOCATION)) null else if (o.has("lat")) o.getDouble("lat") else lat,
            lon = if (!Consent.allowed(Consent.Item.LOCATION)) null else if (o.has("lon")) o.getDouble("lon") else lon,
        )
        }
    }

    private companion object {
        val KEYS = listOf("speed", "steer", "brake", "signal", "gear", "cruise", "airbag", "lat", "roadLimit")
    }
}
