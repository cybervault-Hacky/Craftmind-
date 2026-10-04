package com.craftmind.app.presentation.builds

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.buildplan.LocalBuildRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class BuildsState(
    /** Every retained version; history is local and never replaces older validated records. */
    val records: List<LocalBuildRecord> = emptyList(),
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
) {
    val currentRecords: List<LocalBuildRecord>
        get() = records.groupBy(LocalBuildRecord::buildId).values
            .mapNotNull { versions -> versions.maxByOrNull(LocalBuildRecord::version) }
            .sortedByDescending(LocalBuildRecord::savedAtEpochMillis)

    fun versionsFor(buildId: String): List<LocalBuildRecord> = records
        .filter { it.buildId == buildId }
        .sortedBy(LocalBuildRecord::version)
}

class BuildsViewModel(private val repository: LocalBuildRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(BuildsState())
    val state: StateFlow<BuildsState> = mutableState.asStateFlow()

    init {
        viewModelScope.launch {
            try {
                repository.load()
                mutableState.value = BuildsState(records = repository.records.value, isLoading = false)
                repository.records.collect { records ->
                    mutableState.value = BuildsState(records = records, isLoading = false)
                }
            } catch (error: CancellationException) {
                throw error
            } catch (_: Throwable) {
                mutableState.value = BuildsState(isLoading = false, loadFailed = true)
            }
        }
    }
}
