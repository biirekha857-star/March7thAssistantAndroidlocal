package com.march7th.hkrpg.constant

/**
 * 网络地址常量。
 *
 * # 分三类
 *
 * | 类别 | 谁维护 | 说明 |
 * |---|---|---|
 * | **自有站点** | 我们自己 | 公告、更新清单 —— 托管在 Cloudflare Pages |
 * | **GitHub Release** | 我们自己 | 备用更新源 |
 * | **上游资源** | 别人的 | 崩铁**不用**，留着只为兼容旧代码路径 |
 *
 * ## 自有站点的文件布局
 *
 * Cloudflare Pages 上放这些文件：
 *
 * ```
 * https://<项目名>.pages.dev/
 *   ├── announcement_zh.md   中文公告
 *   ├── announcement_en.md   英文公告
 *   └── update.json          更新清单
 * ```
 *
 * ## update.json 格式
 *
 * ```json
 * {
 *   "versionCode": 6,
 *   "versionName": "0.6.0beta",
 *   "apkUrl": "https://.../m7a-0.6.0beta.apk",
 *   "changelog": "修复虚拟屏拉伸；每日实训支持合成机",
 *   "forceUpdate": false
 * }
 * ```
 *
 * 客户端拿本机 `versionCode` 和它比，小就提示更新。
 */
object M7aApi {

    // ==================== 自有站点（Cloudflare Pages）====================

    /**
     * 自有静态站基础地址。
     *
     * ## 当前状态：**尚未部署**
     *
     * Cloudflare Pages 每个项目都免费给一个 `<项目名>.pages.dev`，
     * 不用买域名就能用。等站点建好、把公告和 `update.json` 传上去之后，
     * 这里的地址才会真正可用。
     *
     * 在那之前，公告与常见问题都会退回到 GitHub 仓库
     * （见 [FAQ_URL]），避免用户点开一片 404。
     *
     * ⚠️ **末尾必须带 `/`** —— 下面用字符串拼接。
     */
    const val SITE_BASE = "https://m7a.pages.dev/"

    /** 中文公告。 */
    const val ANNOUNCEMENT_ZH = "${SITE_BASE}announcement_zh.md"

    /** 英文公告。 */
    const val ANNOUNCEMENT_EN = "${SITE_BASE}announcement_en.md"

    /** 更新清单（见类注释里的 JSON 格式）。 */
    const val UPDATE_MANIFEST = "${SITE_BASE}update.json"

    // ==================== GitHub Release（备用更新源）====================

    /**
     * GitHub 仓库。
     *
     * Issues 与 Release 都指向这里。
     */
    const val APP_GITHUB_OWNER = "biirekha857-star"
    const val APP_GITHUB_REPO = "March7thAssistantAndroidlocal"

    /** 最新 Release（含 prerelease）。 */
    const val APP_GITHUB_RELEASES =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases?per_page=1"

    /** 最近 5 个 Release（beta 通道）。 */
    const val APP_GITHUB_RELEASES_BETA =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases?per_page=5"

    fun appGitHubReleaseByTag(tag: String): String =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases/tags/$tag"

    // ==================== 反馈 ====================

    /** 问题反馈 —— GitHub Issues。 */
    const val FEEDBACK_URL = "https://github.com/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/issues"

    /**
     * QQ 交流群（扫码加入）。 */
    const val QQ_GROUP_URL = "https://qm.qq.com/q/xG9yvDYFgc"

    /**
     * Telegram 交流群。
     *
     * 给海外用户、或者不常用 QQ 的用户一条路。
     * 这是 **invite 链接**（`t.me/+...`），点开需要已安装 Telegram。
     */
    const val TELEGRAM_GROUP_URL = "https://t.me/+1NJAK_tgLU81NmFl"

    /**
     * 常见问题 —— 暂时指向 GitHub 仓库的 README。
     *
     * 自有站点（[SITE_BASE]）还没部署，指向它会 404。
     * 等站点上线后改成 `"${SITE_BASE}faq/"` 即可。
     */
    const val FAQ_URL = "https://github.com/$APP_GITHUB_OWNER/$APP_GITHUB_REPO#readme"

    // ==================== 上游遗留（崩铁不用）====================
    //
    // 下面这些是 MAA / MAA-Meow 的地址。崩铁内核**完全不用**它们
    // （任务资源在 `assets/starrail/` 里，模板自己维护）。
    //
    // 保留常量是为了不破坏旧代码里的引用；新代码不要再用。

    @Deprecated("上游遗留，崩铁不用")
    const val MAA_API = "https://api.maa.plus/MaaAssistantArknights/api/"

    @Deprecated("上游遗留，崩铁不用")
    const val MAA_API_BACKUP = "https://api2.maa.plus/MaaAssistantArknights/api/"

    @Deprecated("上游遗留，崩铁不用")
    val API_URLS = listOf(MAA_API, MAA_API_BACKUP)

    @Deprecated("上游遗留，崩铁不用")
    const val STAGE_ACTIVITY_API = "gui/StageActivityV2.json"

    @Deprecated("上游遗留，崩铁不用")
    const val TASKS_API = "resource/tasks.json"

    @Deprecated("上游遗留，崩铁不用")
    fun getGlobalTasksApi(clientType: String): String =
        "resource/global/${clientType}/resource/tasks.json"

    @Deprecated("上游遗留，崩铁不用")
    const val MIRROR_CHYAN_BASE = "https://mirrorchyan.com/"

    @Deprecated("上游遗留，崩铁不用")
    const val MIRROR_CHYAN_RESOURCE = "https://mirrorchyan.com/api/resources/MaaResource/latest"

    @Deprecated("上游遗留，崩铁不用")
    const val GITHUB_RESOURCE =
        "https://github.com/MaaAssistantArknights/MaaResource/archive/refs/heads/main.zip"

    @Deprecated("上游遗留，崩铁不用")
    const val MIRROR_CHYAN_APP_RESOURCE = "https://mirrorchyan.com/api/resources/MAA-Meow/latest"

    @Deprecated("上游遗留，崩铁不用")
    const val YITULIU_OPERATOR_INFO = "https://backend.yituliu.cn/open-api/operator/info"

    @Deprecated("上游遗留，崩铁不用")
    const val BASE_SCHEDULING_SCHEMA =
        "https://maa.plus/docs/zh-cn/protocol/base-scheduling-schema.html"
}
