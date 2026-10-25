package com.march7th.hkrpg.utils.log

import timber.log.Timber

class DebugTree : Timber.DebugTree() {

    override fun createStackElementTag(element: StackTraceElement): String {
        val className = element.className
        val tag = when {
            className.endsWith("Kt") -> className.substringAfterLast('.').dropLast(2)
            className.contains('$') -> className.substringAfterLast('.').substringBefore('$')
            else -> className.substringAfterLast('.')
        }

        return if (tag.length <= MAX_TAG_LENGTH) tag else tag.take(MAX_TAG_LENGTH)
    }

    companion object {
        private const val MAX_TAG_LENGTH = 23
    }
}
