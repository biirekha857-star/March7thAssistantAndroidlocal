package com.march7th.hkrpg.service

import android.accessibilityservice.AccessibilityService
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import timber.log.Timber
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

class AccessibilityHelperService : AccessibilityService() {

    companion object {
        /** 同时按下两个键的时间容差 (毫秒) */
        private const val SIMULTANEOUS_PRESS_THRESHOLD = 300L
        const val SERVICE_ID = "com.march7th.hkrpg/.service.AccessibilityHelperService"

        val onVolumeUpDownPressed = AtomicReference<(() -> Unit)>()

        private val _isConnected = MutableStateFlow(false)
        val isConnected: StateFlow<Boolean> = _isConnected.asStateFlow()
    }

    private var volumeUpPressTime = 0L

    private var volumeDownPressTime = 0L

    private var triggered = false

    override fun onServiceConnected() {
        super.onServiceConnected()
        _isConnected.value = true
        Timber.d("AccessibilityHelperService connected")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
    }

    override fun onInterrupt() {
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        if (onVolumeUpDownPressed.get() == null) {
            return super.onKeyEvent(event)
        }

        when (event.keyCode) {
            KeyEvent.KEYCODE_VOLUME_UP -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    volumeUpPressTime = System.currentTimeMillis()
                    if (checkSimultaneousPress()) {
                        return true
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    volumeUpPressTime = 0L
                    triggered = false
                }
            }

            KeyEvent.KEYCODE_VOLUME_DOWN -> {
                if (event.action == KeyEvent.ACTION_DOWN) {
                    volumeDownPressTime = System.currentTimeMillis()
                    if (checkSimultaneousPress()) {
                        return true
                    }
                } else if (event.action == KeyEvent.ACTION_UP) {
                    volumeDownPressTime = 0L
                    triggered = false
                }
            }
        }
        return super.onKeyEvent(event)
    }

    private fun checkSimultaneousPress(): Boolean {
        if (triggered) return true

        if (volumeUpPressTime > 0 && volumeDownPressTime > 0) {
            val timeDiff = abs(volumeUpPressTime - volumeDownPressTime)
            if (timeDiff < SIMULTANEOUS_PRESS_THRESHOLD) {
                Timber.d("Volume up + down pressed simultaneously")
                triggered = true
                onVolumeUpDownPressed.get()?.invoke()
                return true
            }
        }
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        _isConnected.value = false
        Timber.d("AccessibilityHelperService destroyed")
    }
}
