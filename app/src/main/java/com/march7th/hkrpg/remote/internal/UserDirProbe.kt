package com.march7th.hkrpg.remote.internal

import java.io.File
import java.io.IOException

/**
 * AsstSetUserDir 前先用 Java IO 探一遍 userDir
 *
 * libMaaUtils 对不可访问路径 stat 会抛未捕获 C++ 异常直接 abort 整个提权进程，
 * 这里失败只返回原因，不做任何会崩的事。不按 SDK 版本判断，只看实际权限
 */
object UserDirProbe {

    private const val PROBE_FILE = ".probe"

    /** 探测通过返回 null，否则返回可读的失败原因 */
    fun probe(userDir: File): String? {
        // App 侧可能还没建出来，能自己建也算通过
        if (!userDir.isDirectory && !runCatching { userDir.mkdirs() }.getOrDefault(false)) {
            return "mkdirs failed: $userDir"
        }
        if (!userDir.canRead()) return "not readable: $userDir"
        if (!userDir.canWrite()) return "not writable: $userDir"

        // canRead/canWrite 只查 DAC 位，FUSE 层拒绝要真写一次才知道
        val debugDir = File(userDir, "debug")
        if (!debugDir.isDirectory && !runCatching { debugDir.mkdirs() }.getOrDefault(false)) {
            return "mkdirs failed: $debugDir"
        }
        val probe = File(debugDir, PROBE_FILE)
        return try {
            probe.writeText(System.currentTimeMillis().toString())
            probe.readText()
            probe.delete()
            null
        } catch (e: IOException) {
            "write probe failed: ${e.message}"
        } catch (e: SecurityException) {
            "write probe denied: ${e.message}"
        }
    }
}
