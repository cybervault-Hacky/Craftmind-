package com.craftmind.app.presentation.app

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.weight
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.craftmind.app.AppContainer
import com.craftmind.app.BuildConfig
import com.craftmind.app.R
import com.craftmind.app.core.designsystem.CraftMindTheme
import com.craftmind.app.core.designsystem.Elevation
import com.craftmind.app.core.designsystem.LayoutBreakpoint
import com.craftmind.app.core.designsystem.MotionDuration
import com.craftmind.app.core.designsystem.Space
import com.craftmind.app.core.navigation.AppDestination
import com.craftmind.app.domain.settings.resolveDarkTheme
import com.craftmind.app.presentation.builder.BuilderViewModel
import com.craftmind.app.presentation.builds.BuildsScreen
import com.craftmind.app.presentation.home.HomeScreen
import com.craftmind.app.presentation.settings.SettingsScreen
import com.craftmind.app.presentation.settings.SettingsViewModel
import androidx.compose.foundation.isSystemInDarkTheme

@Composable
fun CraftMindRoot(container: AppContainer) {
    val builderFactory = remember(container.imageReferenceRepository) {
        BuilderViewModel.Factory(container.imageReferenceRepository)
    }
    val settingsFactory = remember(container.settingsRepository) {
        SettingsViewModel.Factory(container.settingsRepository)
    }
    val builderViewModel: BuilderViewModel = viewModel(factory = builderFactory)
    val settingsViewModel: SettingsViewModel = viewModel(factory = settingsFactory)
    val builderState by builderViewModel.uiState.collectAsStateWithLifecycle()
    val settingsState by settingsViewModel.uiState.collectAsStateWithLifecycle()
    val darkTheme = settingsState.appearance.resolveDarkTheme(isSystemInDarkTheme())

    CraftMindTheme(darkTheme = darkTheme) {
        CraftMindNavigation(
            container = container,
            builderViewModel = builderViewModel,
            builderState = builderState,
            settingsViewModel = settingsViewModel,
            settingsAppearance = settingsState.appearance,
            appVersion = BuildConfig.VERSION_NAME,
        )
    }
}

@Composable
private fun CraftMindNavigation(
    container: AppContainer,
    builderViewModel: BuilderViewModel,
    builderState: com.craftmind.app.presentation.builder.BuilderUiState,
    settingsViewModel: SettingsViewModel,
    settingsAppearance: com.craftmind.app.domain.settings.AppearanceMode,
    appVersion: String,
) {
    val navController = rememberNavController()
    val currentEntry by navController.currentBackStackEntryAsState()
    val selectedDestination = AppDestination.fromRoute(currentEntry?.destination?.route)
        ?: AppDestination.HOME
    val imagePicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
        onResult = { uri -> uri?.let { builderViewModel.onImageSelected(it.toString()) } },
    )

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val compact = maxWidth < LayoutBreakpoint.navigationRail
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = MaterialTheme.colorScheme.background,
            bottomBar = {
                if (compact) {
                    CompactNavigationBar(
                        selected = selectedDestination,
                        onDestinationSelected = { navigateTo(navController, it) },
                    )
                }
            },
        ) { contentPadding ->
            Row(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(contentPadding),
            ) {
                if (!compact) {
                    ExpandedNavigationRail(
                        selected = selectedDestination,
                        onDestinationSelected = { navigateTo(navController, it) },
                    )
                }
                NavHost(
                    navController = navController,
                    startDestination = AppDestination.HOME.route,
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    enterTransition = {
                        fadeIn(tween(MotionDuration.screenMillis)) +
                            slideInHorizontally(tween(MotionDuration.screenMillis)) { width -> width / 28 }
                    },
                    exitTransition = {
                        fadeOut(tween(MotionDuration.screenMillis)) +
                            slideOutHorizontally(tween(MotionDuration.screenMillis)) { width -> -width / 36 }
                    },
                    popEnterTransition = {
                        fadeIn(tween(MotionDuration.screenMillis)) +
                            slideInHorizontally(tween(MotionDuration.screenMillis)) { width -> -width / 28 }
                    },
                    popExitTransition = {
                        fadeOut(tween(MotionDuration.screenMillis)) +
                            slideOutHorizontally(tween(MotionDuration.screenMillis)) { width -> width / 36 }
                    },
                ) {
                    composable(AppDestination.HOME.route) {
                        HomeScreen(
                            state = builderState,
                            imageReferenceRepository = container.imageReferenceRepository,
                            onPromptChanged = builderViewModel::onPromptChanged,
                            onChooseImage = {
                                imagePicker.launch(
                                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                                )
                            },
                            onRemoveImage = builderViewModel::onRemoveImage,
                            onUrlEditorVisibilityChanged = builderViewModel::onUrlEditorVisibilityChanged,
                            onUrlDraftChanged = builderViewModel::onUrlDraftChanged,
                            onAddUrlReference = builderViewModel::onAddUrlReference,
                            onRemoveUrlReference = builderViewModel::onRemoveUrlReference,
                            onBuildPressed = builderViewModel::onBuildPressed,
                            onDismissSubmissionNotice = builderViewModel::dismissSubmissionNotice,
                            onOpenBuilds = { navigateTo(navController, AppDestination.BUILDS) },
                        )
                    }
                    composable(AppDestination.BUILDS.route) {
                        BuildsScreen(
                            onStartBuild = { navigateTo(navController, AppDestination.HOME) },
                        )
                    }
                    composable(AppDestination.SETTINGS.route) {
                        SettingsScreen(
                            appearance = settingsAppearance,
                            appVersion = appVersion,
                            onAppearanceSelected = settingsViewModel::setAppearance,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun CompactNavigationBar(
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    NavigationBar(
        containerColor = MaterialTheme.colorScheme.surface,
        tonalElevation = Elevation.flat,
    ) {
        AppDestination.entries.forEach { destination ->
            val label = destinationLabel(destination)
            NavigationBarItem(
                selected = destination == selected,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destinationIcon(destination),
                        contentDescription = null,
                    )
                },
                label = { Text(label) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun ExpandedNavigationRail(
    selected: AppDestination,
    onDestinationSelected: (AppDestination) -> Unit,
) {
    NavigationRail(
        modifier = Modifier.fillMaxHeight(),
        containerColor = MaterialTheme.colorScheme.background,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        header = {
            Spacer(Modifier.height(Space.huge))
        },
    ) {
        AppDestination.entries.forEach { destination ->
            NavigationRailItem(
                selected = destination == selected,
                onClick = { onDestinationSelected(destination) },
                icon = {
                    Icon(
                        imageVector = destinationIcon(destination),
                        contentDescription = null,
                    )
                },
                label = { Text(destinationLabel(destination)) },
                alwaysShowLabel = true,
            )
        }
    }
}

@Composable
private fun destinationLabel(destination: AppDestination): String = when (destination) {
    AppDestination.HOME -> stringResource(R.string.navigation_home)
    AppDestination.BUILDS -> stringResource(R.string.navigation_builds)
    AppDestination.SETTINGS -> stringResource(R.string.navigation_settings)
}

@Composable
private fun destinationIcon(destination: AppDestination) = when (destination) {
    AppDestination.HOME -> Icons.Filled.Home
    AppDestination.BUILDS -> Icons.Outlined.Build
    AppDestination.SETTINGS -> Icons.Outlined.Settings
}

private fun navigateTo(controller: NavHostController, destination: AppDestination) {
    controller.navigate(destination.route) {
        launchSingleTop = true
        restoreState = true
        popUpTo(controller.graph.startDestinationId) {
            saveState = true
        }
    }
}
