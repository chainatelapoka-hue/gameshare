/*
 * Copyright 2026 GameShare Contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.permissions.PermissionsHelper
import com.example.ui.screens.DashboardScreen
import com.example.ui.screens.SettingsScreen
import com.example.ui.theme.GameShareTheme
import com.example.ui.viewmodel.GameShareViewModel

private enum class AppScreen {
    DASHBOARD,
    SETTINGS,
}

/**
 * Single-activity host for GameShare, managing edge-to-edge rendering, runtime permission
 * requests (`NEARBY_WIFI_DEVICES`, `ACCESS_FINE_LOCATION`, `POST_NOTIFICATIONS`), and
 * switching between the Dashboard and Settings views.
 */
class MainActivity : ComponentActivity() {

    private val viewModel: GameShareViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            GameShareTheme {
                GameShareAppContent(viewModel = viewModel)
            }
        }
    }
}

@Composable
private fun GameShareAppContent(viewModel: GameShareViewModel) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val settings by viewModel.settings.collectAsStateWithLifecycle()
    var currentScreen by rememberSaveable { mutableStateOf(AppScreen.DASHBOARD) }
    var pendingStartAfterPermission by rememberSaveable { mutableStateOf(false) }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.refreshPermissions()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
    ) {
        viewModel.refreshPermissions()
        if (pendingStartAfterPermission && viewModel.uiState.value.permissionsGranted) {
            pendingStartAfterPermission = false
            viewModel.startSharing()
        } else {
            pendingStartAfterPermission = false
        }
    }

    AnimatedContent(
        targetState = currentScreen,
        transitionSpec = { fadeIn() togetherWith fadeOut() },
        label = "screen_transition",
    ) { screen ->
        when (screen) {
            AppScreen.DASHBOARD -> DashboardScreen(
                uiState = uiState,
                settings = settings,
                onToggleSharing = {
                    if (uiState.isSharing) {
                        viewModel.stopSharing()
                    } else if (uiState.permissionsGranted) {
                        viewModel.startSharing()
                    } else {
                        pendingStartAfterPermission = true
                        permissionLauncher.launch(PermissionsHelper.requiredPermissions())
                    }
                },
                onRequestPermissions = {
                    pendingStartAfterPermission = false
                    permissionLauncher.launch(PermissionsHelper.requiredPermissions())
                },
                onOpenSettings = { currentScreen = AppScreen.SETTINGS },
            )

            AppScreen.SETTINGS -> SettingsScreen(
                settings = settings,
                isSharing = uiState.isSharing,
                onUpdateMtu = viewModel::updateMtu,
                onUpdatePort = viewModel::updatePort,
                onUpdateAuthEnabled = viewModel::updateAuthEnabled,
                onUpdateCredentials = viewModel::updateCredentials,
                onUpdateGamingMode = viewModel::updateGamingMode,
                onNavigateBack = { currentScreen = AppScreen.DASHBOARD },
            )
        }
    }
}
