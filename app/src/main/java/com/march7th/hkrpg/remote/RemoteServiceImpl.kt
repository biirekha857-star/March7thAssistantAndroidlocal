package com.march7th.hkrpg.remote

import android.content.Intent
import android.graphics.Bitmap
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import android.system.Os
import android.view.Surface
import com.march7th.hkrpg.ITouchEventCallback
import com.march7th.hkrpg.M7aCoreService
import com.march7th.hkrpg.RemoteService
import com.march7th.hkrpg.bridge.NativeBridgeLib
import com.march7th.hkrpg.constant.DefaultDisplayConfig
import com.march7th.hkrpg.constant.DisplayMode
import com.march7th.hkrpg.core.InputControlUtils
import com.march7th.hkrpg.remote.internal.ActivityUtils
import com.march7th.hkrpg.remote.internal.CoreDataStore
import com.march7th.hkrpg.remote.internal.GameAudioMuteController
import com.march7th.hkrpg.remote.internal.GameFpsMonitor
import com.march7th.hkrpg.remote.internal.GestureRecorder
import com.march7th.hkrpg.remote.internal.PermissionGrantHelper
import com.march7th.hkrpg.remote.internal.PowerController
import com.march7th.hkrpg.remote.internal.RemoteUtils
import com.march7th.hkrpg.remote.internal.ScreenManager
import com.march7th.hkrpg.remote.internal.UserDirProbe
import com.march7th.hkrpg.remote.internal.VirtualDisplayManager
import com.march7th.hkrpg.remote.internal.WakeUnlockController
import com.march7th.hkrpg.remote.internal.XmsfFirewall
import com.march7th.hkrpg.third.FakeContext
import com.march7th.hkrpg.third.Ln
import com.march7th.hkrpg.third.Workarounds
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.system.exitProcess

class RemoteServiceImpl : RemoteService.Stub() {

    companion object {
        private const val TAG = "RemoteService"
        private const val HEARTBEAT_INTERVAL_MS = 5_000L

        @JvmStatic
        fun performEmergencyCleanup() {
            Ln.i("$TAG: performEmergencyCleanup triggered")
            runCatching {
                // 先释放虚拟屏：否则进程退出后 MAA_VD 会留在系统里累积泄漏
                VirtualDisplayManager.forceRelease()
                GameAudioMuteController.restoreAll()
                XmsfFirewall.restoreIfNeeded()
                PowerController.destroy()
                ScreenManager.destroy()
                CoreManager.destroy()
            }.onFailure {
                Ln.e("$TAG: Emergency cleanup failed: ${it.message}")
            }
        }

        /**
         * 收割**孤儿提权进程**。
         *
         * ## 为什么需要
         *
         * 提权进程是 `app_process` 拉起来的独立进程，**不受 App 的
         * `pm clear` / `am force-stop` 管辖**。App 被强杀或重装时，若
         * `linkToDeath` 没来得及触发，旧进程会带着它创建的虚拟屏一起
         * 变成孤儿（PPID=1）继续存活 —— 实测留下过一个 1280x720 的
         * `MAA_VD` 一直挂在系统里。
         *
         * 新进程启动时把这些兄弟进程收掉，虚拟屏随之被系统回收。
         * 只杀"同名但不是我"的进程，绝不动自己。
         */
        @JvmStatic
        fun reapOrphanServices() {
            val myPid = Process.myPid()
            val myName = "com.march7th.hkrpg:shizuku_service"
            val killed = mutableListOf<Int>()
            runCatching {
                File("/proc").listFiles()?.forEach { dir ->
                    val pid = dir.name.toIntOrNull() ?: return@forEach
                    if (pid == myPid) return@forEach
                    val cmdline = runCatching {
                        File(dir, "cmdline").readBytes().toString(Charsets.UTF_8)
                    }.getOrNull() ?: return@forEach
                    // cmdline 是 \0 分隔的，app_process 起的进程首段就是 nice-name
                    val name = cmdline.split('\u0000').firstOrNull { it.isNotBlank() } ?: return@forEach
                    if (!name.contains(myName)) return@forEach
                    Ln.w("$TAG: 收割孤儿提权进程 pid=$pid name=$name")
                    runCatching { Process.killProcess(pid) }
                        .onSuccess { killed += pid }
                }
            }.onFailure { Ln.w("$TAG: 收割孤儿进程失败: ${it.message}") }
            if (killed.isNotEmpty()) Ln.i("$TAG: 已收割孤儿提权进程 $killed")
        }
    }

