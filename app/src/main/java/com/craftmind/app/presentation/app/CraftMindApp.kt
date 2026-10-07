package com.craftmind.app.presentation.app

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
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
import androidx.compose.material.icons.filled.Build
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.List
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
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
import com.craftmind.app.designsystem.CraftMindLayout
import com.craftmind.app.designsystem.craftMindMotion
import com.craftmind.app.domain.buildplan.BuildImageAnalysisSource
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildReferenceAnalysisSource
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.minecraft.compatibility.MinecraftCompatibilityResolver
import com.craftmind.app.domain.settings.ThemeMode
import com.craftmind.app.presentation.about.AboutScreen
import com.craftmind.app.presentation.builds.BuildExecutionEvent
import com.craftmind.app.presentation.builds.BuildExecutionFlow
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
import com.craftmind.app.presentation.minecraft.MinecraftScreen
import com.craftmind.app.presentation.minecraft.minecraftConnectionSummary
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

/**
 * Navigation items, in the order fixed by [MainDestination] (Phase 15 §4).
 *
 * Icons come from the Material core set and are drawn with the design-system icon size: no custom pixel-art icons, no
 * copied game artwork.
 */
private val NavigationItems = listOf(
    AppNavigationItem(MainDestination.HOME, Icons.Default.Home),
    AppNavigationItem(MainDestination.BUILDS, Icons.Default.List),
    AppNavigationItem(MainDestination.MINECRAFT, Icons.Default.Build),
    AppNavigationItem(MainDestination.SETTINGS, Icons.Default.Settings),
)

