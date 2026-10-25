package com.march7th.hkrpg.remote.internal

import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.os.Build
import android.view.Surface
import com.march7th.hkrpg.bridge.NativeBridgeLib
import com.march7th.hkrpg.constant.AndroidVersions
import com.march7th.hkrpg.constant.DefaultDisplayConfig
import com.march7th.hkrpg.constant.DefaultDisplayConfig.VD_NAME
import com.march7th.hkrpg.third.Ln
import com.march7th.hkrpg.third.wrappers.ServiceManager
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference


object VirtualDisplayManager {

    private const val STATE_IDLE = 0
    private const val STATE_CAPTURING = 1

    private const val VIRTUAL_DISPLAY_FLAG_PUBLIC: Int = DisplayManager.VIRTUAL_DISPLAY_FLAG_PUBLIC
    private const val VIRTUAL_DISPLAY_FLAG_PRESENTATION: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_PRESENTATION
    private const val VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY: Int =
        DisplayManager.VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
    private const val VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH: Int = 1 shl 6
    private const val VIRTUAL_DISPLAY_FLAG_ROTATES_WITH_CONTENT: Int = 1 shl 7
    private const val VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL: Int = 1 shl 8
    private const val VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS: Int = 1 shl 9
    private const val VIRTUAL_DISPLAY_FLAG_TRUSTED: Int = 1 shl 10
    private const val VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP: Int = 1 shl 11
    private const val VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED: Int = 1 shl 12
    private const val VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED: Int = 1 shl 13
    private const val VIRTUAL_DISPLAY_FLAG_OWN_FOCUS: Int = 1 shl 14
    private const val VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP: Int = 1 shl 15
    private const val VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED: Int = 1 shl 16

    private const val VD_SYSTEM_DECORATIONS = false
    private const val VD_DESTROY_CONTENT = true

    const val DISPLAY_NONE = -1

    data class DisplayConfig(
        val width: Int = DefaultDisplayConfig.WIDTH,
        val height: Int = DefaultDisplayConfig.HEIGHT,
        val dpi: Int = DefaultDisplayConfig.DPI
    )

    private val state = AtomicInteger(STATE_IDLE)
    private val config = AtomicReference(DisplayConfig())
    private val displayId = AtomicInteger(DISPLAY_NONE)
    private val virtualDisplay = AtomicReference<VirtualDisplay?>()

    private val monitorSurface = AtomicReference<Surface?>()

    fun setMonitorSurface(surface: Surface?) {
        val old = monitorSurface.getAndSet(surface)
        if (old != null && old != surface) {
            old.release()
            Ln.i("Old monitor surface released")
        }
        Ln.i("setMonitorSurface: old=${old != null}, new=${surface != null}")
    }

    /**
     * 启动虚拟屏。
     *
     * **幂等**：已在捕获中则直接返回现有 displayId，绝不重建。
     * 这是最关键的一处 —— `VD_DESTROY_CONTENT = true` 时，
     * 释放虚拟屏会连带销毁跑在它上面的应用任务（游戏会被杀），
     * 所以每次调用都重建会导致"游戏莫名消失"。
     */
    @Synchronized
    fun start(): Int {
        if (state.get() == STATE_CAPTURING) {
            Ln.w("start: already capturing, reuse displayId=${displayId.get()}")
            return displayId.get()
        }
        state.set(STATE_CAPTURING)
        return startInternal()
    }

    @Synchronized
    fun stop() {
        if (state.get() != STATE_CAPTURING) {
            return
        }
        state.set(STATE_IDLE)
        releaseResources()
        monitorSurface.getAndSet(null)?.release()
        Ln.i("VirtualDisplayManager stopped")
    }

    /**
     * 重建虚拟屏。
     *
     * ⚠️ 必须**全程持有 state 锁**：
     * 原实现 `state.get() != CAPTURING` 判断与后续 `releaseResources()/startInternal()`
     * 之间有窗口，并发的 `start()` 会通过 CAS 又建一个屏 —— 实测出现过
     * 两个 `MAA_VD` 同时存在（display 57 与 59）的泄漏。
     *
     * 这里用 synchronized 把「检查 → 释放 → 重建」做成临界区，并且
     * 用 `state.set(STATE_IDLE)` 显式让位，避免 start() 的 CAS 落在半途。
     */
    @Synchronized
    fun restart() {
        if (state.get() != STATE_CAPTURING) {
            return
        }
        state.set(STATE_IDLE)
        releaseResources()
        state.set(STATE_CAPTURING)
        startInternal()
    }

