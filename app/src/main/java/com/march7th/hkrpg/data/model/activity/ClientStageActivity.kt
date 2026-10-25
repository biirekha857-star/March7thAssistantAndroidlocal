package com.march7th.hkrpg.data.model.activity

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * 客户端活动数据 (Official / YoStarEN / YoStarJP / YoStarKR / txwy 共用同一结构)
 */
@Serializable
data class ClientStageActivity(
    @SerialName("sideStoryStage")
    val sideStoryStage: Map<String, SideStoryStageEntry>? = null,

    @SerialName("resourceCollection")
    val resourceCollection: ResourceCollectionInfo? = null,

    @SerialName("miniGame")
    val miniGame: List<MiniGameEntry>? = null
)