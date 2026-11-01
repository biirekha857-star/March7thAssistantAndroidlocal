package com.march7th.hkrpg.remote

import com.march7th.hkrpg.constant.M7aFiles

/**
 * MaaCore 独立数据目录（CoreDataLocation.LOCAL_TMP）两侧共用的约定
 *
 * /data/local/tmp 是 ext4 直写，shell / root 都可读写，App 进程不可见
 * 资源由提权进程直接从 APK 解出，App 只投递热更包与用户小文件；core 写的 debug/ 导出日志时拉回
 */
object CoreDataDir {

    /** shell:shell 0771，两种后端都可写 */
    const val ROOT = "/data/local/tmp/maameow"

    const val RESOURCE_ASSET_PREFIX = "assets/${M7aFiles.ASSET_DIR_NAME}/"
    const val OVERRIDES_ASSET_ENTRY = "assets/${M7aFiles.OVERRIDES_ASSET_TASKS}"
    const val OVERRIDES_TASKS_REL = M7aFiles.OVERRIDES_ASSET_TASKS

    /** 热更包顶层目录（GitHub 归档有，镜像源可能没有），两侧过滤规则必须一致 */
    private const val HOT_UPDATE_TOP_DIR = "MaaResource-main/"
    private const val HOT_UPDATE_RESOURCE_DIR = "resource/"

    /**
     * 自定义任务包目录（见 `docs/task-file.md`）
     *
     * 执行器 [com.march7th.hkrpg.starrail.engine.ScriptedTaskRunner] 跑在提权进程，
     * 根目录**恒为** [ROOT]，和 `coreDataLocation` 设置无关 —— 所以这个目录
     * 不能只放在 [USER_DATA_DIRS] 里（那份只在 LOCAL_TMP 模式投递），
     * 必须由 `CoreDataPusher` 在两种模式下都推过去。
     */
    const val TASKPACKS_DIR = "taskpacks"

    /**
     * App 写、core 读，整体投递
     *
     * 只在 MaaCore 用独立目录（LOCAL_TMP）时才整体推送；`taskpacks` 见
     * [TASKPACKS_DIR]，崩铁内核两种模式都要读，单独处理。
     */
    val USER_DATA_DIRS = listOf(M7aFiles.OVERRIDES, "copilot", "custom_infrast")

    /** 热更包 zip 条目 → cache/resource 下的相对路径；不属于资源的条目返回 null；App 侧解包同样用它 */
    fun hotUpdateEntryToRelPath(entryName: String): String? {
        val name = entryName.removePrefix(HOT_UPDATE_TOP_DIR)
        if (!name.startsWith(HOT_UPDATE_RESOURCE_DIR)) return null
        return name.removePrefix(HOT_UPDATE_RESOURCE_DIR).ifEmpty { null }
    }

    /** 跨进程传来的相对路径只允许落在根目录内 */
    fun isSafeRelPath(rel: String): Boolean =
        rel.isNotBlank() && !rel.startsWith("/") && rel.split('/').none { it == ".." || it.isEmpty() }
}