    @Synchronized
    fun setResolution(width: Int, height: Int, dpi: Int = config.get().dpi) {
        val newConfig = DisplayConfig(width, height, dpi)
        val oldConfig = config.getAndSet(newConfig)
        if (state.get() == STATE_CAPTURING && oldConfig != newConfig) {
            Ln.i("Resolution changed: ${oldConfig.width}x${oldConfig.height} -> ${width}x${height}, restart")
            restart()
        }
    }

    /**
     * 强制清理：无论当前状态如何都释放底层资源。
     *
     * 用于服务退出/重连等场景，兜住任何状态机遗漏导致的虚拟屏泄漏。
     */
    @Synchronized
    fun forceRelease() {
        Ln.i("VirtualDisplayManager forceRelease (state=${state.get()})")
        state.set(STATE_IDLE)
        releaseResources()
        monitorSurface.getAndSet(null)?.release()
    }

    fun getDisplayId(): Int = displayId.get()

    private fun startInternal(): Int {
        try {
            val cfg = config.get()
            val surface = NativeBridgeLib.setupNativeCapturer(cfg.width, cfg.height)

            // ## 诊断：Surface 是否有效（尺寸一致性靠后续帧校验兜底）
            //
            // 实测踩过"画面被拉伸/截断"的问题（1920×1080 的虚拟屏
            // 采出来的帧却是 1920×872，下半截「点击进入」直接被切掉）。
            //
            // Surface 拿不到宽高，所以这里只确认有效性；
            // 真正的尺寸校验在 ShellAutomation.takeScreenshot 里做
            // （帧 vs 虚拟屏尺寸不一致会报错）。
            runCatching {
                Ln.i(
                    "NativeCapturer Surface: isValid=${surface.isValid} " +
                        "（虚拟屏期望 ${cfg.width}x${cfg.height}）",
                )
                if (!surface.isValid) {
                    Ln.e("⚠️ NativeCapturer Surface 无效 —— 采集不到画面！")
                }
            }

            createVirtualDisplay(surface, cfg)

            Ln.i("VirtualDisplayManager started, displayId=${displayId.get()}")
            return displayId.get()
        } catch (e: Exception) {
            Ln.e("VirtualDisplayManager start failed", e)
            state.set(STATE_IDLE)
            return DISPLAY_NONE
        }
    }

    private fun releaseResources() {
        virtualDisplay.getAndSet(null)?.release()
        NativeBridgeLib.releaseNativeCapturer()
        displayId.set(DISPLAY_NONE)
    }

