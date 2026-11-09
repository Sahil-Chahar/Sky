package com.example.ui.components

import android.graphics.BlurMaskFilter
import android.view.HapticFeedbackConstants
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SkyTheme

/**
 * Reusable Neo 3D Neumorphic Surface (`NeuSurface`).
 *
 * Depths:
 *  -1 : Inset (text fields, toggle tracks, selected tab, pressed button)
 *   0 : Flat (flush surface with 1dp hairline)
 *   1 : Raised (cards, chat bubbles, buttons)
 *   2 : Floating (bottom input card, primary voice button, drawer)
 *
 * Performance optimization:
 *  When [useCachedShadowForList] is true (for repeated list items such as chat bubbles,
 *  history rows, and model cards), uses pre-cached BlurMaskFilter Paint instances in a
 *  single drawBehind pass without re-allocating masks per frame.
 */
private class CachedShadowPaints {
    val lightPaint = Paint().apply { isAntiAlias = true }
    val darkPaint = Paint().apply { isAntiAlias = true }
    var lastBlurPx: Float = -1f
    var lastLightArgb: Int = 0
    var lastDarkArgb: Int = 0

    fun updateIfNeeded(blurPx: Float, lightColor: Color, darkColor: Color) {
        val lArgb = lightColor.toArgb()
        val dArgb = darkColor.toArgb()
        if (blurPx != lastBlurPx) {
            val safeBlur = blurPx.coerceAtLeast(1f)
            val mask = BlurMaskFilter(safeBlur, BlurMaskFilter.Blur.NORMAL)
            lightPaint.asFrameworkPaint().maskFilter = mask
            darkPaint.asFrameworkPaint().maskFilter = mask
            lastBlurPx = blurPx
        }
        if (lArgb != lastLightArgb) {
            lightPaint.asFrameworkPaint().color = lArgb
            lastLightArgb = lArgb
        }
        if (dArgb != lastDarkArgb) {
            darkPaint.asFrameworkPaint().color = dArgb
            lastDarkArgb = dArgb
        }
    }
}

