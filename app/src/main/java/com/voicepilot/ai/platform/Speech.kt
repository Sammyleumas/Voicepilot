package com.voicepilot.ai.platform

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognitionService
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import com.voicepilot.core.voice.RecognitionConfig
import com.voicepilot.core.voice.SpeechError
import com.voicepilot.core.voice.SpeechEvent
import com.voicepilot.core.voice.SpeechRecognitionEngine
import com.voicepilot.core.voice.SpeechSynthesizer
import com.voicepilot.core.voice.VoiceOption
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.Locale
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume

/** Finds a speech recognition service other than VoicePilot's own delegating one. */
object RecognizerComponents {
    private val PREFERRED = listOf("com.google.android.googlequicksearchbox", "com.google.android.as", "com.google.android.tts")

    fun systemRecognizer(context: Context): ComponentName? {
        @Suppress("DEPRECATION")
        val services = context.packageManager.queryIntentServices(Intent(RecognitionService.SERVICE_INTERFACE), 0)
            .map { ComponentName(it.serviceInfo.packageName, it.serviceInfo.name) }
            .filter { it.packageName != context.packageName }
        return services.minByOrNull { c -> PREFERRED.indexOf(c.packageName).let { if (it < 0) Int.MAX_VALUE else it } }
    }
}

internal fun mapRecognizerError(code: Int): SpeechError = when (code) {
    SpeechRecognizer.ERROR_NO_MATCH -> SpeechError.NO_MATCH
    SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> SpeechError.SPEECH_TIMEOUT
    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> SpeechError.PERMISSION_DENIED
    SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_SERVER, 11 -> SpeechError.NETWORK
    SpeechRecognizer.ERROR_RECOGNIZER_BUSY, 10 -> SpeechError.BUSY
    SpeechRecognizer.ERROR_AUDIO -> SpeechError.AUDIO
    SpeechRecognizer.ERROR_CLIENT -> SpeechError.CLIENT
    12, 13 -> SpeechError.LANGUAGE_UNAVAILABLE
    else -> SpeechError.UNKNOWN
}

/**
 * Speech-to-text through Android's SpeechRecognizer. Works with whatever
 * recognition service the device provides; on-device recognition is only
 * reported when the platform says it's available.
 */
@Singleton
class AndroidSpeechRecognitionEngine @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechRecognitionEngine {
    override val id = "android-speech-recognizer"

    override fun isAvailable(): Boolean =
        SpeechRecognizer.isRecognitionAvailable(context) || RecognizerComponents.systemRecognizer(context) != null

    override fun supportsOffline(): Boolean =
        Build.VERSION.SDK_INT >= 31 && SpeechRecognizer.isOnDeviceRecognitionAvailable(context)

    /** When the last recognizer was released; some recognizers report "busy" if a new one starts too soon. */
    @Volatile private var lastReleasedAt = 0L

    /**
     * Listens once, retrying automatically when the device recognizer reports
     * it is busy or disconnected before any speech was heard.
     */
    override fun listen(config: RecognitionConfig): Flow<SpeechEvent> = flow {
        var attempt = 0
        while (true) {
            var retry = false
            var heardSpeech = false
            attemptOnce(config).collect { event ->
                val transient = event is SpeechEvent.Error &&
                    (event.error == SpeechError.BUSY || event.error == SpeechError.CLIENT || event.error == SpeechError.NETWORK)
                if (transient && !heardSpeech && attempt < MAX_RETRIES) {
                    retry = true
                } else {
                    if (event is SpeechEvent.SpeechStarted || event is SpeechEvent.Partial) heardSpeech = true
                    emit(event)
                }
            }
            if (!retry) break
            attempt++
            delay(RETRY_DELAY_MS * attempt)
        }
    }.flowOn(Dispatchers.Main.immediate)

    /**
     * Uses the phone's default recognizer, which is what works on most devices
     * (Tecno, Infinix and Samsung included). Only when VoicePilot itself is the
     * default (as the assistant) does it pick another installed recognizer, so it
     * never ends up talking to itself.
     */
    private fun createRecognizer(): SpeechRecognizer {
        val default = android.provider.Settings.Secure.getString(context.contentResolver, "voice_recognition_service")
        val defaultIsSelf = default?.startsWith(context.packageName + "/") == true
        if (!defaultIsSelf && SpeechRecognizer.isRecognitionAvailable(context)) {
            return SpeechRecognizer.createSpeechRecognizer(context)
        }
        val other = RecognizerComponents.systemRecognizer(context)
        return if (other != null) SpeechRecognizer.createSpeechRecognizer(context, other) else SpeechRecognizer.createSpeechRecognizer(context)
    }

