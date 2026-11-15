package com.voicepilot.ai.ui.settings

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.NotificationsActive
import androidx.compose.material.icons.filled.NotificationsOff
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.voicepilot.ai.ui.assistant.rememberCapabilityRequester
import com.voicepilot.ai.ui.components.EmptyState
import com.voicepilot.ai.ui.components.GroupCard
import com.voicepilot.ai.ui.components.PrimaryButton
import com.voicepilot.ai.ui.components.SectionHeader
import com.voicepilot.ai.ui.components.SecondaryButton
import com.voicepilot.ai.ui.components.SettingRow
import com.voicepilot.ai.ui.components.SwitchRow
import com.voicepilot.ai.ui.components.TextInputDialog
import com.voicepilot.ai.ui.components.VpCard
import com.voicepilot.ai.ui.components.VpTopBar
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.core.action.BuiltInActions
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.RiskLevel
import com.voicepilot.core.notifications.NotificationFilter
import com.voicepilot.core.notifications.NotificationItem
import com.voicepilot.core.subscription.Entitlements
import com.voicepilot.core.subscription.Feature
import com.voicepilot.core.subscription.Plan

/** Enable or disable each allowlisted action. */
@Composable
fun IntegrationsScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    Scaffold(topBar = { VpTopBar("Integrations", onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screen)) {
            item {
                Text(
                    "Turn off anything you don't want VoicePilot to do. Disabled actions are refused even if the AI suggests them.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md),
                )
            }
            items(BuiltInActions.all, key = { it.id.wireName }) { def ->
                SwitchRow(
                    title = def.title,
                    subtitle = "${def.description} Risk: ${def.risk.name.lowercase()}${if (def.risk != RiskLevel.LOW) " · asks first by default" else ""}",
                    checked = def.id !in s.disabledActions,
                    onCheckedChange = { enabled ->
                        viewModel.update { st -> st.copy(disabledActions = if (enabled) st.disabledActions - def.id else st.disabledActions + def.id) }
                    },
                )
            }
        }
    }
}

