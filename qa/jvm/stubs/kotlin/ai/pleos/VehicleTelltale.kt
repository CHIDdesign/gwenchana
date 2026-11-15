package ai.pleos.playground.vehicle.api.model.telltale

import ai.pleos.playground.vehicle.constant.telltale.TelltaleState
import ai.pleos.playground.vehicle.constant.telltale.TelltaleType

class Telltale(val type: TelltaleType, val state: TelltaleState)
class Telltales(val telltales: List<Telltale>)
