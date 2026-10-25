package com.march7th.hkrpg.data.model

import kotlinx.serialization.Serializable


@Serializable
data class AssetManifest(val files: List<String>)
