package com.march7th.hkrpg.presentation.view.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.pm.ActivityInfo
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.DrawScope
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

/** 图上取到的一个标记，坐标都是 0~1 的比例。 */
private sealed interface Pick {
    data class Point(val x: Float, val y: Float) : Pick
    data class Swipe(val fx: Float, val fy: Float, val tx: Float, val ty: Float) : Pick
    data class Region(val x: Float, val y: Float, val w: Float, val h: Float) : Pick
}

private enum class PickerMode { TAP, SWIPE, REGION }

private fun f(v: Float): String = String.format(Locale.US, "%.4f", v)

/** 面板上显示的一行。 */
private fun Pick.describe(): String = when (this) {
    is Pick.Point -> "点  x=${f(x)}  y=${f(y)}"
    is Pick.Swipe -> "滑动  (${f(fx)}, ${f(fy)}) → (${f(tx)}, ${f(ty)})"
    is Pick.Region -> "区域  [${f(x)}, ${f(y)}, ${f(w)}, ${f(h)}]"
}

/**
 * 把取到的标记拼成任务包步骤。
 *
 * 点、滑动是步骤（`op`），框选不是 —— 它只出现在 `region` 参数里，
 * 所以单独列在注释里，避免生成一段跑不通的 JSON。
 */
private fun buildStepCode(picks: List<Pick>): String {
    val steps = picks.mapNotNull { p ->
        when (p) {
            is Pick.Point -> """  { "op": "tap", "x": ${f(p.x)}, "y": ${f(p.y)} }"""
            is Pick.Swipe -> """  { "op": "swipe", "fromX": ${f(p.fx)}, "fromY": ${f(p.fy)}, """ +
                """"toX": ${f(p.tx)}, "toY": ${f(p.ty)}, "durationMs": 320 }"""
            is Pick.Region -> null
        }
    }
    val regions = picks.filterIsInstance<Pick.Region>()

    return buildString {
        if (steps.isEmpty()) {
            append("// 没有可取代码的点或滑动\n")
        } else {
            append("[\n")
            append(steps.joinToString(",\n"))
            append("\n]\n")
        }
        if (regions.isNotEmpty()) {
            append("\n// 框选区域，填到 region 参数：\n")
            regions.forEach { append("// [${f(it.x)}, ${f(it.y)}, ${f(it.w)}, ${f(it.h)}]\n") }
        }
    }
}

/**
 * 取坐标：在游戏截图上点、拖线或框选，得到任务包能用的 0~1 坐标。
 *
 * 布局是「左面板 + 中间画面 + 右侧坐标」，画面尺寸只由可用空间决定，
 * 不会因为坐标列表变长而缩小。
 */
