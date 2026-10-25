package com.march7th.hkrpg.remote

import java.io.File

/**
 * 资源目录文件布局的两侧共用小工具：App 进程与提权进程都会解资源、读版本
 *
 * 纯 java.io，不带日志，便于 JVM 单测
 */
object ResourceFiles {

    const val VERSION_FILE = "version.json"
    private val LAST_UPDATED = Regex("\"last_updated\"\\s*:\\s*\"([^\"]*)\"")

    /** MaaCore 读 tasks/tasks.json，而资源包里是 tasks.json：源更新才复制，返回是否成功或无需处理 */
    fun deriveTasksJson(resourceDir: File): Boolean = runCatching {
        val src = File(resourceDir, "tasks.json")
        if (!src.isFile) return true
        val dest = File(resourceDir, "tasks/tasks.json")
        if (dest.isFile && dest.length() == src.length() && dest.lastModified() >= src.lastModified()) return true
        dest.parentFile?.mkdirs()
        src.copyTo(dest, overwrite = true)
    }.isSuccess

    /** version.json 的 last_updated，缺失或解析失败为 null */
    fun readLastUpdated(versionFile: File): String? =
        runCatching { readLastUpdated(versionFile.readText()) }.getOrNull()

    fun readLastUpdated(versionJson: String): String? = LAST_UPDATED.find(versionJson)?.groupValues?.get(1)
}
