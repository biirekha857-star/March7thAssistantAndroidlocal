package com.march7th.hkrpg.remote.internal

import android.os.SystemClock
import com.march7th.hkrpg.third.Ln
import java.util.Locale
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * 坐标记录 —— 把用户的真实点击换算成归一化坐标写进日志。
 *
 * ## 为什么需要它
 *
 * 任务包里的 `tap` 要填 0~1 的比例坐标，但用户面对的是游戏画面，
 * 只能靠「拿截图去量」「猜一个数再试」来校准 —— 一个按钮试十几次很常见。
 *
 * 这个功能让人**用眼睛点一下**就行了：开着它回到游戏，想校准哪个按钮就点哪个，
 * 每次点击都会在日志里留一行 `x=0.5234 y=0.4410`，抄进任务包即可。
 *
 * ## 为什么能读到用户的点击
 *
 * 直读 `/dev/input/eventN` —— 和 [GestureRecorder] 录解锁手势走的是同一条路。
 * 触摸屏节点在 shell 身份下可读，所以不需要悬浮窗、不需要无障碍，
 * 也**不会挡住用户操作游戏**（悬浮窗方案会在屏幕上盖一层遮罩）。
 *
 * 顺带有个好处：引擎自己注入的点击（`injectInputEvent`）走的是另一条通道，
 * 不会出现在 evdev 流里 —— 所以这里记到的**只有真实手指**，
 * 不会把自动任务的点击混进来。
 *
 * ## 边界
 *
 * - 只在提权进程（`:shizuku_service`）里跑，App 进程读不到 `/dev/input`
 * - 单次最长 [MAX_DURATION_MS]，到点自动停，免得用户忘了关一直耗电
 * - 最多记 [MAX_ENTRIES] 条，防止误触刷满日志
 */
internal object CoordinateTracer {

    /** 单次记录的最长时间，到点自动停。 */
    private const val MAX_DURATION_MS = 30 * 60 * 1000L

    /** 单次最多记多少条动作。 */
    private const val MAX_ENTRIES = 400

    /** 主循环多久收割一批轨迹。太短会空转，太长会让日志滞后于手感。 */
    private const val DRAIN_INTERVAL_MS = 120L

    /** 判定「点击」的最大位移（当前屏幕像素）。 */
    private const val TAP_MAX_MOVE_PX = 24

    /** 判定「点击」的最大按压时长。超过就按长按处理，单独标出来。 */
    private const val TAP_MAX_DURATION_MS = 600

    /** 长按的门槛。 */
    private const val LONG_PRESS_MS = 900

    private const val READER_JOIN_MS = 800L

    @Volatile
    private var worker: Thread? = null

    /** 当前会话的停止闸门；同时用作可打断的睡眠。 */
    @Volatile
    private var stopLatch: CountDownLatch? = null

    @Synchronized
    fun start() {
        if (worker?.isAlive == true) {
            Ln.i("坐标记录：已经在跑了，忽略这次重复开启")
            return
        }
        val latch = CountDownLatch(1)
        stopLatch = latch
        worker = Thread({ run(latch) }, "coord-trace").apply {
            isDaemon = true
            start()
        }
        Ln.i("坐标记录：已启动，正在探测触摸屏…")
    }

    fun stop() {
        val latch = stopLatch ?: return
        Ln.i("坐标记录：收到停止请求")
        latch.countDown()
    }

    fun isRunning(): Boolean = worker?.isAlive == true

    private fun run(latch: CountDownLatch) {
        try {
            record(latch)
        } catch (t: Throwable) {
            Ln.w("坐标记录：异常结束 —— ${t.message}")
        } finally {
            stopLatch = null
        }
    }

