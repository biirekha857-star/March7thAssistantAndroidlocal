package com.march7th.hkrpg.utils.i18n

open class LocalizedException(val uiText: UiText, cause: Throwable? = null) : Exception(cause)
