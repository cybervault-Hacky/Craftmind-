package com.craftmind.app.presentation.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.ai.AiModelIdValidator
import com.craftmind.app.domain.ai.AiProviderDescriptor
import com.craftmind.app.domain.ai.AiProviderRegistry
import com.craftmind.app.domain.ai.CredentialAlias
import com.craftmind.app.domain.ai.CredentialLimits
import com.craftmind.app.domain.ai.CredentialStore
import com.craftmind.app.domain.ai.ProviderConfiguration
import com.craftmind.app.domain.ai.ProviderConfigurationRepository
import com.craftmind.app.domain.ai.ProviderConnectionResult
import com.craftmind.app.domain.ai.ProviderId
import com.craftmind.app.domain.ai.SecretValue
import com.craftmind.app.domain.ai.TestProviderConnectionUseCase
import com.craftmind.app.domain.model.BuildLimits
import com.craftmind.app.domain.model.BuildError
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

sealed interface ProviderConnectionUiState {
    data object Idle : ProviderConnectionUiState
    data object Testing : ProviderConnectionUiState
    data object Connected : ProviderConnectionUiState
    data class Failed(val error: BuildError) : ProviderConnectionUiState
}

enum class ProviderSettingsMessage {
    PROVIDER_REQUIRED,
    MODEL_REQUIRED,
    MODEL_INVALID,
    API_KEY_EMPTY,
    API_KEY_TOO_LONG,
    STORAGE_ERROR,
}

data class AiProviderSettingsUiState(
    val providers: List<AiProviderDescriptor> = emptyList(),
    val providerId: ProviderId? = null,
    val modelId: String = "",
    val apiKeyConfigured: Boolean = false,
    val isLoading: Boolean = true,
    val isSavingConfiguration: Boolean = false,
    val isSavingCredential: Boolean = false,
    val connection: ProviderConnectionUiState = ProviderConnectionUiState.Idle,
    val message: ProviderSettingsMessage? = null,
)

