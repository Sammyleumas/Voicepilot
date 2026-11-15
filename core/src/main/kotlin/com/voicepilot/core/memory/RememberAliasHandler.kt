package com.voicepilot.core.memory

import com.voicepilot.core.action.ActionHandler
import com.voicepilot.core.action.Params
import com.voicepilot.core.contacts.ContactDirectory
import com.voicepilot.core.model.ActionRequest
import com.voicepilot.core.model.ActionResult
import com.voicepilot.core.model.FailureReason
import com.voicepilot.core.settings.AssistantSettings
import com.voicepilot.core.text.NameMatcher
import com.voicepilot.core.time.Clock

/**
 * Stores a user-approved contact nickname ("my brother" → "Samuel Okafor").
 * Respects the memory toggle and only stores names that exist in contacts.
 */
class RememberAliasHandler(
    private val memory: MemoryRepository,
    private val contacts: ContactDirectory,
    private val settings: () -> AssistantSettings,
    private val clock: Clock,
) : ActionHandler {

    override suspend fun execute(request: ActionRequest): ActionResult {
        if (!settings().memoryEnabled) {
            return ActionResult.Failed("Memory is turned off. You can enable it in Settings › Privacy.", FailureReason.BLOCKED_BY_POLICY, true)
        }
        val alias = MemoryRepository.normalizeKey(request.param(Params.ALIAS).orEmpty())
        val spokenName = request.param(Params.CONTACT).orEmpty()
        if (alias.isEmpty() || spokenName.isBlank()) {
            return ActionResult.Failed("I need both the nickname and the contact name.", FailureReason.INVALID_PARAMETERS, true)
        }
        val match = contacts.search(spokenName)
            .map { it to NameMatcher.score(spokenName, it.displayName) }
            .filter { it.second >= 0.85 }
            .maxByOrNull { it.second }
            ?.first
            ?: return ActionResult.Failed("I couldn't find $spokenName in your contacts.", FailureReason.NOT_FOUND, true)
        val now = clock.now().toEpochMilli()
        val existing = memory.find(MemoryKind.CONTACT_ALIAS, alias)
        memory.upsert(
            MemoryItem(
                id = existing?.id ?: 0,
                kind = MemoryKind.CONTACT_ALIAS,
                key = alias,
                value = match.displayName,
                createdAtMillis = existing?.createdAtMillis ?: now,
                updatedAtMillis = now,
            ),
        )
        return ActionResult.Completed("Got it. “My $alias” now means ${match.displayName}.")
    }
}
