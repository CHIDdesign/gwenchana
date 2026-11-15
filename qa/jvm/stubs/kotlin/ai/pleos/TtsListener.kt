package ai.pleos.playground.tts.listener

import ai.pleos.playground.tts.constant.ErrorType

interface OnInitializeListener { fun onSuccess(); fun onFailure(errorType: ErrorType) }
interface OnServerConnectionListener { fun onConnected(); fun onFailed(errorType: ErrorType) }
