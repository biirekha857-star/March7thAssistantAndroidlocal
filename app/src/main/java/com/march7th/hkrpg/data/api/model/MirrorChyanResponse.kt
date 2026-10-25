package com.march7th.hkrpg.data.api.model

import kotlinx.serialization.Serializable

@Serializable
data class MirrorChyanResponse(
    val code: Int,
    val msg: String? = null,
    val data: MirrorChyanData? = null
) {
    companion object {
        val UNKNOWN_ERR = MirrorChyanResponse(-1)
    }
}