package com.march7th.hkrpg.schedule

import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.march7th.hkrpg.data.repository.FakePreferencesDataStore
import com.march7th.hkrpg.schedule.data.ScheduleStrategyRepository
import com.march7th.hkrpg.schedule.model.ScheduleStrategy
import com.march7th.hkrpg.utils.JsonUtils
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class ScheduleStrategyRepositoryTest {
    private val strategy = ScheduleStrategy(id = "schedule-1", name = "Daily", profileId = "profile-1")

    @Test
    fun loadedSignalIsPublishedAfterStrategies() = runBlocking {
        val store = FakePreferencesDataStore()
        store.edit {
            it[stringPreferencesKey("strategies")] = JsonUtils.common.encodeToString(listOf(strategy))
        }
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repository = ScheduleStrategyRepository(store, scope)
            var observed: List<ScheduleStrategy>? = null
            val observer = launch(Dispatchers.Unconfined) {
                repository.isLoaded.first { it }
                observed = repository.strategies.value
            }
            assertFalse(repository.isLoaded.value)
            dispatcher.runPending()
            observer.join()
            assertEquals(listOf(strategy), observed)
        } finally {
            scope.cancel()
            dispatcher.runPending()
        }
    }

    @Test
    fun lookupSeesPersistedChangesBeforeCollectorRuns() = runBlocking {
        val store = FakePreferencesDataStore()
        val dispatcher = PausedDispatcher()
        val scope = CoroutineScope(SupervisorJob() + dispatcher)
        try {
            val repository = ScheduleStrategyRepository(store, scope)
            repository.add(strategy)
            assertFalse(repository.isLoaded.value)
            assertEquals(strategy, repository.getById(strategy.id))
            repository.setEnabled(strategy.id, false)
            assertEquals(false, repository.getById(strategy.id)?.enabled)
            repository.remove(strategy.id)
            assertNull(repository.getById(strategy.id))
        } finally {
            scope.cancel()
            dispatcher.runPending()
        }
    }

    private class PausedDispatcher : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            pending.addLast(block)
        }

        fun runPending() {
            while (pending.isNotEmpty()) pending.removeFirst().run()
        }
    }
}