    private fun record(latch: CountDownLatch) {
        val device = InputDeviceProbe.findTouchDevice()
        if (device == null) {
            Ln.w("坐标记录：没找到可读的触摸屏，无法记录。换 Root 方式或换台机器再试")
            return
        }
        if (!device.usable) {
            Ln.w("坐标记录：${device.path} 的量程读不出来，放弃")
            return
        }

        val reader = runCatching { InputEventReader.open(device.path) }.getOrElse { t ->
            Ln.w("坐标记录：打不开 ${device.path}（${t.message}）")
            return
        }

        val screen = ScreenGeometry.current()
        val orientation = if (screen.rotation % 2 == 0) "竖屏" else "横屏"
        Ln.i(
            "坐标记录：触摸屏 ${device.path}，屏幕 ${screen.width}x${screen.height} $orientation。" +
                    "现在回游戏里点想校准的位置，每次点击都会记一行"
        )
        Ln.i("坐标记录：记下来的 x / y 是 0~1 的比例，直接填进任务包的 tap；导出的日志里搜 [tap] 就能找到")

        val parser = TouchStreamParser()
        val base = SystemClock.uptimeMillis()
        // 局部变量不能标 @Volatile，用原子量让读取线程能及时停手
        val feeding = AtomicBoolean(true)

        val readerThread = Thread({
            runCatching {
                reader.readLoop { type, code, value, atMs ->
                    if (feeding.get()) {
                        synchronized(parser) {
                            parser.onEvent(type, code, value, (atMs - base).toInt())
                        }
                    }
                }
            }.onFailure { Ln.i("坐标记录：读取线程结束 —— ${it.message}") }
        }, "coord-trace-reader").apply {
            isDaemon = true
            start()
        }

        var seq = 0
        val deadline = base + MAX_DURATION_MS
        var stopReason = "手动停止"

        try {
            drain@ while (true) {
                // 返回值 true 表示被要求停止；超时只是睡醒了
                if (latch.await(DRAIN_INTERVAL_MS, TimeUnit.MILLISECONDS)) {
                    stopReason = "手动停止"
                    break@drain
                }
                if (SystemClock.uptimeMillis() > deadline) {
                    stopReason = "到 30 分钟上限"
                    break@drain
                }

                // 先看有没有新动作，再采样屏幕：两者都是 binder 往返，
                // 空转时（用户还没点）没必要每 120ms 去问一次显示服务
                val fresh = synchronized(parser) { parser.drainStrokes() }
                if (fresh.isEmpty()) continue

                // 方向可能与启动时不同（游戏横竖屏切换），每批重新采样
                val current = ScreenGeometry.current()
                for (stroke in fresh) {
                    if (seq >= MAX_ENTRIES) {
                        stopReason = "记满 $MAX_ENTRIES 条"
                        break@drain
                    }
                    seq++
                    emit(seq, stroke, device, current)
                }
            }
        } finally {
            feeding.set(false)
            reader.close()
            readerThread.join(READER_JOIN_MS)
        }

        val seconds = (SystemClock.uptimeMillis() - base) / 1000
        Ln.i("坐标记录：已停止（$stopReason），共记录 $seq 个动作，历时 ${seconds}s")
    }

    /** 把一条轨迹按形状分类后写日志。 */
    private fun emit(
        seq: Int,
        stroke: TouchStroke,
        device: TouchDeviceInfo,
        screen: ScreenGeometry,
    ) {
        val points = stroke.points
        if (points.isEmpty()) return

        val first = points.first()
        val last = points.last()
        val (x1, y1) = TouchCoordMapper.mapPoint(first.x, first.y, device, screen)
        val (x2, y2) = TouchCoordMapper.mapPoint(last.x, last.y, device, screen)

        val duration = (stroke.endTMs - first.tMs).coerceAtLeast(0)
        val moved = hypot((x2 - x1).toDouble(), (y2 - y1).toDouble())
        val orientation = if (screen.rotation % 2 == 0) "竖屏" else "横屏"
        val geometry = "${screen.width}x${screen.height} $orientation"

        when {
            moved <= TAP_MAX_MOVE_PX && duration < LONG_PRESS_MS -> {
                // 前缀固定用 [tap]，中英文用户都能靠它一眼搜到
                val kind = if (duration > TAP_MAX_DURATION_MS) "长按" else "点击"
                Ln.i(
                    "[tap] #$seq  x=${norm(x1, screen.width)} y=${norm(y1, screen.height)}" +
                            "  （$kind · 像素 $x1,$y1 · $duration ms · $geometry）"
                )
            }

            else -> Ln.i(
                "[swipe] #$seq  x1=${norm(x1, screen.width)} y1=${norm(y1, screen.height)}" +
                        " → x2=${norm(x2, screen.width)} y2=${norm(y2, screen.height)}" +
                        "  （像素 $x1,$y1 → $x2,$y2 · $duration ms · 移动 ${moved.toInt()}px · $geometry）"
            )
        }
    }

    /** 像素 → 0~1 的比例，固定四位小数，避免小数点被本地化成逗号。 */
    private fun norm(value: Int, size: Int): String {
        if (size <= 0) return "0.0000"
        return String.format(Locale.US, "%.4f", value.toDouble() / size)
    }
}