@Composable
fun NeuSurface(
    modifier: Modifier = Modifier,
    depth: Int = 1,
    shape: Shape = RoundedCornerShape(24.dp),
    pressed: Boolean = false,
    useCachedShadowForList: Boolean = false,
    accentFill: Boolean = false,
    cornerRadiusDp: Dp = 24.dp,
    onClick: (() -> Unit)? = null,
    contentAlignment: Alignment = Alignment.TopStart,
    content: @Composable BoxScope.() -> Unit
) {
    val spec = SkyTheme.spec
    val view = LocalView.current
    val density = LocalDensity.current
    val interactionSource = remember { MutableInteractionSource() }
    val isPressedInteraction by interactionSource.collectIsPressedAsState()
    val effectivePressed = pressed || isPressedInteraction
    val effectiveDepth = if (effectivePressed && depth > 0) -1 else depth.coerceIn(-1, 2)

    val scale by animateFloatAsState(
        targetValue = if (isPressedInteraction && onClick != null) 0.97f else 1.0f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioMediumBouncy,
            stiffness = Spring.StiffnessMedium
        ),
        label = "neuPressScale"
    )

    val cachedPaints = remember { CachedShadowPaints() }
    val cornerPx = with(density) { cornerRadiusDp.toPx() }
    val offsetDp = when (effectiveDepth) {
        2 -> if (useCachedShadowForList) 7.dp else 10.dp
        1 -> if (useCachedShadowForList) 5.dp else 8.dp
        else -> 4.dp
    }
    val blurDp = when (effectiveDepth) {
        2 -> if (useCachedShadowForList) 14.dp else 22.dp
        1 -> if (useCachedShadowForList) 10.dp else 16.dp
        else -> 8.dp
    }
    val offsetPx = with(density) { offsetDp.toPx() }
    val blurPx = with(density) { blurDp.toPx() }

    val baseFill = if (accentFill) spec.accentFill else spec.bg
    // Subtle +/- 4% diagonal gradient for 3D sculpted feel
    val topLeftTint = if (effectiveDepth == -1) {
        lerpColor(baseFill, spec.lo, if (spec.isDark) 0.35f else 0.25f)
    } else {
        lerpColor(baseFill, spec.hi, if (spec.isDark) 0.18f else 0.28f)
    }
    val bottomRightTint = if (effectiveDepth == -1) {
        lerpColor(baseFill, spec.hi, if (spec.isDark) 0.12f else 0.20f)
    } else {
        lerpColor(baseFill, spec.lo, if (spec.isDark) 0.22f else 0.16f)
    }

    val borderColor = when {
        accentFill -> spec.hi.copy(alpha = 0.32f)
        effectiveDepth == -1 -> spec.lo.copy(alpha = if (spec.isDark) 0.75f else 0.65f)
        effectiveDepth >= 1 -> spec.hi.copy(alpha = if (spec.isDark) 0.55f else 0.85f)
        else -> spec.hairline
    }

    val shadowModifier = if (effectiveDepth >= 1) {
        Modifier.drawBehind {
            val lightShadow = spec.hi.copy(alpha = if (spec.isDark) 0.48f else 0.88f)
            val darkShadow = spec.lo.copy(alpha = if (spec.isDark) 0.85f else 0.72f)
            cachedPaints.updateIfNeeded(blurPx, lightShadow, darkShadow)

            drawIntoCanvas { canvas ->
                // Top-left light highlight shadow
                canvas.drawRoundRect(
                    left = -offsetPx * 0.65f,
                    top = -offsetPx * 0.65f,
                    right = size.width - offsetPx * 0.35f,
                    bottom = size.height - offsetPx * 0.35f,
                    radiusX = cornerPx,
                    radiusY = cornerPx,
                    paint = cachedPaints.lightPaint
                )
                // Bottom-right dark drop shadow
                canvas.drawRoundRect(
                    left = offsetPx * 0.45f,
                    top = offsetPx * 0.45f,
                    right = size.width + offsetPx * 0.75f,
                    bottom = size.height + offsetPx * 0.75f,
                    radiusX = cornerPx,
                    radiusY = cornerPx,
                    paint = cachedPaints.darkPaint
                )
            }
        }
    } else {
        Modifier
    }

    val insetOverlayModifier = if (effectiveDepth == -1) {
        Modifier.drawWithContent {
            drawContent()
            val strokeW = 3.dp.toPx()
            // Top-left inner shadow
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        spec.lo.copy(alpha = if (spec.isDark) 0.9f else 0.75f),
                        Color.Transparent
                    ),
                    start = Offset.Zero,
                    end = Offset(size.width * 0.45f, size.height * 0.45f)
                ),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                size = Size(size.width, size.height),
                style = Stroke(width = strokeW)
            )
            // Bottom-right inner highlight
            drawRoundRect(
                brush = Brush.linearGradient(
                    colors = listOf(
                        Color.Transparent,
                        spec.hi.copy(alpha = if (spec.isDark) 0.35f else 0.75f)
                    ),
                    start = Offset(size.width * 0.55f, size.height * 0.55f),
                    end = Offset(size.width, size.height)
                ),
                cornerRadius = CornerRadius(cornerPx, cornerPx),
                size = Size(size.width, size.height),
                style = Stroke(width = 1.5.dp.toPx())
            )
        }
    } else {
        Modifier
    }

    val clickModifier = if (onClick != null) {
        Modifier
            .defaultMinSize(minWidth = 48.dp, minHeight = 48.dp)
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                role = Role.Button
            ) {
                view.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK)
                onClick()
            }
    } else {
        Modifier
    }

    Box(
        modifier = modifier
            .scale(scale)
            .then(shadowModifier)
            .clip(shape)
            .background(
                brush = Brush.linearGradient(
                    colors = listOf(topLeftTint, baseFill, bottomRightTint),
                    start = Offset.Zero,
                    end = Offset(800f, 800f)
                ),
                shape = shape
            )
            .border(BorderStroke(1.dp, borderColor), shape)
            .then(insetOverlayModifier)
            .then(clickModifier),
        contentAlignment = contentAlignment,
        content = content
    )
}

private fun lerpColor(start: Color, stop: Color, fraction: Float): Color {
    val f = fraction.coerceIn(0f, 1f)
    return Color(
        red = start.red + (stop.red - start.red) * f,
        green = start.green + (stop.green - start.green) * f,
        blue = start.blue + (stop.blue - start.blue) * f,
        alpha = start.alpha + (stop.alpha - start.alpha) * f
    )
}
