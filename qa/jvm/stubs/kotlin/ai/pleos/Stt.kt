// JVM stubs of the Pleos Playground STT SDK (signatures only as used by the app; no engine on the bench).
package ai.pleos.playground.stt

import ai.pleos.playground.stt.constant.ErrorType
import ai.pleos.playground.stt.constant.Mode
import ai.pleos.playground.stt.listener.OnInitializeListener
import ai.pleos.playground.stt.listener.OnServerConnectionListener
import ai.pleos.playground.stt.listener.ResultListener
import android.content.Context
import java.util.Locale

class SpeechToText(ctx: Context, mode: Mode) {
    fun initialize(listener: OnInitializeListener) { listener.onFailure(ErrorType.UNKNOWN) }
    fun setLocale(locale: Locale) {}
    fun addListener(l: ResultListener) {}
    fun removeListener(l: ResultListener) {}
    fun registerApp(clientID: String, clientSecret: String, listener: OnServerConnectionListener) {}
    fun request() { throw IllegalStateException("stub") }
    fun stop() {}
    fun release() {}
}
