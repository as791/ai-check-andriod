package com.genned.domain.model

/**
 * Deliberately never REAL/FAKE/DEFINITELY_* — those imply certainty this app cannot
 * back up without verified provenance. See [com.genned.domain.evidence.EvidenceEngine].
 */
enum class Classification {
    LOW,
    UNCERTAIN,
    HIGH,
}
