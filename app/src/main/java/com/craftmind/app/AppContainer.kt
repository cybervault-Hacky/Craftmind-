package com.craftmind.app

import android.content.Context
import com.craftmind.app.data.ai.AiBuildEngine
import com.craftmind.app.data.ai.BuildPlanParser
import com.craftmind.app.data.ai.ContentResolverImageInputPreparer
import com.craftmind.app.data.ai.GoogleGeminiProviderAdapter
import com.craftmind.app.data.reference.AndroidPublicVideoFrameExtractor
import com.craftmind.app.data.reference.GitHubRawVideoReferenceResolver
import com.craftmind.app.data.reference.PublicVideoHttpClient
import com.craftmind.app.data.builds.AtomicLocalBuildRepository
import com.craftmind.app.data.builds.AtomicLocalBuildExecutionRepository
import com.craftmind.app.data.minecraft.AndroidMinecraftBridgePairingRepository
import com.craftmind.app.data.account.AndroidKeystoreAccountSessionStore
import com.craftmind.app.data.account.FileLocalOwnershipMarkerStore
import com.craftmind.app.data.security.AndroidKeystoreCredentialStore
import com.craftmind.app.data.settings.DataStoreAiProviderSelectionRepository
import com.craftmind.app.data.settings.DataStoreThemePreferenceRepository
import com.craftmind.app.domain.account.AccountSessionManager
import com.craftmind.app.domain.account.AccountSessionStore
import com.craftmind.app.domain.account.CraftMindAccountFoundation
import com.craftmind.app.domain.account.LocalOwnershipMigration
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.buildplan.BuildHistoryPolicy
import com.craftmind.app.domain.buildplan.DefaultBuildPlanValidator
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRepository
import com.craftmind.app.domain.minecraft.MinecraftBridgePairingRepository
import com.craftmind.app.domain.minecraft.compatibility.DefaultMinecraftCompatibility
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.security.CredentialStore
import com.craftmind.app.domain.settings.ThemePreferenceRepository

class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val themePreferences: ThemePreferenceRepository = DataStoreThemePreferenceRepository(appContext)
    val credentialStore: CredentialStore = AndroidKeystoreCredentialStore(appContext)

    /**
     * Account layer (Phase 16). The authenticator comes from [CraftMindAccountFoundation], which in this build reports
     * that no account service exists; the session store is a separate Keystore namespace from [credentialStore], so an
     * account secret and an AI provider key can never be confused for one another.
     */
    val accountSessionStore: AccountSessionStore = AndroidKeystoreAccountSessionStore(appContext)
    val accountSessionManager: AccountSessionManager =
        CraftMindAccountFoundation.sessionManager(sessionStore = accountSessionStore)
    val accountOwnershipMigration: LocalOwnershipMigration =
        LocalOwnershipMigration(store = FileLocalOwnershipMarkerStore(appContext))
    val providerRegistry = AiProviderRegistry(listOf(GoogleGeminiProviderAdapter()))
    val providerSelections = DataStoreAiProviderSelectionRepository(appContext)
    val buildPlanValidator = DefaultBuildPlanValidator()
    val buildPlanParser = BuildPlanParser(buildPlanValidator)
    private val publicVideoHttpClient = PublicVideoHttpClient.create()
    val publicVideoReferenceResolver = GitHubRawVideoReferenceResolver(publicVideoHttpClient)
    val publicVideoFrameExtractor = AndroidPublicVideoFrameExtractor(publicVideoHttpClient)
    val aiBuildEngine = AiBuildEngine(
        registry = providerRegistry,
        credentialStore = credentialStore,
        selections = providerSelections,
        parser = buildPlanParser,
        planValidator = buildPlanValidator,
        imageInputPreparer = ContentResolverImageInputPreparer(appContext),
        publicVideoReferenceResolver = publicVideoReferenceResolver,
        publicVideoFrameExtractor = publicVideoFrameExtractor,
    )
    val buildHistoryPolicy = BuildHistoryPolicy(buildPlanValidator)
    val localBuilds: LocalBuildRepository = AtomicLocalBuildRepository(appContext, historyPolicy = buildHistoryPolicy)
    val localBuildExecutions: LocalBuildExecutionRepository = AtomicLocalBuildExecutionRepository(appContext)
    val minecraftCompatibilityResolver: MinecraftCompatibilityResolver = DefaultMinecraftCompatibility.resolver
    val minecraftBridge: MinecraftBridgePairingRepository =
        AndroidMinecraftBridgePairingRepository(appContext, minecraftCompatibilityResolver)
}
