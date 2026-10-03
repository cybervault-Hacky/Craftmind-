package com.craftmind.app

import android.content.Context
import com.craftmind.app.data.ai.DefaultAiProviderRegistry
import com.craftmind.app.data.credentials.AndroidKeystoreAesGcmCipher
import com.craftmind.app.data.credentials.DataStoreCredentialRecordStore
import com.craftmind.app.data.credentials.EncryptedCredentialStore
import com.craftmind.app.data.media.AndroidImageReferenceRepository
import com.craftmind.app.data.settings.DataStoreProviderConfigurationRepository
import com.craftmind.app.data.settings.DataStoreSettingsRepository
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.CredentialStore
import com.craftmind.app.domain.ai.TestProviderConnectionUseCase
import com.craftmind.app.domain.planning.BuildPlanValidator
import com.craftmind.app.domain.planning.GenerateBuildPlanUseCase
import com.craftmind.app.domain.validation.BuildRequestValidator

/** Application-scoped composition root; production providers always use encrypted credentials. */
class AppContainer(context: Context) {
    private val applicationContext = context.applicationContext

    val imageReferenceRepository = AndroidImageReferenceRepository(applicationContext.contentResolver)
    val settingsRepository = DataStoreSettingsRepository(applicationContext)
    val providerConfigurationRepository = DataStoreProviderConfigurationRepository(applicationContext)
    val credentialStore: CredentialStore = EncryptedCredentialStore(
        cipher = AndroidKeystoreAesGcmCipher(),
        records = DataStoreCredentialRecordStore(applicationContext),
    )
    val aiProviderRegistry: AiProviderRegistry = DefaultAiProviderRegistry()
    val generateBuildPlanUseCase = GenerateBuildPlanUseCase(
        requestValidator = BuildRequestValidator(),
        configurationRepository = providerConfigurationRepository,
        credentialStore = credentialStore,
        providerRegistry = aiProviderRegistry,
        planValidator = BuildPlanValidator(),
    )
    val testProviderConnectionUseCase = TestProviderConnectionUseCase(
        credentialStore = credentialStore,
        providerRegistry = aiProviderRegistry,
    )
}
