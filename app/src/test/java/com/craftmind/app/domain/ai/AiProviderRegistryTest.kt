package com.craftmind.app.domain.ai

import com.craftmind.app.domain.security.ProviderCredential
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class AiProviderRegistryTest {
    @Test
    fun exposesOnlyRegisteredAdaptersAndSortsDisplayDefinitions() {
        val zeta = FakeAdapter(AiProviderId("zeta"), "Zeta")
        val alpha = FakeAdapter(AiProviderId("alpha"), "Alpha")
        val registry = AiProviderRegistry(listOf(zeta, alpha))

        assertEquals(listOf("Alpha", "Zeta"), registry.providers().map { it.displayName })
        assertEquals(alpha, registry.adapter(AiProviderId("alpha")))
        assertNull(registry.adapter(AiProviderId("unsupported")))
        assertNotNull(registry.provider(AiProviderId("zeta")))
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsDuplicateProviderIdentifiers() {
        AiProviderRegistry(
            listOf(
                FakeAdapter(AiProviderId("duplicate"), "One"),
                FakeAdapter(AiProviderId("duplicate"), "Two"),
            ),
        )
    }

    private class FakeAdapter(
        override val definition: AiProviderDefinition,
    ) : AiProviderAdapter {
        constructor(id: AiProviderId, name: String) : this(
            AiProviderDefinition(
                id = id,
                displayName = name,
                credentialType = CredentialType.API_KEY,
                baseEndpoint = "https://example.invalid/",
                capabilities = AiProviderCapabilities(
                    textGeneration = true,
                    vision = false,
                    publicUrlReferences = false,
                    structuredOutput = StructuredOutputMode.JSON_MIME_TYPE,
                    cancellation = true,
                    streaming = false,
                ),
            ),
        )

        override suspend fun listModels(credential: ProviderCredential): List<AiModel> = emptyList()
        override suspend fun generateContent(request: AiProviderRequest, credential: ProviderCredential) =
            AiProviderResponse("{}")
    }
}
