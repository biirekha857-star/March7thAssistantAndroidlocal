package com.march7th.hkrpg.data.log

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class LogFileInfo(
    val fileName: String,
    val filePath: String,
    val startTime: Long,
    val fileSize: Long,
    val taskCount: Int
) {
    companion object {
        private val P = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss (Z)")
    }

    val displayTime: String
        get() = Instant.ofEpochMilli(startTime)
            .atZone(ZoneId.systemDefault())
            .format(P)
}
