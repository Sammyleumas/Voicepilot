package com.voicepilot.ai.ui.navigation

import androidx.compose.runtime.Composable
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import com.voicepilot.ai.ui.assistant.AssistantViewModel
import com.voicepilot.ai.ui.assistant.ConversationScreen
import com.voicepilot.ai.ui.assistant.HomeScreen
import com.voicepilot.ai.ui.library.HistoryScreen
import com.voicepilot.ai.ui.library.LibraryViewModel
import com.voicepilot.ai.ui.library.MemoryScreen
import com.voicepilot.ai.ui.library.NotesScreen
import com.voicepilot.ai.ui.library.RoutinesScreen
import com.voicepilot.ai.ui.onboarding.OnboardingScreen
import com.voicepilot.ai.ui.settings.IntegrationsScreen
import com.voicepilot.ai.ui.settings.NotificationAssistantScreen
import com.voicepilot.ai.ui.settings.SettingsScreen
import com.voicepilot.ai.ui.settings.SettingsViewModel
import com.voicepilot.ai.ui.settings.SubscriptionScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val HOME = "home"
    const val CONVERSATION = "conversation"
    const val HISTORY = "history"
    const val SETTINGS = "settings"
    const val MEMORY = "memory"
    const val ROUTINES = "routines"
    const val NOTES = "notes"
    const val NOTIFICATIONS = "notifications"
    const val SUBSCRIPTION = "subscription"
    const val INTEGRATIONS = "integrations"
}

@Composable
fun VoicePilotNavHost(
    onboardingComplete: Boolean,
    assistant: AssistantViewModel,
    navController: NavHostController = rememberNavController(),
) {
    val back: () -> Unit = { navController.popBackStack() }
    NavHost(navController, startDestination = if (onboardingComplete) Routes.HOME else Routes.ONBOARDING) {
        composable(Routes.ONBOARDING) {
            OnboardingScreen(hiltViewModel<SettingsViewModel>()) {
                assistant.refreshAi()
                navController.navigate(Routes.HOME) { popUpTo(Routes.ONBOARDING) { inclusive = true } }
            }
        }
        composable(Routes.HOME) {
            HomeScreen(
                viewModel = assistant,
                onOpenConversation = { navController.navigate(Routes.CONVERSATION) },
                onOpenHistory = { navController.navigate(Routes.HISTORY) },
                onOpenSettings = { navController.navigate(Routes.SETTINGS) },
                onOpenNotes = { navController.navigate(Routes.NOTES) },
            )
        }
        composable(Routes.CONVERSATION) { ConversationScreen(assistant, back) }
        composable(Routes.HISTORY) {
            HistoryScreen(hiltViewModel<LibraryViewModel>(), back) { entry ->
                assistant.retry(entry)
                navController.navigate(Routes.CONVERSATION) { popUpTo(Routes.HOME) }
            }
        }
        composable(Routes.SETTINGS) {
            SettingsScreen(
                viewModel = hiltViewModel(),
                onBack = { assistant.refreshAi(); back() },
                onOpenMemory = { navController.navigate(Routes.MEMORY) },
                onOpenRoutines = { navController.navigate(Routes.ROUTINES) },
                onOpenNotifications = { navController.navigate(Routes.NOTIFICATIONS) },
                onOpenSubscription = { navController.navigate(Routes.SUBSCRIPTION) },
                onOpenIntegrations = { navController.navigate(Routes.INTEGRATIONS) },
            )
        }
        composable(Routes.MEMORY) { MemoryScreen(hiltViewModel(), back) }
        composable(Routes.ROUTINES) { RoutinesScreen(hiltViewModel(), back) }
        composable(Routes.NOTES) { NotesScreen(hiltViewModel(), back) }
        composable(Routes.NOTIFICATIONS) { NotificationAssistantScreen(hiltViewModel(), back) }
        composable(Routes.SUBSCRIPTION) { SubscriptionScreen(hiltViewModel(), back) }
        composable(Routes.INTEGRATIONS) { IntegrationsScreen(hiltViewModel(), back) }
    }
}
