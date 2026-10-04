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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.craftmind.app.domain.buildplan.BuildPlan
import com.craftmind.app.domain.buildplan.BuildRequest
import com.craftmind.app.domain.buildplan.BuildRequestSnapshot
import com.craftmind.app.domain.buildplan.LocalBuildRecord
import com.craftmind.app.domain.settings.ThemeMode
import com.craftmind.app.presentation.builds.BuildsScreen
import com.craftmind.app.presentation.builds.BuildsState
import com.craftmind.app.presentation.builds.PlanReviewScreen
import com.craftmind.app.presentation.home.BuildComposerEvent
import com.craftmind.app.presentation.home.BuildComposerState
import com.craftmind.app.presentation.home.HomeScreen
import com.craftmind.app.presentation.navigation.MainDestination
import com.craftmind.app.presentation.settings.ProviderSettingsEvent
import com.craftmind.app.presentation.settings.ProviderSettingsState
import com.craftmind.app.presentation.settings.SettingsScreen

private data class AppNavigationItem(val destination: MainDestination, val icon: ImageVector)
private data class PlanReviewContent(val plan: BuildPlan, val request: BuildRequestSnapshot)

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
    buildsState: BuildsState,
) {
    var selectedRoute by rememberSaveable { mutableStateOf(MainDestination.HOME.route) }
    var reviewContent by remember { mutableStateOf<PlanReviewContent?>(null) }
    val destination = MainDestination.fromRoute(selectedRoute)

    BackHandler(enabled = reviewContent != null || destination != MainDestination.HOME) {
        if (reviewContent != null) reviewContent = null else selectedRoute = MainDestination.HOME.route
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(androidx.compose.material3.MaterialTheme.colorScheme.background),
    ) {
        if (maxWidth >= 840.dp) {
            Row(
                modifier = Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing),
            ) {
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
                        buildsState = buildsState,
                        onReviewGeneratedPlan = { plan, request ->
                            reviewContent = PlanReviewContent(plan.plan, request.toSnapshot())
                        },
                        onReviewSavedPlan = { record -> reviewContent = PlanReviewContent(record.plan, record.request) },
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
                        buildsState = buildsState,
                        onReviewGeneratedPlan = { plan, request ->
                            reviewContent = PlanReviewContent(plan.plan, request.toSnapshot())
                        },
                        onReviewSavedPlan = { record -> reviewContent = PlanReviewContent(record.plan, record.request) },
                        onNavigate = { selectedRoute = it.route },
                    )
                }
            }
        }
    }

    reviewContent?.let { review ->
        PlanReviewScreen(plan = review.plan, request = review.request, onDismiss = { reviewContent = null })
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
    buildsState: BuildsState,
    onReviewGeneratedPlan: (com.craftmind.app.domain.buildplan.ValidatedBuildPlan, BuildRequest) -> Unit,
    onReviewSavedPlan: (LocalBuildRecord) -> Unit,
    onNavigate: (MainDestination) -> Unit,
) {
    when (destination) {
        MainDestination.HOME -> HomeScreen(
            state = composerState,
            onEvent = onComposerEvent,
            onPickImage = onPickImage,
            onReviewPlan = onReviewGeneratedPlan,
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
        )
    }
}

private fun BuildRequest.toSnapshot() = BuildRequestSnapshot(
    prompt = prompt,
    imageContentUri = imageReference?.contentUri,
    imageMediaType = imageReference?.mediaType,
    imageDisplayName = imageReference?.displayName,
    imageSizeBytes = imageReference?.sizeBytes,
    urlReference = urlReference?.url,
)
