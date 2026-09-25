package com.genned.app.data.detection.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeneratorSignaturesTest {

    @Test
    fun `finds a known signature as a substring, case-insensitively`() {
        assertThat(GeneratorSignatures.matchSoftware("Generated with STABLE DIFFUSION webui"))
            .isEqualTo("Stable Diffusion")
    }

    @Test
    fun `finds a match anywhere in a longer free-form string`() {
        assertThat(GeneratorSignatures.matchSoftware("v1.2 / rendered via ComfyUI pipeline"))
            .isEqualTo("ComfyUI")
    }

    @Test
    fun `matches an AI tool named in the Software field`() {
        assertThat(GeneratorSignatures.matchSoftware("Adobe Firefly")).isEqualTo("Adobe Firefly")
    }

    @Test
    fun `returns null for ordinary camera software strings`() {
        assertThat(GeneratorSignatures.matchSoftware("Adobe Photoshop 25.0")).isNull()
        assertThat(GeneratorSignatures.matchSoftware("Google Pixel 8 Pro")).isNull()
    }

    @Test
    fun `returns null for blank or null input`() {
        assertThat(GeneratorSignatures.matchSoftware(null)).isNull()
        assertThat(GeneratorSignatures.matchSoftware("")).isNull()
        assertThat(GeneratorSignatures.matchSoftware("   ")).isNull()
        assertThat(GeneratorSignatures.matchStructuredParameters(null)).isNull()
        assertThat(GeneratorSignatures.matchStructuredParameters("   ")).isNull()
    }

    @Test
    fun `does not treat a caption mentioning an AI company as generator metadata`() {
        val caption = "OpenAI CEO Sam Altman speaks at a conference"

        assertThat(GeneratorSignatures.matchStructuredParameters(caption)).isNull()
        assertThat(GeneratorSignatures.matchStructuredParameters(caption, pngKeyword = "Description")).isNull()
    }

    @Test
    fun `matches an A1111-style parameter block in a user comment`() {
        val comment = "a cat\nSteps: 20, Sampler: Euler a, CFG scale: 7, Seed: 1"

        assertThat(GeneratorSignatures.matchStructuredParameters(comment)).isNotNull()
    }

    @Test
    fun `does not match a single parameter label on its own`() {
        assertThat(GeneratorSignatures.matchStructuredParameters("Seed: 5")).isNull()
    }

    @Test
    fun `matches a ComfyUI graph only in a prompt or workflow chunk`() {
        val graph = """{"3": {"class_type": "KSampler", "inputs": {}}}"""

        assertThat(GeneratorSignatures.matchStructuredParameters(graph, pngKeyword = "prompt")).isNotNull()
        assertThat(GeneratorSignatures.matchStructuredParameters(graph, pngKeyword = "Workflow")).isNotNull()
        assertThat(GeneratorSignatures.matchStructuredParameters(graph, pngKeyword = "Comment")).isNull()
        assertThat(GeneratorSignatures.matchStructuredParameters(graph)).isNull()
    }
}
