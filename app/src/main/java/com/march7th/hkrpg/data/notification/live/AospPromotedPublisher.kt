package com.march7th.hkrpg.data.notification.live

import android.app.Notification
import android.content.Context
import com.march7th.hkrpg.domain.notification.LiveBackend
import com.march7th.hkrpg.domain.notification.LiveCapability
import com.march7th.hkrpg.domain.notification.LiveSession
import com.march7th.hkrpg.domain.notification.LiveUpdatePublisher

class AospPromotedPublisher(
    context: Context,
    private val factory: LiveNotificationFactory,
    promotedDetector: AospPromotedDetector,
) : LiveUpdatePublisher {

    private val plain = PlainNotificationPublisher(context, factory, promotedDetector)

    override val capability: LiveCapability
        get() = plain.capability.copy(
            backend = LiveBackend.AOSP_PROMOTED,
            promotedGranted = true,
        )

    override fun build(session: LiveSession): Notification =
        factory.build(session, requestPromoted = session.ongoing)

    override fun publish(session: LiveSession) {
        plain.notifyOrSkip(session.sessionId, build(session))
    }

    override fun publishForeground(session: LiveSession): Notification =
        build(session).also { plain.notifyOrSkip(session.sessionId, it) }

    override fun cancel(sessionId: String) = plain.cancel(sessionId)
}
