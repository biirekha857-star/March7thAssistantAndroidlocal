package com.march7th.hkrpg.schedule.data

import android.content.Context
import androidx.datastore.core.CorruptionException
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.march7th.hkrpg.schedule.model.ScheduleStrategy
import com.march7th.hkrpg.utils.JsonUtils
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.retryWhen
import kotlinx.coroutines.launch
import timber.log.Timber
import java.io.IOException

class ScheduleStrategyRepository internal constructor(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    constructor(context: Context) : this(
        context.store,
        CoroutineScope(SupervisorJob() + Dispatchers.IO),
    )

    companion object {
        private val Context.store: DataStore<Preferences> by preferencesDataStore(name = "schedule_strategies")
        private val STRATEGIES_KEY = stringPreferencesKey("strategies")
    }

    private val json = JsonUtils.common

    private val _isLoaded = MutableStateFlow(false)
    val isLoaded: StateFlow<Boolean> = _isLoaded.asStateFlow()

    /** 从 DataStore 自动同步的策略列表 */
    private val _strategies = MutableStateFlow<List<ScheduleStrategy>>(emptyList())
    val strategies: StateFlow<List<ScheduleStrategy>> = _strategies.asStateFlow()

    init {
        scope.launch {
            store.data.retryWhen { cause, _ ->
                // 文件损坏每次读都失败，重试只会刷日志
                if (cause !is IOException || cause is CorruptionException) return@retryWhen false
                Timber.w(cause, "读取调度策略失败，稍后重试")
                delay(1_000L)
                true
            }.collect { prefs ->
                // 先发布列表，再通知等待加载的调用方
                _strategies.value = decodeStrategies(prefs[STRATEGIES_KEY])
                _isLoaded.value = true
            }
        }
    }

    /** 调度读持久化快照，避免落盘后 StateFlow 尚未更新 */
    suspend fun getAll(): List<ScheduleStrategy> =
        decodeStrategies(store.data.first()[STRATEGIES_KEY])

    // ---- 策略 CRUD ----

    suspend fun add(strategy: ScheduleStrategy) {
        store.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            current.add(strategy)
            prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
            Timber.d("添加调度策略: %s (%s)", strategy.name, strategy.id)
        }
    }

    suspend fun update(strategy: ScheduleStrategy) {
        store.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            val idx = current.indexOfFirst { it.id == strategy.id }
            if (idx >= 0) {
                current[idx] = strategy
                prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
                Timber.d("更新调度策略: %s (%s)", strategy.name, strategy.id)
            }
        }
    }

    suspend fun remove(strategyId: String) {
        store.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            if (current.removeAll { it.id == strategyId }) {
                prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
                Timber.d("删除调度策略: %s", strategyId)
            }
        }
    }

    suspend fun setEnabled(strategyId: String, enabled: Boolean) {
        store.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            val idx = current.indexOfFirst { it.id == strategyId }
            if (idx >= 0) {
                current[idx] = current[idx].copy(enabled = enabled)
                prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
                Timber.d("策略 %s 启用状态 -> %s", strategyId, enabled)
            }
        }
    }

    suspend fun getById(strategyId: String): ScheduleStrategy? {
        return getAll().find { it.id == strategyId }
    }

    suspend fun recordExecutionResult(
        strategyId: String,
        result: com.march7th.hkrpg.schedule.model.ExecutionResult,
        message: String? = null,
        executedAt: Long = System.currentTimeMillis(),
    ) {
        store.edit { prefs ->
            val current = decodeStrategies(prefs[STRATEGIES_KEY]).toMutableList()
            val idx = current.indexOfFirst { it.id == strategyId }
            if (idx < 0) {
                return@edit
            }

            current[idx] = current[idx].copy(
                lastExecutedAt = executedAt,
                lastResult = result,
                lastResultMessage = message,
            )
            prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(current)
            Timber.d("记录调度结果: %s -> %s (%s)", strategyId, result, message)
        }
    }


    suspend fun importStrategies(strategies: List<ScheduleStrategy>) {
        store.edit { prefs ->
            prefs[STRATEGIES_KEY] = json.encodeToString<List<ScheduleStrategy>>(strategies)
            Timber.d("导入 %d 条调度策略", strategies.size)
        }
    }

    private fun decodeStrategies(raw: String?): List<ScheduleStrategy> {
        if (raw.isNullOrEmpty()) return emptyList()
        return runCatching {
            json.decodeFromString<List<ScheduleStrategy>>(raw)
        }.getOrElse {
            Timber.w(it, "解析调度策略失败，返回空列表")
            emptyList()
        }
    }
}
