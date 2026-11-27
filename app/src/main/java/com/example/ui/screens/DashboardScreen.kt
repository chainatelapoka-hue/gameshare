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

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.CellTower
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Gamepad
import androidx.compose.material.icons.filled.PowerSettingsNew
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.WifiTethering
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Security
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.example.R
import com.example.data.ConnectedDevice
import com.example.data.GameShareUiState
import com.example.data.ProxySettings
import com.example.data.SharingStatus
import com.example.data.TrafficStats
import com.example.ui.components.QrCodeImage
import com.example.ui.components.gameShareJoinPayload
import com.example.ui.theme.GameShareTheme
import com.example.ui.theme.LocalGameShareColors
import com.example.ui.theme.MonoCodeStyle
import com.example.ui.theme.MonoMetricStyle
import kotlinx.coroutines.launch
import java.util.Locale

/**
 * Primary single-screen GameShare dashboard displaying sharing toggle, live status pill,
 * UDP relay indicator, 1-second throughput stats, P2P + SOCKS5 connection info, and
 * expandable connected device details.
 */
@Composable
fun DashboardScreen(
    uiState: GameShareUiState,
    settings: ProxySettings,
    onToggleSharing: () -> Unit,
    onRequestPermissions: () -> Unit,
    onOpenSettings: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val snackbarHostState = remember { SnackbarHostState() }
    val coroutineScope = rememberCoroutineScope()
    var devicesExpanded by rememberSaveable { mutableStateOf(false) }

    val copyText: (String, String) -> Unit = { label, value ->
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm?.setPrimaryClip(ClipData.newPlainText(label, value))
        coroutineScope.launch {
            snackbarHostState.currentSnackbarData?.dismiss()
            snackbarHostState.showSnackbar("Copied $label: $value")
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets.safeDrawing,
        containerColor = MaterialTheme.colorScheme.background,
        snackbarHost = { SnackbarHost(hostState = snackbarHostState) },
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
                DashboardHeader(
                    udpRelayReady = uiState.udpRelayReady,
                    activeUdpAssociations = uiState.activeUdpAssociations,
                    gamingMode = settings.gamingMode,
                    onOpenSettings = onOpenSettings,
                )

                StatusAndToggleHero(
                    uiState = uiState,
                    onToggleSharing = onToggleSharing,
                    onRequestPermissions = onRequestPermissions,
                )

                StatsCard(
                    connectedCount = uiState.connectedDevices.size,
                    trafficStats = uiState.trafficStats,
                )

                ConnectionInfoCard(
                    uiState = uiState,
                    authEnabled = settings.authEnabled,
                    onCopySocks = { copyText("SOCKS5", uiState.socksAddress) },
                    onCopyPassphrase = { pass -> copyText("Passphrase", pass) },
                )

                DeviceDetailsCard(
                    devices = uiState.connectedDevices,
                    expanded = devicesExpanded,
                    socksAddress = uiState.socksAddress,
                    onToggleExpand = { devicesExpanded = !devicesExpanded },
                )

                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun DashboardHeader(
    udpRelayReady: Boolean,
    activeUdpAssociations: Int,
    gamingMode: Boolean,
    onOpenSettings: () -> Unit,
) {
    val extColors = LocalGameShareColors.current

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Filled.WifiTethering,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = stringResource(R.string.app_name),
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.onBackground,
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // UDP Relay Indicator (critical for online gaming & voice chat)
                val udpBg = if (udpRelayReady) extColors.activeContainer else MaterialTheme.colorScheme.surfaceVariant
                val udpFg = if (udpRelayReady) extColors.active else MaterialTheme.colorScheme.onSurfaceVariant
                Surface(
                    shape = RoundedCornerShape(50),
                    color = udpBg,
                    border = BorderStroke(
                        1.dp,
                        if (udpRelayReady) extColors.active.copy(alpha = 0.45f) else extColors.cardBorder,
                    ),
                    modifier = Modifier.testTag("udp_relay_indicator"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Box(
                            modifier = Modifier
                                .size(7.dp)
                                .clip(CircleShape)
                                .background(udpFg),
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = if (udpRelayReady) {
                                if (activeUdpAssociations > 0) "UDP Relay Active ($activeUdpAssociations)" else "UDP Relay Ready"
                            } else {
                                "UDP Relay Standby"
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = udpFg,
                        )
                    }
                }

                if (gamingMode) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.7f),
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Gamepad,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(12.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Low-Ping",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer,
                            )
                        }
                    }
                }
            }
        }

        IconButton(
            onClick = onOpenSettings,
            modifier = Modifier
                .minimumInteractiveComponentSize()
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .testTag("settings_button"),
        ) {
            Icon(
                imageVector = Icons.Filled.Settings,
                contentDescription = stringResource(R.string.cd_settings),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }
    }
}

