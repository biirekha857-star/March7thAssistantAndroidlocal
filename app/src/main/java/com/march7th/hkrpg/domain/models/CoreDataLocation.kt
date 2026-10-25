package com.march7th.hkrpg.domain.models

/** MaaCore 数据目录：默认读 App 的 Android/data，部分 Android 11 ROM 下 shell 访问不了它（#227）时切到 /data/local/tmp，见 CoreDataDir */
enum class CoreDataLocation {
    APP_DIR,
    LOCAL_TMP;

    companion object {
        fun parse(raw: String?): CoreDataLocation = entries.firstOrNull { it.name == raw } ?: APP_DIR
    }
}
