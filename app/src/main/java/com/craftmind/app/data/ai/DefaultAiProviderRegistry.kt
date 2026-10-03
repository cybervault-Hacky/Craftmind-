package com.craftmind.app.data.ai

import com.craftmind.app.data.ai.openai.OpenAiProvider
import com.craftmind.app.data.ai.openai.OkHttpOpenAiHttpTransport
import com.craftmind.app.domain.ai.AiProvider
import com.craftmind.app.domain.ai.AiProviderDescriptor
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.ProviderId

/** Production registry contains only the provider adapter actually implemented in this phase. */
internal class DefaultAiProviderRegistry(
    openAiProvider: AiProvider = OpenAiProvider(OkHttpOpenAiHttpTransport()),
) : AiProviderRegistry {
    private val providers = listOf(openAiProvider).associateBy { it.descriptor.id }

    override fun availableProviders(): List<AiProviderDescriptor> =
        providers.values.map { it.descriptor }.sortedBy { it.displayName }

    override fun find(providerId: ProviderId): AiProvider? = providers[providerId]
}
