package com.example.ui.theme

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

enum class SkyThemePreset(val id: String, val displayName: String, val isV1Default: Boolean) {
    SKY("sky", "Sky", true),
    CLAY("clay", "Clay", false),
    GRAPHITE("graphite", "Graphite", false)
}

enum class SkyThemeMode(val id: String, val displayName: String) {
    SYSTEM("system", "System"),
    LIGHT("light", "Light"),
    DARK("dark", "Dark")
}

@Immutable
data class ThemeSpec(
    val preset: SkyThemePreset,
    val isDark: Boolean,
    val bg: Color,
    val hi: Color,
    val lo: Color,
    val primaryText: Color,
    val mutedText: Color,
    val accent: Color,
    val accentFill: Color,
    val onAccentText: Color,
    val accentText: Color,
    val hairline: Color,
    val successFill: Color = Color(0xFF2FBF71),
    val warningFill: Color = Color(0xFFF5A524),
    val errorFill: Color = Color(0xFFE5484D),
    val successText: Color,
    val warningText: Color,
    val errorText: Color
)

object SkyThemePalettes {

    val SkyLight = ThemeSpec(
        preset = SkyThemePreset.SKY,
        isDark = false,
        bg = Color(0xFFE8EEF6),
        hi = Color(0xFFFFFFFF),
        lo = Color(0xFFBCC8DA),
        primaryText = Color(0xFF1B2433),
        mutedText = Color(0xFF5A687C),
        accent = Color(0xFF1E88E5),
        accentFill = Color(0xFF1565C0),
        onAccentText = Color(0xFFFFFFFF),
        accentText = Color(0xFF1565C0),
        hairline = Color(0xFFD0DBE9),
        successText = Color(0xFF166938),
        warningText = Color(0xFF8A5000),
        errorText = Color(0xFFB71C1C)
    )

    val SkyDark = ThemeSpec(
        preset = SkyThemePreset.SKY,
        isDark = true,
        bg = Color(0xFF1C222D),
        hi = Color(0xFF28313F),
        lo = Color(0xFF12161D),
        primaryText = Color(0xFFE8EEF7),
        mutedText = Color(0xFF93A0B4),
        accent = Color(0xFF5CB8FF),
        accentFill = Color(0xFF5CB8FF),
        onAccentText = Color(0xFF0D131A),
        accentText = Color(0xFF5CB8FF),
        hairline = Color(0xFF2C3646),
        successText = Color(0xFF4CD987),
        warningText = Color(0xFFFFB84D),
        errorText = Color(0xFFFF7A7E)
    )

    val ClayLight = ThemeSpec(
        preset = SkyThemePreset.CLAY,
        isDark = false,
        bg = Color(0xFFF0EBE1),
        hi = Color(0xFFFFFDF8),
        lo = Color(0xFFD3CABA),
        primaryText = Color(0xFF2B2926),
        mutedText = Color(0xFF6B645B),
        accent = Color(0xFFC6613F),
        accentFill = Color(0xFFA84A2C),
        onAccentText = Color(0xFFFFFFFF),
        accentText = Color(0xFFA84A2C),
        hairline = Color(0xFFDCD3C4),
        successText = Color(0xFF166938),
        warningText = Color(0xFF8A5000),
        errorText = Color(0xFFB71C1C)
    )

    val ClayDark = ThemeSpec(
        preset = SkyThemePreset.CLAY,
        isDark = true,
        bg = Color(0xFF262421),
        hi = Color(0xFF322F2C),
        lo = Color(0xFF181716),
        primaryText = Color(0xFFECE8E1),
        mutedText = Color(0xFFA29B90),
        accent = Color(0xFFE58C6C),
        accentFill = Color(0xFFE58C6C),
        onAccentText = Color(0xFF181614),
        accentText = Color(0xFFE58C6C),
        hairline = Color(0xFF383430),
        successText = Color(0xFF4CD987),
        warningText = Color(0xFFFFB84D),
        errorText = Color(0xFFFF7A7E)
    )

    val GraphiteLight = ThemeSpec(
        preset = SkyThemePreset.GRAPHITE,
        isDark = false,
        bg = Color(0xFFECEDEF),
        hi = Color(0xFFFFFFFF),
        lo = Color(0xFFC9CCD2),
        primaryText = Color(0xFF0D0D0D),
        mutedText = Color(0xFF666677),
        accent = Color(0xFF0E8F6F),
        accentFill = Color(0xFF0B7259),
        onAccentText = Color(0xFFFFFFFF),
        accentText = Color(0xFF0B7259),
        hairline = Color(0xFFD5D8DE),
        successText = Color(0xFF166938),
        warningText = Color(0xFF8A5000),
        errorText = Color(0xFFB71C1C)
    )

    val GraphiteDark = ThemeSpec(
        preset = SkyThemePreset.GRAPHITE,
        isDark = true,
        bg = Color(0xFF212121),
        hi = Color(0xFF2E2E2E),
        lo = Color(0xFF151515),
        primaryText = Color(0xFFECECEC),
        mutedText = Color(0xFF9B9B9B),
        accent = Color(0xFF10A37F),
        accentFill = Color(0xFF10A37F),
        onAccentText = Color(0xFF0D1714),
        accentText = Color(0xFF10A37F),
        hairline = Color(0xFF333333),
        successText = Color(0xFF4CD987),
        warningText = Color(0xFFFFB84D),
        errorText = Color(0xFFFF7A7E)
    )

    val AllSpecs: List<ThemeSpec> = listOf(
        SkyLight,
        SkyDark,
        ClayLight,
        ClayDark,
        GraphiteLight,
        GraphiteDark
    )

    fun resolve(preset: SkyThemePreset, isDark: Boolean): ThemeSpec {
        return when (preset) {
            SkyThemePreset.SKY -> if (isDark) SkyDark else SkyLight
            SkyThemePreset.CLAY -> if (isDark) ClayDark else ClayLight
            SkyThemePreset.GRAPHITE -> if (isDark) GraphiteDark else GraphiteLight
        }
    }

    /**
     * Siri Wake Animation Multicolor Palette (#BC82F3 #F5B9EA #8D99FF #AA6EEE #FF6778 #FFBA71 #C686FF)
     */
    val SiriGlowPalette: List<Color> = listOf(
        Color(0xFFBC82F3),
        Color(0xFFF5B9EA),
        Color(0xFF8D99FF),
        Color(0xFFAA6EEE),
        Color(0xFFFF6778),
        Color(0xFFFFBA71),
        Color(0xFFC686FF)
    )

    /**
     * Computes exact WCAG 2.1 contrast ratio between two Compose Colors.
     */
    fun wcagContrastRatio(foreground: Color, background: Color): Double {
        val l1 = relativeLuminance(foreground)
        val l2 = relativeLuminance(background)
        val lighter = max(l1, l2)
        val darker = min(l1, l2)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double {
        fun channelLum(c: Float): Double {
            val v = c.toDouble()
            return if (v <= 0.03928) v / 12.92 else ((v + 0.055) / 1.055).pow(2.4)
        }
        val r = channelLum(color.red)
        val g = channelLum(color.green)
        val b = channelLum(color.blue)
        return 0.2126 * r + 0.7152 * g + 0.0722 * b
    }
}
