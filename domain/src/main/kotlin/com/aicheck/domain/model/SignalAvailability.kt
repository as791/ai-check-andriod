package com.aicheck.domain.model

/**
 * Whether a provider actually produced usable evidence. [UNAVAILABLE] must be used
 * (never a fabricated score) whenever a detector is not implemented, has no model
 * bundled, or the check is not applicable to the input — the UI is expected to show
 * this state honestly rather than hide it.
 */
enum class SignalAvailability {
    AVAILABLE,
    UNAVAILABLE,
    ERROR,
}
