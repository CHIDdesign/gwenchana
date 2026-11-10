package com.askthecar

import ai.pleos.playground.location.fused.FusedLocation
import ai.pleos.playground.location.fused.listener.FusedLocationListener
import ai.pleos.playground.location.fused.model.LocationData
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlin.concurrent.thread

/** 지금 이 순간의 차 상태. 실차 SDK가 쓴다(시연 도구가 붙으면 그쪽 값이 우선). */
object CarState {
    @Volatile var now = Sample(t = 0.0)
        private set

    @Synchronized fun update(f: Sample.() -> Sample) { now = now.f() }

    /** 시연 도구가 대신 주는 항목 이름들. 이 항목은 실차 SDK 값을 무시한다 (섞이면 경위서가 오염됨). 실차에선 항상 빔 */
    @Volatile var overridden: Set<String> = emptySet()

    fun fromCar(key: String, f: Sample.() -> Sample) { if (key !in overridden) update(f) }

    /** 비상등 켜짐: Pleos SDK 계기판 경고등(Telltales)의 좌·우 지시등이 함께 켜짐 */
    @Volatile var hazard = false

    /** 경고등 값을 한 번이라도 받음. 못 받는 차는 비상등을 확인할 수 없음 */
    @Volatile var telltalesSeen = false

    /** 현재 도로 제한속도(km/h), 0 = 모름. 실차: NaviHelper onCurrentRoadSpeedLimit(1초마다), 게임: 브리지.
     *  10초 넘게 새 값이 없으면 모름으로: 내비가 값을 멈추면 앞서 달린 고속도로 값이 시내 사고에 남지 않게 */
    @Volatile private var roadLimitRaw = 0
    @Volatile private var roadLimitAt = 0L
    var roadLimitKph: Int
        get() = if (System.currentTimeMillis() - roadLimitAt > 10_000) 0 else roadLimitRaw
        set(v) { roadLimitRaw = v; roadLimitAt = System.currentTimeMillis() }
}

private const val TAG = "AskTheCar"

/** 실차(에뮬레이터 VHAL) 값: Pleos Vehicle SDK + AAOS 속도·기어 + FusedLocation + NaviHelper 도로 제한속도. */
class PleosSource(private val ctx: Context) {
    private val vehicle = Vehicle(ctx)
    private var car: Car? = null
    private var fused: FusedLocation? = null
    private var navi: NaviHelper? = null
    private val driverSeat = SeatArea(SeatValue.ROW_1_LEFT)
    @Volatile private var polling = true

