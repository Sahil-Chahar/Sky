package com.example.ui.components

import android.graphics.BlurMaskFilter
import android.os.Build
import android.provider.Settings
import android.view.RoundedCorner
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.PaintingStyle
import androidx.compose.ui.graphics.SweepGradientShader
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import com.example.ui.theme.SkyThemePalettes
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

enum class SiriWakeStyle(val id: String, val displayName: String, val subtitle: String) {
    SIRI_GLOW(
        "siri_glow",
        "Siri Glow (Default)",
        "iOS 18–26 perimeter light following display corners & cutout"
    ),
    SIRI_ORB(
        "siri_orb",
        "Siri Orb",
        "iOS 27 glowing dynamic pill expanding from the top camera cutout"
    )
}

enum class AssistantOverlayPhase {
    IDLE,
    LISTENING,
    THINKING,
    EXECUTING_TASK,
    COUNTDOWN_TIER2,
    STREAMING_REPLY
}

/**
 * Hardware-accelerated Siri Wake Animation supporting both:
 * 1. "Siri Glow" (Default; iOS 18–26 full-screen perimeter light with 4 stacked strokes:
 *    4/6/8/11 dp widths, 0/3/10/18 dp blurs, additive blend, real WindowInsets.getRoundedCorner
 *    on API 31+ and 28.dp fallback on API 29–30, randomized color-stop drift every ~600ms).
 * 2. "Siri Orb" (iOS 27 top-center morphing pill/orb around the status bar / cutout).
 *
 * Pre-allocates Paint & FloatArray buffers so zero objects are allocated per frame (60–120 fps).
 */
private class PreAllocatedGlowResources(
    palette: List<Color>,
    strokeWidthsPx: FloatArray,
    blurRadiiPx: FloatArray
) {
    val colors: List<Color> = palette + palette.first()
    val stopPositions: Array<FloatArray> = Array(4) { layer ->
        FloatArray(colors.size) { idx ->
            (idx.toFloat() / (colors.size - 1).toFloat()).coerceIn(0f, 1f)
        }
    }
    val targetPositions: Array<FloatArray> = Array(4) { layer ->
        FloatArray(colors.size) { idx ->
            (idx.toFloat() / (colors.size - 1).toFloat()).coerceIn(0f, 1f)
        }
    }
    val paints: Array<Paint> = Array(4) { i ->
        Paint().apply {
            isAntiAlias = true
            style = PaintingStyle.Stroke
            strokeWidth = strokeWidthsPx[i]
            blendMode = BlendMode.Plus
            if (blurRadiiPx[i] > 0.5f) {
                asFrameworkPaint().maskFilter =
                    BlurMaskFilter(blurRadiiPx[i], BlurMaskFilter.Blur.NORMAL)
            }
        }
    }

    fun randomizeTargets(rng: Random) {
        for (layer in 0 until 4) {
            val n = colors.size
            targetPositions[layer][0] = 0f
            targetPositions[layer][n - 1] = 1f
            for (i in 1 until n - 1) {
                val base = i.toFloat() / (n - 1).toFloat()
                val jitter = (rng.nextFloat() - 0.5f) * 0.11f
                targetPositions[layer][i] = (base + jitter).coerceIn(0.05f, 0.95f)
            }
            targetPositions[layer].sort()
            targetPositions[layer][0] = 0f
            targetPositions[layer][n - 1] = 1f
        }
    }

    fun stepInterpolation(factor: Float) {
        for (layer in 0 until 4) {
            for (i in stopPositions[layer].indices) {
                val cur = stopPositions[layer][i]
                val tgt = targetPositions[layer][i]
                stopPositions[layer][i] = cur + (tgt - cur) * factor
            }
        }
    }
}