class AiProviderSettingsViewModel(
    private val configurationRepository: ProviderConfigurationRepository,
    private val credentialStore: CredentialStore,
    private val providerRegistry: AiProviderRegistry,
    private val testProviderConnection: TestProviderConnectionUseCase,
) : ViewModel() {
    private val _uiState = MutableStateFlow(
        AiProviderSettingsUiState(providers = providerRegistry.availableProviders()),
    )
    val uiState: StateFlow<AiProviderSettingsUiState> = _uiState.asStateFlow()
    private var credentialCheckSequence = 0L

    init {
        viewModelScope.launch {
            configurationRepository.configuration
                .catch { error ->
                    if (error is CancellationException) throw error
                    _uiState.update {
                        it.copy(isLoading = false, message = ProviderSettingsMessage.STORAGE_ERROR)
                    }
                }
                .collect { configuration ->
                _uiState.update {
                    it.copy(
                        providerId = configuration.providerId,
                        modelId = configuration.modelId,
                        isLoading = false,
                        connection = if (it.connection == ProviderConnectionUiState.Testing) {
                            ProviderConnectionUiState.Testing
                        } else {
                            ProviderConnectionUiState.Idle
                        },
                    )
                }
                refreshCredentialStatus(configuration.providerId)
            }
        }
    }

    fun selectProvider(providerId: ProviderId) {
        if (providerRegistry.find(providerId) == null) return
        val current = _uiState.value
        if (current.isSavingCredential || current.isSavingConfiguration || current.connection == ProviderConnectionUiState.Testing) return
        if (current.providerId == providerId) return
        saveConfiguration(ProviderConfiguration(providerId, current.modelId))
    }

    fun onModelIdChanged(value: String) {
        _uiState.update {
            it.copy(
                modelId = value.take(BuildLimits.MAX_MODEL_ID_CHARACTERS),
                connection = ProviderConnectionUiState.Idle,
                message = null,
            )
        }
    }

    fun saveConfiguration() {
        val current = _uiState.value
        if (current.isSavingConfiguration || current.isSavingCredential || current.connection == ProviderConnectionUiState.Testing) return
        val providerId = current.providerId
        if (providerId == null) {
            _uiState.update { it.copy(message = ProviderSettingsMessage.PROVIDER_REQUIRED) }
            return
        }
        val modelId = AiModelIdValidator.normalize(current.modelId)
        if (modelId == null) {
            _uiState.update {
                it.copy(message = if (current.modelId.isBlank()) ProviderSettingsMessage.MODEL_REQUIRED else ProviderSettingsMessage.MODEL_INVALID)
            }
            return
        }
        saveConfiguration(ProviderConfiguration(providerId, modelId))
    }

    fun saveApiKey(value: CharArray) {
        val currentState = _uiState.value
        if (currentState.isSavingCredential || currentState.isSavingConfiguration) {
            value.fill('\u0000')
            return
        }
        val providerId = currentState.providerId
        if (providerId == null) {
            value.fill('\u0000')
            _uiState.update { it.copy(message = ProviderSettingsMessage.PROVIDER_REQUIRED) }
            return
        }
        if (value.isEmpty() || value.all { it.isWhitespace() }) {
            value.fill('\u0000')
            _uiState.update { it.copy(message = ProviderSettingsMessage.API_KEY_EMPTY) }
            return
        }
        if (value.size > CredentialLimits.MAX_API_KEY_CHARACTERS || value.any { it !in '!'..'~' }) {
            value.fill('\u0000')
            _uiState.update { it.copy(message = ProviderSettingsMessage.API_KEY_TOO_LONG) }
            return
        }

        val secret = try {
            SecretValue.copyOf(value)
        } finally {
            value.fill('\u0000')
        }
        credentialCheckSequence++
        _uiState.update { it.copy(isSavingCredential = true, message = null, connection = ProviderConnectionUiState.Idle) }
        val job = viewModelScope.launch(start = CoroutineStart.LAZY) {
            try {
                credentialStore.put(CredentialAlias(providerId.value), secret)
                _uiState.update { it.copy(apiKeyConfigured = true, isSavingCredential = false, message = null) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isSavingCredential = false, message = ProviderSettingsMessage.STORAGE_ERROR)
                }
            } finally {
                secret.clear()
            }
        }
        job.invokeOnCompletion { secret.clear() }
        job.start()
    }

    fun removeApiKey() {
        val current = _uiState.value
        if (current.isSavingCredential || current.isSavingConfiguration) return
        val providerId = current.providerId ?: return
        credentialCheckSequence++
        _uiState.update { it.copy(isSavingCredential = true, message = null, connection = ProviderConnectionUiState.Idle) }
        viewModelScope.launch {
            try {
                credentialStore.remove(CredentialAlias(providerId.value))
                _uiState.update { it.copy(apiKeyConfigured = false, isSavingCredential = false, connection = ProviderConnectionUiState.Idle) }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update { it.copy(isSavingCredential = false, message = ProviderSettingsMessage.STORAGE_ERROR) }
            }
        }
    }

    fun testConnection() {
        val current = _uiState.value
        val providerId = current.providerId
        if (providerId == null) {
            _uiState.update { it.copy(message = ProviderSettingsMessage.PROVIDER_REQUIRED) }
            return
        }
        val modelId = AiModelIdValidator.normalize(current.modelId)
        if (modelId == null) {
            _uiState.update {
                it.copy(message = if (current.modelId.isBlank()) ProviderSettingsMessage.MODEL_REQUIRED else ProviderSettingsMessage.MODEL_INVALID)
            }
            return
        }
        if (current.isSavingCredential || current.isSavingConfiguration || current.connection == ProviderConnectionUiState.Testing) return

        _uiState.update {
            it.copy(connection = ProviderConnectionUiState.Testing, message = null, isSavingConfiguration = true)
        }
        viewModelScope.launch {
            try {
                val configuration = ProviderConfiguration(providerId, modelId)
                configurationRepository.save(configuration)
                val result = testProviderConnection(configuration)
                _uiState.update {
                    it.copy(
                        providerId = providerId,
                        modelId = modelId,
                        isSavingConfiguration = false,
                        connection = when (result) {
                            ProviderConnectionResult.Connected -> ProviderConnectionUiState.Connected
                            is ProviderConnectionResult.Failed -> ProviderConnectionUiState.Failed(result.error)
                        },
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(
                        isSavingConfiguration = false,
                        connection = ProviderConnectionUiState.Idle,
                        message = ProviderSettingsMessage.STORAGE_ERROR,
                    )
                }
            }
        }
    }

    private fun saveConfiguration(configuration: ProviderConfiguration) {
        _uiState.update { it.copy(isSavingConfiguration = true, message = null, connection = ProviderConnectionUiState.Idle) }
        viewModelScope.launch {
            try {
                configurationRepository.save(configuration)
                val hasCredential = configuration.providerId?.let { providerId ->
                    credentialStore.contains(CredentialAlias(providerId.value))
                } ?: false
                _uiState.update {
                    it.copy(
                        providerId = configuration.providerId,
                        modelId = configuration.modelId,
                        apiKeyConfigured = hasCredential,
                        isSavingConfiguration = false,
                    )
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                _uiState.update {
                    it.copy(isSavingConfiguration = false, message = ProviderSettingsMessage.STORAGE_ERROR)
                }
            }
        }
    }

    private suspend fun refreshCredentialStatus(providerId: ProviderId?) {
        val sequence = ++credentialCheckSequence
        var storageFailed = false
        val configured = try {
            providerId?.let { credentialStore.contains(CredentialAlias(it.value)) } ?: false
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            storageFailed = true
            false
        }
        if (sequence == credentialCheckSequence) {
            _uiState.update {
                it.copy(
                    apiKeyConfigured = configured,
                    message = if (storageFailed) ProviderSettingsMessage.STORAGE_ERROR else it.message,
                )
            }
        }
    }

    class Factory(
        private val configurationRepository: ProviderConfigurationRepository,
        private val credentialStore: CredentialStore,
        private val providerRegistry: AiProviderRegistry,
        private val testProviderConnection: TestProviderConnectionUseCase,
    ) : ViewModelProvider.Factory {
        @Suppress("UNCHECKED_CAST")
        override fun <T : ViewModel> create(modelClass: Class<T>): T {
            require(modelClass.isAssignableFrom(AiProviderSettingsViewModel::class.java)) {
                "Unknown ViewModel class: ${modelClass.name}"
            }
            return AiProviderSettingsViewModel(
                configurationRepository,
                credentialStore,
                providerRegistry,
                testProviderConnection,
            ) as T
        }
    }
}