    init {
        RemoteBootTrace.mark("CTOR_START")
        Runtime.getRuntime().addShutdownHook(Thread {
            runCatching { performEmergencyCleanup() }
        }.apply { name = "remote-shutdown-hook" })
    }

    private val appPid = AtomicInteger(0)
    private val destroyed = AtomicBoolean(false)
    /** 同一进程内 setup 幂等：成功后再调直接返回 OK，失败则下次重试 */
    private var setup = false
    private val coreData = CoreDataStore()

    init {
        // ctor 必须轻量：重活放 setup()，attach 前零阻塞
        // Root 下放开 umask，core 与本进程写的文件对 shell 可读写（切回 Shizuku 后还能追加）；父目录 0771 挡住其他 App
        if (Process.myUid() != Process.SHELL_UID) runCatching { Os.umask(0) }
        Workarounds.apply()
        startHeartbeatWatchdog()
        Ln.i("$TAG: RemoteServiceImpl created (lightweight ctor)")
        RemoteBootTrace.mark("CTOR_DONE")
    }

    override fun destroy() {
        if (!destroyed.compareAndSet(false, true)) {
            return
        }
        Ln.i("$TAG: destroy()")
        InputControlUtils.setTouchCallback(null)
        GameFpsMonitor.stop()
        performEmergencyCleanup()
        exitProcess(0)
    }

    override fun exit() = destroy()

    override fun getM7aCoreService(): M7aCoreService {
        return CoreManager.maaService
    }

    override fun version(): String {
        val maaVersion = CoreManager.MaaContext?.AsstGetVersion() ?: "Not loaded"
        return """
            ==== Build Info ====
            BridgeInfo: ${NativeBridgeLib.ping()}
            Kernel: StarRail ${com.march7th.hkrpg.starrail.StarRailEngine.VERSION}
            MaaCore Version: $maaVersion
            =====================
        """.trimIndent()
    }

    override fun pid(): Int = Process.myPid()

    override fun setup(userDir: String?, isDebug: Boolean): Int {
        if (setup) return SetupResult.OK
        RemoteBootTrace.mark("SETUP_BEGIN")
        // 清上一实例可能残留的断网规则，同步执行先于业务 AIDL
        runCatching { XmsfFirewall.ensureRestored() }
            .onFailure { Ln.w("XmsFw boot restore failed: ${it.message}") }
        RemoteBootTrace.mark("SETUP_XMSF_RESTORED")
        // 收割孤儿提权进程：它们持有的虚拟屏不受 forceRelease 管辖（在别的进程里），
        // 只能靠把进程收掉让系统回收。必须早于 forceRelease —— 顺序反了会先清掉
        // 自己的、再被孤儿的那块继续占着。
        runCatching { reapOrphanServices() }
            .onFailure { Ln.w("$TAG: 收割孤儿进程异常: ${it.message}") }
        // 冷启动自愈：上一次服务实例若异常退出（如进程被杀），它的虚拟屏可能仍在。
        // 这里先清一遍，避免 MAA_VD 累积泄漏。
        runCatching { VirtualDisplayManager.forceRelease() }
            .onFailure { Ln.w("$TAG: 启动时清理虚拟屏失败: ${it.message}") }
        // 不可访问的路径进 AsstSetUserDir 会 abort 整个进程（#227），这里只报告，换到哪由用户在设置里决定
        val dir = File(userDir.orEmpty())
        val probeFailure = UserDirProbe.probe(dir)
        if (probeFailure != null) {
            Ln.e("$TAG: setup failed - userDir inaccessible: $probeFailure (uid=${Process.myUid()} sdk=${Build.VERSION.SDK_INT})")
            RemoteBootTrace.mark("SETUP_USER_DIR_INACCESSIBLE", probeFailure)
            return SetupResult.ERR_USER_DIR_INACCESSIBLE
        }
        RemoteBootTrace.bindUserDir(dir)
        // 换芯为崩铁后不再依赖 MaaCore：它的 userDir 设置是可选的。
        // MaaContext 为 null 说明没装 MaaCore（正常情况），继续走崩铁引擎的初始化。
        val ctx = CoreManager.MaaContext
        if (ctx == null) {
            Ln.i("$TAG: 前代内核未加载（崩铁内核），跳过 AsstSetUserDir")
        } else {
            Ln.i("NativeBridgeLib ping ${NativeBridgeLib.ping()}")
            with(ctx) {
                if (!AsstSetUserDir(dir.path)) {
                    Ln.w("$TAG: AsstSetUserDir($dir) 返回 false（崩铁内核可忽略）")
                } else {
                    Ln.i("MaaCore ${AsstGetVersion()} userDir=$dir")
                }
            }
        }
        // 崩铁引擎就绪：加载 OpenCV 等识别依赖
        runCatching { CoreManager.starRailEngine.loadResource(null) }
            .onFailure { Ln.w("$TAG: 崩铁引擎资源加载失败: ${it.message}") }
        PermissionGrantHelper.disablePhantomProcessKiller()
        setup = true
        RemoteBootTrace.mark("SETUP_DONE")
        return SetupResult.OK
    }

