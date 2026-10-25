package com.march7th.hkrpg.remote

/** RemoteService.setup 返回码，两侧共用 */
object SetupResult {
    const val OK = 0

    const val ERR_CORE_NOT_LOADED = 1

    /** 提权进程读写不了 userDir（#227），换目录前重试无意义 */
    const val ERR_USER_DIR_INACCESSIBLE = 2

    const val ERR_SET_USER_DIR = 3

    fun describe(code: Int): String = when (code) {
        OK -> "OK"
        ERR_CORE_NOT_LOADED -> "CORE_NOT_LOADED"
        ERR_USER_DIR_INACCESSIBLE -> "USER_DIR_INACCESSIBLE"
        ERR_SET_USER_DIR -> "SET_USER_DIR_FAILED"
        else -> "UNKNOWN($code)"
    }
}