    private fun createVirtualDisplay(surface: Surface, cfg: DisplayConfig) {
        val flags = buildDisplayFlags()
        val wm = ServiceManager.getWindowManager()
        val physicalRotation = runCatching { wm.rotation }.getOrDefault(-1)
        Ln.i("Physical display rotation: $physicalRotation")

        val vd = ServiceManager.getDisplayManager()
            .createNewVirtualDisplay(
                VD_NAME,
                cfg.width,
                cfg.height,
                cfg.dpi,
                surface,
                flags
            )
        virtualDisplay.set(vd)
        val vdId = vd.display.displayId
        displayId.set(vdId)

        val d = vd.display
        Ln.i(
            "VD created: id=$vdId" +
                    ", configured=${cfg.width}x${cfg.height}" +
                    ", actual=${d.width}x${d.height}" +
                    ", rotation=${d.rotation}" +
                    ", flags=0x${flags.toString(16)}"
        )

        // ## ⚠️ 长屏设备会把画面拉伸
        //
        // 实测两台设备出过同一个现象（画面横向压扁、下半个屏幕看不见）：
        //
        // | 设备 | 物理屏 | 宽高比 |
        // |---|---|---|
        // | OnePlus PKG110 | 1264×2780 | 2.20 |
        // | HONOR AAP-AN00 | 1272×2800 | 2.20 |
        //
        // 共同点：**物理屏是 2.2 的超长屏**，而虚拟屏是 16:9（1.78）。
        //
        // 这些定制 ROM（ColorOS / MagicOS）在给虚拟屏上的应用做布局时，
        // 会参考**物理屏的比例**而不是虚拟屏自己声明的分辨率，
        // 于是游戏按 2.2 的窗口渲染，内容被横向拉伸。
        //
        // `freezeRotation` 只在 rotation≠0 时管用，这类设备 rotation 是 0，
        // 所以之前完全没兜住。
        //
        // 兜底：**无条件**再强制一次 display size，让系统彻底知道
        // 这块屏就是 1920×1080，不要拿物理屏比例来套。
        runCatching {
            wm.setForcedDisplaySize(vdId, cfg.width, cfg.height)
            val after = vd.display
            Ln.i(
                "setForcedDisplaySize(${cfg.width}x${cfg.height}) 已应用，" +
                    "复核 actual=${after.width}x${after.height} rotation=${after.rotation}",
            )
            if (after.width != cfg.width || after.height != cfg.height) {
                Ln.e(
                    "⚠️ 虚拟屏尺寸仍是 ${after.width}x${after.height}（期望 ${cfg.width}x${cfg.height}）" +
                        "—— 画面可能被拉伸！",
                )
            }
        }.onFailure { e -> Ln.w("setForcedDisplaySize failed: ${e.message}") }

        if (d.rotation != Surface.ROTATION_0) {
            // 所有旋转非零的情况都先尝试 freezeRotation
            runCatching {
                wm.freezeRotation(vdId, Surface.ROTATION_0)
                Ln.i("freezeRotation done, post-freeze rotation=${vd.display.rotation}")
            }.onFailure { e -> Ln.w("freezeRotation failed: ${e.message}") }

            if (physicalRotation == Surface.ROTATION_0) {
                // 物理屏处于自然方向（rotation=0）而 VD 却有旋转角，
                // 这是横屏原生设备如AYN Odin2的典型特征：
                // 此类设备的定制 ROM 对二级显示调 freezeRotation 无效，
                // 额外调 setForcedDisplaySize 强制 VD 向内部 app 上报横屏尺寸。
                Ln.w(
                    "Landscape-native device detected (physRot=0, vdRot=${d.rotation}), " +
                            "applying setForcedDisplaySize",
                )
            }
        }
    }

    private fun buildDisplayFlags(): Int {
        var flags = (VIRTUAL_DISPLAY_FLAG_PUBLIC
                or VIRTUAL_DISPLAY_FLAG_OWN_CONTENT_ONLY
                or VIRTUAL_DISPLAY_FLAG_SUPPORTS_TOUCH)

        if (VD_DESTROY_CONTENT) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_DESTROY_CONTENT_ON_REMOVAL
        }
        if (VD_SYSTEM_DECORATIONS) {
            flags = flags or VIRTUAL_DISPLAY_FLAG_SHOULD_SHOW_SYSTEM_DECORATIONS
        }
        if (Build.VERSION.SDK_INT >= AndroidVersions.API_33_ANDROID_13) {
            flags = flags or (VIRTUAL_DISPLAY_FLAG_TRUSTED
                    or VIRTUAL_DISPLAY_FLAG_OWN_DISPLAY_GROUP
                    or VIRTUAL_DISPLAY_FLAG_ALWAYS_UNLOCKED
                    or VIRTUAL_DISPLAY_FLAG_TOUCH_FEEDBACK_DISABLED)
            if (Build.VERSION.SDK_INT >= AndroidVersions.API_34_ANDROID_14) {
                flags = flags or (VIRTUAL_DISPLAY_FLAG_OWN_FOCUS
                        or VIRTUAL_DISPLAY_FLAG_DEVICE_DISPLAY_GROUP
                        or VIRTUAL_DISPLAY_FLAG_STEAL_TOP_FOCUS_DISABLED)
            }
        }
        return flags
    }
}
