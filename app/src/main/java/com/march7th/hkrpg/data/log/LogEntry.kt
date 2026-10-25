package com.march7th.hkrpg.data.log

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
sealed class LogEntry {
    abstract val type: String

    @Serializable
    @SerialName("header")
    data class Header(
        override val type: String = "header",
        val startTime: Long,
        val tasks: List<String>
    ) : LogEntry()

    @Serializable
    @SerialName("log")
    data class Log(
        override val type: String = "log",
        val time: Long,
        val level: String,
        val content: String
    ) : LogEntry()

    @Serializable
    @SerialName("footer")
    data class Footer(
        override val type: String = "footer",
        val endTime: Long,
        val status: String
    ) : LogEntry()
}