@Composable
fun SiriWakeOverlay(
    style: SiriWakeStyle,
    phase: AssistantOverlayPhase,
    micRms: Float,
    ttsPulse: Float,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val view = LocalView.current
    val density = LocalDensity.current

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

    // Read real hardware corner radius on API 31+, fallback to 28.dp on API 29-30
    val fallbackCornerPx = with(density) { 28.dp.toPx() }
    val hardwareCornerPx = remember(view, fallbackCornerPx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            val insets = view.rootWindowInsets
            val topLeft = insets?.getRoundedCorner(RoundedCorner.POSITION_TOP_LEFT)?.radius
            if (topLeft != null && topLeft > 0) topLeft.toFloat() else fallbackCornerPx
        } else {
            fallbackCornerPx
        }
    }

    // 4 stacked strokes (~4 / 6 / 8 / 11 dp wide, blur ~0 / 3 / 10 / 18 dp)
    val strokeWidthsPx = remember(density) {
        with(density) {
            floatArrayOf(4.dp.toPx(), 6.dp.toPx(), 8.dp.toPx(), 11.dp.toPx())
        }
    }
    val blurRadiiPx = remember(density) {
        with(density) {
            floatArrayOf(0f, 3.dp.toPx(), 10.dp.toPx(), 18.dp.toPx())
        }
    }

    val resources = remember(strokeWidthsPx, blurRadiiPx) {
        PreAllocatedGlowResources(
            palette = SkyThemePalettes.SiriGlowPalette,
            strokeWidthsPx = strokeWidthsPx,
            blurRadiiPx = blurRadiiPx
        )
    }

    // Entry expansion spring (~0.5s)
    val entryExpansion = remember { Animatable(0f) }
    LaunchedEffect(phase != AssistantOverlayPhase.IDLE) {
        if (phase != AssistantOverlayPhase.IDLE) {
            entryExpansion.animateTo(
                targetValue = 1f,
                animationSpec = spring(
                    dampingRatio = Spring.DampingRatioLowBouncy,
                    stiffness = Spring.StiffnessMediumLow
                )
            )
        } else {
            entryExpansion.animateTo(0f, tween(300))
        }
    }

    // Periodic re-randomization of color-stop targets every ~650ms
    val rng = remember { Random(42) }
    LaunchedEffect(phase, reducedMotion) {
        if (!reducedMotion) {
            while (phase != AssistantOverlayPhase.IDLE) {
                resources.randomizeTargets(rng)
                kotlinx.coroutines.delay(if (phase == AssistantOverlayPhase.THINKING) 420L else 680L)
            }
        }
    }

    val smoothedRms by animateFloatAsState(
        targetValue = when (phase) {
            AssistantOverlayPhase.LISTENING -> (micRms * 2.4f).coerceIn(0.08f, 1.0f)
            AssistantOverlayPhase.THINKING -> 0.65f
            AssistantOverlayPhase.STREAMING_REPLY -> (0.25f + ttsPulse * 0.65f).coerceIn(0.2f, 1f)
            AssistantOverlayPhase.COUNTDOWN_TIER2 -> 0.75f
            else -> 0.25f
        },
        animationSpec = spring(stiffness = Spring.StiffnessMedium),
        label = "smoothedRms"
    )

    val infiniteTransition = rememberInfiniteTransition(label = "siriWakeFlow")
    val phaseAngle by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(
            animation = tween(
                durationMillis = if (phase == AssistantOverlayPhase.THINKING) 1800 else 3200,
                easing = LinearEasing
            ),
            repeatMode = RepeatMode.Restart
        ),
        label = "phaseAngle"
    )

    if (style == SiriWakeStyle.SIRI_GLOW) {
        Canvas(
            modifier = modifier
                .fillMaxSize()
                .graphicsLayer {
                    compositingStrategy = CompositingStrategy.Offscreen
                    alpha = entryExpansion.value.coerceIn(0f, 1f)
                }
        ) {
            if (!reducedMotion) {
                resources.stepInterpolation(0.08f)
            }
            val w = size.width
            val h = size.height
            val cx = w * 0.5f
            val cy = h * 0.5f
            val gain = 1f + smoothedRms * 0.85f

            drawIntoCanvas { canvas ->
                for (layer in 0 until 4) {
                    val paint = resources.paints[layer]
                    val dynamicStroke = strokeWidthsPx[layer] * gain * entryExpansion.value
                    paint.strokeWidth = dynamicStroke

                    val rad = Math.toRadians((phaseAngle * (if (layer % 2 == 0) 1f else -0.8f) + layer * 67f).toDouble())
                    val centerOffset = Offset(
                        x = cx + (cos(rad) * w * 0.14f).toFloat(),
                        y = cy + (sin(rad) * h * 0.14f).toFloat()
                    )

                    paint.shader = SweepGradientShader(
                        center = centerOffset,
                        colors = resources.colors,
                        colorStops = resources.stopPositions[layer].toList()
                    )

                    val inset = dynamicStroke * 0.35f
                    val corner = (hardwareCornerPx - inset).coerceAtLeast(16f)
                    canvas.drawRoundRect(
                        left = inset,
                        top = inset,
                        right = w - inset,
                        bottom = h - inset,
                        radiusX = corner,
                        radiusY = corner,
                        paint = paint
                    )
                }
            }
        }
    } else {
        // Siri Orb (iOS 27 look): glowing pill/orb swelling from top-center camera cutout
        Box(
            modifier = modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            contentAlignment = Alignment.TopCenter
        ) {
            Canvas(
                modifier = Modifier
                    .fillMaxWidth(0.88f)
                    .height(110.dp)
                    .graphicsLayer {
                        compositingStrategy = CompositingStrategy.Offscreen
                        alpha = entryExpansion.value.coerceIn(0f, 1f)
                    }
            ) {
                if (!reducedMotion) {
                    resources.stepInterpolation(0.08f)
                }
                val pillWidth = size.width * (0.58f + smoothedRms * 0.28f) * entryExpansion.value
                val pillHeight = (54.dp.toPx() + smoothedRms * 22.dp.toPx()) * entryExpansion.value
                val left = (size.width - pillWidth) * 0.5f
                val top = 8.dp.toPx()
                val cx = size.width * 0.5f
                val cy = top + pillHeight * 0.5f

                drawIntoCanvas { canvas ->
                    for (layer in 0 until 4) {
                        val paint = resources.paints[layer]
                        paint.strokeWidth = strokeWidthsPx[layer] * (1.2f + smoothedRms * 0.6f)
                        val rad = Math.toRadians((phaseAngle + layer * 80f).toDouble())
                        paint.shader = SweepGradientShader(
                            center = Offset(
                                cx + (cos(rad) * 36f).toFloat(),
                                cy + (sin(rad) * 18f).toFloat()
                            ),
                            colors = resources.colors,
                            colorStops = resources.stopPositions[layer].toList()
                        )
                        canvas.drawRoundRect(
                            left = left,
                            top = top,
                            right = left + pillWidth,
                            bottom = top + pillHeight,
                            radiusX = pillHeight * 0.5f,
                            radiusY = pillHeight * 0.5f,
                            paint = paint
                        )
                    }
                }
            }
        }
    }
}
