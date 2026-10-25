package com.march7th.hkrpg.data.notification.live

import android.app.Notification
import android.content.Context
import androidx.core.app.NotificationManagerCompat
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import com.march7th.hkrpg.domain.notification.LiveBackend
import com.march7th.hkrpg.domain.notification.LiveCapability
import com.march7th.hkrpg.domain.notification.LiveSession
import com.march7th.hkrpg.domain.notification.LiveUpdatePublisher
import timber.log.Timber

/**
 * 决定这次通知走哪条"实时活动"通道。
 *
 * # 选择顺序（从上到下，命中即止）
 *
 * | 顺序 | 后端 | 条件 | 典型设备 |
 * |---|---|---|---|
 * | 1 | `HYPER_OS_FOCUS` | 小米焦点通知可用 + 用户没关旁路 | 小米/红米 HyperOS |
 * | 2 | `AOSP_LIVE_UPDATE` | **API 36+** | 原生 Android 16、**ColorOS 16 流体云** |
 * | 3 | `AOSP_PROMOTED` | promoted ongoing 已授权 | 三星 One UI 8.5+ |
 * | 4 | `PLAIN` | 以上都不满足 | ColorOS 15、旧系统 |
 *
 * # 为什么 AOSP_LIVE_UPDATE 排在 HYPER_OS 之后
 *
 * 小米 HyperOS 有自己的焦点协议，体验比通用 Live Updates 更贴合
 * 小米的灵动岛（能控制浮出动画、AOD 文案等）。所以在小米设备上
 * 优先用私有协议，**只有在它不可用时**才退回标准 API。
 *
 * # ColorOS 16 的流体云靠什么
 *
 * 不靠 OPPO 私有接口，而是第 2 档的 **Android 16 标准 API**。
 * OPPO 官方明确说 ColorOS 16 流体云完整接入 Live Updates，
 * "遵循谷歌实时活动 API 开发规范就能直接适配"。
 *
 * 换句话说：**这台设备升级到 ColorOS 16 后，无需改代码自动生效。**
 */
class LivePublisherRouter(
    context: Context,
    factory: LiveNotificationFactory,
    sequenceStore: FocusSequenceStore,
    xmsfGate: XmsfNetworkGate,
    private val appSettings: AppSettingsManager,
    private val hyperDetector: HyperOsFocusDetector,
    private val promotedDetector: AospPromotedDetector,
    private val liveUpdateDetector: AospLiveUpdateDetector,
) : LiveUpdatePublisher {

    private val appContext = context.applicationContext
    private val hyper = HyperOsFocusPublisher(
        appContext, factory, sequenceStore, xmsfGate, appSettings, promotedDetector,
    )
    private val liveUpdate = AospLiveUpdatePublisher(appContext, factory, promotedDetector)
    private val aosp = AospPromotedPublisher(appContext, factory, promotedDetector)
    private val plain = PlainNotificationPublisher(appContext, factory, promotedDetector)

    override val capability: LiveCapability
        get() = snapshot()

    override fun build(session: LiveSession): Notification = current().build(session)

    override fun publish(session: LiveSession) = current().publish(session)

    override fun prepareProgress() = current().prepareProgress()

    override fun publishForeground(session: LiveSession): Notification =
        current().publishForeground(session)

    override fun cancel(sessionId: String) {
        hyper.cancel(sessionId)
        liveUpdate.cancel(sessionId)
        aosp.cancel(sessionId)
        plain.cancel(sessionId)
    }

    override fun refreshCapability(): LiveCapability {
        hyperDetector.invalidate()
        return snapshot()
    }

    fun snapshot(): LiveCapability {
        val post = NotificationManagerCompat.from(appContext).areNotificationsEnabled()
        val focusLikely = hyperDetector.isLikelyDevice()
        val focusGranted = hyperDetector.hasFocusPermission()
        val promoted = promotedDetector.isGranted()
        val liveOk = liveUpdateDetector.isAvailable()

        // 关掉旁路后岛会被云端鉴权摘掉，继续发焦点负载只是白构建，直接退到下一档
        val backend = when {
            hyperDetector.isAvailable() && appSettings.liveIslandXmsfBypass.value ->
                LiveBackend.HYPER_OS_FOCUS

            liveOk -> LiveBackend.AOSP_LIVE_UPDATE

            promoted -> LiveBackend.AOSP_PROMOTED
            else -> LiveBackend.PLAIN
        }

        Timber.d(
            "LiveBackend=$backend (hyperFocus=$focusGranted liveUpdate=$liveOk " +
                "promoted=$promoted colorOs=${liveUpdateDetector.isColorOs()} " +
                "ver=${liveUpdateDetector.colorOsVersion()})",
        )

        return LiveCapability(
            backend = backend,
            postNotifications = post,
            promotedAvailable = promotedDetector.isApiSupported(),
            promotedGranted = promoted,
            focusLikely = focusLikely,
            focusGranted = focusGranted,
            liveUpdatesAvailable = liveOk,
            isColorOs = liveUpdateDetector.isColorOs(),
            colorOsVersion = liveUpdateDetector.colorOsVersion(),
        )
    }

    private fun current(): LiveUpdatePublisher = when (snapshot().backend) {
        LiveBackend.HYPER_OS_FOCUS -> hyper
        LiveBackend.AOSP_LIVE_UPDATE -> liveUpdate
        LiveBackend.AOSP_PROMOTED -> aosp
        LiveBackend.PLAIN -> plain
    }

    /** 设置页展示用：当前这台设备的实时活动能力说明。 */
    fun statusHint(): String = liveUpdateDetector.statusHint()
}
