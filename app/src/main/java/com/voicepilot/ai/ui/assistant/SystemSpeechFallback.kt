package com.voicepilot.ai.ui.assistant

import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle

/**
 * Opens the phone's own speech popup (for example Google's) when the in-app
 * recognizer is busy or unavailable, or when the user prefers it in Settings.
 */
@Composable
fun SystemSpeechFallback(viewModel: AssistantViewModel) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val text = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
        viewModel.onSystemDialogResult(text)
    }
    val request = state.voice.systemDialogRequest
    LaunchedEffect(request) {
        if (request == 0) return@LaunchedEffect
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            .putExtra(RecognizerIntent.EXTRA_LANGUAGE, state.settings.language)
            .putExtra(RecognizerIntent.EXTRA_PROMPT, "Speak to ${state.settings.assistantName}")
            .putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
        try {
            launcher.launch(intent)
        } catch (_: ActivityNotFoundException) {
            viewModel.onSystemDialogUnavailable()
        }
    }
}