    override fun test(map: MutableMap<String, String>) {
    }

    // ---- 独立数据目录 ----

    override fun ensureCoreResources(apkPath: String?, stamp: String?): Boolean {
        if (apkPath.isNullOrBlank() || stamp.isNullOrBlank()) return false
        val start = System.currentTimeMillis()
        val ok = coreData.ensureResources(File(apkPath), stamp)
        Ln.i("$TAG: ensureCoreResources stamp=$stamp ok=$ok in ${System.currentTimeMillis() - start}ms")
        return ok
    }

    override fun applyCoreHotUpdate(zip: ParcelFileDescriptor?): Boolean {
        zip ?: return false
        val ok = ParcelFileDescriptor.AutoCloseInputStream(zip).use { coreData.applyHotUpdate(it) }
        Ln.i("$TAG: applyCoreHotUpdate ok=$ok version=${coreData.resourceVersion()}")
        return ok
    }

    override fun getCoreResourceVersion(): String = coreData.resourceVersion()

    override fun putCoreFile(relPath: String?, src: ParcelFileDescriptor?): Boolean {
        if (relPath == null || src == null) {
            src?.close()
            return false
        }
        return ParcelFileDescriptor.AutoCloseInputStream(src).use { coreData.putFile(relPath, it) }
            .also { if (!it) Ln.w("$TAG: putCoreFile rejected: $relPath") }
    }

    override fun listCoreDebugFiles(): MutableList<String> = coreData.listDebugFiles().toMutableList()

