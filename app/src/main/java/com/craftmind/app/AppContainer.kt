package com.craftmind.app

import android.content.Context
import com.craftmind.app.data.ai.AiBuildEngine
import com.craftmind.app.data.ai.BuildPlanParser
import com.craftmind.app.data.ai.GoogleGeminiProviderAdapter
import com.craftmind.app.data.builds.AtomicLocalBuildRepository
import com.craftmind.app.data.minecraft.AndroidMinecraftBridgePairingRepository
import com.craftmind.app.data.security.AndroidKeystoreCredentialStore
import com.craftmind.app.data.settings.DataStoreAiProviderSelectionRepository
import com.craftmind.app.data.settings.DataStoreThemePreferenceRepository
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.buildplan.BuildHistoryPolicy
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.security.CredentialStore
import com.craftmind.app.domain.settings.ThemePreferenceRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val themePreferences: ThemePreferenceRepository = DataStoreThemePreferenceRepository(appContext)
    val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(appContext)
    val providerRegistry = AiProviderRegistry(listOf(GoogleGeminiProviderAdapter()))
    val providerSelections = DataStoreAiProviderSelectionRepository(appContext)
    val buildPlanValidator = DefaultBuildPlanValidator()
    val buildPlanParser = BuildPlanParser(buildPlanValidator)
    val aiBuildEngine = AiBuildEngine(
        registry = providerRegistry,
        credentialStore = credentialStore,
        selections = providerSelections,
        parser = buildPlanParser,
        planValidator = buildPlanValidator,
    )
    val buildHistoryPolicy = BuildHistoryPolicy(buildPlanValidator)
    val localBuilds: LocalBuildRepository = AtomicLocalBuildRepository(appContext, historyPolicy = buildHistoryPolicy)
    val minecraftBridge: MinecraftBridgePairingRepository = AndroidMinecraftBridgePairingRepository(appContext)
}
