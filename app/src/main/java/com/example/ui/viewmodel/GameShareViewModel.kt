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

package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.GameShareApplication
import com.example.data.GameShareUiState
import com.example.data.ProxySettings
import com.example.service.GameShareService
import com.example.ui.permissions.PermissionsHelper
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * ViewModel backing the GameShare Dashboard and Settings screens.
 */
class GameShareViewModel(application: Application) : AndroidViewModel(application) {

    private val app = application as GameShareApplication
    private val coordinator = app.coordinator
    private val settingsRepository = app.settingsRepository

    val uiState: StateFlow<GameShareUiState> = coordinator.uiState

    val settings: StateFlow<ProxySettings> = settingsRepository.settingsFlow.stateIn(
        scope = viewModelScope,
        started = SharingStarted.WhileSubscribed(5_000L),
        initialValue = ProxySettings(),
    )

    init {
        refreshPermissions()
    }

    fun refreshPermissions() {
        val granted = PermissionsHelper.hasAllRequiredPermissions(app)
        coordinator.updatePermissionsGranted(granted)
    }

    fun startSharing() {
        GameShareService.startSharing(app)
    }

    fun stopSharing() {
        GameShareService.stopSharing(app)
    }

    fun updateMtu(mtu: Int) {
        viewModelScope.launch { settingsRepository.updateMtu(mtu) }
    }

    fun updatePort(port: Int) {
        viewModelScope.launch { settingsRepository.updatePort(port) }
    }

    fun updateAuthEnabled(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateAuthEnabled(enabled) }
    }

    fun updateCredentials(username: String, password: String) {
        viewModelScope.launch { settingsRepository.updateCredentials(username, password) }
    }

    fun updateGamingMode(enabled: Boolean) {
        viewModelScope.launch { settingsRepository.updateGamingMode(enabled) }
    }
}