    private fun attemptOnce(config: RecognitionConfig): Flow<SpeechEvent> = callbackFlow {
        if (!isAvailable()) {
            trySend(SpeechEvent.Error(SpeechError.UNAVAILABLE))
            close()
            return@callbackFlow
        }
        val sinceRelease = System.currentTimeMillis() - lastReleasedAt
        if (sinceRelease < COOLDOWN_MS) delay(COOLDOWN_MS - sinceRelease)
        val recognizer = createRecognizer()
        recognizer.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { trySend(SpeechEvent.ReadyForSpeech) }
            override fun onBeginningOfSpeech() { trySend(SpeechEvent.SpeechStarted) }
            override fun onRmsChanged(rmsdB: Float) { trySend(SpeechEvent.Level(((rmsdB + 2f) / 12f).coerceIn(0f, 1f))) }
            override fun onBufferReceived(buffer: ByteArray?) = Unit
            override fun onEndOfSpeech() { trySend(SpeechEvent.SpeechEnded) }
            override fun onError(error: Int) {
                trySend(SpeechEvent.Error(mapRecognizerError(error)))
                close()
            }
            override fun onResults(results: Bundle?) {
                val texts = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION).orEmpty()
                val scores = results?.getFloatArray(SpeechRecognizer.CONFIDENCE_SCORES)
                val best = texts.firstOrNull()?.trim()
                if (best.isNullOrEmpty()) {
                    trySend(SpeechEvent.Error(SpeechError.NO_MATCH))
                } else {
                    trySend(SpeechEvent.Final(best, scores?.firstOrNull()?.takeIf { it >= 0f }, texts.drop(1)))
                }
                close()
            }
            override fun onPartialResults(partialResults: Bundle?) {
                partialResults?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull()
                    ?.takeIf { it.isNotBlank() }
                    ?.let { trySend(SpeechEvent.Partial(it)) }
            }
            override fun onEvent(eventType: Int, params: Bundle?) = Unit
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, config.languageTag)
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, config.partialResults)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, config.maxResults)
            putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE, context.packageName)
            if (config.preferOffline) putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, true)
        }
        recognizer.startListening(intent)
        awaitClose {
            // Always release the microphone when the collector goes away.
            runCatching { recognizer.cancel() }
            runCatching { recognizer.destroy() }
            lastReleasedAt = System.currentTimeMillis()
        }
    }

    private companion object {
        const val MAX_RETRIES = 2
        const val RETRY_DELAY_MS = 400L
        const val COOLDOWN_MS = 350L
    }
}

/** Spoken responses through the platform TextToSpeech engine. */
@Singleton
class AndroidSpeechSynthesizer @Inject constructor(
    @ApplicationContext private val context: Context,
) : SpeechSynthesizer {
    private val _isSpeaking = MutableStateFlow(false)
    override val isSpeaking: StateFlow<Boolean> = _isSpeaking.asStateFlow()

    private var tts: TextToSpeech? = null
    private var ready: CompletableDeferred<Boolean>? = null
    var languageTag: String = "en-NG"

    private suspend fun engine(): TextToSpeech? = withContext(Dispatchers.Main.immediate) {
        val existing = ready
        if (existing != null) return@withContext if (existing.await()) tts else null
        val deferred = CompletableDeferred<Boolean>()
        ready = deferred
        tts = TextToSpeech(context) { status -> deferred.complete(status == TextToSpeech.SUCCESS) }
        val ok = withTimeoutOrNull(5_000) { deferred.await() } ?: false
        if (!ok) {
            tts?.shutdown(); tts = null; ready = null
            return@withContext null
        }
        tts
    }

    override suspend fun speak(text: String, rate: Float, voiceName: String?) {
        val engine = engine() ?: return
        withContext(Dispatchers.Main.immediate) {
            applyLanguage(engine)
            engine.setSpeechRate(rate)
            voiceName?.let { name -> engine.voices?.firstOrNull { it.name == name }?.let { engine.voice = it } }
        }
        val id = UUID.randomUUID().toString()
        _isSpeaking.value = true
        try {
            suspendCancellableCoroutine { cont ->
                engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    @Deprecated("Deprecated in Java")
                    override fun onError(utteranceId: String?) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                    override fun onError(utteranceId: String?, errorCode: Int) { if (utteranceId == id && cont.isActive) cont.resume(Unit) }
                })
                val result = engine.speak(text, TextToSpeech.QUEUE_FLUSH, null, id)
                if (result != TextToSpeech.SUCCESS && cont.isActive) cont.resume(Unit)
                cont.invokeOnCancellation { engine.stop() }
            }
        } finally {
            _isSpeaking.value = false
        }
    }

    private fun applyLanguage(engine: TextToSpeech) {
        val wanted = Locale.forLanguageTag(languageTag)
        val result = engine.setLanguage(wanted)
        if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
            engine.setLanguage(Locale.US)
        }
    }

    override fun stop() {
        tts?.stop()
        _isSpeaking.value = false
    }

    override suspend fun voices(): List<VoiceOption> {
        val engine = engine() ?: return emptyList()
        return withContext(Dispatchers.Main.immediate) {
            engine.voices.orEmpty()
                .filter { it.locale.language == "en" }
                .sortedWith(compareBy({ it.isNetworkConnectionRequired }, { it.name }))
                .map { VoiceOption(it.name, it.locale.toLanguageTag(), it.isNetworkConnectionRequired) }
        }
    }
}
