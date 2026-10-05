package com.craftmind.app.presentation.builds

import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.LocalBuildExecutionRecord
import com.craftmind.app.domain.minecraft.MinecraftExecutionPreview

sealed interface BuildExecutionFlow {
    data object Idle : BuildExecutionFlow
    data class Preparing(val planRecordId: String, val executionId: String, val attempt: Int = 1) : BuildExecutionFlow
    data class PreviewReady(
        val planRecordId: String,
        val preview: MinecraftExecutionPreview,
        val errorCode: String? = null,
    ) : BuildExecutionFlow
    data class Starting(val planRecordId: String, val preview: MinecraftExecutionPreview) : BuildExecutionFlow
    data class Tracking(
        val record: LocalBuildExecutionRecord,
        val refreshing: Boolean = false,
        val connectionReasonCode: String? = null,
        val bridgeRecordMissing: Boolean = false,
        val cancellationRequested: Boolean = false,
    ) : BuildExecutionFlow
    data class Failed(
        val planRecord: LocalBuildRecord?,
        val executionId: String?,
        val reasonCode: String,
        val retryPrepare: Boolean = false,
        val retrySameId: Boolean = false,
        val detailMessage: String? = null,
    ) : BuildExecutionFlow
}

data class BuildExecutionState(
    val records: List<LocalBuildExecutionRecord> = emptyList(),
    val flow: BuildExecutionFlow = BuildExecutionFlow.Idle,
    val isLoading: Boolean = true,
    val loadFailed: Boolean = false,
)

sealed interface BuildExecutionEvent {
    data class Prepare(val record: LocalBuildRecord) : BuildExecutionEvent
    data object RetryPrepare : BuildExecutionEvent
    data object Confirm : BuildExecutionEvent
    data object DismissPreview : BuildExecutionEvent
    data class RefreshStatus(val executionId: String) : BuildExecutionEvent
    data class Cancel(val executionId: String) : BuildExecutionEvent
    data object Dismiss : BuildExecutionEvent
}
