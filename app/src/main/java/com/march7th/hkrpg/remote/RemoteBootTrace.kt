package com.march7th.hkrpg.remote

import android.os.Build
import android.os.Process
import com.march7th.hkrpg.constant.M7aFiles
import com.march7th.hkrpg.third.Ln
import java.io.File

/**
 * 服务进程启动诊断 trace（Shizuku / Root 用户服务进程侧）
 *
 * 记录 ctor 与 setup 各阶段时间戳定位慢点
 * 进程早期无 Context，FakeContext.getExternalFilesDir() 解析的是 com.android.shell 的目录，不可用；
 * 根目录也不能在这里自拼——部分 Android 11 ROM 下 shell 对 Android/data/<pkg> 无访问权（#227），
 * 且用户可把根目录切到 /data/local/tmp
 *
 * 因此 setup 前的 mark 先攒在内存，[bindUserDir] 收到 App 传来且探测通过的 userDir 后
 * 一次性落到 {userDir}/debug/service_boot_debug.log，与 root_launch_debug.log 同目录
 *
 * 全程 runCatching 兜底：写入失败也不影响主流程（每条同时进 logcat，App 侧 service_bind_debug.log 仍可定位）
 */
object RemoteBootTrace {

    private const val FILE_NAME = "service_boot_debug.log"
    private const val MAX_BYTES = 256 * 1024L
    private const val MAX_PENDING_LINES = 256

    private val lock = Any()

    @Volatile
    private var traceFile: File? = null

    private val pending = ArrayList<String>()

    fun bindUserDir(userDir: File) {
        synchronized(lock) {
            if (traceFile != null) return
            val file = File(userDir, "${M7aFiles.DEBUG}/$FILE_NAME")
            traceFile = file
            runCatching {
                file.parentFile?.mkdirs()
                if (file.exists() && file.length() > MAX_BYTES) file.delete()
                file.appendText(header())
                if (pending.isNotEmpty()) {
                    file.appendText(pending.joinToString(""))
                }
            }
            pending.clear()
        }
    }

    fun mark(stage: String, msg: String = "") {
        val line = if (msg.isEmpty()) {
            "${System.currentTimeMillis()}  $stage\n"
        } else {
            "${System.currentTimeMillis()}  $stage  $msg\n"
        }
        synchronized(lock) {
            val file = traceFile
            if (file == null) {
                if (pending.size < MAX_PENDING_LINES) pending.add(line)
            } else {
                runCatching { file.appendText(line) }
            }
        }
        // 同时进 logcat / root 的 stderr 日志（Ln 写 FileDescriptor.out/err）
        Ln.i("[BOOT] $stage${if (msg.isEmpty()) "" else " $msg"}")
    }

    private fun header(): String =
        "==== service boot pid=${Process.myPid()} ${Build.MANUFACTURER} ${Build.MODEL} " +
                "api=${Build.VERSION.SDK_INT} abi=${Build.SUPPORTED_ABIS.joinToString(",")} " +
                "t=${System.currentTimeMillis()} ====\n"
}
