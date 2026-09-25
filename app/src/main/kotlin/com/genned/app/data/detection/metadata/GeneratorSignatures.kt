package com.genned.app.data.detection.metadata

/**
 * Publicly documented signatures that generative-image tools are known to write into
 * image metadata. Two separate checks, because a false match here directly inflates
 * [com.genned.domain.model.SignalType.GENERATOR_METADATA]'s contribution:
 *
 * - [matchSoftware]: tool names, applied only to fields that name the producing
 *   software (EXIF Software, PNG "Software" chunk). Never applied to free text such
 *   as captions or artist names, where "OpenAI" or "Firefly" can appear innocently.
 * - [matchStructuredParameters]: applied to free-text fields; matches only
 *   recognizable generation-parameter blocks (A1111-style settings, ComfyUI graphs).
 *
 * The tool-name list is intentionally narrow and only grows as new, verifiable
 * signatures are confirmed — never guessed. A match is real, observed metadata, not
 * an inference — but its absence proves nothing (see internal-docs/ARCHITECTURE.md).
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

    /** Labels AUTOMATIC1111/Stable Diffusion WebUI writes into its settings line. */
    private val SD_PARAMETER_LABELS: List<String> = listOf(
        "Steps:",
        "Sampler:",
        "CFG scale:",
        "Seed:",
        "Model hash:",
    )

    private val COMFYUI_CHUNK_KEYWORDS: List<String> = listOf("prompt", "workflow")

    /** Case-insensitive tool-name match; only for Software-type fields. */
    fun matchSoftware(text: String?): String? {
        if (text.isNullOrBlank()) return null
        return KNOWN_SUBSTRINGS.firstOrNull { text.contains(it, ignoreCase = true) }
    }

    /**
     * Matches a generation-parameter block in free text, returning a short label for
     * what was found. [pngKeyword] is the PNG text-chunk keyword, when the text came
     * from one (needed to recognize ComfyUI's graph chunks).
     */
    fun matchStructuredParameters(text: String?, pngKeyword: String? = null): String? {
        if (text.isNullOrBlank()) return null
        val isComfyUiChunk = pngKeyword != null &&
            COMFYUI_CHUNK_KEYWORDS.any { pngKeyword.equals(it, ignoreCase = true) }
        if (isComfyUiChunk && text.contains("\"class_type\"")) {
            return "ComfyUI workflow"
        }
        // One label alone (e.g. "Seed: 5") is too common in ordinary text to count.
        val labelCount = SD_PARAMETER_LABELS.count { text.contains(it, ignoreCase = true) }
        return if (labelCount >= 2) "Stable Diffusion–style generation parameters" else null
    }
}