/** Plan comparison. Purchasing is only offered when store billing is configured. */
@Composable
fun SubscriptionScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val current by viewModel.entitlements.collectAsStateWithLifecycle()
    val usage by viewModel.usageSnapshot.collectAsStateWithLifecycle()
    Scaffold(topBar = { VpTopBar("Subscription", onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(horizontal = Spacing.screen)) {
            SectionHeader("Your plan")
            VpCard {
                Text(current.plan.label, style = MaterialTheme.typography.titleLarge)
                Text("Used ${usage.used} of ${usage.limit} AI requests today. On-device commands are unlimited.", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Plan.entries.forEach { plan -> PlanCard(Entitlements.forPlan(plan), current.plan == plan) }
            Spacer(Modifier.height(Spacing.lg))
            if (viewModel.billingAvailable) {
                PrimaryButton("Upgrade to Premium", onClick = viewModel::startPurchase)
            } else {
                Text(
                    "Premium isn't available to buy in this build yet. Store billing will be enabled with the Google Play release. Everything else works on the Free plan.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(Spacing.xxl))
        }
    }
}

@Composable
private fun PlanCard(e: Entitlements, active: Boolean) {
    SectionHeader(e.plan.label + if (active) " · current" else "")
    VpCard {
        listOf(
            "${e.dailyAiRequests} AI requests per day",
            "Up to ${e.maxPlanSteps} steps per request",
            "Up to ${e.maxRoutines} routines",
        ).plus(Feature.entries.filter { e.has(it) }.map { it.label }).forEach {
            SettingRow(it, icon = if (e.plan == Plan.PREMIUM) Icons.Filled.Star else Icons.Filled.Check, trailing = null)
        }
    }
}

/** Notification assistant: access, allowed apps, announcements and a live summary. */
@Composable
fun NotificationAssistantScreen(viewModel: SettingsViewModel, onBack: () -> Unit) {
    val s by viewModel.settings.collectAsStateWithLifecycle()
    val caps by viewModel.capabilities.collectAsStateWithLifecycle()
    val apps by viewModel.installedApps.collectAsStateWithLifecycle()
    val items by viewModel.notificationHub.items.collectAsStateWithLifecycle()
    val request = rememberCapabilityRequester { viewModel.refresh() }
    var replyTo by rememberSaveable { mutableStateOf<String?>(null) }
    val hasAccess = caps[Capability.NOTIFICATION_LISTENER] == true

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) { viewModel.refresh() }
    LaunchedEffect(Unit) { viewModel.loadApps() }

    Scaffold(topBar = { VpTopBar("Notification assistant", onBack = onBack) }, containerColor = MaterialTheme.colorScheme.background) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding).padding(horizontal = Spacing.screen)) {
            item {
                Text(
                    "VoicePilot can summarise and announce notifications from apps you pick, and reply where the app offers a reply button. " +
                        "Notifications stay in memory on this device and are never uploaded. It can't read full chat histories.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = Spacing.md),
                )
                GroupCard {
                    SwitchRow("Notification assistant", if (hasAccess) "Access granted" else "Needs notification access", s.notificationAssistantEnabled, { v ->
                        if (v && !hasAccess) request(setOf(Capability.NOTIFICATION_LISTENER))
                        viewModel.update { it.copy(notificationAssistantEnabled = v) }
                    })
                    SwitchRow("Announce new notifications", "Says who it's from — never the content", s.announceNotifications, { v -> viewModel.update { it.copy(announceNotifications = v) } }, enabled = s.notificationAssistantEnabled)
                    SettingRow("Clear cached notifications", null, onClick = viewModel::clearNotificationCache)
                }
                SectionHeader("Right now")
            }
            if (!s.notificationAssistantEnabled || !hasAccess) {
                item { EmptyState("Assistant is off", "Turn it on and grant access to see a summary here.", Icons.Filled.NotificationsOff) }
            } else if (items.isEmpty()) {
                item { EmptyState("All clear", "No notifications from your allowed apps.", Icons.Filled.NotificationsActive) }
            } else {
                item {
                    VpCard { Text(viewModel.notificationHub.summary(), style = MaterialTheme.typography.bodyLarge) }
                    Spacer(Modifier.height(Spacing.sm))
                }
                items(items, key = { it.key }) { n -> NotificationRow(n, onReply = { replyTo = n.key }, onOpen = { viewModel.notificationHub.open(n.key) }) }
            }
            item { SectionHeader("Allowed apps") }
            val sorted = apps.sortedByDescending { it.packageName in NotificationFilter.MESSAGING_PACKAGES }
            items(sorted, key = { it.packageName }) { app ->
                SwitchRow(app.label, null, app.packageName in s.notificationAllowedPackages, { allowed ->
                    viewModel.update { st ->
                        st.copy(notificationAllowedPackages = if (allowed) st.notificationAllowedPackages + app.packageName else st.notificationAllowedPackages - app.packageName)
                    }
                })
            }
        }
    }

    replyTo?.let { key ->
        val target = items.firstOrNull { it.key == key }
        TextInputDialog(
            title = "Reply to ${target?.title ?: "message"}",
            initial = "",
            onDismiss = { replyTo = null },
            onConfirm = { text ->
                if (text.isNotBlank()) viewModel.notificationHub.reply(key, text)
                replyTo = null
            },
            supporting = "This sends immediately through ${target?.appLabel ?: "the app"}'s reply button.",
            maxLength = 1000,
            confirmLabel = "Send",
        )
    }
}

@Composable
private fun NotificationRow(n: NotificationItem, onReply: () -> Unit, onOpen: () -> Unit) {
    VpCard(onClick = onOpen, modifier = Modifier.padding(vertical = Spacing.xs)) {
        Text("${n.appLabel} · ${n.title}", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(n.text, style = MaterialTheme.typography.bodyMedium, maxLines = 3)
        if (n.canReply) {
            Spacer(Modifier.height(Spacing.sm))
            SecondaryButton("Reply", onClick = onReply)
        }
    }
}
