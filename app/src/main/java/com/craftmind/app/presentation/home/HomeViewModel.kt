package com.craftmind.app.presentation.home

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class HomeViewModel(
    private val reducer: BuildComposerReducer = BuildComposerReducer(),
) : ViewModel() {
    private val _state = MutableStateFlow(BuildComposerState())
    val state: StateFlow<BuildComposerState> = _state.asStateFlow()

    fun dispatch(event: BuildComposerEvent) {
        _state.update { current -> reducer.reduce(current, event) }
    }
}
