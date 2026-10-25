package com.march7th.hkrpg.data.notification.live

import android.app.Notification
import android.content.Context
import android.graphics.drawable.Icon
import com.march7th.hkrpg.R
import com.march7th.hkrpg.domain.notification.LiveBackend
import com.march7th.hkrpg.domain.notification.LiveCapability
import com.march7th.hkrpg.domain.notification.LiveCategory
import com.march7th.hkrpg.domain.notification.LiveSession
import com.march7th.hkrpg.domain.notification.LiveUpdatePublisher
import timber.log.Timber

/**
 * **Android 16 实时更新（Live Updates）** 发布器。
 *
 * # 它是干什么的
 *
 * 把任务进度做成系统认可的"进行中活动"，由系统在状态栏胶囊、
 * 锁屏、AOD 里渲染。在 **OPPO ColorOS 16+ 上就表现为流体云**。
 *
 * # 关键 API
 *
 * | API | 作用 |
 * |---|---|
 * | `Notification.ProgressStyle` | 声明这是进度型活动，支持**多段进度** |
 * | `ProgressStyle.Segment(len, color)` | 一段进度条（可多段拼成不同颜色） |
 * | `setProgressTrackerIcon` | 进度头部的图标 |
 * | `setShortCriticalText` | 胶囊里的极短文案（**≤7 字符**） |
 * | `setRequestPromotedOngoing` | 申请提升为持续活动 |
 *
 * # 与 HyperOS 焦点通知的区别
 *
 * - HyperOS：往 extras 里塞私有 JSON（`miui.focus.*`），靠小米系统解析；
 * - Live Updates：全部走**公开 SDK**，不碰反射、不塞私有 key。
 *
 * 所以这个类可以在任何 API 36+ 设备上安全运行。
 */
class AospLiveUpdatePublisher(
    context: Context,
    private val factory: LiveNotificationFactory,
    promotedDetector: AospPromotedDetector,
) : LiveUpdatePublisher {

    private val appContext = context.applicationContext
    private val plain = PlainNotificationPublisher(context, factory, promotedDetector)

    override val capability: LiveCapability
        get() = plain.capability.copy(
            backend = LiveBackend.AOSP_LIVE_UPDATE,
            promotedGranted = true,
            liveUpdatesAvailable = true,
        )

    override fun build(session: LiveSession): Notification =
        factory.build(
            session,
            requestPromoted = session.ongoing,
            liveUpdate = true,
        )

    override fun publish(session: LiveSession) {
        val n = build(session)
        Timber.d(
            "LiveUpdate publish: cat=${session.category} " +
                "progress=${session.progressCurrent}/${session.progressMax} " +
                "label=${session.progressLabel}",
        )
        plain.notifyOrSkip(session.sessionId, n)
    }

    override fun publishForeground(session: LiveSession): Notification =
        build(session).also { plain.notifyOrSkip(session.sessionId, it) }

    override fun cancel(sessionId: String) = plain.cancel(sessionId)
}

/**
 * 构造 Android 16 `ProgressStyle` 的工具。
 *
 * 单独抽出来是为了让 [LiveNotificationFactory] 不用关心
 * 版本判断和 API 差异 —— 那边只管"要不要用"，细节都在这里。
 */
internal object LiveUpdateStyles {

    /** 胶囊文案最多 7 个字符（系统建议值，超了会被截断）。 */
    private const val CAPSULE_MAX = 7

    /**
     * 出胶囊时显示的极短文案。
     *
     * 优先级：
     * 1. 结果类 → [LiveSession.capsuleText]（如"完成"/"失败"）
     * 2. 进度类且有计数 → `2/5`
     * 3. 进度类只有百分比 → `45%`
     * 4. 都没有 → `…`
     *
     * ⚠️ 必须**很短**：状态栏胶囊空间只有几个字。超长会被系统截断
     * 成"…"，反而不如自己截断得好看。
     */
    fun capsuleText(session: LiveSession): String {
        val raw = when {
            session.category == LiveCategory.RESULT -> session.capsuleText
            session.progressLabel != null -> session.progressLabel
            session.progressPercent() != null -> "${session.progressPercent()}%"
            else -> "…"
        }
        return raw.take(CAPSULE_MAX)
    }

    /**
     * 构造进度段。
     *
     * ## 为什么用多段
     *
     * `ProgressStyle` 支持把进度条切成多段、每段不同颜色。
     * 这里用它表达**已完成 / 未完成**两部分：
     * - 已完成段：主题色（或错误色）
     * - 未完成段：半透明白
     *
     * 单段也能工作，但系统渲染出来只有一条纯色，看不出进度感。
     *
     * ## 不定进度
     *
     * 没有明确的 max（`max == 0` 或 null）时返回空段列表，
     * 由调用方走 `setProgressIndeterminate(true)`。
     */
    fun segments(session: LiveSession, activeColor: Int): List<Notification.ProgressStyle.Segment> {
        val max = session.progressMax ?: return emptyList()
        if (max <= 0) return emptyList()
        val current = (session.progressCurrent ?: 0).coerceIn(0, max)
        val remaining = max - current
        if (remaining <= 0) {
            // 已经满了，只画一段
            return listOf(Notification.ProgressStyle.Segment(max).setColor(activeColor))
        }
        return listOf(
            Notification.ProgressStyle.Segment(current).setColor(activeColor),
            Notification.ProgressStyle.Segment(remaining).setColor(UNREACHED_COLOR),
        ).filter { it != null }
    }

    /**
     * 进度头部的图标（进度条前端那个小圆点/图标）。
     */
    fun trackerIcon(context: Context): Icon? = runCatching {
        Icon.createWithResource(context, R.drawable.ic_progress_tracker)
    }.getOrNull()

    /** 未完成段的颜色：半透明白，深浅色主题下都能看见。 */
    const val UNREACHED_COLOR = 0x33FFFFFF.toInt()
}
