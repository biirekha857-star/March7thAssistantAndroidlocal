package com.march7th.hkrpg.data.resource

import com.march7th.hkrpg.BuildConfig.MAA_CORE_VERSION


object CoreVersion {

    /** 当前 MAA Core 版本（构建期由 .maaversion 写入，未部署时为空串） */
    val current: String get() = MAA_CORE_VERSION

    /**
     * 检查当前版本是否满足最低要求
     * @param minimumRequired 最低要求版本（如 "v6.0.0-beta.1"）
     * @param current 当前 MAA Core 版本，默认取构建期写入的 BuildConfig.MAA_CORE_VERSION
     * @return true 表示满足要求，false 表示版本过低
     */
    fun meetsMinimumRequired(
        minimumRequired: String?,
        current: String = MAA_CORE_VERSION
    ): Boolean {
        // 无版本要求
        if (minimumRequired.isNullOrBlank()) return true

        // 当前版本未知（未经 setup_maa_core.py 部署），宽松放行
        if (current.isBlank()) return true

        return runCatching {
            compareVersions(parseVersion(current), parseVersion(minimumRequired)) >= 0
        }.getOrDefault(true)
    }

    /**
     * 解析版本号
     * 支持格式：v6.0.0, v6.0.0-beta.1, 6.0.0
     */
    private fun parseVersion(version: String): VersionInfo {
        val cleanVersion = version.removePrefix("v").removePrefix("V")
        val parts = cleanVersion.split("-", limit = 2)
        val mainPart = parts[0]
        val preRelease = parts.getOrNull(1)

        val numbers = mainPart.split(".").map { it.toIntOrNull() ?: 0 }
        return VersionInfo(
            major = numbers.getOrElse(0) { 0 },
            minor = numbers.getOrElse(1) { 0 },
            patch = numbers.getOrElse(2) { 0 },
            preRelease = preRelease
        )
    }

    /**
     * 比较两个版本
     * @return 负数表示 a < b，0 表示相等，正数表示 a > b
     */
    private fun compareVersions(a: VersionInfo, b: VersionInfo): Int {
        // 比较主版本号
        if (a.major != b.major) return a.major - b.major
        if (a.minor != b.minor) return a.minor - b.minor
        if (a.patch != b.patch) return a.patch - b.patch

        // 比较预发布版本
        // 没有预发布标签的版本 > 有预发布标签的版本
        return when {
            a.preRelease == null && b.preRelease == null -> 0
            a.preRelease == null -> 1  // a 是正式版，b 是预发布
            b.preRelease == null -> -1 // a 是预发布，b 是正式版
            else -> comparePreRelease(a.preRelease, b.preRelease)
        }
    }

    /**
     * 按 SemVer 规则比较预发布标签：以 "." 分段，数字段按数值比较（beta.2 < beta.10），
     * 非数字段按字典序，数字段 < 非数字段，前缀相同时段数少者小
     */
    private fun comparePreRelease(a: String, b: String): Int {
        val aParts = a.split(".")
        val bParts = b.split(".")
        for (i in 0 until minOf(aParts.size, bParts.size)) {
            val aNum = aParts[i].toIntOrNull()
            val bNum = bParts[i].toIntOrNull()
            val cmp = when {
                aNum != null && bNum != null -> aNum.compareTo(bNum)
                aNum != null -> -1
                bNum != null -> 1
                else -> aParts[i].compareTo(bParts[i])
            }
            if (cmp != 0) return cmp
        }
        return aParts.size - bParts.size
    }

    private data class VersionInfo(
        val major: Int,
        val minor: Int,
        val patch: Int,
        val preRelease: String?
    )
}
