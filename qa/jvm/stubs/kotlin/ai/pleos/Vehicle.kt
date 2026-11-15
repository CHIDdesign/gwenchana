// JVM stubs of the Pleos Vehicle SDK (only what Sources.kt references). No vehicle on the bench.
package ai.pleos.playground.vehicle

import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AreaBounds
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.CruiseControlStateListener
import ai.pleos.playground.vehicle.listener.SeatAirbagDeployedListener
import ai.pleos.playground.vehicle.listener.SteeringWheelAngleListener
import ai.pleos.playground.vehicle.listener.TelltaleInfoListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.content.Context

class Vehicle(ctx: Context) {
    fun initialize() { throw IllegalStateException("no vehicle on JVM bench") }
    fun release() {}
    fun getSteeringWheel() = SteeringWheel()
    fun getBrake() = Brake()
    fun getTurnSignal() = TurnSignal()
    fun getSafety() = Safety()
    fun getDrivingMode() = DrivingMode()
    fun getDoor() = Door()

    class SteeringWheel { fun registerSteeringWheelAngle(l: SteeringWheelAngleListener) {}; fun unregisterSteeringWheelAngle(l: SteeringWheelAngleListener) {} }
    class Brake { fun registerBrakePedalStatus(l: BrakePedalStatusListener) {}; fun unregisterBrakePedalStatus(l: BrakePedalStatusListener) {} }
    class TurnSignal { fun registerTurnSignalState(l: TurnSignalStateListener) {}; fun unregisterTurnSignalState(l: TurnSignalStateListener) {} }
    class Safety {
        fun registerSeatAirbagDeployed(area: SeatArea, l: SeatAirbagDeployedListener) {}
        fun unregisterSeatAirbagDeployed(area: SeatArea, l: SeatAirbagDeployedListener) {}
        fun registerTelltales(l: TelltaleInfoListener) {}
        fun unregisterTelltales(l: TelltaleInfoListener) {}
    }
    class DrivingMode { fun registerCruiseControlState(l: CruiseControlStateListener) {}; fun unregisterCruiseControlState(l: CruiseControlStateListener) {} }
    class Door {
        fun checkDoorPositionCapabilities(onResult: (CapabilityConfig, Map<DoorArea, AreaBounds<Int>>) -> Unit, onError: (Exception) -> Unit) { onError(IllegalStateException("stub")) }
        fun setDoorPosition(area: DoorArea, value: Int, onResult: (DoorArea, Int) -> Unit, onError: (Exception) -> Unit) { onError(IllegalStateException("stub")) }
    }
}
