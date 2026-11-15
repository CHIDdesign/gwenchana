package ai.pleos.playground.vehicle.api.model.area

import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.SeatValue

class SeatArea(vararg val values: SeatValue)
class DoorArea(vararg values: DoorValue) { val values: List<DoorValue> = values.toList() }
