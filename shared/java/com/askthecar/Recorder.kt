package com.askthecar

import kotlin.math.PI
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.tan

/** 한 시점의 차량 상태. 실차 SDK와 테스트 모드 모두 이 모양으로 넣는다. t = epoch 초 */
data class Sample(
    val t: Double,
    val speedKph: Double = 0.0,
    val steerDeg: Double = 0.0, // 앞바퀴 각도, 좌회전 음수 (Pleos SteeringWheel 규약)
    val brake: Boolean = false,
    val signal: String = "none", // "L" | "R" | "none"
    val gear: String = "D", // "P" | "R" | "N" | "D"
    val cruise: Boolean = false,
    val lat: Double? = null,
    val lon: Double? = null,
    val airbag: Boolean = false,
    /** 켜진 계기판 경고등(Pleos SDK Telltales, 쉼표 구분 한글 이름). 예: "ABS 작동,비상등" */
    val lamps: String = "",
    /** 차가 도는 빠르기(라디안/초, 왼쪽으로 돌면 +). 안드로이드 자이로 센서. 없으면 null → 조향각으로 계산 */
    val yawRate: Double? = null,
)

/** 최근 windowSec 초만 기억하는 링버퍼. */
class Recorder(private val windowSec: Double = 60.0) {
    private val buf = ArrayDeque<Sample>()

    @Synchronized
    fun add(s: Sample) {
        buf.addLast(s)
        while (buf.first().t < s.t - windowSec) buf.removeFirst()
    }

    @Synchronized
    fun clear() = buf.clear()

    @Synchronized
    fun between(from: Double, to: Double): List<Sample> = buf.filter { it.t in from..to }
}

/** 차 자세. x = 시작 방향 앞, y = 왼쪽(미터), yaw = 반시계 라디안 */
data class Pose(val x: Double, val y: Double, val yaw: Double)

/** 속도 + 회전(자이로가 있으면 자이로, 없으면 앞바퀴 각도로 자전거 모델) 추측항법. 축거 2.9m는 가정값. 결과는 시작점 기준 자세 */
fun trackPoses(samples: List<Sample>, wheelbaseM: Double = 2.9): List<Pose> {
    var x = 0.0; var y = 0.0; var yaw = 0.0
    val out = mutableListOf(Pose(0.0, 0.0, 0.0))
    for ((a, b) in samples.zipWithNext()) {
        val dt = b.t - a.t
        val v = kotlin.math.abs(a.speedKph) / 3.6 * if (a.gear == "R") -1 else 1 // 방향은 기어로: 부호가 있는 속도(AAOS 후진 음수)가 와도 뒤집히지 않게
        // 자이로는 미끄러짐·충돌로 돌아간 것도 잡음. 조향각은 좌회전(음수) = 반시계(+). 멈춰 있을 때 자이로 값은 잔차라 더하지 않음
        if (kotlin.math.abs(v) > 0.3) yaw += (a.yawRate ?: (v * tan(Math.toRadians(-a.steerDeg)) / wheelbaseM)) * dt
        x += v * cos(yaw) * dt
        y += v * sin(yaw) * dt
        out += Pose(x, y, yaw)
    }
    return out
}

/**
 * 속도·자이로 값은 신호가 느릴 때 1초씩 그대로 있다가 튄다. 그대로 계산하면 차가 멈칫·급회전하므로, 값이 바뀌기 전 최대 1.5초를 직선으로 이어 준다.
 * 궤적 계산용. 기록·경위서·화면의 속도 숫자에는 쓰지 않는다.
 */
fun easeSteps(samples: List<Sample>): List<Sample> {
    fun eased(get: (Sample) -> Double): DoubleArray {
        val out = DoubleArray(samples.size) { get(samples[it]) }
        var start = 0
        for (i in 1 until samples.size) {
            if (get(samples[i]) == get(samples[i - 1])) continue
            var from = start
            while (samples[i].t - samples[from].t > 1.5) from++
            for (j in from until i) out[j] = get(samples[from]) + (get(samples[i]) - get(samples[from])) * (samples[j].t - samples[from].t) / (samples[i].t - samples[from].t)
            start = i
        }
        return out
    }
    val v = eased { it.speedKph }; val w = eased { it.yawRate ?: Double.NaN }
    return samples.mapIndexed { i, s -> s.copy(speedKph = v[i], yawRate = if (s.yawRate == null || w[i].isNaN()) s.yawRate else w[i]) }
}

