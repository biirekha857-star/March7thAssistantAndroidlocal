package com.march7th.hkrpg.constant


object LogConfig {
    /** 内存中最大运行时日志条数 */
    const val MAX_RUNTIME_LOG_COUNT = 750

    /** 任务日志保留天数 */
    const val MAX_TASK_LOG_DAYS = 30

    /** 错误日志单文件最大大小 */
    const val MAX_ERROR_LOG_SIZE = 2L * 1024 * 1024 // 2MB

    /** 错误日志最大文件数 */
    const val MAX_ERROR_LOG_FILES = 5

    /** 崩溃日志最大文件数 */
    const val MAX_CRASH_LOG_FILES = 10

    /**
     * 日志批量刷新间隔（毫秒）。
     *
     * 原来 75ms：任务跑起来每秒 13 次真实磁盘 flush，外加 13 次
     * `_logs` 列表替换，整页跟着重组。日志是给人回放看的，
     * 250ms 的延迟完全感觉不到，CPU 和 IO 却少了一大半。
     */
    const val LOG_FLUSH_INTERVAL_MS = 250L

    /** 导出时 gui / schedule / error_logs / crash_logs 仅保留近 N 天 */
    const val EXPORT_ROLLING_LOG_DAYS = 7

    /**
     * 导出时调试截图最多打包多少张。
     *
     * 引擎帧已改 JPEG（约 250 KB/张），80 张约 20 MB；留这个上限主要是
     * 兜住改动前留在设备上的 PNG 老帧（1.5~2.3 MB/张）——实测用户导出过
     * 369 MB 的日志包，其中 363 MB 全是截图。
     */
    const val MAX_EXPORT_SCREENSHOTS = 80
}
