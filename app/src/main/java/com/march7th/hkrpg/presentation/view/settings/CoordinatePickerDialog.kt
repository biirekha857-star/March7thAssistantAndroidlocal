package com.march7th.hkrpg.presentation.view.settings

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.march7th.hkrpg.R
import java.util.Locale

/**
 * 取坐标：在一张游戏截图上点，直接得到任务包能用的 0~1 坐标。
 *
 * ## 为什么要截图，而不是读用户的真实点击
 *
 * 游戏跑在虚拟屏（1920×1080）上 —— 任务包里的 `tap` 用的就是这套坐标。
 * 但用户手指点的是物理屏（这台机器 1264×2780），两者宽高比不同（16:9
 * vs 20:9），游戏会把 UI 摆在不同的相对位置，归一化也对不上。
 *
 * 截图是虚拟屏的原始帧，图上点哪儿、换算出来就是哪儿，中间不需要任何
 * 跨屏映射 —— 这才是"抄进任务包就能用"的坐标。
 *
 * ## 可以连着点多下
 *
 * 校准一个界面往往要取好几个按钮（领取、确认、关闭…），所以不点一下关一次：
 * 图上会留下标记，下面按顺序列出来，最后一次性复制。
 */
@Composable
fun CoordinatePickerDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    // 用比例存，和显示尺寸解耦：窗口旋转/改大小都不会让已取的点漂移
    val picks = remember { mutableStateListOf<Pair<Float, Float>>() }

    fun format(p: Pair<Float, Float>): String =
        String.format(Locale.US, "x=%.4f  y=%.4f", p.first, p.second)

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize().padding(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        text = stringResource(R.string.settings_coord_pick_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close))
                    }
                }
                Text(
                    text = stringResource(R.string.settings_coord_pick_hint),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))

                BoxWithConstraints(
                    modifier = Modifier.fillMaxWidth().weight(1f),
                    contentAlignment = Alignment.Center,
                ) {
                    val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                    // 等比适配：留黑边也不拉伸，否则点出来的坐标会有系统偏差
                    val fitsByHeight = maxWidth / maxHeight > aspect
                    val frameWidth = if (fitsByHeight) maxHeight * aspect else maxWidth
                    val frameHeight = if (fitsByHeight) maxHeight else maxWidth / aspect

                    Box(
                        modifier = Modifier
                            .width(frameWidth)
                            .height(frameHeight)
                            .pointerInput(bitmap) {
                                detectTapGestures { offset ->
                                    val x = (offset.x / size.width).coerceIn(0f, 1f)
                                    val y = (offset.y / size.height).coerceIn(0f, 1f)
                                    picks.add(x to y)
                                }
                            },
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = null,
                            contentScale = ContentScale.FillBounds,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            picks.forEach { p ->
                                val center = Offset(p.first * size.width, p.second * size.height)
                                drawCircle(
                                    color = Color.Red,
                                    radius = 11.dp.toPx(),
                                    center = center,
                                    style = Stroke(width = 2.dp.toPx()),
                                )
                                drawCircle(color = Color.White, radius = 3.dp.toPx(), center = center)
                            }
                        }
                    }
                }

                Spacer(Modifier.height(8.dp))

                if (picks.isEmpty()) {
                    Text(
                        text = stringResource(R.string.settings_coord_pick_empty),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                } else {
                    Text(
                        text = stringResource(R.string.settings_coord_pick_count, picks.size),
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                    )
                    Column(
                        modifier = Modifier
                            .heightIn(max = 120.dp)
                            .verticalScroll(rememberScrollState()),
                    ) {
                        picks.reversed().forEachIndexed { i, p ->
                            Text(
                                text = "${picks.size - i}.  ${format(p)}",
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace,
                            )
                        }
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = {
                            val text = picks.joinToString("\n") { format(it) }
                            clipboard.setText(AnnotatedString(text))
                            Toast.makeText(
                                context,
                                context.getString(R.string.settings_coord_pick_copied),
                                Toast.LENGTH_SHORT,
                            ).show()
                        }) {
                            Text(stringResource(R.string.settings_coord_pick_copy))
                        }
                        TextButton(onClick = { picks.clear() }) {
                            Text(stringResource(R.string.settings_coord_pick_clear))
                        }
                    }
                }
            }
        }
    }
}
