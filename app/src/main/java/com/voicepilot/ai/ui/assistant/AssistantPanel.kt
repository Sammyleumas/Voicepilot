package com.voicepilot.ai.ui.assistant

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.ErrorOutline
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.voicepilot.ai.platform.AndroidCapabilities
import com.voicepilot.ai.platform.GrantMethod
import com.voicepilot.ai.ui.components.PrimaryButton
import com.voicepilot.ai.ui.components.SecondaryButton
import com.voicepilot.ai.ui.components.VpCard
import com.voicepilot.ai.ui.theme.LocalStatusColors
import com.voicepilot.ai.ui.theme.Spacing
import com.voicepilot.core.conversation.AssistantStatus
import com.voicepilot.core.model.Capability
import com.voicepilot.core.model.RiskLevel
import com.voicepilot.core.planner.ActionDescriber

/** Short label for the current state, shown above the microphone. */
fun statusLabel(status: AssistantStatus, voice: VoiceState): String = when {
    voice.listening -> "Listening…"
    else -> when (status) {
        AssistantStatus.Idle -> "Ready"
        AssistantStatus.Processing -> "Understanding…"
        AssistantStatus.Planning -> "Planning…"
        is AssistantStatus.Executing -> "Working on step ${status.step} of ${status.total}"
        is AssistantStatus.AwaitingInput -> "Waiting for your answer"
        is AssistantStatus.AwaitingChoice -> "Choose one"
        is AssistantStatus.AwaitingConfirmation -> "Needs your approval"
        is AssistantStatus.NeedsPermission -> "Permission needed"
        is AssistantStatus.Success -> "Done"
        is AssistantStatus.Error -> "Couldn't finish"
    }
}

/**
 * Renders the assistant's current turn: the latest response, progress, a
 * confirmation card, a contact choice list or a permission request. Every
 * voice interaction also has a touch alternative here.
 */
@Composable
fun AssistantTurn(
    status: AssistantStatus,
    onConfirm: (Boolean) -> Unit,
    onChoose: (Int) -> Unit,
    onRetryPermissions: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val colors = LocalStatusColors.current
    AnimatedContent(
        targetState = status,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        contentKey = { it::class },
        label = "turn",
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
    ) { s ->
        when (s) {
            is AssistantStatus.Executing -> Column(Modifier.fillMaxWidth()) {
                Text(s.label, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Spacing.sm))
                LinearProgressIndicator(progress = { (s.step - 0.5f) / s.total }, modifier = Modifier.fillMaxWidth())
            }
            is AssistantStatus.AwaitingConfirmation -> ConfirmationCard(s, onConfirm)
            is AssistantStatus.AwaitingChoice -> VpCard {
                Text(s.question, style = MaterialTheme.typography.bodyLarge)
                Spacer(Modifier.height(Spacing.sm))
                s.options.forEachIndexed { i, option ->
                    TextButton(onClick = { onChoose(i) }, modifier = Modifier.fillMaxWidth()) {
                        Text("${i + 1}. $option", modifier = Modifier.fillMaxWidth())
                    }
                }
                TextButton(onClick = { onConfirm(false) }) { Text("None of these") }
            }
            is AssistantStatus.NeedsPermission -> PermissionCard(s, onRetryPermissions)
            is AssistantStatus.AwaitingInput -> ResponseText(s.question)
            is AssistantStatus.Success -> Row(verticalAlignment = Alignment.Top) {
                Icon(Icons.Filled.CheckCircle, contentDescription = null, tint = colors.success, modifier = Modifier.padding(top = 3.dp).size(18.dp))
                Spacer(Modifier.width(Spacing.sm))
                ResponseText(s.message, align = TextAlign.Start)
            }
            is AssistantStatus.Error -> Column {
                Row(verticalAlignment = Alignment.Top) {
                    Icon(Icons.Filled.ErrorOutline, contentDescription = null, tint = colors.danger, modifier = Modifier.padding(top = 3.dp).size(18.dp))
                    Spacer(Modifier.width(Spacing.sm))
                    ResponseText(s.message, align = TextAlign.Start)
                }
                s.hint?.let {
                    Spacer(Modifier.height(Spacing.xs))
                    Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            else -> Spacer(Modifier.height(0.dp))
        }
    }
}

@Composable
private fun ResponseText(text: String, align: TextAlign = TextAlign.Center) {
    Text(text, style = MaterialTheme.typography.bodyLarge, textAlign = align, modifier = Modifier.fillMaxWidth())
}

@Composable
private fun ConfirmationCard(s: AssistantStatus.AwaitingConfirmation, onConfirm: (Boolean) -> Unit) {
    val colors = LocalStatusColors.current
    VpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Shield, contentDescription = null, tint = if (s.plan.risk == RiskLevel.HIGH) colors.danger else colors.warning, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text(
                if (s.plan.risk == RiskLevel.HIGH) "Sensitive action — please review" else "Please review",
                style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.md))
        s.plan.steps.forEachIndexed { i, step ->
            Text(
                (if (s.plan.steps.size > 1) "${i + 1}. " else "") + ActionDescriber.describe(step.request),
                style = MaterialTheme.typography.bodyLarge,
                modifier = Modifier.padding(vertical = 2.dp),
            )
        }
        if (s.plan.steps.any { it.definition.outcome == com.voicepilot.core.model.OutcomeKind.HANDS_OFF }) {
            Spacer(Modifier.height(Spacing.sm))
            Text(
                "You'll complete the final step in the other app — VoicePilot won't send anything on its own.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton("Cancel", onClick = { onConfirm(false) }, modifier = Modifier.weight(1f))
            PrimaryButton("Go ahead", onClick = { onConfirm(true) }, modifier = Modifier.weight(1f))
        }
    }
}

