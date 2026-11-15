package ai.pleos.playground.vehicle.api.model.capability
enum class AccessType { READ, WRITE, READ_WRITE }
class CapabilityConfig(val accessType: AccessType)
class AreaBounds<T>(val minValue: T, val maxValue: T)
