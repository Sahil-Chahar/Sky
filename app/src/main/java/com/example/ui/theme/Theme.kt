package com.example.ui.theme

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.dp

val LocalSkyThemeSpec = staticCompositionLocalOf { SkyThemePalettes.SkyLight }

val SkyShapes = Shapes(
    extraSmall = RoundedCornerShape(12.dp),
    small = RoundedCornerShape(16.dp),
    medium = RoundedCornerShape(22.dp),
    large = RoundedCornerShape(26.dp),
    extraLarge = RoundedCornerShape(28.dp)
)

object SkyTheme {
    val spec: ThemeSpec
        @Composable
        @ReadOnlyComposable
        get() = LocalSkyThemeSpec.current
}

@Composable
fun SkyAssistantTheme(
    preset: SkyThemePreset = SkyThemePreset.SKY,
    mode: SkyThemeMode = SkyThemeMode.SYSTEM,
    content: @Composable () -> Unit
) {
    val systemDark = isSystemInDarkTheme()
    val useDark = when (mode) {
        SkyThemeMode.SYSTEM -> systemDark
        SkyThemeMode.LIGHT -> false
        SkyThemeMode.DARK -> true
    }

    val targetSpec = SkyThemePalettes.resolve(preset, useDark)
    val colorSpring = spring<androidx.compose.ui.graphics.Color>(stiffness = Spring.StiffnessMediumLow)

    val animatedBg by animateColorAsState(targetSpec.bg, colorSpring, label = "bg")
    val animatedHi by animateColorAsState(targetSpec.hi, colorSpring, label = "hi")
    val animatedLo by animateColorAsState(targetSpec.lo, colorSpring, label = "lo")
    val animatedText by animateColorAsState(targetSpec.primaryText, colorSpring, label = "text")
    val animatedMuted by animateColorAsState(targetSpec.mutedText, colorSpring, label = "muted")
    val animatedAccent by animateColorAsState(targetSpec.accent, colorSpring, label = "accent")
    val animatedAccentFill by animateColorAsState(targetSpec.accentFill, colorSpring, label = "accentFill")
    val animatedOnAccent by animateColorAsState(targetSpec.onAccentText, colorSpring, label = "onAccent")
    val animatedAccentText by animateColorAsState(targetSpec.accentText, colorSpring, label = "accentText")
    val animatedHairline by animateColorAsState(targetSpec.hairline, colorSpring, label = "hairline")

    val activeSpec = targetSpec.copy(
        bg = animatedBg,
        hi = animatedHi,
        lo = animatedLo,
        primaryText = animatedText,
        mutedText = animatedMuted,
        accent = animatedAccent,
        accentFill = animatedAccentFill,
        onAccentText = animatedOnAccent,
        accentText = animatedAccentText,
        hairline = animatedHairline
    )

    val m3Scheme = if (useDark) {
        darkColorScheme(
            primary = activeSpec.accentFill,
            onPrimary = activeSpec.onAccentText,
            secondary = activeSpec.accent,
            onSecondary = activeSpec.onAccentText,
            background = activeSpec.bg,
            onBackground = activeSpec.primaryText,
            surface = activeSpec.bg,
            onSurface = activeSpec.primaryText,
            surfaceVariant = activeSpec.lo,
            onSurfaceVariant = activeSpec.mutedText,
            outline = activeSpec.hairline,
            error = activeSpec.errorFill
        )
    } else {
        lightColorScheme(
            primary = activeSpec.accentFill,
            onPrimary = activeSpec.onAccentText,
            secondary = activeSpec.accent,
            onSecondary = activeSpec.onAccentText,
            background = activeSpec.bg,
            onBackground = activeSpec.primaryText,
            surface = activeSpec.bg,
            onSurface = activeSpec.primaryText,
            surfaceVariant = activeSpec.lo,
            onSurfaceVariant = activeSpec.mutedText,
            outline = activeSpec.hairline,
            error = activeSpec.errorFill
        )
    }

    CompositionLocalProvider(LocalSkyThemeSpec provides activeSpec) {
        MaterialTheme(
            colorScheme = m3Scheme,
            typography = SkyTypography,
            shapes = SkyShapes,
            content = content
        )
    }
}
