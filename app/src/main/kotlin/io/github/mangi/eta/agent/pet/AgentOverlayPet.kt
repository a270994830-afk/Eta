package io.github.mangi.eta.agent.pet

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

/**
 * 桌面宠物：完全用 Canvas 画出来的小家伙，不依赖任何图片资源。
 *
 * 情绪来自 [AgentPetMood]：空闲会呼吸眨眼，思考冒泡泡，干活时绕圈，成功撒花，失败发抖变灰。
 * 点击与拖拽由上层 [EtaPetService] 处理（前者打开 Eta，后者保存位置）。
 */
@Composable
internal fun AgentOverlayPet(
    mood: AgentPetMood,
    speech: String?,
    onTap: () -> Unit,
    onDrag: (Float, Float) -> Unit,
    onDragEnd: () -> Unit = {},
    onLongPress: () -> Unit = {},
) {
    val transition = rememberInfiniteTransition(label = "pet")
    val breathe by transition.animateFloat(
        initialValue = 1f,
        targetValue = 1.045f,
        animationSpec = infiniteRepeatable(tween(1500), RepeatMode.Reverse),
        label = "breathe",
    )
    val blink by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(4600, easing = LinearEasing)),
        label = "blink",
    )
    val orbit by transition.animateFloat(
        initialValue = 0f,
        targetValue = 360f,
        animationSpec = infiniteRepeatable(tween(2600, easing = LinearEasing)),
        label = "orbit",
    )
    val bob by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(1800), RepeatMode.Reverse),
        label = "bob",
    )
    val eyesClosed = mood == AgentPetMood.SLEEP || blink > 0.94f

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        speech?.let { text ->
            Box(
                modifier = Modifier
                    .padding(bottom = 6.dp)
                    .widthIn(max = 190.dp)
                    .background(Color(0xF2FFFFFF), RoundedCornerShape(12.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text = text, color = Color(0xFF202124), fontSize = 12.sp)
            }
        }
        Box(
            modifier = Modifier
                .size(84.dp)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { onTap() },
                        onLongPress = { onLongPress() },
                    )
                }
                .pointerInput(Unit) {
                    detectDragGestures(
                        onDragEnd = { onDragEnd() },
                    ) { _, drag ->
                        onDrag(drag.x, drag.y)
                    }
                },
            contentAlignment = Alignment.Center,
        ) {
            androidx.compose.foundation.Canvas(modifier = Modifier.size(84.dp)) {
                val unit = size.minDimension
                val cx = size.width / 2f
                val cy = size.height / 2f + unit * 0.04f * bob
                val bodyR = unit * 0.30f * breathe
                val shake = if (mood == AgentPetMood.ERROR) (sin(orbit.toDouble() * 0.25).toFloat()) * unit * 0.03f else 0f
                val accent = moodColor(mood)
                val dark = accent.copy(alpha = 0.35f)

                // 触角
                val antennaTop = cy - bodyR - unit * 0.16f - unit * 0.02f * bob
                drawLine(
                    color = dark,
                    start = Offset(cx + shake, cy - bodyR * 0.9f),
                    end = Offset(cx + shake, antennaTop),
                    strokeWidth = unit * 0.018f,
                )
                drawCircle(
                    color = accent,
                    radius = unit * 0.035f,
                    center = Offset(cx + shake, antennaTop),
                )

                // 身体（带一点呼吸起伏）
                drawCircle(color = accent, radius = bodyR, center = Offset(cx + shake, cy))
                drawCircle(
                    color = Color.White.copy(alpha = 0.18f),
                    radius = bodyR * 0.62f,
                    center = Offset(cx + shake - bodyR * 0.22f, cy - bodyR * 0.26f),
                )

                // 眼睛
                val eyeDx = bodyR * 0.34f
                val eyeY = cy - bodyR * 0.12f
                if (eyesClosed) {
                    for (dir in intArrayOf(-1, 1)) {
                        drawArc(
                            color = Color(0xFF1F2328),
                            startAngle = 200f,
                            sweepAngle = 140f,
                            useCenter = false,
                            topLeft = Offset(cx + shake + dir * eyeDx - bodyR * 0.16f, eyeY - bodyR * 0.10f),
                            size = Size(bodyR * 0.32f, bodyR * 0.22f),
                            style = Stroke(width = unit * 0.02f),
                        )
                    }
                } else {
                    for (dir in intArrayOf(-1, 1)) {
                        drawCircle(
                            color = Color(0xFF1F2328),
                            radius = bodyR * 0.11f,
                            center = Offset(cx + shake + dir * eyeDx, eyeY),
                        )
                        drawCircle(
                            color = Color.White.copy(alpha = 0.9f),
                            radius = bodyR * 0.035f,
                            center = Offset(cx + shake + dir * eyeDx - bodyR * 0.03f, eyeY - bodyR * 0.04f),
                        )
                    }
                }

                // 嘴
                val mouthY = cy + bodyR * 0.30f
                when (mood) {
                    AgentPetMood.SUCCESS -> drawArc(
                        color = Color(0xFF1F2328),
                        startAngle = 20f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx + shake - bodyR * 0.22f, mouthY - bodyR * 0.18f),
                        size = Size(bodyR * 0.44f, bodyR * 0.34f),
                        style = Stroke(width = unit * 0.022f),
                    )
                    AgentPetMood.ERROR -> drawArc(
                        color = Color(0xFF1F2328),
                        startAngle = 200f,
                        sweepAngle = 140f,
                        useCenter = false,
                        topLeft = Offset(cx + shake - bodyR * 0.22f, mouthY - bodyR * 0.02f),
                        size = Size(bodyR * 0.44f, bodyR * 0.30f),
                        style = Stroke(width = unit * 0.022f),
                    )
                    AgentPetMood.THINKING -> drawCircle(
                        color = Color(0xFF1F2328),
                        radius = bodyR * 0.09f,
                        center = Offset(cx + shake, mouthY),
                    )
                    AgentPetMood.SLEEP -> drawLine(
                        color = Color(0xFF1F2328),
                        start = Offset(cx + shake - bodyR * 0.14f, mouthY),
                        end = Offset(cx + shake + bodyR * 0.14f, mouthY),
                        strokeWidth = unit * 0.022f,
                    )
                    else -> drawArc(
                        color = Color(0xFF1F2328),
                        startAngle = 20f,
                        sweepAngle = 120f,
                        useCenter = false,
                        topLeft = Offset(cx + shake - bodyR * 0.18f, mouthY - bodyR * 0.14f),
                        size = Size(bodyR * 0.36f, bodyR * 0.26f),
                        style = Stroke(width = unit * 0.02f),
                    )
                }

                // 情绪装饰
                when (mood) {
                    AgentPetMood.THINKING -> {
                        for (i in 0..2) {
                            val a = 0.25f + 0.55f * ((bob + i * 0.33f) % 1f)
                            drawCircle(
                                color = accent.copy(alpha = a),
                                radius = unit * 0.03f,
                                center = Offset(cx + bodyR * 1.15f, cy - bodyR - unit * 0.02f * (i + 1)),
                            )
                        }
                    }
                    AgentPetMood.WORKING -> {
                        for (i in 0..1) {
                            val rad = Math.toRadians((orbit + i * 180f).toDouble())
                            drawCircle(
                                color = accent.copy(alpha = 0.85f),
                                radius = unit * 0.035f,
                                center = Offset(
                                    cx + (bodyR * 1.35f * cos(rad)).toFloat(),
                                    cy + (bodyR * 1.35f * sin(rad)).toFloat(),
                                ),
                            )
                        }
                    }
                    AgentPetMood.SUCCESS -> {
                        for (i in 0..4) {
                            val rad = Math.toRadians((orbit * 1.4f + i * 72f).toDouble())
                            drawLine(
                                color = accent,
                                start = Offset(
                                    cx + (bodyR * 1.25f * cos(rad)).toFloat(),
                                    cy + (bodyR * 1.25f * sin(rad)).toFloat(),
                                ),
                                end = Offset(
                                    cx + (bodyR * 1.55f * cos(rad)).toFloat(),
                                    cy + (bodyR * 1.55f * sin(rad)).toFloat(),
                                ),
                                strokeWidth = unit * 0.02f,
                            )
                        }
                    }
                    AgentPetMood.SLEEP -> {
                        for (i in 0..1) {
                            drawCircle(
                                color = accent.copy(alpha = 0.75f - i * 0.25f),
                                radius = unit * 0.026f,
                                center = Offset(cx + bodyR + unit * 0.06f * (i + 1), cy - bodyR - unit * 0.06f * (i + 1)),
                            )
                        }
                    }
                    else -> Unit
                }
            }
        }
    }
}

private fun moodColor(mood: AgentPetMood): Color = when (mood) {
    AgentPetMood.IDLE -> Color(0xFF6C8EF5)
    AgentPetMood.SLEEP -> Color(0xFF8E9BAE)
    AgentPetMood.THINKING -> Color(0xFFB388F0)
    AgentPetMood.WORKING -> Color(0xFF4FC3F7)
    AgentPetMood.SUCCESS -> Color(0xFF4CD08A)
    AgentPetMood.ERROR -> Color(0xFFE4707C)
}
