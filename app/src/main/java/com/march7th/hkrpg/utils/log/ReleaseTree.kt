package com.march7th.hkrpg.utils.log

import android.annotation.SuppressLint
import android.util.Log
import timber.log.Timber

class ReleaseTree : Timber.Tree() {

    override fun isLoggable(tag: String?, priority: Int): Boolean {
        return priority >= Log.WARN
    }

    @SuppressLint("LogNotTimber")
    override fun log(priority: Int, tag: String?, message: String, t: Throwable?) {
        if (!isLoggable(tag, priority)) return
        when (priority) {
            Log.WARN -> Log.w(tag, message, t)
            Log.ERROR -> Log.e(tag, message, t)
            Log.ASSERT -> Log.wtf(tag, message, t)
        }
    }
}
