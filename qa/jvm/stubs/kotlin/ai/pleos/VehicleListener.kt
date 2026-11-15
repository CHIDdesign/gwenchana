package ai.pleos.playground.vehicle.listener

import ai.pleos.playground.vehicle.api.model.telltale.Telltales
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.CruiseControl

interface SteeringWheelAngleListener { fun onSteeringWheelAngleUpdated(angle: Float?); fun onFailed(e: Exception) }
interface BrakePedalStatusListener { fun onBrakePedalStatusUpdated(status: Boolean?); fun onFailed(e: Exception) }
interface TurnSignalStateListener { fun onTurnSignalStateUpdated(state: TurnSignalValue?); fun onFailed(e: Exception) }
interface SeatAirbagDeployedListener { fun onSeatAirbagDeployUpdated(deployed: Boolean?); fun onFailed(e: Exception) }
interface TelltaleInfoListener { fun onTelltaleInfoUpdated(t: Telltales?); fun onFailed(e: Exception) }
interface CruiseControlStateListener { fun onCruiseControlStateUpdated(state: CruiseControl?); fun onFailed(e: Exception) }
