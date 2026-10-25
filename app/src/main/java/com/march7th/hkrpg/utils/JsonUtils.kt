package com.march7th.hkrpg.utils

import kotlinx.serialization.json.Json

object JsonUtils {
    val common = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        isLenient = true
        decodeEnumsCaseInsensitive = true
        allowTrailingComma = true
        allowComments = true
    }
}