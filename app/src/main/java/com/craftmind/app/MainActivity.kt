package com.craftmind.app

import android.net.Uri
import android.os.Bundle
import android.provider.OpenableColumns
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.craftmind.app.designsystem.CraftMindTheme
import com.craftmind.app.domain.build.BuildInput
import com.craftmind.app.domain.settings.ThemeMode
import com.craftmind.app.presentation.app.CraftMindApp
import com.craftmind.app.presentation.home.BuildComposerEvent
import com.craftmind.app.presentation.home.HomeViewModel
import com.craftmind.app.presentation.home.HomeViewModelFactory
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val appContainer = (application as CraftMindApplication).container
        setContent {
            val themeMode by appContainer.themePreferences.themeMode
                .collectAsStateWithLifecycle(initialValue = ThemeMode.SYSTEM)
            val homeViewModel: HomeViewModel = viewModel(factory = remember { HomeViewModelFactory() })
            val composerState by homeViewModel.state.collectAsStateWithLifecycle()
            val preferenceScope = rememberCoroutineScope()
            val writeThemeMode: (ThemeMode) -> Unit = remember(appContainer.themePreferences, preferenceScope) {
                { selected: ThemeMode ->
                    preferenceScope.launch { appContainer.themePreferences.setThemeMode(selected) }
                    Unit
                }
            }
            val context = LocalContext.current
            val view = LocalView.current
            val darkTheme = when (themeMode) {
                ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }

            SideEffect {
                window.statusBarColor = android.graphics.Color.TRANSPARENT
                window.navigationBarColor = android.graphics.Color.TRANSPARENT
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }

            val imagePicker = rememberLauncherForActivityResult(
                contract = ActivityResultContracts.GetContent(),
            ) { uri: Uri? ->
                if (uri != null) {
                    homeViewModel.dispatch(
                        BuildComposerEvent.ImageSelected(readImageReference(context, uri)),
                    )
                }
            }

            CraftMindTheme(themeMode = themeMode) {
                CraftMindApp(
                    composerState = composerState,
                    onComposerEvent = homeViewModel::dispatch,
                    onPickImage = { imagePicker.launch("image/*") },
                    themeMode = themeMode,
                    // Only the appearance choice is persisted in Phase 1.
                    onThemeModeSelected = writeThemeMode,
                )
            }
        }
    }
}

private fun readImageReference(context: android.content.Context, uri: Uri): BuildInput.ImageReference {
    val mediaType = runCatching { context.contentResolver.getType(uri) }.getOrNull().orEmpty()
    var byteCount: Long? = null
    var displayName: String? = null

    runCatching {
        context.contentResolver.query(
            uri,
            arrayOf(OpenableColumns.SIZE, OpenableColumns.DISPLAY_NAME),
            null,
            null,
            null,
        )?.use { cursor ->
            if (cursor.moveToFirst()) {
                val sizeColumn = cursor.getColumnIndex(OpenableColumns.SIZE)
                if (sizeColumn >= 0 && !cursor.isNull(sizeColumn)) {
                    byteCount = cursor.getLong(sizeColumn).takeIf { it >= 0L }
                }
                val nameColumn = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                if (nameColumn >= 0 && !cursor.isNull(nameColumn)) {
                    displayName = cursor.getString(nameColumn)
                }
            }
        }
    }

    return BuildInput.ImageReference(
        contentUri = uri.toString(),
        mediaType = mediaType,
        sizeBytes = byteCount,
        displayName = displayName,
    )
}
