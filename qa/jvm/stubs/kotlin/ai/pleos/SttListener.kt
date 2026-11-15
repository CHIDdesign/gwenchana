package ai.pleos.playground.stt.listener

import ai.pleos.playground.stt.constant.ErrorType

interface ResultListener {
    fun onReady()
    fun onStartedRecognition()
    fun onUpdated(stt: String, completed: Boolean)
    fun onUpdatedEpdData(on: Long, off: Long)
    fun onUpdatedRms(rms: Double)
    fun onEndedRecognition()
    fun onError(errorType: ErrorType)
}
interface OnInitializeListener { fun onSuccess(); fun onFailure(errorType: ErrorType) }
interface OnServerConnectionListener { fun onConnected(); fun onFailed(errorType: ErrorType) }
