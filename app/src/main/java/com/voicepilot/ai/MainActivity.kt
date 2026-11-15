package com.voicepilot.ai

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.runtime.getValue
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.data.SettingsRepository
import com.voicepilot.ai.ui.assistant.AssistantViewModel
import com.voicepilot.ai.ui.navigation.VoicePilotNavHost
import com.voicepilot.ai.ui.theme.VoicePilotTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject lateinit var settingsRepository: SettingsRepository
    private val assistant: AssistantViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val onboarded = settingsRepository.current.onboardingComplete
        setContent {
            val settings by settingsRepository.settings.collectAsStateWithLifecycle()
            VoicePilotTheme(settings.theme) {
                VoicePilotNavHost(onboardingComplete = onboarded, assistant = assistant)
            }
        }
    }

    override fun onStop() {
        // Never keep the microphone open once VoicePilot is no longer visible.
        assistant.stopListening()
        super.onStop()
    }
}
