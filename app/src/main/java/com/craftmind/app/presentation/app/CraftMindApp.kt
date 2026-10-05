package com.craftmind.app.presentation.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.VerticalDivider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildReferenceAnalysisSource
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.settings.ThemeMode
import com.craftmind.app.presentation.builds.BuildExecutionEvent
import com.craftmind.app.presentation.builds.BuildExecutionState
import com.craftmind.app.presentation.builds.BuildRefinementEvent
import com.craftmind.app.presentation.builds.BuildRefinementState
import com.craftmind.app.presentation.builds.BuildsScreen
import com.craftmind.app.presentation.builds.BuildsState
import com.craftmind.app.presentation.builds.PlanReviewScreen
import com.craftmind.app.presentation.home.BuildComposerEvent
import com.craftmind.app.presentation.home.BuildComposerState
import com.craftmind.app.presentation.home.BuildGenerationState
import com.craftmind.app.presentation.home.HomeScreen
import com.craftmind.app.presentation.navigation.MainDestination
import com.craftmind.app.presentation.settings.BridgePairingEvent
import com.craftmind.app.presentation.settings.BridgePairingState
import com.craftmind.app.presentation.settings.ProviderSettingsEvent
import com.craftmind.app.presentation.settings.ProviderSettingsState
import com.craftmind.app.presentation.settings.SettingsScreen

private data class AppNavigationItem(val destination: MainDestination, val icon: ImageVector)
private data class PlanReviewContent(
    val plan: BuildPlan,
    val request: BuildRequestSnapshot,
    val record: LocalBuildRecord?,
)

private val NavigationItems = listOf(
    AppNavigationItem(MainDestination.HOME, Icons.Default.Home),
    AppNavigationItem(MainDestination.BUILDS, Icons.Default.List),
    AppNavigationItem(MainDestination.SETTINGS, Icons.Default.Settings),
)

