package com.march7th.hkrpg.overlay

import androidx.lifecycle.HasDefaultViewModelProviderFactory
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.ViewModelStoreOwner
import androidx.lifecycle.viewmodel.MutableCreationExtras
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import org.koin.core.component.KoinComponent

class OverlayViewModelOwner : ViewModelStoreOwner,
    LifecycleOwner,
    SavedStateRegistryOwner,
    HasDefaultViewModelProviderFactory,
    KoinComponent {

    private val store = ViewModelStore()
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateRegistryController = SavedStateRegistryController.create(this)

    init {
        savedStateRegistryController.performRestore(null)
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

    override val viewModelStore get() = store

    override val lifecycle get() = lifecycleRegistry

    override val savedStateRegistry get() = savedStateRegistryController.savedStateRegistry

    override val defaultViewModelProviderFactory by lazy {
        object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                return getKoin().get(modelClass.kotlin)
            }
        }
    }

    override val defaultViewModelCreationExtras get() = MutableCreationExtras()

    /**
     * 启动生命周期（当悬浮窗显示时调用）
     */
    fun start() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.RESUMED
    }

    /**
     * 停止生命周期（当悬浮窗隐藏时调用）
     */
    fun stop() {
        lifecycleRegistry.currentState = Lifecycle.State.STARTED
        lifecycleRegistry.currentState = Lifecycle.State.CREATED
    }

}
