package com.aicheck.app.data.detection.metadata

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GeneratorSignaturesTest {

    @Test
    fun `finds a known signature as a substring, case-insensitively`() {
        assertThat(GeneratorSignatures.findMatch("Generated with STABLE DIFFUSION webui"))
            .isEqualTo("Stable Diffusion")
    }

    @Test
    fun `finds a match anywhere in a longer free-form string`() {
        assertThat(GeneratorSignatures.findMatch("v1.2 / rendered via ComfyUI pipeline"))
            .isEqualTo("ComfyUI")
    }

    @Test
    fun `returns null for ordinary camera software strings`() {
        assertThat(GeneratorSignatures.findMatch("Adobe Photoshop 25.0")).isNull()
        assertThat(GeneratorSignatures.findMatch("Google Pixel 8 Pro")).isNull()
    }

    @Test
    fun `returns null for blank or null input`() {
        assertThat(GeneratorSignatures.findMatch(null)).isNull()
        assertThat(GeneratorSignatures.findMatch("")).isNull()
        assertThat(GeneratorSignatures.findMatch("   ")).isNull()
    }
}
