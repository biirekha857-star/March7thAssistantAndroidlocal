package com.march7th.hkrpg.domain.notification

import java.util.concurrent.atomic.AtomicLong

enum class LiveBackend {
    /** 小米 HyperOS 焦点通知（私有协议） */
    HYPER_OS_FOCUS,

    /**
     * **Android 16 标准「实时更新」（Live Updates）**。
     *
     * 对应 `Notification.ProgressStyle` + `setShortCriticalText()`，
     * Android 16（API 36）引入，系统自己负责在状态栏/锁屏/胶囊里
     * 渲染持续更新的进度。
     *
     * ## 为什么单列一个档位
     *
     * 它比 [AOSP_PROMOTED] 强得多：后者只是"提升"（promoted ongoing），
     * 停止更新后系统会自动降级；而 Live Updates 是**一等公民**，
     * 系统知道这是一个正在进行的活动，会：
     * - 在状态栏显示胶囊；
     * - 支持分段进度（多段不同颜色）；
     * - 锁屏和 AOD 也展示。
     *
     * ## 谁会用这个档位
     *
     * - 原生 Android 16+；
     * - **OPPO ColorOS 16+** —— OPPO 官方明确说明流体云完整接入了
     *   Android 16 Live Updates API，"遵循谷歌实时活动 API 规范
     *   就能直接适配"（设计总监陈希）。
     *
     * 也就是说：**这一档写对了，ColorOS 16 的流体云自动生效**，
     * 不需要写任何 OPPO 私有接口。
     */
    AOSP_LIVE_UPDATE,

    /** AOSP 提升式持续通知（Android 16 之前的主要手段） */
    AOSP_PROMOTED,

    /** 普通通知（兜底） */
    PLAIN,
}

enum class LiveCategory {
    PROGRESS,
    RESULT,
}

enum class LiveResultKind {
    COMPLETED,
    STOPPED,
    FAILED,
    SERVICE_DIED,
}

data class LiveCapability(
    val backend: LiveBackend,
    val postNotifications: Boolean,
    val promotedAvailable: Boolean,
    val promotedGranted: Boolean,
    val focusLikely: Boolean,
    val focusGranted: Boolean,
    /**
     * 系统是否支持 Android 16 实时更新（API 36+）。
     *
     * 为 true 时状态栏会出现系统渲染的活动胶囊，ColorOS 16+ 上
     * 表现为**流体云**。
     */
    val liveUpdatesAvailable: Boolean = false,
    /**
     * 是否处于 ColorOS / OPPO 系 ROM。
     *
     * 仅用于日志和设置页文案（告诉用户"升级到 ColorOS 16 可用流体云"），
     * **不影响逻辑分支** —— 标准 API 的判定只认 [liveUpdatesAvailable]。
     */
    val isColorOs: Boolean = false,
    /** ColorOS 版本号（如 15.0.2），仅用于提示；拿不到为 null */
    val colorOsVersion: String? = null,
)

data class LiveSession(
    val sessionId: String,
    val category: LiveCategory,
    val title: String,
    val text: String,
    val capsuleText: String,
    val progressCurrent: Int? = null,
    val progressMax: Int? = null,
    /** 任务计数文案，如 "2/5"；岛左栏用它替代百分比 */
    val progressLabel: String? = null,
    val ongoing: Boolean,
    val firstFloat: Boolean,
    val timeoutSec: Int? = null,
    val isError: Boolean = false,
    /** 结果通知是否走弹出通道，跟随内部通知级别 */
    val alert: Boolean = false,
) {
    fun fingerprint(): String =
        "$sessionId|$title|$text|$capsuleText|$progressCurrent|$progressMax|$progressLabel|$ongoing|$isError"

    fun progressPercent(): Int? {
        val cur = progressCurrent ?: return null
        val max = progressMax ?: return null
        if (max <= 0) return null
        return ((cur.toLong() * 100) / max).toInt().coerceIn(0, 100)
    }
}

object FocusSequence {
    fun next(last: Long, nowMs: Long): Long = maxOf(last + 1L, nowMs)
}

object LiveNotifyIds {
    const val RESULT = 9002
    const val PROGRESS = 9003
    const val TEST = 9005

    const val PROGRESS_SESSION = "maa:task-progress"
    const val RESULT_SESSION = "maa:task-result"
    const val TEST_SESSION = "maa:live-test"

    const val CHANNEL_PROGRESS = "task_execution_live"

    /** 静默结果通道；旧的 task_execution_result 建在默认提示音上，只能换 id 重开 */
    const val CHANNEL_RESULT = "task_execution_result_silent"
    const val CHANNEL_RESULT_ALERT = "task_execution_result_alert"
    const val CHANNEL_ISLAND = "maa_live_progress_island"
    const val GROUP_ISLAND = "maa_live_island"

    /** 一次性迁移用：早期版本沿用了第三方应用的分组命名，且岛通道与进度通道重名 */
    const val LEGACY_CHANNEL_RESULT = "task_execution_result"
    const val LEGACY_GROUP_ISLAND = "noticeflow_hyper_island"

    const val PROGRESS_STYLE_MAX = 1000

    fun of(sessionId: String): Int = when (sessionId) {
        PROGRESS_SESSION -> PROGRESS
        RESULT_SESSION -> RESULT
        TEST_SESSION -> TEST
        else -> sessionId.hashCode() and Int.MAX_VALUE
    }
}

/** 进度占用时结果进待发队列 */
class LiveSessionSlot {
    @Volatile
    var progressActive: Boolean = false
        private set

    var pendingResult: LiveSession? = null
        private set

    private var lastFingerprint: String? = null
    private var lastProgress: LiveSession? = null
    private var lastResult: LiveSession? = null
    private var progressFloated: Boolean = false

    fun beginProgress(session: LiveSession): LiveSession {
        progressActive = true
        val floated = session.copy(firstFloat = session.firstFloat && !progressFloated)
        progressFloated = true
        lastProgress = floated
        lastFingerprint = floated.fingerprint()
        return floated
    }

    fun updateProgress(session: LiveSession): LiveSession? {
        if (!progressActive) return null
        val next = session.copy(firstFloat = false)
        val fp = next.fingerprint()
        if (fp == lastFingerprint) return null
        lastProgress = next
        lastFingerprint = fp
        return next
    }

    fun currentProgress(): LiveSession? = lastProgress?.takeIf { progressActive }

    fun offerResult(session: LiveSession): LiveSession? {
        if (progressActive) {
            pendingResult = session
            return null
        }
        lastResult = session
        lastFingerprint = session.fingerprint()
        return session
    }

    fun endProgress(): LiveSession? {
        progressActive = false
        progressFloated = false
        lastProgress = null
        val pending = pendingResult
        pendingResult = null
        if (pending != null) {
            lastResult = pending
            lastFingerprint = pending.fingerprint()
        }
        return pending
    }

    fun currentResult(): LiveSession? = lastResult

    fun clearResult() {
        lastResult = null
        if (!progressActive) lastFingerprint = null
    }
}

/** 一次运行一个 token；结果通知每轮只发一次 */
class LiveRunGate {
    private val token = AtomicLong(0)

    @Volatile
    var resultPublished: Boolean = false
        private set

    fun beginRun(): Long {
        resultPublished = false
        return token.incrementAndGet()
    }

    fun current(): Long = token.get()

    fun isCurrent(runToken: Long): Boolean = runToken != 0L && runToken == token.get()

    @Synchronized
    fun tryClaimResult(runToken: Long): Boolean {
        if (!isCurrent(runToken) || resultPublished) return false
        resultPublished = true
        return true
    }
}
