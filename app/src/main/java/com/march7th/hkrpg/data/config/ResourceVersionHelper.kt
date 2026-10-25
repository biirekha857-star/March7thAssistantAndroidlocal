package com.march7th.hkrpg.data.config

import java.time.LocalDateTime
import java.time.ZoneId
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object ResourceVersionHelper {
    private val VERSION_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")
    private val DISPLAY_FORMATTER = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")

    fun compareVersions(v1: String, v2: String): Int {
        return try {
            val v1t = runCatching {
                LocalDateTime.parse(v1, VERSION_FORMATTER)
            }.getOrDefault(LocalDateTime.MIN)
            val v2t = runCatching {
                LocalDateTime.parse(v2, VERSION_FORMATTER)
            }.getOrDefault(LocalDateTime.MIN)
            v1t.compareTo(v2t)
        } catch (e: Exception) {
            0
        }
    }

    fun formatVersionForDisplay(version: String): String {
        return runCatching {
            LocalDateTime.parse(version, VERSION_FORMATTER)
                .atZone(ZoneOffset.UTC)
                .withZoneSameInstant(ZoneId.systemDefault())
                .format(DISPLAY_FORMATTER)
        }.getOrDefault(version)
    }
}
