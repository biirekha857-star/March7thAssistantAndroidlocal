package com.march7th.hkrpg.utils.log

import com.march7th.hkrpg.BuildConfig
import com.march7th.hkrpg.data.log.ApplicationLogWriter
import com.march7th.hkrpg.data.preferences.AppSettingsManager
import timber.log.Timber

class LogTreeHolder(
    private val writer: ApplicationLogWriter,
    private val appSettings: AppSettingsManager
) {
    fun getTrees(): Array<Timber.Tree> {
        return arrayOf(
            if (BuildConfig.DEBUG) {
                DebugTree()
            } else {
                ReleaseTree()
            },
            FileLogTree(writer, appSettings.debugMode)
        )
    }

    fun setup() {
        getTrees().forEach {
            Timber.plant(it)
        }
    }
}