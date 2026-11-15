package com.voicepilot.ai.ui.assistant

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle

/** Lets the visible screen receive wake phrase detections from the hands-free session. */
@Composable
fun WakeListenerEffect(viewModel: AssistantViewModel) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(viewModel, lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            viewModel.screenVisible(true)
            try {
                viewModel.wakeDetections.collect { viewModel.onWake(it) }
            } finally {
                viewModel.screenVisible(false)
            }
        }
    }
}
