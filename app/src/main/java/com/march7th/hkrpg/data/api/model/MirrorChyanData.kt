package com.march7th.hkrpg.data.api.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable


@Serializable
data class MirrorChyanData(
    @SerialName("version_name")
    val versionName: String,
    @SerialName("url")
    val url: String? = null,
    @SerialName("release_note")
    val releaseNote: String? = null
)