@Composable
fun CoordinatePickerDialog(
    bitmap: Bitmap,
    onDismiss: () -> Unit,
) {
    val clipboard = LocalClipboardManager.current
    val context = LocalContext.current
    val picks = remember { mutableStateListOf<Pick>() }
    var mode by remember { mutableStateOf(PickerMode.TAP) }
    var showCode by remember { mutableStateOf(false) }

    // 拖动过程中的实时预览
    var dragStart by remember { mutableStateOf<Offset?>(null) }
    var dragEnd by remember { mutableStateOf<Offset?>(null) }

    // 关闭时恢复原方向
    val activity = context.findActivity()
    DisposableEffect(activity) {
        val original = activity?.requestedOrientation
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        onDispose {
            activity?.requestedOrientation =
                original ?: ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = stringResource(R.string.settings_coord_pick_title),
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                    )
                    TextButton(onClick = onDismiss) {
                        Text(stringResource(R.string.common_close))
                    }
                }

                Row(modifier = Modifier.weight(1f).fillMaxWidth()) {
                    // ---- 左：操作面板 ----
                    Column(
                        modifier = Modifier
                            .width(132.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(10.dp),
                        verticalArrangement = Arrangement.spacedBy(6.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_coord_pick_title),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        FilterChip(
                            selected = mode == PickerMode.TAP,
                            onClick = { mode = PickerMode.TAP; dragStart = null; dragEnd = null },
                            label = { Text(stringResource(R.string.coord_pick_mode_tap)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilterChip(
                            selected = mode == PickerMode.SWIPE,
                            onClick = { mode = PickerMode.SWIPE; dragStart = null; dragEnd = null },
                            label = { Text(stringResource(R.string.coord_pick_mode_swipe)) },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        FilterChip(
                            selected = mode == PickerMode.REGION,
                            onClick = { mode = PickerMode.REGION; dragStart = null; dragEnd = null },
                            label = { Text(stringResource(R.string.coord_pick_mode_region)) },
                            modifier = Modifier.fillMaxWidth(),
                        )

                        Spacer(Modifier.height(4.dp))
                        HorizontalDivider()

                        Text(
                            text = when (mode) {
                                PickerMode.TAP -> stringResource(R.string.settings_coord_pick_hint)
                                PickerMode.SWIPE -> stringResource(R.string.coord_pick_hint_swipe)
                                PickerMode.REGION -> stringResource(R.string.coord_pick_hint_region)
                            },
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )

                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = { showCode = true },
                            enabled = picks.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.coord_pick_gen_code),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                        OutlinedButton(
                            onClick = { picks.clear() },
                            enabled = picks.isNotEmpty(),
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                text = stringResource(R.string.settings_coord_pick_clear),
                                style = MaterialTheme.typography.labelMedium,
                            )
                        }
                    }

                    // ---- 中：画面。比例固定，两侧黑边 ----
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(Color.Black),
                        contentAlignment = Alignment.Center,
                    ) {
                        BoxWithConstraints(modifier = Modifier.fillMaxSize()) {
                            val aspect = bitmap.width.toFloat() / bitmap.height.toFloat()
                            val byHeight = maxWidth / maxHeight > aspect
                            val frameWidth = if (byHeight) maxHeight * aspect else maxWidth
                            val frameHeight = if (byHeight) maxHeight else maxWidth / aspect

                            val gesture = if (mode == PickerMode.TAP) {
                                Modifier.pointerInput(mode) {
                                    detectTapGestures { offset ->
                                        picks.add(
                                            Pick.Point(
                                                (offset.x / size.width).coerceIn(0f, 1f),
                                                (offset.y / size.height).coerceIn(0f, 1f),
                                            )
                                        )
                                    }
                                }
                            } else {
                                Modifier.pointerInput(mode) {
                                    detectDragGestures(
                                        onDragStart = { dragStart = it; dragEnd = it },
                                        onDrag = { change, _ -> dragEnd = change.position },
                                        onDragCancel = { dragStart = null; dragEnd = null },
                                        onDragEnd = {
                                            val s = dragStart
                                            val e = dragEnd
                                            dragStart = null
                                            dragEnd = null
                                            // 拖动太短当误触
                                            if (s != null && e != null &&
                                                (e - s).getDistance() >= 24f
                                            ) {
                                                val w = size.width.toFloat()
                                                val h = size.height.toFloat()
                                                val x1 = (s.x / w).coerceIn(0f, 1f)
                                                val y1 = (s.y / h).coerceIn(0f, 1f)
                                                val x2 = (e.x / w).coerceIn(0f, 1f)
                                                val y2 = (e.y / h).coerceIn(0f, 1f)
                                                picks.add(
                                                    if (mode == PickerMode.SWIPE) {
                                                        Pick.Swipe(x1, y1, x2, y2)
                                                    } else {
                                                        Pick.Region(
                                                            minOf(x1, x2), minOf(y1, y2),
                                                            kotlin.math.abs(x2 - x1),
                                                            kotlin.math.abs(y2 - y1),
                                                        )
                                                    }
                                                )
                                            }
                                        },
                                    )
                                }
                            }

                            Box(
                                modifier = Modifier
                                    .align(Alignment.Center)
                                    .width(frameWidth)
                                    .height(frameHeight)
                                    .then(gesture),
                            ) {
                                Image(
                                    bitmap = bitmap.asImageBitmap(),
                                    contentDescription = null,
                                    contentScale = ContentScale.FillBounds,
                                    modifier = Modifier.fillMaxSize(),
                                )
                                Canvas(modifier = Modifier.fillMaxSize()) {
                                    picks.forEach { drawPick(it) }
                                    val s = dragStart
                                    val e = dragEnd
                                    if (s != null && e != null) {
                                        val w = size.width
                                        val h = size.height
                                        drawPick(
                                            if (mode == PickerMode.SWIPE) {
                                                Pick.Swipe(s.x / w, s.y / h, e.x / w, e.y / h)
                                            } else {
                                                Pick.Region(
                                                    minOf(s.x, e.x) / w, minOf(s.y, e.y) / h,
                                                    kotlin.math.abs(e.x - s.x) / w,
                                                    kotlin.math.abs(e.y - s.y) / h,
                                                )
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }

                    // ---- 右：坐标信息 ----
                    Column(
                        modifier = Modifier
                            .width(210.dp)
                            .fillMaxHeight()
                            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
                            .padding(10.dp),
                    ) {
                        Text(
                            text = stringResource(R.string.settings_coord_pick_count, picks.size),
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Spacer(Modifier.height(6.dp))

                        if (picks.isEmpty()) {
                            Text(
                                text = stringResource(R.string.settings_coord_pick_empty),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        } else {
                            Column(
                                modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                picks.reversed().forEachIndexed { i, p ->
                                    Text(
                                        text = "${picks.size - i}. ${p.describe()}",
                                        style = MaterialTheme.typography.labelSmall,
                                        fontFamily = FontFamily.Monospace,
                                    )
                                }
                            }
                            TextButton(
                                onClick = {
                                    clipboard.setText(
                                        AnnotatedString(picks.joinToString("\n") { it.describe() })
                                    )
                                    Toast.makeText(
                                        context,
                                        context.getString(R.string.settings_coord_pick_copied),
                                        Toast.LENGTH_SHORT,
                                    ).show()
                                },
                                modifier = Modifier.fillMaxWidth(),
                            ) {
                                Text(stringResource(R.string.settings_coord_pick_copy))
                            }
                        }
                    }
                }
            }
        }
    }

    if (showCode) {
        val code = buildStepCode(picks)
        AlertDialog(
            onDismissRequest = { showCode = false },
            title = { Text(stringResource(R.string.coord_pick_gen_code)) },
            text = {
                Text(
                    text = code,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.verticalScroll(rememberScrollState()),
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(code))
                    Toast.makeText(
                        context,
                        context.getString(R.string.settings_coord_pick_copied),
                        Toast.LENGTH_SHORT,
                    ).show()
                    showCode = false
                }) {
                    Text(stringResource(R.string.settings_coord_pick_copy))
                }
            },
            dismissButton = {
                TextButton(onClick = { showCode = false }) {
                    Text(stringResource(R.string.common_close))
                }
            },
        )
    }
}

/** 按画布尺寸把比例还原成像素再画。 */
private fun DrawScope.drawPick(pick: Pick) {
    val stroke = Stroke(width = 2.dp.toPx())
    when (pick) {
        is Pick.Point -> {
            val c = Offset(pick.x * size.width, pick.y * size.height)
            drawCircle(Color.Red, radius = 11.dp.toPx(), center = c, style = stroke)
            drawCircle(Color.White, radius = 3.dp.toPx(), center = c)
        }

        is Pick.Swipe -> {
            val a = Offset(pick.fx * size.width, pick.fy * size.height)
            val b = Offset(pick.tx * size.width, pick.ty * size.height)
            drawLine(Color.Red, a, b, strokeWidth = 2.dp.toPx())
            drawCircle(Color.Red, radius = 7.dp.toPx(), center = a, style = stroke)
            drawCircle(Color.Red, radius = 7.dp.toPx(), center = b, style = stroke)
        }

        is Pick.Region -> drawRect(
            color = Color.Red,
            topLeft = Offset(pick.x * size.width, pick.y * size.height),
            size = Size(pick.w * size.width, pick.h * size.height),
            style = stroke,
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}
