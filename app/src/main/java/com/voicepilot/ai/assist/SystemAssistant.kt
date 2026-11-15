package com.voicepilot.ai.assist

import android.app.PendingIntent
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.RemoteException
import android.service.quicksettings.TileService
import android.service.voice.VoiceInteractionService
import android.service.voice.VoiceInteractionSession
import android.service.voice.VoiceInteractionSessionService
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.SpeechRecognizer
import com.voicepilot.ai.platform.RecognizerComponents

/**
 * Entry point for Android's "Default digital assistant app" role. When the user
 * selects VoicePilot, the assist gesture / long-press home opens [AssistActivity].
 */
class VoicePilotInteractionService : VoiceInteractionService()

class VoicePilotSessionService : VoiceInteractionSessionService() {
    override fun onNewSession(args: Bundle?): VoiceInteractionSession = VoicePilotSession(this)
}

class VoicePilotSession(service: VoicePilotSessionService) : VoiceInteractionSession(service) {
    override fun onShow(args: Bundle?, showFlags: Int) {
        super.onShow(args, showFlags)
        startAssistantActivity(AssistActivity.intent(context, null))
        hide()
    }
}

/**
 * Required by the assistant role. VoicePilot has no speech model of its own, so
 * this service forwards recognition to the device's existing recognizer
 * (for example Google's), never to itself.
 */
class VoicePilotRecognitionService : RecognitionService() {
    private var delegate: SpeechRecognizer? = null

    override fun onStartListening(recognizerIntent: Intent, listener: Callback) {
        val component = RecognizerComponents.systemRecognizer(this)
        if (component == null) {
            safe { listener.error(SpeechRecognizer.ERROR_CLIENT) }
            return
        }
        delegate?.destroy()
        delegate = SpeechRecognizer.createSpeechRecognizer(this, component).apply {
            setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) = safe { listener.readyForSpeech(params ?: Bundle()) }
                override fun onBeginningOfSpeech() = safe { listener.beginningOfSpeech() }
                override fun onRmsChanged(rmsdB: Float) = safe { listener.rmsChanged(rmsdB) }
                override fun onBufferReceived(buffer: ByteArray?) = safe { buffer?.let(listener::bufferReceived) }
                override fun onEndOfSpeech() = safe { listener.endOfSpeech() }
                override fun onError(error: Int) = safe { listener.error(error) }
                override fun onResults(results: Bundle?) = safe { listener.results(results ?: Bundle()) }
                override fun onPartialResults(partialResults: Bundle?) = safe { listener.partialResults(partialResults ?: Bundle()) }
                override fun onEvent(eventType: Int, params: Bundle?) = Unit
            })
            startListening(recognizerIntent)
        }
    }

    override fun onStopListening(listener: Callback) {
        delegate?.stopListening()
    }

    override fun onCancel(listener: Callback) {
        delegate?.cancel()
    }

    override fun onDestroy() {
        delegate?.destroy()
        delegate = null
        super.onDestroy()
    }

    private inline fun safe(block: () -> Unit) {
        try {
            block()
        } catch (_: RemoteException) {
            // Client went away.
        }
    }
}

/** Quick Settings tile that opens the assistant overlay. */
class TalkTileService : TileService() {
    override fun onClick() {
        super.onClick()
        val intent = AssistActivity.intent(this, null).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (Build.VERSION.SDK_INT >= 34) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION", "StartActivityAndCollapseDeprecated")
            startActivityAndCollapse(intent)
        }
    }
}
