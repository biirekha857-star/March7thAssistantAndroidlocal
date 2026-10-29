package com.march7th.hkrpg.data.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.first
import timber.log.Timber

/** 独立的 DataStore，避免和 App 设置互相影响 */
private val Context.announcementDataStore: DataStore<Preferences> by preferencesDataStore(
    name = "announcement_state",
)

/**
 * 记录公告的阅读状态。
 *
 * # 为什么要记“内容指纹”而不是“已读”
 *
 * 需求是：**首次启动弹一次**，但**换了公告内容还得再弹**。
 *
 * 所以不能只存一个 `hasRead = true` —— 那样以后更新公告用户就永远看不到了。
 * 这里存**公告内容的哈希**：
 *
 * ```
 * 当前公告哈希 == 上次看过的哈希  → 不弹
 * 否则                          → 弹，并记下新哈希
 * ```
 *
 * 文案一改哈希就变，自然重新弹；只是换个措辞也不会打扰用户。
 */
class AnnouncementStateStore(private val context: Context) {

    companion object {
        private const val TAG = "AnnouncementState"
        private val KEY_LAST_SEEN = stringPreferencesKey("last_seen_hash")
    }

    /**
     * 判断这条公告是否**还没被看过**。
     *
     * @param contentHash 公告内容的哈希（调用方算，一般用 `hashCode().toString()`）
     */
    suspend fun isUnseen(contentHash: String): Boolean = runCatching {
        val last = context.announcementDataStore.data
            .first()[KEY_LAST_SEEN]
        last != contentHash
    }.getOrElse { e ->
        Timber.w(e, "$TAG: 读取已读状态失败，按未读处理")
        true
    }

    /** 标记这条公告已看过。 */
    suspend fun markSeen(contentHash: String) {
        runCatching {
            context.announcementDataStore.edit { prefs ->
                prefs[KEY_LAST_SEEN] = contentHash
            }
        }.onFailure { Timber.w(it, "$TAG: 写入已读状态失败") }
    }
}