    private val steer = object : SteeringWheelAngleListener {
        override fun onSteeringWheelAngleUpdated(angle: Float?) { angle?.let { a -> CarState.fromCar("steer") { copy(steerDeg = a.toDouble()) } } }
        override fun onFailed(e: Exception) = fail("steer", e)
    }
    private val brake = object : BrakePedalStatusListener {
        override fun onBrakePedalStatusUpdated(status: Boolean?) { status?.let { b -> CarState.fromCar("brake") { copy(brake = b) } } }
        override fun onFailed(e: Exception) = fail("brake", e)
    }
    private val turn = object : TurnSignalStateListener {
        override fun onTurnSignalStateUpdated(state: TurnSignalValue?) {
            val s = when (state) { TurnSignalValue.LEFT -> "L"; TurnSignalValue.RIGHT -> "R"; else -> "none" }
            CarState.fromCar("signal") { copy(signal = s) }
        }
        override fun onFailed(e: Exception) = fail("turn", e)
    }
    private val airbag = object : SeatAirbagDeployedListener {
        override fun onSeatAirbagDeployUpdated(deployed: Boolean?) { deployed?.let { d -> CarState.fromCar("airbag") { copy(airbag = d) } } }
        override fun onFailed(e: Exception) = fail("airbag", e)
    }
    private val telltales = object : TelltaleInfoListener {
        // 계기판 경고등: 사고 기록용 + 비상등(좌·우 지시등 표시가 함께 켜짐). 게임은 브리지가 VC 경고등 흉내로 넣음
        override fun onTelltaleInfoUpdated(t: Telltales?) {
            t ?: return
            val on = t.telltales.filter { it.state != TelltaleState.OFF }.map { it.type }.toSet()
            val hazard = TelltaleType.TURN_SIGNAL_LEFT in on && TelltaleType.TURN_SIGNAL_RIGHT in on
            CarState.hazard = hazard
            CarState.telltalesSeen = true
            CarState.fromCar("lamps") { copy(lamps = (LAMPS.filterKeys { it in on }.values + listOfNotNull("비상등".takeIf { hazard })).joinToString(",")) }
        }
        override fun onFailed(e: Exception) = fail("telltales", e)
    }
    private val cruise = object : CruiseControlStateListener {
        // ACTIVATED = 크루즈가 실제로 속도를 제어 중 (ENABLED 는 켜져만 있는 대기 상태)
        override fun onCruiseControlStateUpdated(state: CruiseControl?) { CarState.fromCar("cruise") { copy(cruise = state == CruiseControl.ACTIVATED) } }
        override fun onFailed(e: Exception) = fail("cruise", e)
    }
    private val roadLimit = object : NaviHelperEventListener {
        // 1초마다 온다(문서). 게임 브리지가 제한속도를 보내는 동안은 그쪽 값을 쓴다
        override fun onCurrentRoadSpeedLimit(limit: Int) { if ("roadLimit" !in CarState.overridden) CarState.roadLimitKph = limit }
    }
    private val props = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(v: CarPropertyValue<*>) {
            when (v.propertyId) {
                // AAOS 규칙상 후진은 음수. 방향은 기어로 판단하므로 크기만(아니면 후진 재현이 앞으로 뒤집힘)
                VehiclePropertyIds.PERF_VEHICLE_SPEED -> (v.value as? Float)?.let { ms -> CarState.fromCar("speed") { copy(speedKph = kotlin.math.abs(ms) * 3.6) } }
                VehiclePropertyIds.GEAR_SELECTION -> (v.value as? Int)?.let { g -> CarState.fromCar("gear") { copy(gear = gearName(g)) } }
            }
        }
        override fun onErrorEvent(propertyId: Int, zone: Int) { Log.w(TAG, "car property error $propertyId") }
    }
    private val location = object : FusedLocationListener {
        override fun onChanged(location: LocationData?) {
            if (location == null || !Consent.allowed(Consent.Item.LOCATION)) return
            CarState.fromCar("lat") { copy(lat = location.latitude, lon = location.longitude) }
        }
        override fun onError(exception: Exception) = fail("location", exception)
    }

    fun start(locationGranted: Boolean) {
        // 항목마다 따로 감싼다: 권한 하나(SecurityException)가 나머지 기록을 막지 않게
        runCatching { vehicle.initialize() }.onFailure { fail("vehicle", it) }
        runCatching { vehicle.getSteeringWheel().registerSteeringWheelAngle(steer) }.onFailure { fail("steer", it) }
        runCatching { vehicle.getBrake().registerBrakePedalStatus(brake) }.onFailure { fail("brake", it) }
        runCatching { vehicle.getTurnSignal().registerTurnSignalState(turn) }.onFailure { fail("turn", it) }
        runCatching { vehicle.getSafety().registerSeatAirbagDeployed(driverSeat, airbag) }.onFailure { fail("airbag", it) }
        runCatching { vehicle.getDrivingMode().registerCruiseControlState(cruise) }.onFailure { fail("cruise", it) }
        runCatching { vehicle.getSafety().registerTelltales(telltales) }.onFailure { fail("telltales", it) }
        navi = runCatching { NaviHelper(ctx).apply { initialize(); addListener(roadLimit) } }.onFailure { fail("navi", it) }.getOrNull()

        car = runCatching { Car.createCar(ctx) }.onFailure { fail("car", it) }.getOrNull()?.also { c ->
            val cpm = c.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
            for (id in listOf(VehiclePropertyIds.PERF_VEHICLE_SPEED, VehiclePropertyIds.GEAR_SELECTION)) {
                // 속도는 10Hz 기록·0.3초 급감 감지에 쓰여 가장 빠르게 (NORMAL = 약 1Hz)
                val rate = if (id == VehiclePropertyIds.PERF_VEHICLE_SPEED) CarPropertyManager.SENSOR_RATE_FASTEST else CarPropertyManager.SENSOR_RATE_NORMAL
                runCatching { cpm.registerCallback(props, id, rate) }
                    .onFailure { fail("property $id", it) } // CAR_SPEED는 런타임 권한이라 거부되면 여기로 옴
            }
            // 기어 변경 알림을 놓친 적이 있어(R→P) 1초마다 현재 기어를 다시 읽는다
            thread(name = "gear-poll", isDaemon = true) {
                while (polling) {
                    runCatching { cpm.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }
                        .onSuccess { g -> CarState.fromCar("gear") { copy(gear = gearName(g)) } }
                    Thread.sleep(1000)
                }
            }
        }
        if (locationGranted) fused = runCatching {
            FusedLocation(ctx).takeIf { it.isSupported() }?.apply {
                initialize()
                registerFusedLocationCallback(location)
            }
        }.onFailure { fail("location", it) }.getOrNull()
    }

    fun stop() {
        polling = false
        runCatching { vehicle.getSteeringWheel().unregisterSteeringWheelAngle(steer) }
        runCatching { vehicle.getBrake().unregisterBrakePedalStatus(brake) }
        runCatching { vehicle.getTurnSignal().unregisterTurnSignalState(turn) }
        runCatching { vehicle.getSafety().unregisterSeatAirbagDeployed(driverSeat, airbag) }
        runCatching { vehicle.getDrivingMode().unregisterCruiseControlState(cruise) }
        runCatching { vehicle.getSafety().unregisterTelltales(telltales) }
        runCatching { vehicle.release() }
        runCatching { navi?.run { removeListener(roadLimit); release() } }
        runCatching { fused?.run { unregisterFusedLocationCallback(location); release() } }
        runCatching { car?.disconnect() }
    }

    private fun fail(what: String, e: Throwable) { Log.w(TAG, "$what 실패", e) }

    private companion object {
        /** 사고 기록에 남길 경고등 (나머지는 무시) */
        val LAMPS = mapOf(
            TelltaleType.ABS to "ABS 작동", TelltaleType.ESC to "차체 자세 제어(ESC) 작동", TelltaleType.FCA to "전방 충돌방지 보조(FCA)",
            TelltaleType.AIRBAG to "에어백 경고등", TelltaleType.SEAT_BELT to "안전벨트 경고등", TelltaleType.EMERGENCY_CALL to "긴급 호출",
            TelltaleType.DOOR_OPEN to "문 열림", TelltaleType.TRUNK_OPEN to "트렁크 열림", TelltaleType.PARKING_BRAKE to "주차 브레이크",
        )
    }

    // VehicleGear 상수 (AOSP android.car.VehicleGear)
    private fun gearName(g: Int) = when (g) { 1 -> "N"; 2 -> "R"; 4 -> "P"; else -> "D" }
}