/**
 * 충돌 뒤 궤적의 길이만 GPS 로 줄인다. 속도 신호는 충돌 뒤 실제 움직임보다 크게 남는 일이 많다. 방향은 그대로 둬서 옆으로 끌려가지 않고, 같은 배율이라 멈칫·튐도 없다.
 * GPS 는 약 1초 늦게 들어와서(시험 기록으로 확인) 충돌 순간의 값이 충돌 전 위치다. 그래서 충돌 1초 뒤 값부터 재고, 그 1초 동안의 이동을 1.5m 로 친다.
 * shortcut: 1초·1.5m 는 게임 시험 기록 28건으로 맞춘 값이고 실차 GPS 지연은 미확인, 실차에서 재면 다시 맞춘다. 늘리지는 않아서 바퀴가 잠긴 채 미끄러지면 짧게 나온다.
 */
fun fitAfterCrash(samples: List<Sample>, poses: List<Pose>, crashIdx: Int): List<Pose> {
    val first = samples.firstOrNull { it.lat != null && it.lon != null } ?: return poses
    val mPerLon = 111_320.0 * cos(Math.toRadians(first.lat!!))
    fun en(s: Sample) = ((s.lon!! - first.lon!!) * mPerLon) to ((s.lat!! - first.lat) * 110_950.0)
    val fixes = samples.filter { it.lat != null && it.lon != null }
        .fold(mutableListOf<Sample>()) { acc, s -> if (acc.isEmpty() || acc.last().lat != s.lat || acc.last().lon != s.lon) acc += s; acc }
    val at = samples[crashIdx].t + GPS_LAG_SEC
    val before = fixes.lastOrNull { it.t <= at } ?: return poses
    val after = fixes.firstOrNull { it.t > at }
    val (be, bn) = en(before)
    val (ge, gn) = after?.let { a ->
        val (ae, an) = en(a); val f = (at - before.t) / (a.t - before.t)
        (be + (ae - be) * f) to (bn + (an - bn) * f)
    } ?: (be to bn)
    val (ee, en2) = en(fixes.last())
    val c = poses[crashIdx]; val e = poses.last()
    val trackDist = kotlin.math.hypot(e.x - c.x, e.y - c.y)
    if (trackDist < 1.0) return poses
    val k = ((kotlin.math.hypot(ee - ge, en2 - gn) + MOVED_IN_LAG_M) / trackDist).coerceAtMost(1.0)
    return poses.mapIndexed { i, p -> if (i <= crashIdx) p else Pose(c.x + (p.x - c.x) * k, c.y + (p.y - c.y) * k, p.yaw) }
}

private const val GPS_LAG_SEC = 1.0
private const val MOVED_IN_LAG_M = 1.5

/** 끊겨 들어온 값이 급감처럼 보이지 않게, 앞 값이 머문 시간까지 따진다. drop = 줄어든 km/h, heldSec = 앞 값이 그대로였던 시간 */
fun suddenDrop(drop: Double, heldSec: Double, minDrop: Double = 15.0) = drop >= minDrop && drop / maxOf(0.3, heldSec) >= minDrop / 0.3

fun track(samples: List<Sample>, wheelbaseM: Double = 2.9): List<Pair<Double, Double>> = trackPoses(samples, wheelbaseM).map { it.x to it.y }

/**
 * 충격이 들어온 방향(차 기준 도: 앞 0, 오른쪽 90, 뒤 180, 왼쪽 270).
 * right/forward = 충격 순간 가속도(중력 제외). 차는 충격 반대쪽으로 밀리므로 가속도의 반대가 충격 방향.
 */
fun impactFromDeg(right: Double, forward: Double): Double = (Math.toDegrees(atan2(-right, -forward)) + 360) % 360

/** 차 중심에서 그 방향(앞 0°, 시계) 겉면까지 거리. 차 4.5×1.85m(3D 모델과 같음) */
fun ownEdge(impactDeg: Double, lenM: Double = 4.5, widthM: Double = 1.85): Double {
    val r = Math.toRadians(impactDeg)
    return minOf(lenM / 2 / kotlin.math.abs(cos(r)).coerceAtLeast(1e-6), widthM / 2 / kotlin.math.abs(sin(r)).coerceAtLeast(1e-6))
}

/** 상대 차 추정(대략): 충격 방향으로 carLenM(중심 사이 거리)만큼 떨어진 곳에서 내 차 쪽을 향하고 있었다고 가정 */
fun estimateOther(own: Pose, impactDeg: Double, carLenM: Double = 4.5): Pose {
    val dir = own.yaw - Math.toRadians(impactDeg) // 시계방향 각도라 반시계 yaw 에서 뺀다
    return Pose(own.x + carLenM * cos(dir), own.y + carLenM * sin(dir), dir + PI)
}

/** 내 차 기준 앞·오른쪽 미터 → 같은 좌표 (방향은 내 차와 같게) */
fun offsetPose(own: Pose, fwd: Double, right: Double) = Pose(
    own.x + fwd * cos(own.yaw) + right * sin(own.yaw),
    own.y + fwd * sin(own.yaw) - right * cos(own.yaw),
    own.yaw,
)
