package com.march7th.hkrpg.starrail.engine

import android.app.ActivityOptions
import android.content.Intent
import android.os.IBinder
import android.os.SystemClock
import com.march7th.hkrpg.third.FakeContext
import com.march7th.hkrpg.third.Ln

/**
 * 游戏进程控制 —— 启动 / 关闭 / 搬到虚拟屏。
 *
 * ## ⚠️ 两个必须记住的坑（实测踩过）
 *
 * ### 1. 必须先 `force-stop` 再启动
 *
 * 游戏已在运行时，`startActivityAsUser` 会**复用旧 task**，
 * `ActivityOptions.launchDisplayId` 被**静默忽略**：
 *
 * ```
 * force-stop 前: startActivityAsUser -> 3  (START_DELIVERED_TO_TOP)  游戏仍在主屏 ❌
 * force-stop 后: startActivityAsUser -> 0  (START_SUCCESS)           游戏进入虚拟屏 ✅
 * ```
 *
 * 命令都"成功"，必须看返回值语义才能判断。
 *
 * ### 2. 崩铁是「二段跳」启动
 *
 * `ComboSDKActivity`（米哈游登录 SDK）→ 游戏本体 Activity，
 * 第二跳可能丢失 displayId。对策是 [ensureOnDisplay] 的漂移拉回。
 */
object GameLauncher {

    private const val TAG = "GameLauncher"

    /** 崩铁包名（国服）。 */
    const val PKG_CN = "com.miHoYo.hkrpg"

    /** 崩铁包名（国际服）。 */
    const val PKG_OVERSEA = "com.HoYoverse.hkrpgoversea"

    /** 启动 Activity（登录 SDK）。 */
    private const val START_ACTIVITY = "com.mihoyo.combosdk.ComboSDKActivity"

    // ActivityManager.startActivityAsUser 的返回码
    private const val START_SUCCESS = 0
    private const val START_DELIVERED_TO_TOP = 3

    // 等待游戏落到虚拟屏的超时
    private const val PIN_TIMEOUT_MS = 30_000L
    private const val PIN_POLL_MS = 500L

    private fun activityManager(): Any {
        val binder = Class.forName("android.os.ServiceManager")
            .getDeclaredMethod("getService", String::class.java)
            .invoke(null, "activity") as IBinder
        return Class.forName("android.app.IActivityManager\$Stub")
            .getMethod("asInterface", IBinder::class.java)
            .invoke(null, binder)
    }

    /** 强制停止游戏。 */
    fun forceStop(pkg: String = PKG_CN): Boolean = runCatching {
        val am = activityManager()
        am.javaClass.getMethod("forceStopPackage", String::class.java, Int::class.javaPrimitiveType)
            .invoke(am, pkg, -2 /* USER_CURRENT */)
        Ln.i("$TAG: force-stop $pkg")
        true
    }.onFailure { Ln.w("$TAG: force-stop 失败: ${it.message}") }.getOrDefault(false)

    /**
     * 强制停止，并**确认进程真的消失了**才返回。
     *
     * ## 为什么不能发完 force-stop 就当它死了
     *
     * 部分定制 ROM（实测荣耀 MagicOS）有「应用速启 / 游戏预加载」：
     * 游戏被放进内存里的热进程池，`forceStopPackage` 之后会被立刻拉起来。
     * 这个热进程是**带着上一份图形环境**的 —— 它在物理屏上按 1272×2800
     * 初始化过，激活到 1920×1080 的虚拟屏时系统不做重新布局，只把内容
     * 放大裁切填满：
     *
     * ```
     * 1272×2800 按宽度填满 1920 → 放大 1.51 倍
     * 2800×1.51 = 4228，画框只有 1080 → 只显示顶部约 1/4
     * ```
     *
     * 于是背景视频（按 surface 铺满）看着完全正常，而 UI 层
     * （logo、「点击进入」）全在裁切区之外 —— 就是用户看到的
     * 「画面放大了、点击进入不见了」。
     *
     * 同一份代码在 OnePlus 上会直接崩（分辨率不匹配被老实地报出来），
     * 在荣耀上被系统糊过去，所以只有这台机器出问题。
     *
     * @return true = 已确认进程不存在；false = 反复 force-stop 后仍存活（ROM 保活）
     */
    fun forceStopAndConfirm(pkg: String = PKG_CN, timeoutMs: Long = 8_000L): Boolean {
        forceStop(pkg)
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (!isRunning(pkg)) {
                Ln.i("$TAG: 已确认 $pkg 进程退出")
                return true
            }
            SystemClock.sleep(400)
            // 补刀：保活策略可能在上一次 force-stop 后把它拉回来
            forceStop(pkg)
        }

