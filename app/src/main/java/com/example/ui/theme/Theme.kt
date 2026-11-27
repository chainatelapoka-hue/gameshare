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

package com.example.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color

@Immutable
data class GameShareExtendedColors(
    val active: Color,
    val activeContainer: Color,
    val onActiveContainer: Color,
    val waiting: Color,
    val waitingContainer: Color,
    val onWaitingContainer: Color,
    val errorAccent: Color,
    val errorContainerBg: Color,
    val onErrorContainerText: Color,
    val cardBorder: Color,
)

private val DarkExtendedColors = GameShareExtendedColors(
    active = EmeraldActive,
    activeContainer = EmeraldContainer,
    onActiveContainer = EmeraldOnContainer,
    waiting = AmberWaiting,
    waitingContainer = AmberContainer,
    onWaitingContainer = AmberOnContainer,
    errorAccent = CoralError,
    errorContainerBg = CoralContainer,
    onErrorContainerText = CoralOnContainer,
    cardBorder = SlateBorder,
)

private val LightExtendedColors = GameShareExtendedColors(
    active = LightEmerald,
    activeContainer = LightEmeraldContainer,
    onActiveContainer = Color(0xFF00210E),
    waiting = LightAmber,
    waitingContainer = LightAmberContainer,
    onWaitingContainer = Color(0xFF331C00),
    errorAccent = LightCoral,
    errorContainerBg = LightCoralContainer,
    onErrorContainerText = Color(0xFF410002),
    cardBorder = LightBorder,
)

val LocalGameShareColors = staticCompositionLocalOf { DarkExtendedColors }

private val DarkColorScheme = darkColorScheme(
    primary = CyanPrimary,
    onPrimary = CyanOnPrimary,
    primaryContainer = CyanContainer,
    onPrimaryContainer = CyanOnContainer,
    secondary = EmeraldActive,
    onSecondary = EmeraldOn,
    secondaryContainer = EmeraldContainer,
    onSecondaryContainer = EmeraldOnContainer,
    background = MidnightCanvas,
    onBackground = TextPrimaryDark,
    surface = SlateSurface,
    onSurface = TextPrimaryDark,
    surfaceVariant = SlateCard,
    onSurfaceVariant = TextSecondaryDark,
    surfaceContainerHigh = SlateElevated,
    outline = SlateBorder,
    error = CoralError,
    errorContainer = CoralContainer,
    onErrorContainer = CoralOnContainer,
)

private val LightColorScheme = lightColorScheme(
    primary = LightPrimary,
    onPrimary = Color.White,
    primaryContainer = LightPrimaryContainer,
    onPrimaryContainer = LightOnPrimaryContainer,
    secondary = LightEmerald,
    onSecondary = Color.White,
    secondaryContainer = LightEmeraldContainer,
    onSecondaryContainer = Color(0xFF00210E),
    background = LightCanvas,
    onBackground = TextPrimaryLight,
    surface = LightSurface,
    onSurface = TextPrimaryLight,
    surfaceVariant = LightSurfaceVariant,
    onSurfaceVariant = TextSecondaryLight,
    surfaceContainerHigh = Color(0xFFEDF2FA),
    outline = LightBorder,
    error = LightCoral,
    errorContainer = LightCoralContainer,
    onErrorContainer = Color(0xFF410002),
)

@Composable
fun GameShareTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme
    val extendedColors = if (darkTheme) DarkExtendedColors else LightExtendedColors

    CompositionLocalProvider(LocalGameShareColors provides extendedColors) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = GameShareTypography,
            content = content,
        )
    }
}