    override fun openCoreDebugFile(relPath: String?): ParcelFileDescriptor? {
        val file = relPath?.let(coreData::debugFile) ?: return null
        return runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) }.getOrNull()
    }

    override fun clearCoreData(): Boolean = coreData.clear().also { Ln.i("$TAG: clearCoreData ok=$it") }

    override fun screencap(width: Int, height: Int) {
    }

    override fun captureFramePng(dirPath: String?): String? {
        if (dirPath.isNullOrBlank()) {
            Ln.w("$TAG: captureFramePng - blank dirPath")
            return null
        }
        val bitmap = NativeBridgeLib.getFrameBufferBitmap() ?: run {
            Ln.w("$TAG: captureFramePng - no frame available")
            return null
        }
        return try {
            val dir = File(dirPath).apply { mkdirs() }
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.US).format(Date())
            val file = File(dir, "screenshot_$timestamp.png")
            val ok = FileOutputStream(file).use { out ->
                bitmap.compress(Bitmap.CompressFormat.PNG, 100, out)
            }
            if (!ok) {
                Ln.e("$TAG: captureFramePng - PNG compress failed")
                file.delete()
                return null
            }
            Ln.i("$TAG: captureFramePng saved ${file.absolutePath}")
            file.absolutePath
        } catch (e: Exception) {
            Ln.e("$TAG: captureFramePng error: ${e.message}")
            null
        } finally {
            bitmap.recycle()
        }
    }

    override fun setForcedDisplaySize(width: Int, height: Int): Boolean {
        return ScreenManager.setForcedDisplaySize(width, height)
    }

    override fun clearForcedDisplaySize(): Boolean {
        return ScreenManager.clearForcedDisplaySize()
    }

    override fun grantPermissions(request: PermissionGrantRequest): PermissionStateInfo {
        val packageName = request.packageName
        val uid = if (request.uid > 0) request.uid
        else RemoteUtils.getAppUid(packageName).takeIf { it > 0 } ?: request.uid
        val p = request.permissions

        with(PermissionGrantHelper) {
            // 结果不进 PermissionStateInfo 免改 AIDL；放开失败由 App 侧预检兜底
            if (p and PermissionGrantRequest.PERM_FGS_SPECIAL_USE != 0) {
                grantForegroundServiceSpecialUse(packageName)
            }
            return PermissionStateInfo(
                accessibilityPermission = if (p and PermissionGrantRequest.PERM_ACCESSIBILITY != 0) grantAccessibilityService(
                    request.accessibilityServiceId
                ) else false,
                floatingWindowPermission = if (p and PermissionGrantRequest.PERM_FLOATING_WINDOW != 0) grantFloatingWindowPermission(
                    packageName,
                    uid
                ) else false,
                notificationPermission = if (p and PermissionGrantRequest.PERM_NOTIFICATION != 0) grantNotificationPermission(
                    packageName,
                    uid
                ) else false,
                batteryOptimizationExempt = if (p and PermissionGrantRequest.PERM_BATTERY != 0) grantBatteryOptimizationExemption(
                    packageName
                ) else false,
                storagePermission = if (p and PermissionGrantRequest.PERM_STORAGE != 0) grantStoragePermission(
                    packageName,
                    uid
                ) else false,
                backgroundUnrestricted = if (p and PermissionGrantRequest.PERM_BACKGROUND != 0) grantBackgroundUnrestricted(
                    packageName,
                    uid
                ) else false,
            )
        }
    }

    override fun setMonitorSurface(surface: Surface?) {
        Ln.i("$TAG: setMonitorSurface(${surface != null})")
        VirtualDisplayManager.setMonitorSurface(surface)
        NativeBridgeLib.setPreviewSurface(surface)
    }

    override fun setTouchCallback(callback: ITouchEventCallback?) {
        Ln.i("$TAG: setTouchCallback(${callback != null})")
        InputControlUtils.setTouchCallback(callback)
    }

    override fun touchDown(x: Int, y: Int, contact: Int) {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
            InputControlUtils.down(x, y, contact, displayId)
        }
    }

    override fun touchMove(x: Int, y: Int, contact: Int) {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
            InputControlUtils.move(x, y, contact, displayId)
        }
    }

    override fun touchUp(x: Int, y: Int, contact: Int) {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
            InputControlUtils.up(x, y, contact, displayId)
        }
    }

    override fun touchCancel() {
        val displayId = VirtualDisplayManager.getDisplayId()
        if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
            InputControlUtils.cancel(displayId)
        }
    }

    override fun setDisplayPower(on: Boolean) {
        PowerController.setDisplayPower(on)
    }

    override fun startVirtualDisplay(): Int {
        Ln.i("$TAG: startVirtualDisplay()（后台虚拟屏模式）")
        val id = VirtualDisplayManager.start().also { displayId ->
            if (displayId != DefaultDisplayConfig.DISPLAY_NONE) {
                PowerController.startUserActivityKeepAlive(displayId)
            }
        }
        // 把 displayId 交给崩铁引擎（采集通道已由 VirtualDisplayManager 建好，这里只接管）
        if (id != DefaultDisplayConfig.DISPLAY_NONE) {
            runCatching { CoreManager.starRailEngine.attachDisplay(id) }
                .onFailure { Ln.w("$TAG: 接入崩铁引擎失败: ${it.message}") }
        }
        return id
    }

    override fun stopVirtualDisplay() {
        Ln.i("$TAG: stopVirtualDisplay()")
        // 强制释放：兜住任何状态机遗漏导致的虚拟屏泄漏
        // （实测出现过两个 MAA_VD 同时存在）
        runCatching { VirtualDisplayManager.forceRelease() }
            .onFailure { Ln.w("$TAG: VirtualDisplayManager 强制释放失败: ${it.message}") }
        runCatching { GameFpsMonitor.stop() }
        runCatching { PowerController.stopUserActivityKeepAlive() }
        // 通知引擎断开采集
        runCatching { CoreManager.starRailEngine.detachDisplay() }
            .onFailure { Ln.w("$TAG: 引擎断开采集失败: ${it.message}") }
        GameAudioMuteController.restoreAll()
    }

    override fun getGameFps(): Float = GameFpsMonitor.currentFps()

    override fun setPlayAudioOpAllowed(packageName: String?, isAllowed: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        val ok = GameAudioMuteController.setMuted(packageName, muted = !isAllowed)
        if (!ok) {
            Ln.w("$TAG: setPlayAudioOpAllowed($packageName, allowed=$isAllowed) failed")
        }
        return ok
    }

    override fun isAppAlive(packageName: String): Int {
        // ## ⚠️ 必须**先读流、再 waitFor**
        //
        // 原实现是 `waitFor()` 之后才读 stdout/stderr。这在子进程输出
        // 超过管道缓冲区（通常 64KB）时会**永久阻塞** —— 子进程写不进去
        // 就一直不退出，`waitFor()` 就永远等下去。
        //
        // 更麻烦的是它的副作用：超时/异常后返回 UNKNOWN 还好，
        // 但如果 exec 本身失败（比如高并发下 fork 失败），会被静默
        // 当成 DEAD，给上层一个"游戏已死"的错误信号。
        //
        // 实测症状：领邮件任务一开始，AppWatchdog 报
        // `app com.miHoYo.hkrpg is no longer alive`，但游戏明明活得好好的
        // —— 任务链因此被中止。
        //
        // 这里改成：
        // 1. 先读流（避免管道阻塞）；
        // 2. **失败重试 3 次**，全部失败才判 DEAD，单次异常一律 UNKNOWN
        //    （宁可放过，不可错杀）。
        repeat(3) { attempt ->
            val status = runOnce(packageName)
            if (status != AppAliveStatus.UNKNOWN) return status
            if (attempt < 2) Thread.sleep(150)
        }
        Ln.w("$TAG: isAppAlive 连续 3 次无法判定 $packageName，按 UNKNOWN 处理（不放行误判）")
        return AppAliveStatus.UNKNOWN
    }

    /** 单次存活探测。返回 UNKNOWN 表示"没测准"，调用方可以重试。 */
    private fun runOnce(packageName: String): Int {
        return try {
            val process = Runtime.getRuntime().exec(arrayOf("pidof", packageName))
            // 先读流，再 waitFor —— 顺序不能反
            val output = process.inputStream.bufferedReader().use { it.readText() }.trim()
            val errorOutput = process.errorStream.bufferedReader().use { it.readText() }.trim()
            val exitCode = process.waitFor()
            when {
                exitCode == 0 && output.isNotEmpty() -> AppAliveStatus.ALIVE
                // pidof 找不到进程时返回 1 且无输出 —— 这是**唯一**能判定死亡的组合
                exitCode == 1 && output.isEmpty() && errorOutput.isEmpty() -> AppAliveStatus.DEAD
                else -> {
                    Ln.w(
                        "$TAG: isAppAlive 结果不明确 $packageName: exit=$exitCode stdout=[$output] stderr=[$errorOutput]",
                    )
                    AppAliveStatus.UNKNOWN
                }
            }
        } catch (e: Exception) {
            Ln.w("isAppAlive check failed for $packageName: ${e.message}")
            AppAliveStatus.UNKNOWN
        }
    }

    override fun heartbeat(pid: Int) {
        appPid.set(pid)
        Ln.i("$TAG: heartbeat received, app pid=$pid")
    }

    override fun isAppOnVirtualDisplay(packageName: String): Boolean {
        val targetDisplayId = VirtualDisplayManager.getDisplayId()
        if (targetDisplayId == DefaultDisplayConfig.DISPLAY_NONE) return true
        val onDisplay = ActivityUtils.isAppOnDisplay(packageName, targetDisplayId)
        // 游戏不是 MaaMeow 拉起的（未启用自动启动）时，这里是首次得知它在虚拟屏上
        if (onDisplay) GameFpsMonitor.ensureStarted(packageName)
        return onDisplay
    }

    override fun moveAppToVirtualDisplay(packageName: String): Boolean {
        val targetDisplayId = VirtualDisplayManager.getDisplayId()
        if (targetDisplayId == DefaultDisplayConfig.DISPLAY_NONE) {
            Ln.w("$TAG: moveAppToVirtualDisplay: no active virtual display")
            return false
        }
        Ln.i("$TAG: moveAppToVirtualDisplay($packageName) -> display $targetDisplayId")
        return ActivityUtils.repinAppToDisplay(packageName, targetDisplayId)
    }

    /** @return [com.march7th.hkrpg.constant.WakeUnlockResult] */
    override fun unlock(credential: String?): Int =
        WakeUnlockController.unlock(credential.orEmpty())

    /** @return [com.march7th.hkrpg.constant.WakeUnlockResult] */
    override fun lockAndSleep(): Int = WakeUnlockController.lockAndSleep()

    /** @return [com.march7th.hkrpg.constant.WakeUnlockResult] */
    override fun testUnlock(credential: String?): Int =
        WakeUnlockController.testUnlock(credential.orEmpty())

    /** @return [com.march7th.hkrpg.constant.WakeUnlockResult] */
    override fun unlockWithGesture(gestureJson: String?): Int =
        WakeUnlockController.unlockWithGesture(gestureJson.orEmpty())

    /** @return [com.march7th.hkrpg.constant.WakeUnlockResult] */
    override fun testUnlockGesture(gestureJson: String?): Int =
        WakeUnlockController.testUnlockGesture(gestureJson.orEmpty())

    override fun startGestureRecord(timeoutMs: Int) = GestureRecorder.start(timeoutMs)

    /** @return [com.march7th.hkrpg.domain.models.GestureRecordResult] 的 JSON */
    override fun pollGestureRecord(): String = GestureRecorder.poll()

    override fun cancelGestureRecord() = GestureRecorder.cancel()

    override fun isPackageInstalled(packageName: String): Boolean {
        return try {
            FakeContext.get().packageManager.getPackageInfo(packageName, 0)
            true
        } catch (e: Exception) {
            Ln.w("$TAG: isPackageInstalled: $packageName not found", e)
            false
        }
    }

    override fun startActivity(intent: Intent): Boolean {
        return ActivityUtils.startActivity(intent)
    }

    override fun setForceFullscreenOnVirtualDisplay(enabled: Boolean) {
        Ln.i("$TAG: setForceFullscreenOnVirtualDisplay($enabled)")
        ActivityUtils.forceFullscreenOnVirtualDisplay = enabled
    }

    override fun setPackageNetworkingEnabled(packageName: String?, enabled: Boolean): Boolean {
        if (packageName.isNullOrBlank()) return false
        return XmsfFirewall.setNetworkingEnabled(packageName, enabled)
    }

    override fun setVirtualDisplayResolution(width: Int, height: Int, dpi: Int) {
        Ln.i("$TAG: setVirtualDisplayResolution(${width}x${height}, dpi=$dpi)")
        VirtualDisplayManager.setResolution(width, height, dpi)
    }

    override fun setVirtualDisplayMode(mode: Int): Boolean {
        // 前台模式已删除，只接受后台（虚拟屏）模式。
        // 这样也保证了引擎永远拿到固定的 1920×1080 虚拟屏尺寸，
        // 而不是各设备千差万别的物理屏尺寸。
        return if (mode == DisplayMode.BACKGROUND) true else false
    }

    private fun startHeartbeatWatchdog() {
        Thread {
            while (!destroyed.get()) {
                try {
                    Thread.sleep(HEARTBEAT_INTERVAL_MS)
                } catch (_: InterruptedException) {
                    return@Thread
                }
                val pid = appPid.get()
                if (pid <= 0) {
                    continue
                }
                if (!File("/proc/$pid").exists()) {
                    Ln.w("$TAG: app process (pid=$pid) no longer exists, destroying remote service")
                    destroy()
                    return@Thread
                }
            }
        }.apply {
            name = "remote-heartbeat-watchdog"
            isDaemon = true
        }.start()
    }
}
