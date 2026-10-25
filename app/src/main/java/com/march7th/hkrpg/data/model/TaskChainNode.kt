package com.march7th.hkrpg.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TaskChainNode(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val enabled: Boolean = true,
    val order: Int = 0,
    val config: TaskParamProvider
)
