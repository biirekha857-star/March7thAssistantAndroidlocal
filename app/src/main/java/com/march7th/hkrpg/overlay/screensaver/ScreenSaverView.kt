package com.march7th.hkrpg.overlay.screensaver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.march7th.hkrpg.R
import com.march7th.hkrpg.domain.service.CoreSessionLogger
import com.march7th.hkrpg.theme.ScreenSaverDimens
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.time.LocalTime
import java.time.format.DateTimeFormatter

@Composable
fun ScreenSaverView(
    sessionLogger: CoreSessionLogger,
    onUnlock: () -> Unit
) {
    val logs by sessionLogger.logs.collectAsStateWithLifecycle()
    val latestLog = logs.lastOrNull()?.content ?: stringResource(R.string.screensaver_waiting_task)

    val batteryState = rememberBatteryState()
    var currentTime by remember { mutableStateOf(LocalTime.now()) }
    val timeFormatter = remember { DateTimeFormatter.ofPattern("HH:mm") }

    val configuration = LocalConfiguration.current
    val density = LocalDensity.current
    // 防烧屏漂移范围以 px 存储，与 offset { IntOffset } 单位一致
    val maxOffsetXPx = with(density) { (configuration.screenWidthDp / 8).dp.roundToPx() }
    val maxOffsetYPx = with(density) { (configuration.screenHeightDp / 4).dp.roundToPx() }

    var burnInOffsetX by remember { mutableIntStateOf(0) }
    var burnInOffsetY by remember { mutableIntStateOf(0) }

    // 解锁条独立的防烧屏漂移：底部锚定 水平受两侧内边距限制保持小幅
    // 垂直方向向上空间充足、向下受 56dp 底部内边距限制，故上大下小、不对称
    val barDriftXPx = with(density) { 24.dp.roundToPx() }
    val barDriftUpPx = with(density) { 80.dp.roundToPx() }
    val barDriftDownPx = with(density) { 40.dp.roundToPx() }
    var barOffsetX by remember { mutableIntStateOf(0) }
    var barOffsetY by remember { mutableIntStateOf(0) }

    LaunchedEffect(Unit) {
        while (true) {
            currentTime = LocalTime.now()
            delay(30_000L)
            burnInOffsetX = if (maxOffsetXPx > 0) (-maxOffsetXPx..maxOffsetXPx).random() else 0
            burnInOffsetY = if (maxOffsetYPx > 0) (-maxOffsetYPx..maxOffsetYPx).random() else 0
            barOffsetX = (-barDriftXPx..barDriftXPx).random()
            barOffsetY = (-barDriftUpPx..barDriftDownPx).random() // 负=上(空间足) 正=下(限于底部内边距)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        Column(
            modifier = Modifier
                .align(Alignment.Center)
                .offset { IntOffset(burnInOffsetX, burnInOffsetY) },
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text(
                text = currentTime.format(timeFormatter),
                style = MaterialTheme.typography.displayLarge.copy(
                    fontSize = ScreenSaverDimens.ClockFontSize,
                    fontWeight = FontWeight.Light
                ),
                color = Color.White.copy(alpha = 0.5f),
                maxLines = 1
            )

            Text(
                text = "${if (batteryState.isCharging) "⚡" else "🔋"} ${batteryState.level}%",
                style = MaterialTheme.typography.titleMedium,
                color = Color.White.copy(alpha = 0.4f)
            )

            Text(
                text = latestLog,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(horizontal = 32.dp)
            )
        }

        SlideToUnlockBar(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .offset { IntOffset(barOffsetX, barOffsetY) }
                .padding(bottom = 56.dp)
                .padding(horizontal = 32.dp)
                .fillMaxWidth(),
            onUnlock = onUnlock
        )
    }
}

