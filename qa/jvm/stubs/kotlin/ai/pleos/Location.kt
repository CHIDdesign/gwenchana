// JVM stubs of the Pleos FusedLocation SDK.
package ai.pleos.playground.location.fused

import ai.pleos.playground.location.fused.listener.FusedLocationListener
import android.content.Context

class FusedLocation(ctx: Context) {
    fun isSupported(): Boolean = false
    fun initialize() {}
    fun registerFusedLocationCallback(l: FusedLocationListener) {}
    fun unregisterFusedLocationCallback(l: FusedLocationListener) {}
    fun release() {}
}
