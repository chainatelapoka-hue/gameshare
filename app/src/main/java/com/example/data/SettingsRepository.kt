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

package com.example.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import java.io.IOException

private val Context.settingsDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "gameshare_settings",
)

/**
 * Persists [ProxySettings] locally using Jetpack DataStore Preferences.
 */
class SettingsRepository(private val context: Context) {

    private object Keys {
        val MTU = intPreferencesKey("mtu")
        val PORT = intPreferencesKey("socks_port")
        val AUTH_ENABLED = booleanPreferencesKey("auth_enabled")
        val USERNAME = stringPreferencesKey("auth_username")
        val PASSWORD = stringPreferencesKey("auth_password")
        val GAMING_MODE = booleanPreferencesKey("gaming_mode")
    }

    /** Reactive stream of the current [ProxySettings]. */
    val settingsFlow: Flow<ProxySettings> = context.settingsDataStore.data
        .catch { exception ->
            if (exception is IOException) {
                emit(emptyPreferences())
            } else {
                throw exception
            }
        }
        .map { prefs ->
            ProxySettings(
                mtu = (prefs[Keys.MTU] ?: ProxySettings.DEFAULT_MTU)
                    .coerceIn(ProxySettings.MIN_MTU, ProxySettings.MAX_MTU),
                port = (prefs[Keys.PORT] ?: ProxySettings.DEFAULT_PORT)
                    .coerceIn(ProxySettings.MIN_PORT, ProxySettings.MAX_PORT),
                authEnabled = prefs[Keys.AUTH_ENABLED] ?: false,
                username = prefs[Keys.USERNAME]?.ifBlank { ProxySettings.DEFAULT_USERNAME }
                    ?: ProxySettings.DEFAULT_USERNAME,
                password = prefs[Keys.PASSWORD] ?: "",
                gamingMode = prefs[Keys.GAMING_MODE] ?: true,
            )
        }

    suspend fun updateMtu(mtu: Int) {
        val clamped = mtu.coerceIn(ProxySettings.MIN_MTU, ProxySettings.MAX_MTU)
        context.settingsDataStore.edit { it[Keys.MTU] = clamped }
    }

    suspend fun updatePort(port: Int) {
        val clamped = port.coerceIn(ProxySettings.MIN_PORT, ProxySettings.MAX_PORT)
        context.settingsDataStore.edit { it[Keys.PORT] = clamped }
    }

    suspend fun updateAuthEnabled(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.AUTH_ENABLED] = enabled }
    }

    suspend fun updateCredentials(username: String, password: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.USERNAME] = username.trim().ifBlank { ProxySettings.DEFAULT_USERNAME }
            prefs[Keys.PASSWORD] = password
        }
    }

    suspend fun updateGamingMode(enabled: Boolean) {
        context.settingsDataStore.edit { it[Keys.GAMING_MODE] = enabled }
    }
}
