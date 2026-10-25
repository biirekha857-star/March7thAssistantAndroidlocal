package com.march7th.hkrpg.data.model

import kotlinx.serialization.Serializable
import java.util.UUID

@Serializable
data class TaskProfile(
    val id: String = UUID.randomUUID().toString(),
    val name: String,
    val chain: List<TaskChainNode>
)
