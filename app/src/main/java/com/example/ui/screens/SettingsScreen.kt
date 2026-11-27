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

package com.example.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Tune
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ProxySettings
import com.example.ui.theme.GameShareTheme
import com.example.ui.theme.LocalGameShareColors
import com.example.ui.theme.MonoCodeStyle
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * Configuration screen for MTU override (1200–1500), SOCKS5 listening port,
 * optional RFC 1929 authentication, and Gaming Mode socket optimizations.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(
    settings: ProxySettings,
    isSharing: Boolean,
    onUpdateMtu: (Int) -> Unit,
    onUpdatePort: (Int) -> Unit,
    onUpdateAuthEnabled: (Boolean) -> Unit,
    onUpdateCredentials: (String, String) -> Unit,
    onUpdateGamingMode: (Boolean) -> Unit,
    onNavigateBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    BackHandler(onBack = onNavigateBack)

    val extColors = LocalGameShareColors.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()

    var mtuSliderValue by remember(settings.mtu) { mutableFloatStateOf(settings.mtu.toFloat()) }
    var portText by rememberSaveable(settings.port) { mutableStateOf(settings.port.toString()) }
    var usernameText by rememberSaveable(settings.username) { mutableStateOf(settings.username) }
    var passwordText by rememberSaveable(settings.password) { mutableStateOf(settings.password) }
    var showPassword by rememberSaveable { mutableStateOf(false) }

    LaunchedEffect(settings.mtu) {
        mtuSliderValue = settings.mtu.toFloat()
    }

    val parsedPort = portText.toIntOrNull()
    val portError = parsedPort == null || parsedPort !in ProxySettings.MIN_PORT..ProxySettings.MAX_PORT

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "Proxy Settings",
                        style = MaterialTheme.typography.titleLarge,
                    )
                },
                navigationIcon = {
                    IconButton(
                        onClick = onNavigateBack,
                        modifier = Modifier
                            .minimumInteractiveComponentSize()
                            .testTag("back_button"),
                    ) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.cd_navigate_back),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground,
                ),
            )
        },
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding),
            contentAlignment = Alignment.TopCenter,
        ) {
            Column(
                modifier = Modifier
                    .widthIn(max = 600.dp)
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 20.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                if (isSharing) {
                    Surface(
                        shape = RoundedCornerShape(14.dp),
                        color = extColors.waitingContainer,
                        border = BorderStroke(1.dp, extColors.waiting.copy(alpha = 0.4f)),
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        Row(
                            modifier = Modifier.padding(12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = extColors.waiting,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Proxy is currently running. Stop and restart sharing to apply changes.",
                                style = MaterialTheme.typography.bodySmall,
                                color = extColors.onWaitingContainer,
                            )
                        }
                    }
                }

                // Gaming Mode card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, extColors.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Gamepad,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(22.dp),
                            )
                            Spacer(modifier = Modifier.width(12.dp))
                            Column {
                                Text(
                                    text = "Gaming Mode",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                                Text(
                                    text = "Disables Nagle's algorithm (TCP_NODELAY), enables low keep-alive, and sets IPTOS_LOWDELAY.",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Switch(
                            checked = settings.gamingMode,
                            onCheckedChange = onUpdateGamingMode,
                            modifier = Modifier.testTag("gaming_mode_switch"),
                        )
                    }
                }

                // MTU Override card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, extColors.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Filled.Speed,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Text(
                                    text = "MTU Override",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                )
                            }
                            Surface(
                                shape = RoundedCornerShape(8.dp),
                                color = MaterialTheme.colorScheme.primaryContainer,
                            ) {
                                Text(
                                    text = "${mtuSliderValue.roundToInt()} B",
                                    style = MonoCodeStyle,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                )
                            }
                        }

                        Text(
                            text = "Tunes proxy buffer stride & packet framing (1200–1500). Default 1350 prevents cellular/P2P fragmentation.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Slider(
                            value = mtuSliderValue,
                            onValueChange = { mtuSliderValue = it },
                            onValueChangeFinished = {
                                onUpdateMtu(mtuSliderValue.roundToInt())
                            },
                            valueRange = ProxySettings.MIN_MTU.toFloat()..ProxySettings.MAX_MTU.toFloat(),
                            modifier = Modifier.testTag("mtu_slider"),
                        )

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            listOf(1280, 1350, 1420, 1500).forEach { preset ->
                                FilterChip(
                                    selected = mtuSliderValue.roundToInt() == preset,
                                    onClick = {
                                        mtuSliderValue = preset.toFloat()
                                        onUpdateMtu(preset)
                                    },
                                    label = { Text("$preset", style = MonoCodeStyle) },
                                )
                            }
                        }
                    }
                }

                // SOCKS5 Port card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, extColors.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Filled.Tune,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(10.dp))
                            Text(
                                text = "SOCKS5 Port",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface,
                            )
                        }

                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp),
                            verticalAlignment = Alignment.Top,
                        ) {
                            OutlinedTextField(
                                value = portText,
                                onValueChange = { input ->
                                    if (input.length <= 5 && input.all { it.isDigit() }) {
                                        portText = input
                                    }
                                },
                                label = { Text("Port (1024–65535)") },
                                singleLine = true,
                                isError = portError,
                                supportingText = if (portError) {
                                    { Text("Enter a valid port between 1024 and 65535") }
                                } else {
                                    null
                                },
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                                modifier = Modifier
                                    .weight(1f)
                                    .testTag("port_input"),
                            )

                            Button(
                                onClick = {
                                    parsedPort?.let {
                                        onUpdatePort(it)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Saved SOCKS5 port $it")
                                        }
                                    }
                                },
                                enabled = !portError && parsedPort != settings.port,
                                modifier = Modifier
                                    .padding(top = 8.dp)
                                    .height(48.dp)
                                    .testTag("save_port_button"),
                            ) {
                                Text("Save")
                            }
                        }
                    }
                }

                // Authentication (RFC 1929) card
                Card(
                    shape = RoundedCornerShape(20.dp),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
                    border = BorderStroke(1.dp, extColors.cardBorder),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Row(
                                modifier = Modifier.weight(1f),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Lock,
                                    contentDescription = null,
                                    tint = MaterialTheme.colorScheme.primary,
                                    modifier = Modifier.size(20.dp),
                                )
                                Spacer(modifier = Modifier.width(10.dp))
                                Column {
                                    Text(
                                        text = "SOCKS5 Auth (RFC 1929)",
                                        style = MaterialTheme.typography.titleMedium,
                                        color = MaterialTheme.colorScheme.onSurface,
                                    )
                                    Text(
                                        text = "Require username & password from client apps.",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                            Spacer(modifier = Modifier.width(12.dp))
                            Switch(
                                checked = settings.authEnabled,
                                onCheckedChange = onUpdateAuthEnabled,
                                modifier = Modifier.testTag("auth_switch"),
                            )
                        }

                        AnimatedVisibility(visible = settings.authEnabled) {
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                OutlinedTextField(
                                    value = usernameText,
                                    onValueChange = { usernameText = it },
                                    label = { Text("Username") },
                                    singleLine = true,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("username_input"),
                                )

                                OutlinedTextField(
                                    value = passwordText,
                                    onValueChange = { passwordText = it },
                                    label = { Text("Password") },
                                    singleLine = true,
                                    visualTransformation = if (showPassword) {
                                        VisualTransformation.None
                                    } else {
                                        PasswordVisualTransformation()
                                    },
                                    trailingIcon = {
                                        IconButton(
                                            onClick = { showPassword = !showPassword },
                                            modifier = Modifier.minimumInteractiveComponentSize(),
                                        ) {
                                            Icon(
                                                imageVector = if (showPassword) {
                                                    Icons.Filled.VisibilityOff
                                                } else {
                                                    Icons.Filled.Visibility
                                                },
                                                contentDescription = "Toggle password visibility",
                                            )
                                        }
                                    },
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .testTag("password_input"),
                                )

                                Button(
                                    onClick = {
                                        onUpdateCredentials(usernameText, passwordText)
                                        coroutineScope.launch {
                                            snackbarHostState.showSnackbar("Saved SOCKS5 credentials")
                                        }
                                    },
                                    enabled = usernameText.isNotBlank(),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(48.dp)
                                        .testTag("save_credentials_button"),
                                ) {
                                    Text("Save Credentials")
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.height(12.dp))
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
private fun SettingsScreenPreview() {
    GameShareTheme(darkTheme = true) {
        SettingsScreen(
            settings = ProxySettings(authEnabled = true, password = "secret"),
            isSharing = false,
            onUpdateMtu = {},
            onUpdatePort = {},
            onUpdateAuthEnabled = {},
            onUpdateCredentials = { _, _ -> },
            onUpdateGamingMode = {},
            onNavigateBack = {},
        )
    }
}