@Composable
private fun StatusAndToggleHero(
    uiState: GameShareUiState,
    onToggleSharing: () -> Unit,
    onRequestPermissions: () -> Unit,
) {
    val extColors = LocalGameShareColors.current

    val (statusLabel, statusColor, statusBg) = when (uiState.status) {
        SharingStatus.DISCONNECTED -> Triple(
            stringResource(R.string.status_disconnected),
            MaterialTheme.colorScheme.onSurfaceVariant,
            MaterialTheme.colorScheme.surfaceVariant,
        )

        SharingStatus.WAITING_FOR_DEVICES -> Triple(
            stringResource(R.string.status_waiting),
            extColors.waiting,
            extColors.waitingContainer,
        )

        SharingStatus.ACTIVE -> Triple(
            stringResource(R.string.status_active),
            extColors.active,
            extColors.activeContainer,
        )

        SharingStatus.ERROR -> Triple(
            stringResource(R.string.status_error),
            extColors.errorAccent,
            extColors.errorContainerBg,
        )
    }

    val pulseTransition = rememberInfiniteTransition(label = "status_pulse")
    val pulseAlpha by pulseTransition.animateFloat(
        initialValue = 0.35f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(900, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "pulse_alpha",
    )

    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, extColors.cardBorder),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Status pill
                Surface(
                    shape = RoundedCornerShape(50),
                    color = statusBg,
                    modifier = Modifier.testTag("status_pill"),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        val dotAlpha = if (uiState.isSharing) pulseAlpha else 1f
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .clip(CircleShape)
                                .background(statusColor.copy(alpha = dotAlpha)),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = statusLabel,
                            style = MaterialTheme.typography.labelLarge,
                            color = statusColor,
                        )
                    }
                }

                // Cellular uplink pill
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.CellTower,
                        contentDescription = null,
                        tint = if (uiState.hasCellularInternet) {
                            extColors.active
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        modifier = Modifier.size(16.dp),
                    )
                    Text(
                        text = if (uiState.hasCellularInternet) "Cellular Ready" else "No Mobile Data",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (uiState.hasCellularInternet) {
                            extColors.active
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                    )
                }
            }

            if (!uiState.permissionsGranted) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = extColors.waitingContainer,
                    border = BorderStroke(1.dp, extColors.waiting.copy(alpha = 0.4f)),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(horizontal = 12.dp, vertical = 10.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Row(
                            modifier = Modifier.weight(1f),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Security,
                                contentDescription = null,
                                tint = extColors.waiting,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "Nearby Wi-Fi & Location permissions needed to share.",
                                style = MaterialTheme.typography.bodySmall,
                                color = extColors.onWaitingContainer,
                            )
                        }
                        TextButton(
                            onClick = onRequestPermissions,
                            modifier = Modifier.testTag("grant_permissions_button"),
                        ) {
                            Text("Grant", color = extColors.waiting, fontWeight = FontWeight.Bold)
                        }
                    }
                }
            }

            if (uiState.errorMessage != null) {
                Surface(
                    shape = RoundedCornerShape(14.dp),
                    color = extColors.errorContainerBg,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.Info,
                            contentDescription = null,
                            tint = extColors.errorAccent,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = uiState.errorMessage,
                            style = MaterialTheme.typography.bodySmall,
                            color = extColors.onErrorContainerText,
                        )
                    }
                }
            }

            // Large Start / Stop Sharing toggle button
            val buttonContainerColor by animateColorAsState(
                targetValue = if (uiState.isSharing) {
                    extColors.errorContainerBg
                } else {
                    MaterialTheme.colorScheme.primary
                },
                label = "toggle_bg",
            )
            val buttonContentColor by animateColorAsState(
                targetValue = if (uiState.isSharing) {
                    extColors.errorAccent
                } else {
                    MaterialTheme.colorScheme.onPrimary
                },
                label = "toggle_fg",
            )

            val brush = if (uiState.isSharing) {
                Brush.horizontalGradient(
                    listOf(extColors.errorContainerBg, extColors.errorContainerBg),
                )
            } else {
                Brush.horizontalGradient(
                    listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary),
                )
            }

            Button(
                onClick = onToggleSharing,
                enabled = !uiState.isStartingOrStopping,
                shape = RoundedCornerShape(18.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Color.Transparent,
                    contentColor = buttonContentColor,
                    disabledContainerColor = buttonContainerColor.copy(alpha = 0.6f),
                ),
                contentPadding = PaddingValues(0.dp),
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp)
                    .clip(RoundedCornerShape(18.dp))
                    .background(brush)
                    .then(
                        if (uiState.isSharing) {
                            Modifier.border(
                                1.5.dp,
                                extColors.errorAccent.copy(alpha = 0.6f),
                                RoundedCornerShape(18.dp),
                            )
                        } else {
                            Modifier
                        },
                    )
                    .testTag("toggle_sharing_button"),
            ) {
                if (uiState.isStartingOrStopping) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(22.dp),
                        strokeWidth = 2.5.dp,
                        color = buttonContentColor,
                    )
                    Spacer(modifier = Modifier.width(12.dp))
                    Text(
                        text = if (uiState.isSharing) "Stopping..." else "Starting P2P & Proxy...",
                        style = MaterialTheme.typography.titleMedium,
                        color = buttonContentColor,
                    )
                } else {
                    Icon(
                        imageVector = Icons.Filled.PowerSettingsNew,
                        contentDescription = null,
                        tint = buttonContentColor,
                        modifier = Modifier.size(24.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = if (uiState.isSharing) {
                            stringResource(R.string.action_stop_sharing)
                        } else {
                            stringResource(R.string.action_start_sharing)
                        },
                        style = MaterialTheme.typography.titleLarge,
                        color = buttonContentColor,
                    )
                }
            }
        }
    }
}

