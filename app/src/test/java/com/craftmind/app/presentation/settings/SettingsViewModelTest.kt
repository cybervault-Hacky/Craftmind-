package com.craftmind.app.presentation.settings

import com.craftmind.app.domain.settings.AppearanceMode
import com.craftmind.app.domain.settings.SettingsRepository
import com.craftmind.app.presentation.builder.MainDispatcherRule
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class SettingsViewModelTest {
    @get:Rule
    val mainDispatcherRule = MainDispatcherRule()

    @Test
    fun appearanceSelectionIsPersistedAndReflectedInUiState() {
        val repository = InMemorySettingsRepository()
        val viewModel = SettingsViewModel(repository)
        val observer = viewModel.uiState.onEach { }.launchIn(CoroutineScope(Dispatchers.Unconfined))

        viewModel.setAppearance(AppearanceMode.DARK)

        assertEquals(AppearanceMode.DARK, repository.current.value)
        assertEquals(AppearanceMode.DARK, viewModel.uiState.value.appearance)
        observer.cancel()
    }

    private class InMemorySettingsRepository : SettingsRepository {
        val current = MutableStateFlow(AppearanceMode.SYSTEM)
        override val appearance = current

        override suspend fun setAppearance(mode: AppearanceMode) {
            current.value = mode
        }
    }
}
