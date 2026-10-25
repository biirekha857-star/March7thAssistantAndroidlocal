package com.march7th.hkrpg.data.datasource

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import timber.log.Timber
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import java.util.zip.ZipInputStream


class ZipExtractor {

    data class ExtractProgress(
        val progress: Int,
        val current: Int,
        val total: Int
    )

    /**
     * 解压 ZIP 文件
     * @param zipFile ZIP 文件
     * @param destDir 目标目录
     * @param pathFilter 路径过滤器，返回目标相对路径，null 表示跳过
     * @param onProgress 进度回调
     */
    suspend fun extract(
        zipFile: File,
        destDir: File,
        pathFilter: (String) -> String?,
        onProgress: (ExtractProgress) -> Unit
    ): Result<Int> {
        return try {
            val startTime = System.currentTimeMillis()
            Timber.d("开始解压: ${zipFile.name}, 大小: ${zipFile.length() / 1024}KB")

            val extractedCount = AtomicInteger(0)
            val totalBytes = AtomicLong(0)
            val lastUpdateTime = AtomicLong(System.currentTimeMillis())
            val semaphore = Semaphore(Runtime.getRuntime().availableProcessors() * 2)

            // 先扫描获取总文件数
            val totalFiles = countFiles(zipFile, pathFilter)
            Timber.d("文件总数: $totalFiles")
            onProgress(ExtractProgress(0, 0, totalFiles))

            // 解压文件
            withContext(Dispatchers.IO) {
                coroutineScope {
                    ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
                        var entry = zis.nextEntry
                        while (entry != null) {
                            if (!entry.isDirectory) {
                                val targetPath = pathFilter(entry.name)
                                if (targetPath != null) {
                                    val data = zis.readBytes()
                                    val size = entry.size

                                    launch {
                                        semaphore.withPermit {
                                            val file = File(destDir, targetPath)
                                            file.parentFile?.mkdirs()
                                            FileOutputStream(file).use { it.write(data) }

                                            val count = extractedCount.incrementAndGet()
                                            totalBytes.addAndGet(size)

                                            val now = System.currentTimeMillis()
                                            val lastTime = lastUpdateTime.get()
                                            if ((now - lastTime >= 100 || count == totalFiles) &&
                                                lastUpdateTime.compareAndSet(lastTime, now)
                                            ) {
                                                val progress =
                                                    if (totalFiles > 0) (count * 100 / totalFiles) else 0
                                                onProgress(
                                                    ExtractProgress(
                                                        progress,
                                                        count,
                                                        totalFiles
                                                    )
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                            zis.closeEntry()
                            entry = zis.nextEntry
                        }
                    }
                }
            }

            val totalTime = System.currentTimeMillis() - startTime
            Timber.i("解压完成: ${extractedCount.get()} 个文件, 耗时: ${totalTime}ms")

            Result.success(extractedCount.get())
        } catch (e: Exception) {
            Timber.e(e, "解压失败")
            Result.failure(e)
        }
    }

    private fun countFiles(zipFile: File, pathFilter: (String) -> String?): Int {
        var count = 0
        ZipInputStream(BufferedInputStream(FileInputStream(zipFile))).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                if (!entry.isDirectory && pathFilter(entry.name) != null) {
                    count++
                }
                zis.closeEntry()
                entry = zis.nextEntry
            }
        }
        return count
    }
}
