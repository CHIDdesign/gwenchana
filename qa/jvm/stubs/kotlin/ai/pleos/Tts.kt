// JVM stubs of the Pleos Playground TTS SDK.
package ai.pleos.playground.tts

import ai.pleos.playground.tts.constant.ErrorType
import ai.pleos.playground.tts.constant.Mode
import ai.pleos.playground.tts.listener.OnInitializeListener
import ai.pleos.playground.tts.listener.OnServerConnectionListener
import android.content.Context
import java.util.Locale

class TextToSpeech(ctx: Context, mode: Mode) {
    fun initialize(listener: OnInitializeListener) { listener.onFailure(ErrorType.UNKNOWN) }
    fun setLocale(locale: Locale) {}
    fun registerApp(clientID: String, clientSecret: String, listener: OnServerConnectionListener) {}
    fun speak(text: String) {}
    fun stop() {}
    fun release() {}
}