@Composable
private fun SlideToUnlockBar(
    modifier: Modifier = Modifier,
    onUnlock: () -> Unit
) {
    val density = LocalDensity.current
    val thumbDiameter = 48.dp
    val trackHeight = 60.dp
    val trackPadding = 6.dp

    val thumbDiameterPx = with(density) { thumbDiameter.toPx() }
    val trackPaddingPx = with(density) { trackPadding.toPx() }

    var trackWidthPx by remember { mutableFloatStateOf(0f) }
    val maxOffset = (trackWidthPx - thumbDiameterPx - trackPaddingPx * 2).coerceAtLeast(0f)

    var dragOffset by remember { mutableFloatStateOf(0f) }
    val springAnim = remember { Animatable(0f) }
    var isAnimating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    val displayOffset = if (isAnimating) springAnim.value else dragOffset
    val progress = if (maxOffset > 0f) (displayOffset / maxOffset).coerceIn(0f, 1f) else 0f

    val shimmerTransition = rememberInfiniteTransition(label = "shimmer")
    val shimmerPos by shimmerTransition.animateFloat(
        initialValue = -0.5f,
        targetValue = 1.5f,
        animationSpec = infiniteRepeatable(
            animation = tween(2400, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "shimmerPos"
    )
    val shimmerAlpha = (1f - progress) * 0.28f

    Box(
        modifier = modifier
            .height(trackHeight)
            .onSizeChanged { trackWidthPx = it.width.toFloat() }
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.13f))
            .drawBehind {
                val sweepWidth = size.width * 0.42f
                drawRect(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.White.copy(alpha = shimmerAlpha),
                            Color.Transparent
                        ),
                        startX = shimmerPos * size.width - sweepWidth,
                        endX = shimmerPos * size.width + sweepWidth
                    )
                )
            }
            .draggable(
                orientation = Orientation.Horizontal,
                state = rememberDraggableState { delta ->
                    if (!isAnimating && maxOffset > 0f) {
                        dragOffset = (dragOffset + delta).coerceIn(0f, maxOffset)
                    }
                },
                onDragStopped = { velocity ->
                    if (!isAnimating && maxOffset > 0f) {
                        // 位移超过 75% 或快速轻扫（>1000 px/s）均触发解锁
                        if (dragOffset >= maxOffset * 0.75f || velocity > 1000f) {
                            scope.launch {
                                isAnimating = true
                                springAnim.snapTo(dragOffset)
                                springAnim.animateTo(maxOffset, tween(120))
                                // 先重置状态，再调用 onUnlock——后者会销毁 Composition
                                springAnim.snapTo(0f)
                                dragOffset = 0f
                                isAnimating = false
                                onUnlock()
                            }
                        } else {
                            scope.launch {
                                isAnimating = true
                                springAnim.snapTo(dragOffset)
                                dragOffset = 0f
                                springAnim.animateTo(
                                    0f,
                                    spring(
                                        dampingRatio = Spring.DampingRatioMediumBouncy,
                                        stiffness = Spring.StiffnessMedium
                                    )
                                )
                                isAnimating = false
                            }
                        }
                    }
                }
            )
    ) {
        Text(
            text = stringResource(R.string.screensaver_swipe_to_unlock),
            style = MaterialTheme.typography.bodyLarge.copy(letterSpacing = 3.sp),
            color = Color.White.copy(alpha = 0.6f),
            modifier = Modifier
                .align(Alignment.Center)
                .alpha((1f - progress * 2f).coerceIn(0f, 1f))
        )

        Box(
            modifier = Modifier
                .padding(trackPadding)
                .size(thumbDiameter)
                .offset { IntOffset(displayOffset.toInt(), 0) }
                .clip(CircleShape)
                .background(Color.White.copy(alpha = 0.50f)),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = Color(0xFF1A1A1A),
                modifier = Modifier.size(22.dp)
            )
        }
    }
}

data class BatteryState(val level: Int = 100, val isCharging: Boolean = false)

@Composable
fun rememberBatteryState(): BatteryState {
    val context = LocalContext.current
    var state by remember { mutableStateOf(BatteryState()) }

    DisposableEffect(context) {
        // 先读 sticky broadcast 获取初始值，再注册 receiver 监听后续变化，避免覆盖竞态
        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { intent ->
            val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
            val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
            val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                    status == BatteryManager.BATTERY_STATUS_FULL
            if (scale > 0) state = BatteryState(level * 100 / scale, isCharging)
        }

        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context?, intent: Intent?) {
                if (intent?.action == Intent.ACTION_BATTERY_CHANGED) {
                    val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
                    val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
                    val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
                    val isCharging = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                            status == BatteryManager.BATTERY_STATUS_FULL
                    if (scale > 0) state = BatteryState(level * 100 / scale, isCharging)
                }
            }
        }
        context.registerReceiver(receiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))

        onDispose {
            context.unregisterReceiver(receiver)
        }
    }
    return state
}
