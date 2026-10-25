package com.march7th.hkrpg.manager

import com.march7th.hkrpg.domain.models.RemoteBackend

fun interface RemoteAccessStateListener {
    fun onStateChanged(backend: RemoteBackend)
}

interface RemoteAccessPermissionBackend {
    val backend: RemoteBackend

    fun isAvailable(): Boolean

    fun isGranted(): Boolean

    suspend fun requestPermission(): Boolean

    fun addStateListener(listener: RemoteAccessStateListener)

    fun removeStateListener(listener: RemoteAccessStateListener)
}
