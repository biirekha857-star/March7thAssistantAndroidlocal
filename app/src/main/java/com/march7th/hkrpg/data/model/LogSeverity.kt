package com.march7th.hkrpg.data.model

/**
 * 日志严重性级别
 *
 * 用于日志过滤和持久化，与 UI 展示颜色解耦。
 * 业务语义（公招星级、肉鸽事件等）通过 [LogLevel] 表达，
 * 每个 LogLevel 都映射到一个 LogSeverity。
 */
enum class LogSeverity {
    TRACE,
    MESSAGE,
    INFO,
    WARNING,
    ERROR;

    fun isAtLeast(other: LogSeverity): Boolean = ordinal >= other.ordinal
}