        // 最后手段：直接 kill -9
        killByPid(pkg)
        SystemClock.sleep(600)
        return if (!isRunning(pkg)) {
            Ln.i("$TAG: kill -9 后进程已退出")
            true
        } else {
            Ln.w("$TAG: ⚠️ force-stop + kill -9 后 $pkg 仍在运行（ROM 保活/速启），" +
                    "启动时可能复用它那份按物理屏初始化的图形环境")
            false
        }
    }

    /** 按 pid 强杀。返回是否发出了 kill。 */
    private fun killByPid(pkg: String = PKG_CN): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("pidof", pkg))
        // 先读流再 waitFor，避免管道写满把自己堵死
        val out = com.march7th.hkrpg.third.IO.toString(p.inputStream).trim()
        p.waitFor()
        if (out.isEmpty()) {
            false
        } else {
            out.split(Regex("\\s+")).filter { it.isNotEmpty() }.forEach { pid ->
                val k = Runtime.getRuntime().exec(arrayOf("kill", "-9", pid))
                k.waitFor()
            }
            Ln.w("$TAG: kill -9 $pkg (pid=$out)")
            true
        }
    }.onFailure { Ln.w("$TAG: kill -9 失败: ${it.message}") }.getOrDefault(false)

    /**
     * 启动游戏到指定 display。
     *
     * @return true 表示启动指令被接受（**不代表已落到目标屏**，需配合 [ensureOnDisplay]）
     */
    fun launch(pkg: String = PKG_CN, displayId: Int = 0): Boolean {
        return runCatching {
            val pm = FakeContext.get().packageManager
            val intent = pm.getLaunchIntentForPackage(pkg) ?: run {
                Ln.e("$TAG: 找不到 $pkg 的启动 Intent")
                return false
            }
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS)

            val opts = ActivityOptions.makeBasic()
            if (displayId != 0) {
                ActivityOptions::class.java
                    .getMethod("setLaunchDisplayId", Int::class.javaPrimitiveType)
                    .invoke(opts, displayId)
            }

            val am = activityManager()
            val iat = Class.forName("android.app.IApplicationThread")
            val pi = Class.forName("android.app.ProfilerInfo")
            val m = am.javaClass.getMethod(
                "startActivityAsUser",
                iat, String::class.java, Intent::class.java, String::class.java,
                IBinder::class.java, String::class.java,
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                pi, android.os.Bundle::class.java, Int::class.javaPrimitiveType,
            )
            val r = m.invoke(
                am,
                null,                  // caller
                "com.android.shell",   // callingPackage
                intent,                // intent
                null,                  // resolvedType
                null,                  // resultTo
                null,                  // resultWho
                0,                     // requestCode
                0,                     // startFlags
                null,                  // profilerInfo
                opts.toBundle(),       // bOptions
                -2,                    // userId = USER_CURRENT
            ) as Int
            Ln.i("$TAG: startActivityAsUser($pkg, display=$displayId) -> $r" +
                    when (r) {
                        START_SUCCESS -> " (START_SUCCESS)"
                        START_DELIVERED_TO_TOP -> " (START_DELIVERED_TO_TOP — 复用了旧 task，displayId 可能被忽略)"
                        else -> ""
                    })
            r == START_SUCCESS
        }.onFailure { Ln.e("$TAG: launch 失败: ${it.message}") }.getOrDefault(false)
    }

    /**
     * 启动到虚拟屏的完整流程：**确认杀掉** → 等一会儿 → launch。
     *
     * 这里用 [forceStopAndConfirm] 而不是裸的 `forceStop`：
     * 发完指令不代表进程死了，ROM 的速启会把它拉回来，
     * 而那个热进程的图形环境是按物理屏初始化的 —— 直接复用就会错位。
     */
    fun startOnDisplay(pkg: String = PKG_CN, displayId: Int, waitAfterStopMs: Long = 2500L): Boolean {
        Ln.i("$TAG: 启动 $pkg 到 display $displayId")
        forceStopAndConfirm(pkg)
        SystemClock.sleep(waitAfterStopMs)
        return launch(pkg, displayId)
    }

    /**
     * 强制游戏重新按当前显示尺寸布局一次（「主屏往返」）。
     *
     * ## 治的是「画面被放大裁切」
     *
     * 游戏若带着物理屏的图形环境被塞进虚拟屏，系统只会缩放裁切、
     * 不会让它重新布局，于是 UI 层整个跑到画框外（见 [forceStopAndConfirm] 的注释）。
     *
     * 而**一次真实的显示变更会触发重新布局**：把 task 移到主屏、停一下、
     * 再移回来，游戏会重新读取一次显示尺寸，UI 就回到正确位置。
     * 这个动作用户手工验证过 —— 切到主屏再切回来就正常了。
     *
     * @param dwellMs 停留在主屏的时长。太短游戏来不及感知这次变更，
     *   实测 400~600ms 足够；太长会明显闪一下游戏画面。
     */
    fun kickRelayout(pkg: String = PKG_CN, displayId: Int, dwellMs: Long = 450L): Boolean {
        val taskId = findTaskId(pkg)
        if (taskId <= 0) {
            Ln.w("$TAG: kickRelayout 找不到 $pkg 的 task，跳过")
            return false
        }

        Ln.w("$TAG: kickRelayout — 把 task $taskId 移到主屏 ${dwellMs}ms 再移回 display $displayId（强制重新布局）")
        if (!moveTaskToDisplay(taskId, android.view.Display.DEFAULT_DISPLAY)) {
            Ln.w("$TAG: kickRelayout 移到主屏失败")
            return false
        }
        SystemClock.sleep(dwellMs)

        if (!moveTaskToDisplay(taskId, displayId)) {
            Ln.w("$TAG: kickRelayout 移回 display $displayId 失败")
            return false
        }
        SystemClock.sleep(800)

        val back = currentDisplayOf(pkg)
        if (back != displayId) {
            Ln.w("$TAG: kickRelayout 后游戏在 display=$back（期望 $displayId），尝试 repin")
            return repin(pkg, displayId)
        }
        Ln.i("$TAG: kickRelayout 完成，游戏已回到 display $displayId")
        return true
    }

    /** 当前 pkg 所在 displayId。 -1 = 无 task， -2 = 无法判断。 */
    fun currentDisplayOf(pkg: String = PKG_CN): Int {
        // ## ⚠️ 前提是进程真的活着
        //
        // `dumpsys activity activities` 里即使进程已死也可能残留
        // 该包的 task 记录（最近任务、launcher 引用），
        // 只看 task 行会误判"游戏还在某个 display 上"。
        //
        // 所以先确认进程存在，不存在直接返回 -1（没运行）。
        if (!isRunning(pkg)) return -1

        return try {
            val out = runCatching {
                val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))
                // 先读流再 waitFor，避免管道阻塞
                val s = com.march7th.hkrpg.third.IO.toString(p.inputStream)
                p.waitFor()
                s
            }.getOrDefault("")
            if (out.isEmpty()) return -2

            var cur = -1
            for (line in out.split("\n")) {
                val t = line.trim()
                if (t.startsWith("Display #")) {
                    val m = Regex("Display #(\\d+)").find(t)
                    if (m != null) cur = m.groupValues[1].toInt()
                }
                if (cur >= 0 && t.startsWith("* Task{") && t.contains(pkg)) {
                    Ln.i("$TAG: currentDisplayOf($pkg) = $cur（命中 task 行）")
                    return cur
                }
            }
            Ln.i("$TAG: currentDisplayOf($pkg) 未找到 task 行，返回 -1")
            -1
        } catch (t: Throwable) {
            Ln.w("$TAG: currentDisplayOf 失败: ${t.message}")
            -2
        }
    }

    /**
     * 游戏是否**已安装**。
     *
     * ## 为什么需要它
     *
     * 在没装游戏的设备上跑任务，后面的 `launch()` 会失败，
     * 然后虚拟屏建起来但抓不到画面，日志被
     * `取帧失败（3 次）` 刷几十行 —— 用户完全看不出是"没装游戏"。
     *
     * 提前查一次包列表，就能在第一步给出明确提示。
     *
     * ## 实现
     *
     * `pm path <包名>` 命中时输出 `package:/data/app/.../base.apk`，
     * 未安装时输出为空。比 `pm list packages` 快得多（不用枚举全部包）。
     */
    fun isInstalled(pkg: String = PKG_CN): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("pm", "path", pkg))
        val out = com.march7th.hkrpg.third.IO.toString(p.inputStream).trim()
        p.waitFor()
        out.contains("package:")
    }.getOrDefault(false)

    /** 游戏进程是否存活。 */
    fun isRunning(pkg: String = PKG_CN): Boolean = runCatching {
        val p = Runtime.getRuntime().exec(arrayOf("pidof", pkg))
        val out = com.march7th.hkrpg.third.IO.toString(p.inputStream).trim()
        p.waitFor()
        out.isNotEmpty()
    }.getOrDefault(false)

    /**
     * 等待游戏落到目标 display；漂移时尝试拉回。
     *
     * @return 最终是否在目标 display 上
     */
    fun ensureOnDisplay(
        pkg: String = PKG_CN,
        displayId: Int,
        timeoutMs: Long = PIN_TIMEOUT_MS,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var lastLogged = -1000
        while (SystemClock.uptimeMillis() < deadline) {
            val cur = currentDisplayOf(pkg)
            if (cur != lastLogged) {
                Ln.i("$TAG: $pkg display = $cur (期望 $displayId)")
                lastLogged = cur
            }
            if (cur == displayId) return true
            // 漂移到别的屏 -> 尝试拉回
            if (cur >= 0 && cur != displayId) {
                Ln.w("$TAG: 检测到漂移（$cur -> $displayId），尝试拉回")
                repin(pkg, displayId)
            }
            SystemClock.sleep(PIN_POLL_MS)
        }
        Ln.w("$TAG: ensureOnDisplay 超时，最终 display = ${currentDisplayOf(pkg)}")
        return currentDisplayOf(pkg) == displayId
    }

    /**
     * 把游戏 task 拉回目标 display。
     *
     * 三重兜底（对齐 MAA-Meow 的 `repinAppToDisplay`）：
     *   1. `moveRootTaskToDisplay` / `moveStackToDisplay`（反射）
     *   2. `am display move-stack`（命令）
     *   3. 重新投放 launch intent（让系统 reparent）
     */
    fun repin(pkg: String = PKG_CN, displayId: Int): Boolean {
        val taskId = findTaskId(pkg)
        if (taskId > 0) {
            if (moveTaskToDisplay(taskId, displayId)) {
                SystemClock.sleep(1200)
                if (currentDisplayOf(pkg) == displayId) {
                    Ln.i("$TAG: repin 成功（反射/命令）")
                    return true
                }
            }
        }
        // 第 3 重：重新投放
        Ln.i("$TAG: repin 尝试重新投放")
        if (launch(pkg, displayId)) {
            SystemClock.sleep(2000)
            if (currentDisplayOf(pkg) == displayId) {
                Ln.i("$TAG: repin 成功（重新投放）")
                return true
            }
        }
        return false
    }

    /** 找 pkg 的 taskId。 */
    fun findTaskId(pkg: String = PKG_CN): Int {
        return try {
            val p = Runtime.getRuntime().exec(arrayOf("dumpsys", "activity", "activities"))
            val out = com.march7th.hkrpg.third.IO.toString(p.inputStream)
            p.waitFor()
            for (line in out.split("\n")) {
                val t = line.trim()
                if (t.startsWith("* Task{") && t.contains(pkg)) {
                    val m = Regex("#(\\d+)").find(t) ?: continue
                    return m.groupValues[1].toInt()
                }
            }
            -1
        } catch (t: Throwable) {
            Ln.w("$TAG: findTaskId 失败: ${t.message}")
            -1
        }
    }

    private fun moveTaskToDisplay(taskId: Int, displayId: Int): Boolean {
        // 反射优先
        runCatching {
            val binder = Class.forName("android.os.ServiceManager")
                .getDeclaredMethod("getService", String::class.java)
                .invoke(null, "activity_task") as IBinder
            val atm = Class.forName("android.app.IActivityTaskManager\$Stub")
                .getMethod("asInterface", IBinder::class.java)
                .invoke(null, binder)
            for (name in arrayOf("moveRootTaskToDisplay", "moveStackToDisplay")) {
                val r = runCatching {
                    atm.javaClass.getMethod(
                        name, Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
                    ).invoke(atm, taskId, displayId)
                    true
                }.getOrDefault(false)
                if (r) {
                    Ln.i("$TAG: $name($taskId, $displayId) 已调用")
                    return true
                }
            }
        }.onFailure { Ln.w("$TAG: 反射搬移失败: ${it.message}") }

        // 命令兜底
        return runCatching {
            val p = Runtime.getRuntime().exec(
                arrayOf("am", "display", "move-stack", taskId.toString(), displayId.toString())
            )
            val code = p.waitFor()
            Ln.i("$TAG: am display move-stack 退出码=$code")
            code == 0
        }.getOrDefault(false)
    }
}
