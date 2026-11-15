// JVM stubs of the Pleos NaviHelper SDK.
package ai.pleos.playground.navi.helper

import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context

class NaviHelper(ctx: Context) {
    fun initialize() { throw IllegalStateException("no navi on JVM bench") }
    fun addListener(l: NaviHelperEventListener) {}
    fun removeListener(l: NaviHelperEventListener) {}
    fun release() {}
}