@Composable
private fun PermissionCard(s: AssistantStatus.NeedsPermission, onRetry: () -> Unit) {
    val request = rememberCapabilityRequester()
    VpCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(Spacing.sm))
            Text("Permission needed", style = MaterialTheme.typography.labelLarge)
        }
        Spacer(Modifier.height(Spacing.sm))
        Text(s.message, style = MaterialTheme.typography.bodyMedium)
        s.capabilities.forEach { cap ->
            Spacer(Modifier.height(Spacing.xs))
            Text("• ${cap.displayName}: ${AndroidCapabilities.DESCRIPTIONS[cap]}", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Spacer(Modifier.height(Spacing.lg))
        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.md)) {
            SecondaryButton("Grant", onClick = { request(s.capabilities) }, modifier = Modifier.weight(1f))
            PrimaryButton("Retry", onClick = onRetry, modifier = Modifier.weight(1f))
        }
    }
}

/**
 * Returns a function that asks for capabilities the right way: a runtime
 * permission dialog when possible, otherwise the relevant system settings page.
 */
@Composable
fun rememberCapabilityRequester(onResult: (Boolean) -> Unit = {}): (Set<Capability>) -> Unit {
    val context = LocalContext.current
    val capabilities = remember(context) { AndroidCapabilities(context.applicationContext) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { results ->
        onResult(results.values.all { it })
    }
    return { caps ->
        val methods = caps.map(capabilities::grantMethod)
        val runtime = methods.filterIsInstance<GrantMethod.RuntimePermission>().map { it.permission }
        if (runtime.isNotEmpty()) {
            launcher.launch(runtime.toTypedArray())
        } else {
            methods.filterIsInstance<GrantMethod.SettingsPage>().firstOrNull()?.let { context.startSafely(it.intent) }
        }
    }
}

fun Context.startSafely(intent: Intent) {
    try {
        startActivity(intent)
    } catch (_: Exception) {
        startActivity(Intent(android.provider.Settings.ACTION_SETTINGS))
    }
}
