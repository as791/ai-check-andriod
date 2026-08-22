package com.aicheck.domain.model

/**
 * Deliberately never REAL/FAKE/DEFINITELY_* — those imply certainty this app cannot
 * back up without verified provenance. See [com.aicheck.domain.evidence.EvidenceEngine].
 */
enum class Classification {
    LOW,
    UNCERTAIN,
    HIGH,
}
