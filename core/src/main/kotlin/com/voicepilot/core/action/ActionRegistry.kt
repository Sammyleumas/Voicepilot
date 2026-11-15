package com.voicepilot.core.action

import com.voicepilot.core.model.ActionId

/**
 * The allowlist of actions that can be planned and executed. Anything not
 * registered here is rejected before reaching a handler.
 */
class ActionRegistry(definitions: List<ActionDefinition> = BuiltInActions.all) {
    private val byId: Map<ActionId, ActionDefinition> = definitions.associateBy { it.id }

    init {
        require(byId.size == definitions.size) { "Duplicate action definitions" }
    }

    val definitions: Collection<ActionDefinition> get() = byId.values

    operator fun get(id: ActionId): ActionDefinition? = byId[id]

    fun require(id: ActionId): ActionDefinition =
        byId[id] ?: throw IllegalArgumentException("Action ${id.wireName} is not registered")

    fun isRegistered(id: ActionId): Boolean = id in byId
}
