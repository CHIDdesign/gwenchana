package ai.pleos.playground.location.fused.listener

import ai.pleos.playground.location.fused.model.LocationData

interface FusedLocationListener { fun onChanged(location: LocationData?); fun onError(exception: Exception) }
