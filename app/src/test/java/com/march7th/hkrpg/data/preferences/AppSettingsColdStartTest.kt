package com.march7th.hkrpg.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import com.march7th.hkrpg.data.preferences.AppSettingsManager.Companion.dataStore
import com.march7th.hkrpg.data.repository.FakePreferencesDataStore
import com.march7th.hkrpg.domain.models.AppSettingsSchema
import com.march7th.hkrpg.domain.models.CoreDataLocation
import com.march7th.hkrpg.domain.models.RemoteBackend
import com.march7th.hkrpg.domain.models.RunMode
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class AppSettingsColdStartTest {
    private val context = mockk<Context>(relaxed = true)

    @Before
    fun setUp() = mockkObject(AppSettingsManager.Companion)

    @After
    fun tearDown() = unmockkObject(AppSettingsManager.Companion)

    @Test
    fun slowReadDoesNotBlockConstructionAndReadinessPublishesAllPersistedFields() = runTest {
        val store = FakePreferencesDataStore()
        store.edit {
            it[AppSettingsSchema.wakeUnlockType] = "pin"
            it[AppSettingsSchema.wakeCredential] = "1234"
            it[AppSettingsSchema.startupBackend] = "ROOT"
            it[AppSettingsSchema.coreDataLocation] = "LOCAL_TMP"
            it[AppSettingsSchema.runMode] = "FOREGROUND"
            it[AppSettingsSchema.language] = "EN"
            it[AppSettingsSchema.themeMode] = "DARK"
            it[AppSettingsSchema.mutedGamePackage] = "game.package"
        }
        val readGate = CompletableDeferred<Unit>()
        every { context.dataStore } returns object : DataStore<Preferences> by store {
            override val data = flow {
                readGate.await()
                emitAll(store.data)
            }
        }

        val settings = AppSettingsManager(context, mockk(relaxed = true), backgroundScope)
        val ready = async { settings.awaitLoaded() }
        runCurrent()
        assertFalse(ready.isCompleted)

        readGate.complete(Unit)
        ready.await()
        assertEquals("pin", settings.wakeUnlockType.value)
        assertEquals("1234", settings.wakeCredential.value)
        assertEquals(RemoteBackend.ROOT, settings.startupBackend.value)
        assertEquals(CoreDataLocation.LOCAL_TMP, settings.coreDataLocation.value)
        assertEquals(RunMode.FOREGROUND, settings.runMode.value)
        assertEquals(AppSettingsManager.AppLanguage.EN, settings.language.value)
        assertEquals(AppSettingsManager.ThemeMode.DARK, settings.themeMode.value)
        assertEquals("game.package", settings.initialMutedGamePackage)
        assertEquals(1, store.dataCollectCount.get())

        settings.setRunMode(RunMode.BACKGROUND)
        settings.setMutedGamePackage("")
        runCurrent()
        assertEquals(RunMode.BACKGROUND, settings.runMode.value)
        assertEquals("game.package", settings.initialMutedGamePackage)
        assertEquals(1, store.dataCollectCount.get())
    }

    @Test
    fun persistedUnlockTypesAndLegacyThemeAreDecodedAfterLoading() = runTest {
        for ((stored, expected) in listOf("pin" to "pin", "gesture" to "gesture", "invalid" to "swipe")) {
            val store = FakePreferencesDataStore()
            store.edit {
                it[AppSettingsSchema.wakeUnlockType] = stored
                it[AppSettingsSchema.themeMode] = "LIGHT"
            }
            every { context.dataStore } returns store
            val settings = AppSettingsManager(context, mockk(relaxed = true), backgroundScope)
            settings.awaitLoaded()
            assertEquals(expected, settings.wakeUnlockType.value)
            assertEquals(AppSettingsManager.ThemeMode.WHITE, settings.themeMode.value)
        }
    }

    @Test
    fun readFailureIsPropagatedToReadinessWaiters() = runTest {
        val failure = IllegalStateException("settings read failed")
        val store = FakePreferencesDataStore()
        every { context.dataStore } returns object : DataStore<Preferences> by store {
            override val data = flow<Preferences> { throw failure }
        }
        val failures = mutableListOf<Throwable>()
        val scope = CoroutineScope(
            SupervisorJob() + StandardTestDispatcher(testScheduler) +
                CoroutineExceptionHandler { _, cause -> failures += cause },
        )
        try {
            val settings = AppSettingsManager(context, mockk(relaxed = true), scope)
            val result = runCatching { settings.awaitLoaded() }
            assertEquals(failure.javaClass, result.exceptionOrNull()?.javaClass)
            assertEquals(failure.message, result.exceptionOrNull()?.message)
            assertEquals(listOf(failure), failures)
        } finally {
            scope.cancel()
        }
    }
}
