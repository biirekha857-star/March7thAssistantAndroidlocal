package com.march7th.hkrpg.domain.service

import android.content.Context
import android.os.ParcelFileDescriptor
import com.march7th.hkrpg.RemoteService
import com.march7th.hkrpg.constant.M7aFiles
import com.march7th.hkrpg.data.config.CorePathConfig
import com.march7th.hkrpg.manager.RemoteServiceManager
import com.march7th.hkrpg.remote.CoreDataDir
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.File

/**
 * MaaCore 用独立目录时，App 向提权进程投递它自己写不进去的东西，见 [CoreDataDir]
 *
 * 投递类方法在 [CorePathConfig.isCoreSeparated] 为 false 时直接返回成功；
 * [clearRemote] 不看模式，切回应用目录后也要能清残留
 */
class CoreDataPusher(
    private val context: Context,
    private val pathConfig: CorePathConfig,
) {
    private val mutex = Mutex()

    private val apkPath: String
        get() = context.applicationInfo.sourceDir

    /** 同一 APK 装两次（debug 覆盖安装）versionCode 不变，带上 mtime */
    private val resourceStamp: String by lazy {
        "${pathConfig.appVersionCode}:${File(apkPath).lastModified()}"
    }

    private val lastUpdateZip: File
        get() = File(pathConfig.rootDir, M7aFiles.LAST_RESOURCE_UPDATE_ZIP)

    /** LoadResource 前调用：资源、热更、用户文件全部就位才 true */
    suspend fun prepare(srv: RemoteService): Boolean {
        if (!pathConfig.isCoreSeparated) return true
        return locked {
            val start = System.currentTimeMillis()
            if (!srv.ensureCoreResources(apkPath, resourceStamp)) {
                Timber.e("ensureCoreResources failed: apk=%s stamp=%s", apkPath, resourceStamp)
                return@locked false
            }
            Timber.i("core resources ready (stamp=%s) in %dms", resourceStamp, System.currentTimeMillis() - start)
            pushHotUpdateIfNeededLocked(srv) && pushUserDataLocked(srv)
        }
    }

    /**
     * 任务下发前调用，把用户刚改过的作业 / 基建文件送过去
     *
     * `taskpacks` 单独处理：崩铁内核跑在提权进程，根目录恒为 [CoreDataDir.ROOT]，
     * 不受 `coreDataLocation` 影响，所以**两种模式都要推**。曾经把它并进
     * [CoreDataDir.USER_DATA_DIRS]，结果 APP_DIR 模式下整段被 `isCoreSeparated`
     * 短路掉，导入成功的任务包执行时永远「找不到任务包」。
     *
     * 服务没连上时：独立目录模式判失败（旧行为，资源肯定没就位），
     * APP_DIR 模式仍判成功（旧行为，此时任务本身也起不来，不该由这里拦）。
     */
    suspend fun pushUserData(): Boolean = withRemote(!pathConfig.isCoreSeparated) { srv ->
        val packsOk = pushDirsLocked(srv, listOf(CoreDataDir.TASKPACKS_DIR))
        if (!pathConfig.isCoreSeparated) {
            packsOk
        } else {
            packsOk && pushDirsLocked(srv, CoreDataDir.USER_DATA_DIRS)
        }
    }

    /** 热更新完成后调用；未连接则留到下次 prepare */
    suspend fun pushHotUpdateIfNeeded() {
        if (!pathConfig.isCoreSeparated) return
        withRemote(Unit) { pushHotUpdateIfNeededLocked(it) }
    }

    /** 设置页「清除独立目录数据」：需要提权服务在线 */
    suspend fun clearRemote(): Boolean = withRemote(false) { srv ->
        runCatching { srv.clearCoreData() }
            .onFailure { Timber.e(it, "clearCoreData failed") }
            .getOrDefault(false)
    }

    private suspend fun <T> withRemote(default: T, block: (RemoteService) -> T): T {
        val srv = RemoteServiceManager.getInstanceOrNull() ?: return default
        return locked { block(srv) }
    }

    private suspend fun <T> locked(block: suspend () -> T): T =
        mutex.withLock { withContext(Dispatchers.IO) { block() } }

    /** 两侧 resource/version.json 不一致且留有热更包才投递；没留档只能提示用户重跑资源更新，不算失败 */
    private fun pushHotUpdateIfNeededLocked(srv: RemoteService): Boolean {
        val local = pathConfig.readDiskResourceVersion().orEmpty()
        val remote = runCatching { srv.coreResourceVersion }.getOrDefault("")
        if (local.isEmpty() || remote == local) return true
        val zip = lastUpdateZip
        if (!zip.isFile) {
            Timber.w("core resource '%s' != app '%s' and no update zip kept; re-run resource update to sync", remote, local)
            return true
        }
        val start = System.currentTimeMillis()
        val ok = sendFile(zip, "applyCoreHotUpdate") { srv.applyCoreHotUpdate(it) }
        Timber.i("core hot update push %s (%s) in %dms", if (ok) "done" else "FAILED", local, System.currentTimeMillis() - start)
        return ok
    }

    private fun pushUserDataLocked(srv: RemoteService): Boolean =
        pushDirsLocked(srv, CoreDataDir.USER_DATA_DIRS)

    /** 把 [dirs] 下的文件按相对路径整体投递，单个失败不打断其余 */
    private fun pushDirsLocked(srv: RemoteService, dirs: List<String>): Boolean {
        val root = File(pathConfig.rootDir)
        var ok = true
        var count = 0
        for (dir in dirs) {
            val top = File(root, dir)
            if (!top.isDirectory) continue
            top.walkTopDown().filter { it.isFile }.forEach { file ->
                val rel = file.relativeTo(root).invariantSeparatorsPath
                if (sendFile(file, "putCoreFile") { srv.putCoreFile(rel, it) }) count++ else ok = false
            }
        }
        Timber.d("core user data pushed (dirs=%s): %d files ok=%s", dirs, count, ok)
        return ok
    }

    private inline fun sendFile(file: File, what: String, send: (ParcelFileDescriptor) -> Boolean): Boolean =
        runCatching { ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY).use(send) }
            .onFailure { Timber.w(it, "%s failed: %s", what, file.name) }
            .getOrDefault(false)
}