/** 차량 조작 (Pleos Vehicle SDK). 차가 허용하지 않거나 기능이 없으면 onDone(false, 이유) */
object CarControl {
    /** 트렁크(뒷문 REAR) 끝까지 열기/닫기 — 고속도로 "비트박스"의 트렁크 열기. */
    fun setTrunk(ctx: Context, open: Boolean, onDone: (Boolean, String?) -> Unit) {
        val v = Vehicle(ctx).apply { initialize() }
        fun done(ok: Boolean, why: String?) { runCatching { v.release() }; onDone(ok, why) }
        runCatching {
            v.getDoor().checkDoorPositionCapabilities({ cfg: ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig, caps: Map<DoorArea, ai.pleos.playground.vehicle.api.model.capability.AreaBounds<Int>> ->
                val max = caps.entries.firstOrNull { DoorValue.REAR in it.key.values }?.value?.maxValue
                if (max == null) done(false, "이 차는 앱으로 트렁크를 열 수 없어요")
                else if ("WRITE" !in cfg.accessType.name) done(false, "트렁크 조작 권한이 없어요(${cfg.accessType})")
                else v.getDoor().setDoorPosition(DoorArea(DoorValue.REAR), if (open) max else 0, { _, _ -> done(true, null) }, { e -> done(false, e.message) })
            }, { e -> done(false, e.message) })
        }.onFailure { done(false, it.message) }
    }
}
