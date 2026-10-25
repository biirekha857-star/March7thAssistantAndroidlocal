package com.march7th.hkrpg.constant

object M7aFiles {
    const val MAA = "Maa"
    const val RESOURCE = "resource"
    const val CACHE = "cache"
    const val DEBUG = "debug"
    const val SCREENSHOTS = "screenshots"

    /** 独立数据目录模式下，提权进程 debug/ 在日志包里的目录名 */
    const val EXPORT_REMOTE_DIR = "remote"

    /** 最近一次热更包留档（根目录下），独立目录模式下投递给提权进程 */
    const val LAST_RESOURCE_UPDATE_ZIP = "last_resource_update.zip"
    const val ASSET_DIR_NAME = "M7aSync/M7aResource"
    const val VERSION_FILE = "version.json"
    const val APP_VERSION_FILE = ".version"
    const val ASSET_VERSION_FILE = "$ASSET_DIR_NAME/$VERSION_FILE"

    /** Android 特化覆盖目录名 */
    const val OVERRIDES = "overrides"

    /** overrides 内置模板在 assets 中的路径 */
    const val OVERRIDES_ASSET_TASKS = "overrides/resource/tasks/tasks.json"
}