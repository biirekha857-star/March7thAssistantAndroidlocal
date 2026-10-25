package com.march7th.hkrpg.starrail.engine

import android.graphics.Bitmap
import android.os.SystemClock
import com.march7th.hkrpg.bridge.NativeBridgeLib
import com.march7th.hkrpg.core.InputControlUtils
import com.march7th.hkrpg.third.Ln
import java.io.ByteArrayOutputStream

/**
 * 屏控层：截图 + 触控。
 *
 * 运行在**特权进程**内，直连 `libbridge.so`（采集）与 `InputControlUtils`（注入），
 * 不经过 AIDL/TCP，因此没有跨进程拷贝。
 *
 * ## 采集
 * [NativeBridgeLib.setupNativeCapturer] 建 `AImageReader`（RGBA_8888, 5 缓冲）并返回
 * 一个 `Surface`；把这个 Surface 交给虚拟屏后，native 侧每帧写入帧池，
 * [NativeBridgeLib.getFrameBufferBitmap] 取出最新帧的 Bitmap（native 侧已完成
 * RGBA→BGR 转换与打包）。
 *
 * ## 注入
 * 通过 [InputControlUtils] 注入，**必须带 displayId**，否则事件落到主屏。
 *
 * ## 坐标
 * 所有对外坐标都基于**采集到的帧尺寸**。UI 层拿到的预览也是同一尺寸，
 * 因此点击坐标可以直接对应，不需要额外换算。
 */
class ShellAutomation {

    companion object {
        private const val TAG = "ShellAutomation"
        private const val DEFAULT_WIDTH = 1920
        private const val DEFAULT_HEIGHT = 1080
    }

    /** 最近一次采集到的帧。 */
    @Volatile
    var screenshot: Bitmap? = null
        private set

    /** 采集分辨率（由虚拟屏配置决定）。 */
    var width: Int = DEFAULT_WIDTH
        private set
    var height: Int = DEFAULT_HEIGHT
        private set

    private var capturerReady = false

    /** 当前生效的 displayId（虚拟屏）。-1 表示未启动。 */
    @Volatile
    var displayId: Int = -1
        private set

    /**
     * 建立采集通道并创建 Surface。
     *
     * @return 交给虚拟屏的 Surface；失败返回 null
     */
    fun setupCapturer(w: Int = DEFAULT_WIDTH, h: Int = DEFAULT_HEIGHT): android.view.Surface? {
        if (capturerReady) {
            Ln.w("$TAG: capturer 已就绪，先释放")
            releaseCapturer()
        }
        return try {
            val surface = NativeBridgeLib.setupNativeCapturer(w, h)
            if (surface == null) {
                Ln.e("$TAG: setupNativeCapturer 返回 null")
                return null
            }
            width = w
            height = h
            capturerReady = true
            Ln.i("$TAG: 采集通道就绪 ${w}x$h")
            surface
        } catch (t: Throwable) {
            Ln.e("$TAG: setupCapturer 失败: ${t.message}")
            null
        }
    }

    fun releaseCapturer() {
        if (!capturerReady) return
        runCatching { NativeBridgeLib.releaseNativeCapturer() }
            .onFailure { Ln.w("$TAG: releaseNativeCapturer 失败: ${it.message}") }
        capturerReady = false
        screenshot = null
        Ln.i("$TAG: 采集通道已释放")
    }

    fun setDisplayId(id: Int) {
        displayId = id
        Ln.i("$TAG: displayId = $id")
    }

    /**
     * 复用**已存在**的采集通道。
     *
     * `VirtualDisplayManager` 创建虚拟屏时已经调过
     * `NativeBridgeLib.setupNativeCapturer`，帧池已经在写。
     * 这里只需把 displayId 接上并标记就绪，**不要**重复 setup
     * （重复 setup 会先 release 再重建，白白丢帧）。
     */
    fun attachExistingCapturer(id: Int, w: Int, h: Int) {
        displayId = id
        width = w
        height = h
        capturerReady = true
        Ln.i("$TAG: 复用已有采集通道 display=$id ${w}x$h")
    }

