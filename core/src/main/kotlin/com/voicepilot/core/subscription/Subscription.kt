package com.voicepilot.core.subscription

import com.voicepilot.core.time.Clock
import kotlinx.coroutines.flow.Flow
import java.time.LocalDate

enum class Plan(val label: String) { FREE("Free"), PREMIUM("Premium") }

enum class Feature(val label: String) {
    AI_INTERPRETATION("AI command understanding"),
    MULTI_STEP_PLANS("Multi-step workflows"),
    CUSTOM_ROUTINES("Custom routines"),
    NOTIFICATION_ASSISTANT("Notification assistant"),
    ADVANCED_PERSONALIZATION("Advanced personalization"),
    CLOUD_SYNC("Cloud sync"),
}

/** What a plan allows. The core assistant always works on the free plan. */
data class Entitlements(
    val plan: Plan,
    val dailyAiRequests: Int,
    val maxRoutines: Int,
    val maxPlanSteps: Int,
    val features: Set<Feature>,
) {
    fun has(feature: Feature) = feature in features

    companion object {
        fun forPlan(plan: Plan): Entitlements = when (plan) {
            Plan.FREE -> Entitlements(
                plan = plan,
                dailyAiRequests = 25,
                maxRoutines = 2,
                maxPlanSteps = 3,
                features = setOf(Feature.AI_INTERPRETATION, Feature.CUSTOM_ROUTINES, Feature.NOTIFICATION_ASSISTANT),
            )
            Plan.PREMIUM -> Entitlements(
                plan = plan,
                dailyAiRequests = 500,
                maxRoutines = 50,
                maxPlanSteps = 10,
                features = Feature.entries.toSet(),
            )
        }
    }
}

enum class SubscriptionState { NONE, ACTIVE, GRACE_PERIOD, ON_HOLD, EXPIRED, UNKNOWN }

data class SubscriptionStatus(val plan: Plan, val state: SubscriptionState, val expiresAtMillis: Long? = null)

data class ProductOffer(val productId: String, val title: String, val formattedPrice: String, val billingPeriod: String)

/**
 * Store billing integration (Google Play Billing in production). The app ships
 * an implementation that honestly reports when billing isn't configured.
 */
interface BillingProvider {
    val isAvailable: Boolean
    fun observeStatus(): Flow<SubscriptionStatus>
    suspend fun offers(): List<ProductOffer>
    /** Starts the store purchase flow. Returns false if it couldn't be started. */
    suspend fun launchPurchase(productId: String): Boolean
}

/** Persists daily usage counts. */
interface UsageStore {
    suspend fun count(day: LocalDate): Int
    suspend fun increment(day: LocalDate): Int
}

data class UsageSnapshot(val used: Int, val limit: Int) {
    val remaining: Int get() = (limit - used).coerceAtLeast(0)
}

/** Enforces the daily AI request allowance for the active plan. */
class UsageTracker(
    private val store: UsageStore,
    private val clock: Clock,
    private val entitlements: () -> Entitlements,
) {
    suspend fun snapshot(): UsageSnapshot =
        UsageSnapshot(store.count(today()), entitlements().dailyAiRequests)

    /** Records one AI request if allowance remains. Returns false when the limit is reached. */
    suspend fun tryConsume(): Boolean {
        val day = today()
        if (store.count(day) >= entitlements().dailyAiRequests) return false
        store.increment(day)
        return true
    }

    private fun today(): LocalDate = clock.localNow().toLocalDate()
}

class InMemoryUsageStore : UsageStore {
    private val counts = mutableMapOf<LocalDate, Int>()
    override suspend fun count(day: LocalDate) = synchronized(counts) { counts[day] ?: 0 }
    override suspend fun increment(day: LocalDate) = synchronized(counts) {
        val n = (counts[day] ?: 0) + 1
        counts[day] = n
        n
    }
}
