package com.voicepilot.ai.billing

import com.voicepilot.ai.di.ApplicationScope
import com.voicepilot.core.subscription.BillingProvider
import com.voicepilot.core.subscription.Entitlements
import com.voicepilot.core.subscription.Plan
import com.voicepilot.core.subscription.ProductOffer
import com.voicepilot.core.subscription.SubscriptionState
import com.voicepilot.core.subscription.SubscriptionStatus
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Honest placeholder until Google Play Billing is configured (needs a Play
 * Console app, subscription products and a backend to verify purchases).
 * It never grants Premium and never simulates a payment.
 * See docs/INTEGRATIONS.md › Google Play Billing.
 */
@Singleton
class UnconfiguredBillingProvider @Inject constructor() : BillingProvider {
    override val isAvailable: Boolean = false
    override fun observeStatus(): Flow<SubscriptionStatus> = flowOf(SubscriptionStatus(Plan.FREE, SubscriptionState.NONE))
    override suspend fun offers(): List<ProductOffer> = emptyList()
    override suspend fun launchPurchase(productId: String): Boolean = false
}

/** Current plan and entitlements derived from the billing provider. */
@Singleton
class SubscriptionRepository @Inject constructor(
    val billing: BillingProvider,
    @ApplicationScope scope: CoroutineScope,
) {
    val status: StateFlow<SubscriptionStatus> = billing.observeStatus()
        .stateIn(scope, SharingStarted.Eagerly, SubscriptionStatus(Plan.FREE, SubscriptionState.UNKNOWN))

    val entitlements: StateFlow<Entitlements> = status
        .map { s -> Entitlements.forPlan(if (s.state == SubscriptionState.ACTIVE || s.state == SubscriptionState.GRACE_PERIOD) s.plan else Plan.FREE) }
        .stateIn(scope, SharingStarted.Eagerly, Entitlements.forPlan(Plan.FREE))
}