    /**
     * 抓一帧。
     *
     * @param retries 取不到帧时的重试次数（虚拟屏刚建立时可能还没有内容）
     */
    fun takeScreenshot(retries: Int = 3): Boolean {
        if (!capturerReady) {
            Ln.w("$TAG: 采集通道未就绪")
            return false
        }
        for (i in 0 until retries) {
            try {
                val bmp = NativeBridgeLib.getFrameBufferBitmap()
                if (bmp != null && bmp.width > 0 && bmp.height > 0) {
                    // ## 帧尺寸和虚拟屏尺寸不一致 → **主动缩放到目标尺寸**
                    //
                    // 实测踩过：虚拟屏是 1920×1080，采到的帧却是 1920×872
                    // （长屏 ROM 按物理屏 2.2 比例渲染），画面横向压扁，
                    // OCR 认不出字、坐标全错位 —— 启动一直超时。
                    //
                    // 之前只打警告不修正，任务照样跑不通。现在改成：
                    // **把帧缩放到声明的分辨率**，让后续 OCR / 坐标计算
                    // 全部基于 1920×1080，不再受畸形帧影响。
                    //
                    // 注意：缩放只是让坐标体系自洽，画质会略糊；
                    // 真正修好还得靠 `VirtualDisplayManager` 那边
                    // setForcedDisplaySize 把虚拟屏尺寸纠正过来。
                    val fixed = if (bmp.width != width || bmp.height != height) {
                        if (!sizeMismatchWarned) {
                            sizeMismatchWarned = true
                            Ln.e(
                                "$TAG: ⚠️ 帧尺寸异常！帧 ${bmp.width}x${bmp.height} " +
                                    "vs 虚拟屏 ${width}x$height —— 已缩放到目标尺寸继续",
                            )
                        }
                        val scaled = Bitmap.createScaledBitmap(bmp, width, height, true)
                        if (scaled !== bmp) bmp.recycle()
                        scaled
                    } else {
                        bmp
                    }
                    screenshot = fixed
                    return true
                }
            } catch (t: Throwable) {
                Ln.w("$TAG: getFrameBufferBitmap 异常: ${t.message}")
            }
            if (i < retries - 1) SystemClock.sleep(200)
        }
        Ln.w("$TAG: 取帧失败（$retries 次）")
        return false
    }

    /** 帧尺寸警告是否已经打过（只打一次，避免刷屏）。 */
    @Volatile
    private var sizeMismatchWarned = false

    /** 当前帧编码为 PNG。 */
    fun framePng(): ByteArray? {
        val bmp = screenshot ?: return null
        return runCatching {
            ByteArrayOutputStream().use { bos ->
                bmp.compress(Bitmap.CompressFormat.PNG, 100, bos)
                bos.toByteArray()
            }
        }.onFailure { Ln.e("$TAG: PNG 编码失败: ${it.message}") }.getOrNull()
    }

    // ---- 触控 ----

    /** 单击（带按下时长，模拟真实触摸）。 */
    fun tap(x: Int, y: Int, contact: Int = InputControlUtils.SINGLE_CONTACT, durationMs: Long = 60L): Boolean {
        if (displayId < 0) {
            Ln.w("$TAG: tap 时 displayId 未设置")
            return false
        }
        val down = runCatching { InputControlUtils.down(x, y, contact, displayId) }.getOrDefault(false)
        if (!down) {
            Ln.w("$TAG: down($x,$y) 失败")
            return false
        }
        SystemClock.sleep(durationMs)
        val up = runCatching { InputControlUtils.up(x, y, contact, displayId) }.getOrDefault(false)
        if (!up) {
            Ln.w("$TAG: up($x,$y) 失败")
        }
        return up
    }

    /** 长按。 */
    fun longPress(x: Int, y: Int, durationMs: Long = 800L, contact: Int = InputControlUtils.SINGLE_CONTACT): Boolean =
        tap(x, y, contact, durationMs)

    /** 滑动（线性插值，模拟真实手势轨迹）。 */
    fun swipe(
        fromX: Int, fromY: Int, toX: Int, toY: Int,
        durationMs: Long = 300L, steps: Int = 12,
        contact: Int = InputControlUtils.SINGLE_CONTACT,
    ): Boolean {
        if (displayId < 0) {
            Ln.w("$TAG: swipe 时 displayId 未设置")
            return false
        }
        if (!runCatching { InputControlUtils.down(fromX, fromY, contact, displayId) }.getOrDefault(false)) {
            return false
        }
        val stepDelay = (durationMs / steps).coerceAtLeast(1L)
        for (i in 1..steps) {
            val t = i.toFloat() / steps
            val x = (fromX + (toX - fromX) * t).toInt()
            val y = (fromY + (toY - fromY) * t).toInt()
            runCatching { InputControlUtils.move(x, y, contact, displayId) }
            SystemClock.sleep(stepDelay)
        }
        return runCatching { InputControlUtils.up(toX, toY, contact, displayId) }.getOrDefault(false)
    }

    /** 按键。 */
    fun pressKey(keyCode: Int, holdMs: Long = 50L): Boolean {
        if (displayId < 0) return false
        val d = runCatching { InputControlUtils.keyDown(keyCode, displayId) }.getOrDefault(false)
        SystemClock.sleep(holdMs)
        val u = runCatching { InputControlUtils.keyUp(keyCode, displayId) }.getOrDefault(false)
        return d && u
    }

    /** 取消当前手势（异常时自愈）。 */
    fun cancelGesture() {
        if (displayId < 0) return
        runCatching { InputControlUtils.cancel(displayId) }
    }
}
