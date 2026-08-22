package com.aicheck.domain.model

/**
 * Monetization seam only — not wired to any paywall in V1. A future Pro tier plugs in
 * by adding a real [EntitlementProvider] implementation; every caller already goes
 * through this indirection so nothing else has to change. See docs/ROADMAP.md.
 */
enum class Entitlement {
    FREE,
    PRO,
}

fun interface EntitlementProvider {
    fun current(): Entitlement
}

/** The only implementation in V1: everyone is FREE, nothing is gated. */
class AlwaysFreeEntitlementProvider : EntitlementProvider {
    override fun current(): Entitlement = Entitlement.FREE
}