@Composable
private fun StatsCard(
    connectedCount: Int,
    trafficStats: TrafficStats,
) {
    val extColors = LocalGameShareColors.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, extColors.cardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            StatMetric(
                label = "Devices",
                value = connectedCount.toString(),
                unit = "online",
                icon = Icons.Filled.Devices,
                accent = MaterialTheme.colorScheme.primary,
                modifier = Modifier.weight(1f),
            )
            VerticalMetricDivider()
            StatMetric(
                label = "Upload",
                value = String.format(Locale.US, "%.2f", trafficStats.uploadMbps),
                unit = "Mbps",
                icon = Icons.Filled.ArrowUpward,
                accent = extColors.waiting,
                modifier = Modifier.weight(1f),
            )
            VerticalMetricDivider()
            StatMetric(
                label = "Download",
                value = String.format(Locale.US, "%.2f", trafficStats.downloadMbps),
                unit = "Mbps",
                icon = Icons.Filled.ArrowDownward,
                accent = extColors.active,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun VerticalMetricDivider() {
    Box(
        modifier = Modifier
            .width(1.dp)
            .height(44.dp)
            .background(LocalGameShareColors.current.cardBorder),
    )
}

@Composable
private fun StatMetric(
    label: String,
    value: String,
    unit: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    accent: Color,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = accent,
                modifier = Modifier.size(14.dp),
            )
            Spacer(modifier = Modifier.width(4.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = value,
            style = MonoMetricStyle,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
        )
        Text(
            text = unit,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ConnectionInfoCard(
    uiState: GameShareUiState,
    authEnabled: Boolean,
    onCopySocks: () -> Unit,
    onCopyPassphrase: (String) -> Unit,
) {
    val extColors = LocalGameShareColors.current
    var showPassphrase by rememberSaveable { mutableStateOf(false) }

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
                Text(
                    text = "Client Connection Info",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (authEnabled) {
                    Surface(
                        shape = RoundedCornerShape(50),
                        color = MaterialTheme.colorScheme.primaryContainer,
                    ) {
                        Text(
                            text = "Auth ON",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
                        )
                    }
                }
            }

            // P2P Network Name
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "P2P Network Name",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = uiState.p2pNetworkName ?: "Created when sharing starts",
                        style = MonoCodeStyle,
                        color = if (uiState.p2pNetworkName != null) {
                            MaterialTheme.colorScheme.onSurface
                        } else {
                            MaterialTheme.colorScheme.onSurfaceVariant
                        },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
            }

            // Optional Passphrase (shown when Wi-Fi Direct group is active)
            if (!uiState.p2pPassphrase.isNullOrBlank()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Wi-Fi Direct Passphrase",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(
                            text = if (showPassphrase) uiState.p2pPassphrase else "••••••••••••",
                            style = MonoCodeStyle,
                            color = MaterialTheme.colorScheme.onSurface,
                        )
                    }
                    Row {
                        IconButton(
                            onClick = { showPassphrase = !showPassphrase },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = if (showPassphrase) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = stringResource(R.string.cd_toggle_passphrase),
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        IconButton(
                            onClick = { onCopyPassphrase(uiState.p2pPassphrase) },
                            modifier = Modifier.minimumInteractiveComponentSize(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.ContentCopy,
                                contentDescription = stringResource(R.string.cd_copy_passphrase),
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                    }
                }
            }

            val qrSsid = uiState.p2pNetworkName
            val qrPass = uiState.p2pPassphrase
            if (uiState.isSharing && qrSsid != null && !qrPass.isNullOrBlank()) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Text(
                        text = "gameshareX QR: client ফোনে gameshareX খুলে এই QR স্ক্যান করুন",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    QrCodeImage(
                        payload = gameShareJoinPayload(
                            ssid = qrSsid,
                            passphrase = qrPass,
                            host = uiState.socksHost,
                            port = uiState.socksPort,
                            authEnabled = authEnabled,
                        ),
                    )
                }
            }
            HorizontalDivider(color = extColors.cardBorder)

            // SOCKS5 Host:Port row with Copy button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(14.dp))
                    .background(MaterialTheme.colorScheme.surfaceVariant)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "SOCKS5 Proxy Address (TCP + UDP)",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        text = uiState.socksAddress,
                        style = MonoCodeStyle.copy(fontWeight = FontWeight.Bold),
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.testTag("socks_address_text"),
                    )
                }

                IconButton(
                    onClick = onCopySocks,
                    modifier = Modifier
                        .minimumInteractiveComponentSize()
                        .testTag("copy_socks_button"),
                ) {
                    Icon(
                        imageVector = Icons.Filled.ContentCopy,
                        contentDescription = stringResource(R.string.cd_copy_socks),
                        tint = MaterialTheme.colorScheme.primary,
                    )
                }
            }
        }
    }
}

