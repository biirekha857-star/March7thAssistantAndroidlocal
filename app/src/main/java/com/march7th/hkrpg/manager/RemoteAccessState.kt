package com.march7th.hkrpg.manager

import com.march7th.hkrpg.domain.models.RemoteBackend

data class RemoteAccessState(
    val shizukuAvailable: Boolean = false,
    val shizukuGranted: Boolean = false,
    val rootAvailable: Boolean = false,
    val rootGranted: Boolean = false,
    val configuredBackend: RemoteBackend = RemoteBackend.SHIZUKU,
) {
    fun isAvailable(backend: RemoteBackend): Boolean {
        return when (backend) {
            RemoteBackend.SHIZUKU -> shizukuAvailable
            RemoteBackend.ROOT -> rootAvailable
        }
    }

    fun isGranted(backend: RemoteBackend): Boolean {
        return when (backend) {
            RemoteBackend.SHIZUKU -> shizukuGranted
            RemoteBackend.ROOT -> rootGranted
        }
    }
}
