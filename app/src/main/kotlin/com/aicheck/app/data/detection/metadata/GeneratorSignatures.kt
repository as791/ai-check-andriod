package com.aicheck.app.data.detection.metadata

/**
 * Publicly documented strings that generative-image tools are known to write into
 * EXIF (Software/UserComment/ImageDescription), XMP (CreatorTool/dc:creator), or PNG
 * text chunks (Stable Diffusion–family tools commonly write a "parameters" tEXt
 * chunk). This list is intentionally narrow and only grows as new, verifiable
 * signatures are confirmed — never guessed — since a false match here directly
 * inflates [com.aicheck.domain.model.SignalType.GENERATOR_METADATA]'s contribution.
 *
 * Matching is case-insensitive substring search. A match is real, observed metadata,
 * not an inference — but its absence proves nothing (see docs/ARCHITECTURE.md).
 */
object GeneratorSignatures {
    val KNOWN_SUBSTRINGS: List<String> = listOf(
        "Stable Diffusion",
        "stable-diffusion",
        "AUTOMATIC1111",
        "ComfyUI",
        "InvokeAI",
        "NovelAI",
        "Midjourney",
        "DALL-E",
        "DALL·E",
        "OpenAI",
        "Adobe Firefly",
        "Firefly",
        "Leonardo.Ai",
        "Leonardo AI",
        "Playground AI",
        "playground.ai",
        "NightCafe",
        "Bing Image Creator",
        "Microsoft Designer",
        "Canva Magic Media",
        "Ideogram",
        "Flux.1",
        "Recraft",
    )

    /** PNG tEXt/iTXt keyword used by Stable Diffusion WebUI/ComfyUI-family tools. */
    const val SD_PARAMETERS_CHUNK_KEYWORD = "parameters"

    fun findMatch(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return KNOWN_SUBSTRINGS.firstOrNull { text.contains(it, ignoreCase = true) }
    }
}
