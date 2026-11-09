package com.example.ui.components

import android.provider.Settings
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SkyTheme
import kotlin.math.roundToInt

/**
 * Sky Sun-and-Cloud 3D Neumorphic Emblem with gentle empty-state float animation.
 * Honors Android reduced-motion (ANIMATOR_DURATION_SCALE == 0f).
 */
@Composable
fun SkyEmblem(
    modifier: Modifier = Modifier,
    size: Dp = 96.dp,
    animateFloat: Boolean = true
) {
    val context = LocalContext.current
    val spec = SkyTheme.spec
    val reducedMotion = remember(context) {
        try {
            Settings.Global.getFloat(
                context.contentResolver,
                Settings.Global.ANIMATOR_DURATION_SCALE,
                1f
            ) == 0f
        } catch (_: Throwable) {
            false
        }
    }

    val infiniteTransition = rememberInfiniteTransition(label = "skyEmblemFloat")
    val floatOffsetPx by infiniteTransition.animateFloat(
        initialValue = -6f,
        targetValue = 6f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2800, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "floatY"
    )
    val sunGlowScale by infiniteTransition.animateFloat(
        initialValue = 0.96f,
        targetValue = 1.05f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 2200, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "sunPulse"
    )

    val effectiveOffsetY = if (animateFloat && !reducedMotion) floatOffsetPx.roundToInt() else 0
    val effectiveScale = if (animateFloat && !reducedMotion) sunGlowScale else 1f

    NeuSurface(
        modifier = modifier
            .size(size)
            .offset { IntOffset(0, effectiveOffsetY) },
        depth = 2,
        shape = CircleShape,
        cornerRadiusDp = size / 2,
        contentAlignment = Alignment.Center
    ) {
        Box(contentAlignment = Alignment.Center) {
            Canvas(modifier = Modifier.size(size * 0.72f)) {
                val w = this.size.width
                val h = this.size.height

                // 1. Warm Golden Sun (top-left behind cloud)
                val sunCenter = Offset(w * 0.36f, h * 0.38f)
                val sunRadius = w * 0.24f * effectiveScale

                drawCircle(
                    brush = Brush.radialGradient(
                        colors = listOf(
                            Color(0xFFFFD54F).copy(alpha = 0.45f),
                            Color(0xFFFF8F00).copy(alpha = 0.0f)
                        ),
                        center = sunCenter,
                        radius = sunRadius * 1.7f
                    ),
                    radius = sunRadius * 1.7f,
                    center = sunCenter
                )

                drawCircle(
                    brush = Brush.linearGradient(
                        colors = listOf(Color(0xFFFFE082), Color(0xFFFF9800)),
                        start = Offset(sunCenter.x - sunRadius, sunCenter.y - sunRadius),
                        end = Offset(sunCenter.x + sunRadius, sunCenter.y + sunRadius)
                    ),
                    radius = sunRadius,
                    center = sunCenter
                )

                // 2. Sculpted 3D Neumorphic Cloud Foreground
                val cloudTop = if (spec.isDark) Color(0xFFE8EEF7) else Color(0xFFFFFFFF)
                val cloudBottom = if (spec.isDark) Color(0xFFB5C4DA) else Color(0xFFDCE6F4)
                val cloudBrush = Brush.linearGradient(
                    colors = listOf(cloudTop, cloudBottom),
                    start = Offset(w * 0.2f, h * 0.35f),
                    end = Offset(w * 0.8f, h * 0.85f)
                )

                // Cloud base pill
                drawRoundRect(
                    brush = cloudBrush,
                    topLeft = Offset(w * 0.16f, h * 0.52f),
                    size = Size(w * 0.68f, h * 0.26f),
                    cornerRadius = CornerRadius(h * 0.13f, h * 0.13f)
                )
                // Left puff
                drawCircle(
                    brush = cloudBrush,
                    radius = w * 0.15f,
                    center = Offset(w * 0.36f, h * 0.54f)
                )
                // Main center-right puff
                drawCircle(
                    brush = cloudBrush,
                    radius = w * 0.19f,
                    center = Offset(w * 0.56f, h * 0.48f)
                )
                // Subtle accent rim highlight
                drawRoundRect(
                    color = spec.accent.copy(alpha = 0.32f),
                    topLeft = Offset(w * 0.22f, h * 0.74f),
                    size = Size(w * 0.56f, h * 0.03f),
                    cornerRadius = CornerRadius(4f, 4f)
                )
            }
        }
    }
}
