package com.march7th.hkrpg.data.api

/**
 * 更新清单解析器。
 *
 * 站点上的更新清单有两种形态，解析结果都是 [UpdateManifest]：
 *
 * | 文件 | 谁在读 | 为什么还要留着 |
 * |---|---|---|
 * | `update.md` | 0.5.7beta 及以后 | 人写给人看的，发版时直接改这一份 |
 * | `update.json` | 0.5.7beta 以前的旧客户端 | 老版本只认 JSON，删了它们就再也收不到更新 |
 *
 * `update.json` 由 `scripts/update_manifest.py` 从 `update.md` 生成，
 * 所以日常只需要维护 md，不用两份对着改。
 *
 * # update.md 的格式
 *
 * ```markdown
 * ---
 * versionCode: 8
 * versionName: 0.5.7beta
 * apkUrl: https://.../March7thAssistant-0.5.7beta.apk
 * forceUpdate: false
 * minSupportedVersionCode: 1
 * publishedAt: 2026-10-31
 * ---
 *
 * 这里往下全是更新要点，按 Markdown 原样喂给弹窗。
 * ```
 *
 * 头部用 YAML front matter 的写法，但**只认最朴素的 `key: value`** ——
 * 不引入 YAML 解析库：字段就六个，多一个依赖不值当，而且出问题时
 * 一眼能看出是哪一行写坏了。
 *
 * # 为什么正文单独拿出来
 *
 * 正文就是 `changelog`。发版时把 Release 正文整段贴进来即可，
 * 客户端用 Markdown 渲染，不用在 JSON 里塞一堆 `\n` 转义。
 */
object UpdateManifestParser {

    /** front matter 的开闭标记。 */
    private const val FENCE = "---"

    /**
     * 解析 `update.md`。
     *
     * @return 解析失败（没 front matter、缺 `versionCode`/`versionName`、数字写错）
     *   时返回 `null`，由调用方决定是回退 JSON 还是当作"没有更新"。
     */
    fun parseMarkdown(raw: String): UpdateManifest? {
        val text = raw.removePrefix("\uFEFF")
            .replace("\r\n", "\n")
            .replace('\r', '\n')
        val lines = text.lines()

        // 允许文件开头有空行，但第一行有效内容必须是 ---
        val start = lines.indexOfFirst { it.isNotBlank() }
        if (start < 0 || lines[start].trim() != FENCE) return null

        val end = (start + 1 until lines.size).firstOrNull { lines[it].trim() == FENCE }
            ?: return null

        val meta = HashMap<String, String>()
        for (i in start + 1 until end) {
            val line = lines[i].trim()
            // 空行和注释跳过；没有冒号的行也跳过（容忍手写的说明文字）
            if (line.isEmpty() || line.startsWith("#")) continue
            val colon = line.indexOf(':')
            if (colon <= 0) continue
            val key = line.substring(0, colon).trim().lowercase()
            val value = line.substring(colon + 1).trim().removeSurrounding("\"")
            if (value.isNotEmpty()) meta[key] = value
        }

        // 这两个字段是判断"有没有更新"的唯一依据，缺了整份清单就没意义
        val versionCode = meta["versioncode"]?.toIntOrNull() ?: return null
        val versionName = meta["versionname"]?.takeIf { it.isNotBlank() } ?: return null

        return UpdateManifest(
            versionCode = versionCode,
            versionName = versionName,
            apkUrl = meta["apkurl"].orEmpty(),
            // 正文原样作为更新要点；上下的空行去掉，避免弹窗顶部留白
            changelog = lines.drop(end + 1).joinToString("\n").trim(),
            forceUpdate = meta["forceupdate"].toBooleanLoose(),
            minSupportedVersionCode = meta["minsupportedversioncode"]?.toIntOrNull() ?: 0,
            publishedAt = meta["publishedat"].orEmpty(),
        )
    }

    /**
     * 兜底把 `true/false`、`yes/no`、`1/0` 都认成布尔。
     *
     * 手写 md 时很容易写成 `True` 或者 `1`，这里宽容一点，别因为大小写
     * 把"强制更新"悄悄降级成普通更新 —— 那是最不能出错的一个开关。
     */
    private fun String?.toBooleanLoose(): Boolean = when (this?.trim()?.lowercase()) {
        "true", "yes", "y", "1", "on" -> true
        else -> false
    }
}