/**
 * The app shell: four destinations, one review overlay, one about overlay.
 *
 * Layout composes responsively instead of switching layouts: from [CraftMindLayout.mediumBreakpoint] the navigation
 * becomes a rail beside the content, below it a bottom bar. Transitions use the resolved motion tokens, so a device
 * that asks for reduced motion gets an instant switch with no loss of state.
 */
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
    compatibilityResolver: MinecraftCompatibilityResolver,
) {
    var selectedRoute by rememberSaveable { mutableStateOf(MainDestination.PRIMARY.route) }
    var aboutVisible by rememberSaveable { mutableStateOf(false) }
    var reviewContent by remember { mutableStateOf<PlanReviewContent?>(null) }
    val destination = MainDestination.fromRoute(selectedRoute)
    val motion = MaterialTheme.craftMindMotion
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

    // One handler with an explicit precedence order: review sheet first, then About, then back to Home.
    BackHandler(
        enabled = reviewContent != null || aboutVisible || destination != MainDestination.PRIMARY,
    ) {
        when {
            reviewContent != null -> {
                if (refinementState is BuildRefinementState.Generating) onRefinementEvent(BuildRefinementEvent.Cancel)
                if (executionState.flow is BuildExecutionFlow.PreviewReady) {
                    onExecutionEvent(BuildExecutionEvent.DismissPreview)
                }
                reviewContent = null
                onRefinementEvent(BuildRefinementEvent.DismissResult)
            }

            aboutVisible -> aboutVisible = false
            else -> selectedRoute = MainDestination.PRIMARY.route
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background),
    ) {
        val wideLayout = maxWidth >= CraftMindLayout.mediumBreakpoint
        val navigate: (MainDestination) -> Unit = { target ->
            aboutVisible = false
            selectedRoute = target.route
        }
        val destinationContent: @Composable AnimatedVisibilityScope.(MainDestination) -> Unit =
            { current ->
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
                    executionState = executionState,
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
                    onNavigate = navigate,
                    onOpenAbout = { aboutVisible = true },
                )
            }

        if (wideLayout) {
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
                NavigationRail(containerColor = MaterialTheme.colorScheme.surface) {
                    NavigationItems.forEach { item ->
                        NavigationRailItem(
                            selected = destination == item.destination,
                            onClick = { navigate(item.destination) },
                            icon = {
                                androidx.compose.material3.Icon(
                                    imageVector = item.icon,
                                    contentDescription = null,
                                    modifier = Modifier.padding(CraftMindLayout.xxs),
                                )
                            },
                            label = { Text(item.destination.label) },
                        )
                    }
                }
                VerticalDivider(
                    modifier = Modifier.fillMaxHeight(),
                    thickness = CraftMindLayout.hairline,
                    color = MaterialTheme.colorScheme.outlineVariant,
                )
                Crossfade(
                    targetState = destination,
                    animationSpec = tween(motion.base),
                    label = "top-level screen transition",
                    modifier = Modifier.weight(1f),
                    content = destinationContent,
                )
            }
        } else {
            Scaffold(
                containerColor = MaterialTheme.colorScheme.background,
                bottomBar = {
                    NavigationBar(containerColor = MaterialTheme.colorScheme.surface) {
                        NavigationItems.forEach { item ->
                            NavigationBarItem(
                                selected = destination == item.destination,
                                onClick = { navigate(item.destination) },
                                icon = {
                                    androidx.compose.material3.Icon(
                                        imageVector = item.icon,
                                        contentDescription = null,
                                    )
                                },
                                label = { Text(item.destination.label) },
                            )
                        }
                    }
                },
            ) { innerPadding ->
                Crossfade(
                    targetState = destination,
                    animationSpec = tween(motion.base),
                    label = "top-level screen transition",
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(innerPadding),
                    content = destinationContent,
                )
            }
        }

        if (aboutVisible) {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = MaterialTheme.colorScheme.background,
            ) {
                AboutScreen(onClose = { aboutVisible = false })
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
            compatibilityResolver = compatibilityResolver,
            onOpenMinecraft = {
                reviewContent = null
                onRefinementEvent(BuildRefinementEvent.DismissResult)
                navigate(MainDestination.MINECRAFT)
            },
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
    executionState: BuildExecutionState,
    onReviewGeneratedPlan: (BuildGenerationState.Ready) -> Unit,
    onReviewSavedPlan: (LocalBuildRecord) -> Unit,
    onNavigate: (MainDestination) -> Unit,
    onOpenAbout: () -> Unit,
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
            bridgeConnected = bridgePairingState.runtimeResolution?.canExecute == true,
            onOpenSettings = { onNavigate(MainDestination.SETTINGS) },
            onOpenMinecraft = { onNavigate(MainDestination.MINECRAFT) },
        )

        MainDestination.BUILDS -> {
            // The availability line is the same real derivation the Minecraft screen uses, so the library can never
            // promise a build the runtime would refuse.
            val minecraftSummary = remember(bridgePairingState) { minecraftConnectionSummary(bridgePairingState) }
            BuildsScreen(
                state = buildsState,
                onStartBuilding = { onNavigate(MainDestination.HOME) },
                onReview = onReviewSavedPlan,
                executionRecords = executionState.records,
                minecraftStatusLabel = if (minecraftSummary.canBuild) {
                    "Building is available. ${minecraftSummary.headline}."
                } else {
                    minecraftSummary.unavailableReason ?: minecraftSummary.detail
                },
                minecraftStatusTone = minecraftSummary.tone,
                onOpenMinecraft = { onNavigate(MainDestination.MINECRAFT) },
            )
        }

        MainDestination.MINECRAFT -> MinecraftScreen(
            state = bridgePairingState,
            onEvent = onBridgePairingEvent,
        )

        MainDestination.SETTINGS -> SettingsScreen(
            themeMode = themeMode,
            onThemeModeSelected = onThemeModeSelected,
            providerState = providerSettingsState,
            onProviderEvent = onProviderSettingsEvent,
            bridgeState = bridgePairingState,
            onBridgeEvent = onBridgePairingEvent,
            onOpenAbout = onOpenAbout,
            onOpenMinecraft = { onNavigate(MainDestination.MINECRAFT) },
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
