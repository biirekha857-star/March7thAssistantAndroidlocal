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
 *   ├── codes.md             兑换码清单（手工维护）
 *   ├── update.md            更新清单（主源，人写的）
 *   ├── update.json          更新清单（旧客户端兼容，由脚本生成）
 *   └── taskpacks/
 *       ├── index.json       在线任务包目录（**必须签名**）
 *       ├── index.json.sig   目录签名（Base64）
 *       └── *.m7atask        任务包本体
 * ```
 *
 * 除 APK 之外都是小文件。APK 走 GitHub Release —— Pages 单文件上限 25MB，
 * 放不下安装包。
 *
 * ## update.md 格式
 *
 * ```markdown
 * ---
 * versionCode: 6
 * versionName: 0.6.0beta
 * apkUrl: https://.../m7a-0.6.0beta.apk
 * forceUpdate: false
 * minSupportedVersionCode: 1
 * publishedAt: 2026-10-28
 * ---
 *
 * 修复虚拟屏拉伸；每日实训支持合成机
 * ```
 *
 * 客户端拿本机 `versionCode` 和它比，小就提示更新；正文就是弹窗里的更新要点。
 * `update.json` 是同一个内容的老格式，留给 0.5.7beta 以前的客户端，
 * 由 `scripts/update_manifest.py` 从 md 生成，不要手改。
 */
object M7aApi {

    // ==================== 自有站点（Cloudflare Pages）====================

    /**
     * 自有静态站基础地址。
     *
     * 托管在 **Cloudflare Pages** —— 纯静态，没有后端、没有数据库、没有接口。
     * 站上就是几个文件：公告 md、更新清单 md（外加给旧客户端用的 json）。
     * 客户端拿到的都是一份文本，直接 `GET` 下来解析，改内容只要重新部署一次。
     *
     * 也因此**不能**靠服务端做逻辑（比如"按地区下发不同版本"），
     * 所有判断都在客户端做 —— 这也正是清单里要带 `forceUpdate` 的原因。
     *
     * ⚠️ **末尾必须带 `/`** —— 下面用字符串拼接。
     */
    const val SITE_BASE = "https://m7a.pages.dev/"

    /**
     * Shizuku 的安装教程（B 站视频）。
     *
     * 提权方式页和「设置 → 启动与运行」都指向这里 —— Shizuku 是这套方案里
     * 唯一需要用户自己去装、去启动的东西，光写个名字很多人不知道下一步做什么。
     */
    const val SHIZUKU_TUTORIAL = "https://www.bilibili.com/video/BV1UrKazKEkC/" +
        "?share_source=copy_web&vd_source=52cd6c0808394ce4393953d29d048fe9"

    /** 中文公告。 */
    const val ANNOUNCEMENT_ZH = "${SITE_BASE}announcement_zh.md"

    /** 英文公告。 */
    const val ANNOUNCEMENT_EN = "${SITE_BASE}announcement_en.md"

    /**
     * 更新清单（主源）。
     *
     * 格式见 [M7aApi] 类注释里的 `update.md` 示例。和公告一样是静态文件，
     * 发版时改这一份、重新部署即可，客户端不需要任何接口。
     */
    const val UPDATE_MANIFEST_MD = "${SITE_BASE}update.md"

    /**
     * 更新清单（JSON，回退用）。
     *
     * **只给 0.5.7beta 以前的客户端看** —— 那些版本只会解析 JSON。
     * 内容由 `scripts/update_manifest.py` 从 [UPDATE_MANIFEST_MD] 生成，
     * 日常不要手改，否则两份会打架。
     */
    const val UPDATE_MANIFEST = "${SITE_BASE}update.json"

    /**
     * 兑换码清单。
     *
     * 和公告、更新清单一样是站点根目录的一个静态 md：
     *
     * ```markdown
     * ## 3.8 前瞻直播 · 2026-11-15
     * - ABC123DEF456
     * - XYZ789UVW012
     *
     * ## 版本福利 · 长期
     * - WELCOME7TH
     * ```
     *
     * `##` 标题里 `·` 前面是分组名、后面是这批码的截止日期；过期的客户端
     * 自己跳过。没有日期或写「长期」就是永久码，标题只有日期则分组显示为
     * 「限时兑换码」。完整格式见 `docs/CODES.md`。
     *
     * 为什么放在静态站而不是游戏里读：兑换码是运营侧随时追加的，
     * 客户端不可能内置一份还能保持新鲜。改这个文件、重新部署一次站点，
     * 所有用户的「一键兑换」下次执行前就能看到新码。
     */
    const val REDEEM_CODES = "${SITE_BASE}codes.md"

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

    /**
     * Release 页面（给人看的）。
     *
     * 强制更新但拿不到 `update.json` 时用它兜底 —— 至少能把人送到能下载的地方。
     */
    const val APP_GITHUB_RELEASES_LATEST =
        "https://github.com/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases/latest"

    /** 最近 5 个 Release（beta 通道）。 */
    const val APP_GITHUB_RELEASES_BETA =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases?per_page=5"

    fun appGitHubReleaseByTag(tag: String): String =
        "https://api.github.com/repos/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/releases/tags/$tag"

    // ==================== 在线任务包（远程分发）====================
    //
    // 和兑换码一样的路子：站点上放两个文件，客户端拉下来解析。区别只有一个 ——
    // **任务包是会被执行的指令**，不是给人看的文本。索引被篡改就等于远程接管
    // 所有用户的手机，所以索引必须验签。
    //
    // 发布流程（改完索引后必须跑签名，否则客户端一律拒绝）：
    //
    // ```bash
    // python scripts/sign_taskpack_index.py
    // ```
    //
    // 私钥在开发者机器上（`m7a-keystore/taskpack-signing-key.pem`），**不进仓库**。
    // 公钥硬编码在下面 [TASKPACK_INDEX_PUBKEY]，换密钥必须发新版 App。

    /**
     * 在线任务包索引（JSON）。
     *
     * 结构见 `docs/task-file.md` 第 6 节。里面列的是「有哪些包、每个包多大、
     * 指纹是多少、从哪下载」——**不含包本体**，所以几 KB，改完部署就行。
     */
    const val TASKPACK_INDEX = "${SITE_BASE}taskpacks/index.json"

    /** 索引签名（Base64 的 ECDSA P-256 / SHA-256 签名，对 [TASKPACK_INDEX] 原始字节签名）。 */
    const val TASKPACK_INDEX_SIG = "${SITE_BASE}taskpacks/index.json.sig"

    /**
     * 备用索引源 —— 仓库里的同一份文件。
     *
     * Cloudflare 站点挂掉（或被停）时切这里。注意 `raw.githubusercontent.com`
     * 在国内经常连不上，所以它只是**兜底**，不能当主源。
     */
    const val TASKPACK_INDEX_FALLBACK =
        "https://raw.githubusercontent.com/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/main/site/taskpacks/index.json"

    /** 备用索引的签名。 */
    const val TASKPACK_INDEX_SIG_FALLBACK =
        "https://raw.githubusercontent.com/$APP_GITHUB_OWNER/$APP_GITHUB_REPO/main/site/taskpacks/index.json.sig"

    /**
     * 索引验签公钥（X.509 SubjectPublicKeyInfo 的 Base64）。
     *
     * **是多把而不是一把。** ECDSA 私钥一旦丢失只能换新的，可已经发出去的
     * 老版本 App 只认它内置的那一把。把新旧并列放在这里，新版 App 就能同时
     * 验过「旧密钥签的存量索引」和「新密钥签的未来索引」 —— 换钥匙不会
     * 让包商店整体瘫痪。
     *
     * 顺序无所谓，验签是逐把试。新钥匙往后加即可。
     */
    val TASKPACK_INDEX_PUBKEYS: List<String> = listOf(
        // 旧密钥（私钥于 2026-11-07 丢失）。存量 index.json 仍由它签名。
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEBV7DxGojv+uWib45GMhSNH2jF+7gOscw+Djf4lnJQpbI8IUVixpoWSmxM/jC5rpYQsYmGFzWE02/uBXJSugVyA==",
        // 新密钥，2026-11-07 重签时启用。
        "MFkwEwYHKoZIzj0CAQYIKoZIzj0DAQcDQgAEcFL/MdxPOHfFKoe3tQVtUMxRbtCoV9x8vr41I2FEj8EM+Eqyo9hDpJk9vkMM+9yt9/29L0hxIuuq4eAf1Glzlg==",
    )

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
