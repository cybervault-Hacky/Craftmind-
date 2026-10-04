package com.craftmind.app.domain.ai

/** Registry contains only adapters actually shipped in this app build. */
class AiProviderRegistry(adapters: List<AiProviderAdapter>) {
    private val adaptersById: Map<AiProviderId, AiProviderAdapter> = adapters.associateBy { it.definition.id }

    init {
        require(adaptersById.size == adapters.size) { "Provider IDs must be unique" }
    }

    fun providers(): List<AiProviderDefinition> = adaptersById.values
        .map(AiProviderAdapter::definition)
        .sortedBy(AiProviderDefinition::displayName)

    fun provider(id: AiProviderId): AiProviderDefinition? = adaptersById[id]?.definition

    fun adapter(id: AiProviderId): AiProviderAdapter? = adaptersById[id]
}