@Composable
private fun DeviceDetailsCard(
    devices: List<ConnectedDevice>,
    expanded: Boolean,
    socksAddress: String,
    onToggleExpand: () -> Unit,
) {
    val extColors = LocalGameShareColors.current

    Card(
        shape = RoundedCornerShape(20.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = BorderStroke(1.dp, extColors.cardBorder),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .minimumInteractiveComponentSize()
                    .clickable(onClick = onToggleExpand)
                    .padding(horizontal = 16.dp, vertical = 14.dp)
                    .testTag("device_details_toggle"),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Filled.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Device details (${devices.size})",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }

                Icon(
                    imageVector = if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                    contentDescription = stringResource(R.string.cd_toggle_devices),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }

            AnimatedVisibility(
                visible = expanded,
                enter = expandVertically() + fadeIn(),
                exit = shrinkVertically() + fadeOut(),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(start = 16.dp, end = 16.dp, bottom = 16.dp),
                    verticalArrangement = Arrangement.spacedBy(10.dp),
                ) {
                    HorizontalDivider(color = extColors.cardBorder)

                    if (devices.isEmpty()) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 6.dp),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Icon(
                                imageVector = Icons.Outlined.Info,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(18.dp),
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "No clients yet. Join the Wi-Fi Direct group on another Android device and route its SOCKS5 client to $socksAddress.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    } else {
                        devices.forEach { device ->
                            Surface(
                                shape = RoundedCornerShape(12.dp),
                                color = MaterialTheme.colorScheme.surfaceVariant,
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(horizontal = 12.dp, vertical = 10.dp),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    Column(modifier = Modifier.weight(1f)) {
                                        Text(
                                            text = device.hostname ?: "Android Client",
                                            style = MaterialTheme.typography.labelLarge,
                                            color = MaterialTheme.colorScheme.onSurface,
                                        )
                                        Text(
                                            text = buildString {
                                                append(device.ipAddress)
                                                if (!device.macAddress.isNullOrBlank()) {
                                                    append(" • ")
                                                    append(device.macAddress)
                                                }
                                            },
                                            style = MonoCodeStyle.copy(fontSize = MaterialTheme.typography.bodySmall.fontSize),
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }

                                    Column(horizontalAlignment = Alignment.End) {
                                        Text(
                                            text = "${device.activeConnections} sockets",
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.primary,
                                        )
                                        Text(
                                            text = formatBytes(device.bytesTransferred),
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> String.format(Locale.US, "%.2f GB", bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> String.format(Locale.US, "%.1f MB", bytes / 1_048_576.0)
    bytes >= 1_024L -> String.format(Locale.US, "%.1f KB", bytes / 1_024.0)
    else -> "$bytes B"
}

@Preview(showBackground = true, name = "Dashboard Disconnected")
@Composable
private fun DashboardDisconnectedPreview() {
    GameShareTheme(darkTheme = true) {
        DashboardScreen(
            uiState = GameShareUiState(
                status = SharingStatus.DISCONNECTED,
                hasCellularInternet = true,
                permissionsGranted = true,
            ),
            settings = ProxySettings(),
            onToggleSharing = {},
            onRequestPermissions = {},
            onOpenSettings = {},
        )
    }
}

@Preview(showBackground = true, name = "Dashboard Active")
@Composable
private fun DashboardActivePreview() {
    GameShareTheme(darkTheme = true) {
        DashboardScreen(
            uiState = GameShareUiState(
                status = SharingStatus.ACTIVE,
                hasCellularInternet = true,
                permissionsGranted = true,
                p2pNetworkName = "DIRECT-7A-Pixel8Pro",
                p2pPassphrase = "game-9482-share",
                socksHost = "192.168.49.1",
                socksPort = 1080,
                udpRelayReady = true,
                activeUdpAssociations = 2,
                connectedDevices = listOf(
                    ConnectedDevice(
                        ipAddress = "192.168.49.84",
                        hostname = "Galaxy S24",
                        macAddress = "02:1a:11:f0:9c:42",
                        activeConnections = 6,
                        bytesTransferred = 48_234_496L,
                    ),
                ),
                trafficStats = TrafficStats(
                    uploadMbps = 3.42,
                    downloadMbps = 42.18,
                ),
            ),
            settings = ProxySettings(gamingMode = true),
            onToggleSharing = {},
            onRequestPermissions = {},
            onOpenSettings = {},
        )
    }
}