@Composable
fun CraftMindApp(
    composerState: BuildComposerState,
    onComposerEvent: (BuildComposerEvent) -> Unit,
    onPickImage: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    providerSettingsState: ProviderSettingsState,
    onProviderSettingsEvent: (ProviderSettingsEvent) -> Unit,
    bridgePairingState: BridgePairingState,
    onBridgePairingEvent: (BridgePairingEvent) -> Unit,
    buildsState: BuildsState,
    refinementState: BuildRefinementState,
    onRefinementEvent: (BuildRefinementEvent) -> Unit,
    executionState: BuildExecutionState,
    onExecutionEvent: (BuildExecutionEvent) -> Unit,
) {
    var selectedRoute by rememberSaveable { mutableStateOf(MainDestination.HOME.route) }
    var reviewContent by remember { mutableStateOf<PlanReviewContent?>(null) }
    val destination = MainDestination.fromRoute(selectedRoute)
    val completedRevision = when (refinementState) {
        is BuildRefinementState.Accepted -> refinementState.record
        is BuildRefinementState.Reverted -> refinementState.record
        else -> null
    }
    LaunchedEffect(completedRevision?.recordId) {
        val completed = completedRevision ?: return@LaunchedEffect
        val activeReview = reviewContent ?: return@LaunchedEffect
        if (activeReview.record?.buildId == completed.buildId) {
            reviewContent = PlanReviewContent(completed.plan, completed.request, completed)
        }
    }

    BackHandler(enabled = reviewContent != null || destination != MainDestination.HOME) {
        if (reviewContent != null) {
            if (refinementState is BuildRefinementState.Generating) onRefinementEvent(BuildRefinementEvent.Cancel)
            if (executionState.flow is com.craftmind.app.presentation.builds.BuildExecutionFlow.PreviewReady) {
                onExecutionEvent(BuildExecutionEvent.DismissPreview)
            }
            reviewContent = null
            onRefinementEvent(BuildRefinementEvent.DismissResult)
        } else {
            selectedRoute = MainDestination.HOME.route
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
    ) {
        if (maxWidth >= 840.dp) {
            Row(modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                NavigationRail(containerColor = androidx.compose.material3.MaterialTheme.colorScheme.surface) {
                    NavigationItems.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item.destination,
                            onClick = { selectedRoute = item.destination.route },
                            icon = { androidx.compose.material3.Icon(item.icon, contentDescription = null) },
                            label = { androidx.compose.material3.Text(item.destination.label) },
                        )
                    }
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = 1.dp,
                    color = androidx.compose.material3.MaterialTheme.colorScheme.outlineVariant,
                )
                Crossfade(targetState = destination, label = "top-level screen transition", modifier = Modifier.weight(1f)) { current ->
                    DestinationContent(
                        destination = current,
                        composerState = composerState,
                        onComposerEvent = onComposerEvent,
                        onPickImage = onPickImage,
                        themeMode = themeMode,
                        onThemeModeSelected = onThemeModeSelected,
                        providerSettingsState = providerSettingsState,
                        onProviderSettingsEvent = onProviderSettingsEvent,
                        bridgePairingState = bridgePairingState,
                        onBridgePairingEvent = onBridgePairingEvent,
                        buildsState = buildsState,
                        onReviewGeneratedPlan = { ready ->
                            onRefinementEvent(BuildRefinementEvent.DismissResult)
                            reviewContent = PlanReviewContent(
                                ready.plan.plan,
                                ready.request.toSnapshot(ready.imageAnalysisSource, ready.referenceAnalysisSource),
                                ready.localRecord,
                            )
                        },
                        onReviewSavedPlan = { record ->
                            onRefinementEvent(BuildRefinementEvent.DismissResult)
                            reviewContent = PlanReviewContent(record.plan, record.request, record)
                        },
                        onNavigate = { selectedRoute = it.route },
                    )
                }
            }
        } else {
            Scaffold(
                containerColor = androidx.compose.material3.MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar {
                        NavigationItems.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item.destination,
                                onClick = { selectedRoute = item.destination.route },
                                icon = { androidx.compose.material3.Icon(item.icon, contentDescription = null) },
                                label = { androidx.compose.material3.Text(item.destination.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Crossfade(
                    targetState = destination,
                    label = "top-level screen transition",
                    modifier = Modifier.fillMaxSize().padding(innerPadding),
                ) { current ->
                    DestinationContent(
                        destination = current,
                        composerState = composerState,
                        onComposerEvent = onComposerEvent,
                        onPickImage = onPickImage,
                        themeMode = themeMode,
                        onThemeModeSelected = onThemeModeSelected,
                        providerSettingsState = providerSettingsState,
                        onProviderSettingsEvent = onProviderSettingsEvent,
                        bridgePairingState = bridgePairingState,
                        onBridgePairingEvent = onBridgePairingEvent,
                        buildsState = buildsState,
                        onReviewGeneratedPlan = { ready ->
                            onRefinementEvent(BuildRefinementEvent.DismissResult)
                            reviewContent = PlanReviewContent(
                                ready.plan.plan,
                                ready.request.toSnapshot(ready.imageAnalysisSource, ready.referenceAnalysisSource),
                                ready.localRecord,
                            )
                        },
                        onReviewSavedPlan = { record ->
                            onRefinementEvent(BuildRefinementEvent.DismissResult)
                            reviewContent = PlanReviewContent(record.plan, record.request, record)
                        },
                        onNavigate = { selectedRoute = it.route },
                    )
                }
            }
        }
    }

    reviewContent?.let { review ->
        val persistedRecord = review.record?.let { selected ->
            buildsState.records.firstOrNull { it.recordId == selected.recordId } ?: selected
        }
        val currentPlan = persistedRecord?.plan ?: review.plan
        val currentRequest = persistedRecord?.request ?: review.request
        val history = persistedRecord?.let { buildsState.versionsFor(it.buildId) }.orEmpty()
        PlanReviewScreen(
            plan = currentPlan,
            request = currentRequest,
            record = persistedRecord,
            versions = history,
            refinementState = refinementState,
            onRefinementEvent = onRefinementEvent,
            bridgeState = bridgePairingState,
            executionState = executionState,
            onExecutionEvent = onExecutionEvent,
            onDismiss = {
                reviewContent = null
                onRefinementEvent(BuildRefinementEvent.DismissResult)
            },
        )
    }
}

@Composable
private fun DestinationContent(
    destination: MainDestination,
    composerState: BuildComposerState,
    onComposerEvent: (BuildComposerEvent) -> Unit,
    onPickImage: () -> Unit,
    themeMode: ThemeMode,
    onThemeModeSelected: (ThemeMode) -> Unit,
    providerSettingsState: ProviderSettingsState,
    onProviderSettingsEvent: (ProviderSettingsEvent) -> Unit,
    bridgePairingState: BridgePairingState,
    onBridgePairingEvent: (BridgePairingEvent) -> Unit,
    buildsState: BuildsState,
    onReviewGeneratedPlan: (BuildGenerationState.Ready) -> Unit,
    onReviewSavedPlan: (LocalBuildRecord) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    when (destination) {
        MainDestination.HOME -> HomeScreen(
            state = composerState,
            onEvent = onComposerEvent,
            onPickImage = onPickImage,
            onReviewPlan = onReviewGeneratedPlan,
            selectedModelId = providerSettingsState.selectedModelId,
            selectedModel = providerSettingsState.models.firstOrNull {
                it.id == providerSettingsState.selectedModelId && it.providerId == providerSettingsState.activeProviderId
            },
            providerSettingsLoaded = providerSettingsState.providers.isNotEmpty(),
            providerCredentialSaved = providerSettingsState.savedCredentialExists,
            onOpenSettings = { onNavigate(MainDestination.SETTINGS) },
        )
        MainDestination.BUILDS -> BuildsScreen(
            state = buildsState,
            onStartBuilding = { onNavigate(MainDestination.HOME) },
            onReview = onReviewSavedPlan,
        )
        MainDestination.SETTINGS -> SettingsScreen(
            themeMode = themeMode,
            onThemeModeSelected = onThemeModeSelected,
            providerState = providerSettingsState,
            onProviderEvent = onProviderSettingsEvent,
            bridgeState = bridgePairingState,
            onBridgeEvent = onBridgePairingEvent,
        )
    }
}

private fun BuildRequest.toSnapshot(
    imageAnalysisSource: BuildImageAnalysisSource? = null,
    referenceAnalysisSource: BuildReferenceAnalysisSource? = null,
) = BuildRequestSnapshot(
    prompt = prompt,
    imageContentUri = imageReference?.contentUri,
    imageMediaType = imageReference?.mediaType,
    imageDisplayName = imageReference?.displayName,
    imageSizeBytes = imageReference?.sizeBytes,
    urlReference = urlReference?.url,
    imageAnalysisSource = imageAnalysisSource,
    referenceAnalysisSource = referenceAnalysisSource,
)
