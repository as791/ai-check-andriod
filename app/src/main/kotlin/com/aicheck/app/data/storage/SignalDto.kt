package com.aicheck.app.data.storage

import com.aicheck.domain.model.DetectionSignal
import com.aicheck.domain.model.SignalAvailability
import com.aicheck.domain.model.SignalType
import kotlinx.serialization.Serializable

/**
 * Persisted shape of [DetectionSignal]. A separate DTO (rather than making the
 * domain class itself `@Serializable`) keeps the domain module free of a
 * kotlinx.serialization dependency and lets storage's on-disk format evolve
 * independently of the in-memory model.
 */
@Serializable
data class SignalDto(
    val type: String,
    val availability: String,
    val score: Float?,
    val confidence: Float,
    val description: String,
    val evidence: String? = null,
)

fun DetectionSignal.toDto(): SignalDto = SignalDto(
    type = type.name,
    availability = availability.name,
    score = score,
    confidence = confidence,
    description = description,
    evidence = evidence,
)

fun SignalDto.toDomain(): DetectionSignal = DetectionSignal(
    type = SignalType.valueOf(type),
    availability = SignalAvailability.valueOf(availability),
    score = score,
    confidence = confidence,
    description = description,
    evidence = evidence,
)
