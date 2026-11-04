package com.march7th.hkrpg.starrail.engine

import android.os.SystemClock
import com.march7th.hkrpg.third.Ln

/**
 * 界面导航 —— 崩铁手游版。
 *
 * ## 与 PC 版的根本差异（决定实现方式）
 *
 * March7thAssistant 用 `screens.json`（56 界面 / 110 边）+ BFS 找最短路，
 * 每个"返回上一级"动作都是 `press_key('esc')`（48 处）。
 * **手游没有 ESC 键**，所以那套图在手机上有近一半的边不可用。
 *
 * 本实现改为**显式的路径表**：把已验证的界面跳转写成代码，
 * 每步都能带自己的失败重试与校验。好处是可读、可调试、不依赖
 * PC 端素材的手游适配程度；代价是新增界面要改代码（可接受）。
 *
 * ## 坐标基准
 * 所有 `NormRect` 都是**归一化**（相对截图宽高的 0~1），
 * 不写死像素 —— 这样虚拟屏换分辨率也不用改。
 */
class ScreenNavigator(
    private val auto: ShellAutomation,
) {

    companion object {
        private const val TAG = "ScreenNavigator"

        /** 界面识别默认阈值。手游缩放插值 + 动态背景，比 PC 端略低。 */
        const val SCREEN_THRESHOLD = 0.80

        /** 元素查找默认阈值（对齐 M7A 的 0.8）。 */
        const val ELEMENT_THRESHOLD = 0.80

        /** 转场后等待稳定的时间。 */
        private const val SETTLE_MS = 800L

        /**
         * 手机菜单展开动画的等待参数。
         *
         * ## 为什么单独给菜单一组更长的等待
         *
         * 菜单是**从左侧滑出 + 面板渐显**的动画，不是瞬时出现。
         * 原来统一用 [SETTLE_MS]（800ms）就截图判定，实测抓到的是
         * "背景已经变了、面板还没画出来"的中间帧 ——
         * 于是 `isAt(MENU)` 误判为 false，后续点击就落到了别处。
         *
         * 3 次 × 900ms ≈ 2.7s，足够动画走完；期间只"检查"不"点击"。
         */
        private const val MENU_SETTLE_MS = 1_200L

    /** 打开手机菜单的最长等待（轮询）。 */
    /** 手机菜单右侧面板的搜索区（OCR 只跑这块）。 */
    /**
     * 左上角手机图标的搜索区。
     *
     * 手机图标固定在大厅左上角（实测中心约 (53,100)），
     * 限定这一小块能把"打开手机菜单"从 6 秒降到几十毫秒。
     */
    /** 菜单右侧竖栏的「电源键」图标（菜单独有的锚点）。 */
    private const val MENU_POWER_TEMPLATE = "screen/menu.png"

    /** 电源键图标的搜索区（右侧竖栏底部）。 */
    private val CROP_MENU_POWER = NormRect(0.93f, 0.86f, 0.07f, 0.12f)

    private val CROP_MENU_ICON = NormRect(0.00f, 0.04f, 0.12f, 0.14f)

    private val MENU_PANEL_REGION = NormRect(0.58f, 0.30f, 0.42f, 0.70f)

    /**
     * 右上角「✕」的搜索区。
     *
     * 崩铁各子界面的关闭按钮都在右上角一角，实测中心约 (0.96, 0.06)。
     * 以前 `closeCurrent` 直接拿两个模板**全屏**多尺度匹配，一次几百毫秒
     * 到 1 秒；限定到这一小块后只要几十毫秒。
     */
    private val CROP_CLOSE_CORNER = NormRect(0.88f, 0.00f, 0.12f, 0.14f)

    /** 关闭按钮的候选模板（通用关闭、手机界面关闭、指南页的 ✕）。 */
    private val CLOSE_TEMPLATES = listOf(
        "zh_CN/base/click_close.png",
        "share/plot/close_phone.png",
        // 「星际和平指南」右上角那个 ✕：四段斜线 + 中心圆点。
        //
        // 它和上面两个模板**不是同一个图形**，所以 11-03 的日志里明明画面上
        // 就有 ✕，`closeCurrent` 却一路报"未见可点的 ✕"→ 点左上返回（指南页
        // 左侧没有返回）→ 认输。实测这个模板在指南页上 0.874，稳稳过 0.80。
        "screen/close_x.png",
    )

    /**
     * 「指南子标签 → 第几格」的实测结果，进程内记住。
     *
     * 标签栏靠写死的坐标点，一旦游戏改了布局（11-03 就在最左边加了
     * 「新手教程」那格，全部右移一位），写死的 index 就整体错位，
     * 点下去画面纹丝不动。所以位置必须实测，且结果要缓存 ——
     * 一次任务里会反复切标签，不能每次都试六格。
     */
    private val guideTabSlot = java.util.concurrent.ConcurrentHashMap<GuideTab, Int>()

    /** 等大厅时重新判态的间隔。 */
    private const val MAIN_POLL_MS = 250L

    /** 点完设置齿轮后，等设置页渲染完成。 */
    private const val SETTINGS_LOAD_MS = 1_200L

    /** 点完手机菜单右上角的「…」后，等展开面板画出条目。 */
    private const val PANEL_OPEN_MS = 500L

    /** 等兑换码弹窗出现的总时长（含淡入）。 */
    private const val REDEEM_DIALOG_TIMEOUT_MS = 2_400L

    /** 等兑换码弹窗时的轮询间隔。 */
    private const val REDEEM_DIALOG_POLL_MS = 300L

    /**
     * 大厅锚点：**右上角的角色头像**。
     *
     * 为什么用头像而不是左上角的手机图标：手机图标只在大厅可见，
     * 但它本身就是「要点的那个东西」—— 用它判断"在不在大厅"，
     * 会出现「图标被弹窗挡住 → 判定不在大厅 → 去关界面 → 越关越远」。
     * 头像在所有大厅子界面里位置固定、样式不变，且月卡/公告弹窗
     * 盖住它的时候就是**真的不在可操作大厅**，正好该先关弹窗。
     *
     * 区间与 `StarRailTasks.isMainUi` 保持一致（同一张模板、同一个区）。
     */
    private val CROP_MAIN_AVATAR = NormRect(0.90f, 0.00f, 0.10f, 0.12f)

    /** 角色头像模板的匹配阈值（与大厅判定同值，宁严勿松）。 */
    private const val MAIN_AVATAR_THRESHOLD = 0.85

    /** 关掉界面后等它退回大厅的时间。 */
    private const val BACK_TO_LOBBY_POLL_MS = 400L

    /**
     * 手机菜单右上角「…」展开后的面板范围。
     *
     * 只框**右半屏**：条目（漫游签证 / 手机装饰 / 开拓穿搭 / 兑换码）
     * 都落在这一侧，而左侧立绘和底部按钮上不会有「兑换码」这三个字。
     * 限定范围能把一次全屏 OCR（1920×1080 约 1 秒）缩到 0.4 秒左右，
     * 而兑换码任务每成功一次都要重新进一遍入口，这个开销会被放大 N 倍。
     *
     * 万一版本改动把面板挪到中间，[openRedeemEntry] 还有一次全屏兜底。
     */
    private val CROP_MENU_PANEL = NormRect(0.62f, 0.04f, 0.38f, 0.82f)

    /**
     * 兑换码弹窗里「输入框 + 按钮」那一条横带。
     *
     * 用来判断"弹窗到底开没开"：只看这一条，避免把手机菜单里的
     * 其他文字（商店、好友…）读进来。
     */
    private val CROP_REDEEM_DIALOG = NormRect(0.14f, 0.36f, 0.72f, 0.34f)

    /**
     * 兑换码弹窗里**可能出现结果文案**的范围。
     *
     * 两种结果都在这一带：
     * - 失败的红横幅：紧贴输入框下方（y≈0.53）；
     * - 成功的独立弹窗：「兑换提示 / 兑换成功 / 请前往邮箱领取奖励」
     *   居中在屏幕中部（y≈0.35~0.65）。
     *
     * 比全屏小一半，OCR 快一倍，也把背景（手机菜单的商店/好友/委托、
     * 大厅的跃迁/星琼）挡在外面 —— 那些文字里带「成功」「失败」的话，
     * 全屏扫描会直接判错。
     */
    private val CROP_REDEEM_RESULT = NormRect(0.08f, 0.22f, 0.84f, 0.52f)

    /**
     * 游戏自带「提示：请求从剪切板粘贴」弹窗的范围。
     *
     * 弹窗本体实测在 1080×608 帧上占 (200,178)-(880,440)，换成比例约
     * x 0.19~0.81 / y 0.29~0.72；上下各留一点余量，把标题和底部按钮
     * （「不粘贴」/「粘贴」，y≈0.653）都框进来。
     */
    private val CROP_PASTE_PROMPT = NormRect(0.08f, 0.20f, 0.84f, 0.62f)

    /** 设置页左侧竖排分类栏（「账户」「音画」「其他」…）。 */
    private val CROP_SETTINGS_TABS = NormRect(0.00f, 0.08f, 0.32f, 0.84f)

    /** 菜单网格里的关键词，命中 2 个即判定在菜单。 */
    private val MENU_KEYWORDS = listOf(
        "商店", "好友", "委托", "旅情事记", "合成", "成就",
        "短信", "无名勋礼", "跃迁", "角色", "指南", "联机玩法",
        "导航", "教学目录",
    )

    /**
     * 弹窗 OCR 搜索区（屏幕正中偏上）。
     *
     * 崩铁的确认弹窗都居中，宽度约占 40%~50%、高度约 30%，
     * 这里取一个稍大的包围盒，留出动画过程中的位移余量。
     */
    private val POPUP_REGION = NormRect(0.22f, 0.24f, 0.56f, 0.50f)

    /**
     * 危险弹窗的特征文字。
     *
     * 命中任一 ⇒ 这是「要不要退出游戏」的确认框。
     * ⚠️ 这类框**绝不能点确定**，只能点取消 —— 点错就是游戏进程被关，
     * 整条任务链报废，而且虚拟屏里的游戏要重新冷启动（几分钟）。
     */
    private val EXIT_CONFIRM_KEYWORDS = listOf(
        "退出游戏", "是否退出", "离开游戏", "确定要退出", "退出当前账号",
    )

    /** 上一次弹窗探测的时间戳（节流用）。 */
    private var lastPopupProbeAt = 0L

    /**
     * 只在大厅出现的文案。
     *
     * 用来把「主界面」和「星际和平指南-生存索引」区分开：
     * 两个界面都可能出现「生存索引」四个字（任务追踪栏 vs 副本列表），
     * 但大厅一定还挂着跃迁/星琼/开拓等级这些常驻元素。
     */
    private val MAIN_ONLY_WORDS = listOf("跃迁", "星琼", "开拓等级", "星轨通票", "专票")

    private const val MENU_OPEN_TIMEOUT_MS = 2_000L

    /** 菜单轮询间隔。 */
    private const val MENU_POLL_MS = 150L

    /** 点手机图标后仍没打开菜单时，最多再试几次。 */
    private const val MENU_OPEN_RETRIES = 2

    /** 两次点击之间的间隔 —— 云游戏的输入延迟明显比本地大。 */
    private const val MENU_RETRY_DELAY_MS = 1_200L

    /** 加载遮罩（「画面恢复中…」）最长等多久。 */
    private const val LOADING_WAIT_MS = 12_000L

    /** 打开邮件页的最长等待。 */
    /** 点完邮件图标后等多久再判页面（转场 + 列表渲染）。 */
    private const val MAIL_LOAD_MS = 1_000L

    private const val MAIL_OPEN_TIMEOUT_MS = 2_500L

    /** 邮件页轮询间隔。 */
    private const val MAIL_POLL_MS = 150L
        private const val MENU_SETTLE_CHECKS = 3

        /** 单轮调试导出上限。 */
        private const val MAX_DUMPS = 60

        /** 全量记录上限（一次校准通常几十张就够）。
         *
         * ## 为什么从 300 降到 60
         *
         * 300 张 PNG × 约 1.8 MB = **540 MB**。实测一次任务导出 274 帧、
         * 日志包 363 MB —— 用户看到的就是"日志 300 多 MB"，其实文本日志
         * 只有 1.5 MB，全是截图。
         *
         * 现在帧改成 JPEG q85（约 250 KB/帧），60 张约 15 MB，
         * 而且 [capture] 用环形槽位覆盖，**目录总量恒定**，跑再久也不会涨。 */
        private const val MAX_CAPTURES = 60

        /** 调试帧的 JPEG 质量。q85 在 1080p 截图上肉眼无损，体积约为 PNG 的 1/7。 */
        private const val DUMP_JPEG_QUALITY = 85

        /**
         * 弹窗探测的节流间隔。
         *
         * 弹窗探测要跑一次区域 OCR（100~300ms）。`closeCurrent` 每轮都会问
         * 一次，紧挨着问两次没有意义，所以设一个下限；取 800ms 与
         * [SETTLE_MS] 对齐 —— 转场等待时长本身就超过了节流窗口。
         */
        private const val POPUP_PROBE_THROTTLE_MS = 800L

        /**
         * 连续多少次注入失败就判定"触控通道瘸了"。
         *
         * 取 3 —— [ShellAutomation.tap] 内部已经重试过两种模式，
         * 一次 `tap` 失败意味着三种注入方式全被拒；连续 3 次就是 9 次全败，
         * 已经远超偶发抖动的范围，再重试只是浪费时间。
         */
        private const val INPUT_DEAD_THRESHOLD = 3

        // ==================== 忘却之庭 / 混沌回忆 ====================

        /** 点开「忘却之庭」后，等右侧详情面板画出「混沌回忆」的上限。 */
        private const val DETAIL_WAIT_MS = 8_000L

        /**
         * 点「传送」后等混沌回忆页出现（含转场 + 加载）的上限。
         *
         * ## 25 秒不够（实机截图实测）
         *
         * 用户手动走了一遍：16:02:55 点传送 → 16:03:06 还在**角色介绍
         * 加载页**（丹恒立绘 + 档案文字）→ 16:03:09 才进混沌回忆页。
         * 也就是说转场本身要 10 秒以上，中途那张加载页既不是列表页
         * 也不是大厅，任何判据都是 false。25 秒只留了 2 倍余量，
         * 手机发热卡顿一下就超时。放宽到 45 秒。
         */
        private const val CHAOS_TELEPORT_TIMEOUT_MS = 45_000L

        /**
         * 星数进度的正则。
         *
         * OCR 输出的原文可能是 `12/36`、`12 /36`、`★ 12/36`，
         * 所以两边都允许空白。
         */
        private val STAR_PROGRESS_RE = Regex("""(\d{1,2})\s*/\s*(\d{1,2})""")

        /**
         * 「点击空白处继续」这类过场提示的搜索区（画面下半部分）。
         *
         * 提示文字固定在底部一两行，取 y 0.62~0.98 已经足够，
         * 又不至于把上半部分的界面标题读进来。
         */
        private val CROP_BLANK_TAP_PROMPT = NormRect(
            0.10f, 0.62f, 0.80f, 0.36f,
        )

        /**
         * 「画面恢复中…」这类加载遮罩的文字区域。
         *
         * 遮罩是半透明黑底 + 居中加载圈 + 一行字，文字大约在画面正中偏下。
         */
        private val CROP_LOADING_OVERLAY = NormRect(
            0.24f, 0.30f, 0.52f, 0.46f,
        )

        /** 过场提示可能出现的文案（按命中优先级）。 */
        private val BLANK_TAP_TEXTS = listOf(
            "点击空白处继续",
            "点击空白处",
            "点击任意处继续",
            "点击任意位置",
        )
    }

    /**
     * 弹窗探测结果。
     *
     * ## 为什么要把「退出确认框」单独列出来
     *
     * 手游上 BACK 键在主界面会弹出「是否退出游戏」。这个框一旦出现，
     * 反而是**一个可靠的位置证据**：只有主界面才会问「要不要退出」。
     * 所以调用方可以拿 [EXIT_CONFIRM] 当"我已经在主界面了"用，
     * 比模板判态更硬。
     */
    enum class PopupProbe {
        /** 没有弹窗（或者没认出来）。 */
        NONE,

        /** 普通弹窗，已点「取消」/「关闭」。 */
        CANCELED,

        /** 「是否退出游戏」确认框，已点「取消」（游戏没退）。 */
        EXIT_CONFIRM,
    }

    /**
     * 界面识别顺序（**顺序敏感**）。
     *
     * - `MENU` 必须在 `MAIN` 之前：菜单打开时手机图标可能仍可见，
     *   先判 MAIN 会把菜单误判成大厅。
     * - 其余按"越靠上越可能命中"排，靠前短路省时间。
     */
    private val ORDER = listOf(
        Screen.MENU, Screen.MAIL, Screen.ACHIEVEMENT,
        Screen.MEMORY_OF_CHAOS,
        Screen.GUIDE3, Screen.GUIDE4, Screen.GUIDE5, Screen.GUIDE,
        Screen.DISPATCH, Screen.MAIN,
    )

    /**
     * 界面定义：id + 用于识别的锚点模板。
     *
     * ## 锚点全部复用 M7A 的 PC 模板 —— 为什么可以
     *
     * 虚拟屏固定 **1920×1080（16:9）**，与 PC 版**完全一致**；
     * 且崩铁的「左右边距调节」在虚拟屏上会**重置为默认且不可调**。
     *
     * 两条合起来 ⇒ **虚拟屏上的 UI 布局 = PC 版布局**（逐像素确定）。
     *
     * 实测验证（1920×1080 虚拟屏帧）：
     * | 模板 | 分数 | 说明 |
     * |---|---|---|
     * | `screen/main.png` | **0.929** | 左上角手机图标 |
     * | `screen/click_enter.png` | **0.828** | 标题页「点击进入」 |
     * | `screen/mail.png` | **0.964** | 邮箱页标题 |
     * | `screen/menu.png` | **0.984** | 手机菜单电源键（物理屏实测） |
     *
     * 所以**不需要为手游另裁模板**（曾误裁过一批物理屏模板，
     * 因宽高比不同而全部失效，已删除）。
     *
     * ## 几个反直觉的点
     *
     * - `screen/main.png` **不是**大厅场景，而是**左上角的手机图标**本身
     *   （M7A 用它当"我在大厅"的判据，因为它最小最稳）。
     * - `screen/menu.png` **不是**"PC 的 ESC 菜单"，而是
     *   **手机菜单右侧竖栏底部的电源按钮** —— 手游上同样存在。
     */
    enum class Screen(
        val id: String,
        val marker: String,
        /**
         * 锚点的**搜索区域**（归一化）。null = 全屏。
         *
         * 界面锚点位置都是固定的，限定区域能把每次判态从 1~1.5 秒
         * 降到几十毫秒。区域要留足余量（不同机型/版本会小幅浮动）。
         */
        val searchRegion: NormRect? = null,
    ) {
        /**
         * 手机菜单（点左上角手机图标后展开）。
         *
         * ⚠️ **必须排在 [MAIN] 前面**：菜单打开时手机图标可能仍可见，
         * 先判 MAIN 会把菜单误认成大厅。
         */
        /**
         * 手机菜单。
         *
         * ## 判态**不用模板，用 OCR**
         *
         * 菜单右侧那一栏（邮件、对话、设置、相机、电源）是**纯图标、
         * 半透明**，背景随大厅场景变化，模板匹配完全不可靠
         * （原来的 `menu.png` 甚至只是电源键，位置还偏）。
         *
         * 而菜单中间的网格每一项都**带文字**（商店 / 好友 / 委托 /
         * 指南 / 角色 …），OCR 一认一个准，而且不受背景影响。
         *
         * 所以 marker 留空，由 [isAt] 走 [isPhoneMenu] 的 OCR 判态。
         */
        MENU("menu", ""),

        /** 主界面（大厅）—— 锚点就是左上角的手机图标 */
        // 手机图标在左上角
        MAIN("main", "screen/main.png", NormRect(0.00f, 0.00f, 0.20f, 0.20f)),

        /** 邮件 */
        // 邮箱标题在顶部
        /**
         * 邮件页面。
         *
         * ## ⚠️ 锚点用页面内的**实心信封图标**，不是菜单里的半透明图标
         *
         * 手机菜单右侧那个信封是**半透明**的，背景随大厅场景变化，
         * 模板匹配必然失败（实测只有 0.4~0.6 分）。
         *
         * 而邮件页面左上角这个信封是**实心绘制**、背景固定，
         * 而且它是纯图形 —— 不含文字，所以不受语言影响。
         *
         * 搜到区域限定在左上角一小块（0.02~0.10 × 0.01~0.07）。
         */
        MAIL("mail", "screen/mail_envelope.png", NormRect(0.00f, 0.00f, 0.15f, 0.10f)),

        /** 成就 */
        ACHIEVEMENT("achievement", "screen/achievement.png"),

        /** 每日实训（星际和平指南-每日实训） */
        GUIDE("guide", "screen/guide/guide2.png"),

        /**
         * 星际和平指南 - **生存索引**（清体力的副本入口）。
         *
         * ⚠️ **不用模板识别**：`guide3.png` 只有 38×35 像素，
         * 在真实 1920×1080 帧上得分仅 0.648，达不到 0.80 阈值 ——
         * 这是个假锚点。指南页的 tab 是纯图标（没有文字），
         * 图标又小又相似，所以**改为 OCR 识别页内的子标签**来判态。
         *
         * 见 [guideTab] / [switchGuideTab]。
         */
        GUIDE3("guide3", ""),

        /** 星际和平指南 - 逐光捡金（同样靠 OCR 判态） */
        GUIDE4("guide4", ""),

        /** 星际和平指南 - 旷宇纷争（同样靠 OCR 判态） */
        GUIDE5("guide5", ""),

        /**
         * 忘却之庭 - **混沌回忆**（逐层挑战页）。
         *
         * 锚点 `screen/forgottenhall/memory_of_chaos.png` 是该页左上角的
         * 「忘却之庭 / 混沌回忆」标题组合（104×47，含金色图标）。
         *
         * ## 搜索区只取左上角一小块（x 0~15%，y 0~10%）
         *
         * 实机标题在 x[43,200] × y[20,67]，这一块完全够用。
         *
         * 曾经取"顶部 35% 全宽"图保险 —— 但 `isMemoryOfChaosPage()`
         * 是在"等传送完成"的 700ms 轮询里调的，1920×378 的区域做多尺度
         * 匹配一次要接近 1 秒，整条链路白等好几秒。限定小块之后是毫秒级。
         *
         * 而且模板本身**不保证**能中（手游那颗金色图标比 PC 大 15%），
         * 所以 [isMemoryOfChaosPage] 里补了一条 OCR 兜底判据。
         */
        MEMORY_OF_CHAOS(
            "memory_of_chaos",
            "screen/forgottenhall/memory_of_chaos.png",
            NormRect(0.00f, 0.00f, 0.15f, 0.10f),
        ),

        /** 委托 */
        DISPATCH("dispatch", "screen/dispatch.png"),
    }

    /** 归一化矩形（0~1）。 */
    data class NormRect(val x: Float, val y: Float, val w: Float, val h: Float) {
        fun toPixels(frameW: Int, frameH: Int): IntArray = intArrayOf(
            (x * frameW).toInt(),
            (y * frameH).toInt(),
            (w * frameW).toInt(),
            (h * frameH).toInt(),
        )
    }

    /**
     * 手游版导航入口坐标（归一化）。
     *
     * ## 数值来源：**直接抄 M7A 的 `assets/config/screens.json`**
     *
     * 既然虚拟屏布局 = PC 布局（见 [Screen] 的说明），
     * M7A 里那些"硬编码 crop 坐标"就可以原样复用，**不需要自己量**。
     *
     * ## 优先用模板，坐标只作兜底
     *
     * 能用模板找到的元素（如手机图标 = `screen/main.png`）**不要写死坐标** ——
     * 模板匹配自带定位，换设备/换分辨率都不会错。
     * 只有"没有合适模板"的元素才退回坐标点击。
     */
    object Hotspot {
        /**
         * 手机菜单右侧竖栏里的「邮箱」信封。
         *
         * ## ⚠️ M7A 的坐标在这里**不能直接用**
         *
         * M7A `screens.json` 的 `menu -> mail` 是：
         * ```
         * auto.click_element((1846/1920, 253/1080, 43/1920, 37/1080), 'crop')
         * ```
         * 中心 (1867, 271)。
         *
         * **实测（在 1920×1080 虚拟屏菜单帧上量的）真实中心是 (1859, 334)**：
         * X 几乎一致（差 8px），但 **Y 高了 63px** —— 按 M7A 的值点，
         * 落点偏上，点不到信封。
         *
         * 手游菜单的右栏比 PC 版**整体下移**，所以信封更低。
         *
         * 信封本体 bbox ≈ (1834,318)-(1884,351)，即 50×33。
         */
        val MAIL_ICON = NormRect(
            // 实测：菜单右侧竖栏的信封中心在 (1852, 340)
            1822f / 1920f, 310f / 1080f, 60f / 1920f, 60f / 1080f,
        )

        /**
         * 手机菜单右侧竖栏里的**设置齿轮**。
         *
         * 同一根竖栏从上到下依次是：
         *
         * | 图标 | 中心（1920×1080） |
         * |---|---|
         * | 信封（邮件） | (1852, 340) |
         * | 带横线的方框（任务） | (1852, 468) |
         * | **齿轮（设置）** | **(1852, 600)** |
         * | 相机（拍照） | (1852, 728) |
         * | 电源键（底部，危险） | (1856, 986) |
         *
         * 坐标是在 1920×1080 的菜单帧上量的（齿轮 bbox ≈ (1832,580)-(1872,620)）。
         *
         * ## 为什么用坐标而不是模板
         *
         * 齿轮图标只有 40px，且这份菜单是**半透明叠在游戏画面上**的 ——
         * 同样的白色齿轮在设置页、合成台、地图里都能找到，模板匹配很容易
         * 命中别处。而竖栏位置是固定布局，坐标比模板稳。
         *
         * 点错的风险可控：点偏了最多是没进设置页，[gotoRedeem] 会判失败重试。
         */
        val MENU_SETTINGS = NormRect(
            1832f / 1920f, 580f / 1080f, 40f / 1920f, 40f / 1080f,
        )

        /**
         * 手机菜单右上角的 **✕ 关闭**。
         *
         * 实测 bbox = (1794,5)-(1851,62)，57×57，中心 **(1822, 34)**。
         *
         * ## 两帧测量不一致 → 取并集
         *
         * 后来在另一张 1920×1080 原生帧上量到的 ✕ 是
         * bbox (1834,38)-(1876,81)、中心 **(1855, 59)** —— 比上面那组
         * 右移 33px、下移 25px。原因应该是名片面板的高度随「均衡等级」
         * 那一行文字长度略有变化，右上角按钮跟着挪。
         *
         * 两组取并集：(1794,5)-(1885,95)，中心 (1839,50)。✕ 图标本体
         * 只有 43px，但**触摸热区**远大于图标，中心落在两组的中间地带
         * 也能点中；比押注其中一组稳。
         *
         * ## 为什么需要它
         *
         * 手游没有 ESC，M7A 的 `menu -> main` 靠 `press_key('esc')`，
         * 在手游上不可用。而现有的两个关闭模板在这个菜单上**都匹配不到**：
         * | 模板 | 分数 |
         * |---|---|
         * | `zh_CN/base/click_close.png` | 0.30 |
         * | `share/plot/close_phone.png` | 0.54 |
         *
         * 所以必须单独给菜单一个关闭坐标。
         *
         * ## ⚠️ 千万别点右边的电源键
         *
         * 同一竖栏底部的 **电源键在 (1856, 986)**（`screen/menu.png` 匹配它，
         * 0.955）。点它可能**退出登录/关闭游戏**，绝不能拿它当"关闭菜单"。
         */
        val MENU_CLOSE = NormRect(
            1794f / 1920f, 5f / 1080f, 91f / 1920f, 90f / 1080f,
        )

        /**
         * 子界面左上角的「返回」。
         *
         * 崩铁所有二级界面的返回按钮**长得一样、位置也一样**（左上角），
         * 所以不需要为每个页面单独裁模板 —— 直接点固定坐标即可。
         *
         * 它是 BACK 键的正确替代品：BACK 在游戏里只会弹「是否退出游戏」，
         * 而这里点的是真按钮，点空最多是没反应，不会把游戏退掉。
         */
        val BACK_ARROW = NormRect(
            24f / 1920f, 26f / 1080f, 68f / 1920f, 64f / 1080f,
        )

        /**
         * 手机菜单**名片右上角的「…」**（三个点）。
         *
         * ## 它是兑换码入口的前置
         *
         * 实测流程是两跳，不是一跳：
         *
         * ```
         * 大厅 → 手机 → 名片页 → 点「…」→ 面板 → 点「兑换码」
         * ```
         *
         * 之前按"手机菜单右侧竖排直接有兑换码"写了一版，直接 OCR 找
         * 「兑换码」永远找不到 —— 因为那一栏要等「…」展开后才画出来。
         *
         * ## 坐标（1920×1080 原生帧实测）
         *
         * 白色药丸底 + 三个黑点，bbox = (1671,110)-(1751,147)，**81×38**，
         * 中心 **(1711,128)**。
         *
         * ## 为什么用坐标不用模板
         *
         * 它是**纯白圆角矩形**，菜单里另有两处相近的白底（好友头像框、
         * 底部按钮），模板匹配（`TM_CCOEFF_NORMED`）会因为都是"白块"
         * 拿到 0.9+ 的假阳性。而名片布局在 16:9 虚拟屏上是固定的，
         * 坐标反而更可靠 —— 点偏了最多是面板没展开，上层会重试。
         */
        val MENU_MORE = NormRect(
            1671f / 1920f, 110f / 1080f, 81f / 1920f, 38f / 1080f,
        )

        /**
         * 邮件页左下角的「全部领取」。
         *
         * 实测（1920×1080 虚拟屏邮件帧）：按钮中心 **(540, 984)**。
         * 圆形下载图标在 x≈426，文字「全部领取」在 x≈547，
         * 按钮背景跨 x≈400..681、y≈958..1011。
         *
         * ## ⚠️ 这里**只能用坐标**，模板方法本身不适用
         *
         * 「全部领取」是**次级按钮样式**（半透明底），背后的游戏画面
         * 随邮件内容变化。而模板匹配（`TM_CCOEFF_NORMED`）对背景敏感，
         * 所以 M7A 的 `zh_CN/reward/mail/receive_all.png` 在手游上
         * 只有 **0.47**，永远达不到 0.80 —— 这不是阈值调不调的问题，
         * 是方法本身不适用。
         *
         * 虚拟屏分辨率固定 ⇒ 坐标可靠。
         */
        val MAIL_RECEIVE_ALL = NormRect(
            400f / 1920f, 956f / 1080f, 281f / 1920f, 56f / 1080f,
        )

        /**
         * 手机菜单右侧图标区的**裁剪范围**。
         *
         * 来源：M7A `screens.json`，`menu -> guide2/3/4/5/achievement/dispatch/...`
         * 全都用这个 crop 限定搜索范围，避免匹配到左侧立绘上的相似图案。
         * ```
         * crop=(1264.0/1920, 286.0/1080, 546.0/1920, 746.0/1080)
         * ```
         */
        val MENU_ICON_AREA = NormRect(
            1264f / 1920f, 286f / 1080f, 546f / 1920f, 746f / 1080f,
        )

        /**
         * 手机菜单里第 4 排最左的「指南」图标（通往星际和平指南）。
         *
         * ## 实测值（1920×1080 虚拟屏帧）
         *
         * 图标本体 bbox ≈ (1195,865)-(1295,955)，中心 **(1245, 908)**。
         * 下方文字「指南」中心 x≈1228。
         *
         * ## ⚠️ 这里踩过一个很贵的坑
         *
         * 最初按**物理屏（2780×1264 / 20:9）**的归一化坐标折算成
         * (1253, 459)，结果点到了**第 1 排的「商店」** ——
         * 因为崩铁 HUD 是**高度锚定**的，宽高比一变，网格整体重排：
         *
         * | 画面 | 宽高比 | 指南所在行列 | y |
         * |---|---|---|---|
         * | 物理屏 2780×1264 | 2.20 | 第 4 排 | 归一化 ~0.475 |
         * | 虚拟屏 1920×1080 | 1.78 | 第 4 排 | 实测 **908**（归一化 0.841）|
         *
         * **归一化 Y 差了将近一倍。** 归一化只解决"整体缩放"，
         * 不解决"宽高比变化导致的布局重排"。
         */
        val MENU_GUIDE = NormRect(
            // 实测：菜单里「指南」卡片中心在 (1193, 990)
            1143f / 1920f, 940f / 1080f, 100f / 1920f, 100f / 1080f,
        )

        /**
         * 星际和平指南内部左侧的「生存索引」标签。
         *
         * 同样来自物理屏估计，**优先用 `screen/guide/enter_guide3.png` 模板**。
         * M7A 在 `instance.py` 里靠 OCR 点「拟造花萼（金）」这类文字，
         * 而那个标签就在这个位置附近。
         */
        val GUIDE_SURVIVAL_TAB = NormRect(
            262f / 1920f, 289f / 1080f, 180f / 1920f, 60f / 1080f,
        )

        // ==================== 忘却之庭 / 混沌回忆 ====================
        //
        // 下面这几个 crop 全部**直接抄 M7A**（`memoryofchaos.py` 与
        // `screens.json` 里的显式坐标）。理由和 [MAIL_ICON] 一样：
        // 虚拟屏固定 1920×1080，与 PC 版布局逐像素一致，抄过来就能用。

        /**
         * 逐光捡金页**左侧入口列表**（忘却之庭 / 虚构叙事 / 末日幻影）。
         *
         * M7A：`crop=(231/1920, 420/1080, 450/1920, 536/1080)`。
         */
        val RELICS_ENTRY_LIST = NormRect(
            231f / 1920f, 420f / 1080f, 450f / 1920f, 536f / 1080f,
        )

        /**
         * 逐光捡金页**右侧详情面板**。
         *
         * 选中某个入口后，右侧会显示它的标题（「混沌回忆」）、进度
         * （`12/36`）、模式说明和底部的「传送」按钮，全都在这一块。
         *
         * M7A：`crop=(689/1920, 285/1080, 970/1920, 474/1080)`。
         *
         * ## 为什么右边界要从 1659 放宽到 1790
         *
         * M7A 那份 crop 在 PC 上只框到「传送」左边一点。实机截图里
         * 「传送」两字在 **x ≈ 1582~1663**，正好压在 1659 这条线上 ——
         * OCR 拿到半截字就认不出「传送」，任务会卡在"找不到传送按钮"。
         * 顺带把上边界抬到 260，把「本期…持续 5 周」那条黄条也纳进来
         * （它会让整块详情往下挪，放大区域才不会被挪出框）。
         */
        val RELICS_DETAIL = NormRect(
            620f / 1920f, 260f / 1080f, 1170f / 1920f, 520f / 1080f,
        )

        /**
         * 混沌回忆页的**关卡区**（第 1~12 层）。
         *
         * ## 和 PC 版差别很大（必须按实机量）
         *
         * PC 是**横排一行**，M7A 用 `crop=(540,406,1156×516)`。
         * 实机（1920×1080）是**斜向散开的四颗晶石**，一次只显示 4 层，
         * 层号文字的位置逐层不同：
         *
         * | 层 | 层号框 | 星星 |
         * |---|---|---|
         * | 09 | x[400,472] y[564,620] | x[400,520] y[632,672] |
         * | 10 | x[744,816] y[640,700] | x[744,864] y[712,744] |
         * | 11 | x[1132,1192] y[540,592] | x[1128,1248] y[608,648] |
         * | 12 | x[1528,1596] y[468,524] | x[1520,1640] y[536,576] |
         *
         * 所以不能再沿用 PC 的窄条：那条 **x 从 540 起**，会直接把
         * 第 09 层（x=400）切在框外。改成整条斜带：
         * x[192,1804] × y[432,778] —— 上边界避开「记忆紊流 / 常规模式」，
         * 下边界避开右下角的 `36/36`（y≈952，会被当成层号）。
         */
        val CHAOS_LEVEL_BAR = NormRect(
            192f / 1920f, 432f / 1080f, 1612f / 1920f, 346f / 1080f,
        )

        /**
         * 混沌回忆页**左上角标题**（「忘却之庭 / 混沌回忆」双行 + 金色图标）。
         *
         * 用来给 [isMemoryOfChaosPage] 做 OCR 兜底：实机标题在
         * x[43,200] × y[20,67]，取 x[0,576] × y[0,130] 足够宽松。
         *
         * 这块**不会**和逐光捡金页的详情面板串味 —— 那边「混沌回忆」
         * 四个字在 x≈1030 的右侧面板里，不在这块。
         */
        val CHAOS_TITLE = NormRect(
            0f, 0f, 576f / 1920f, 130f / 1080f,
        )

        /**
         * 配队页右下角的「开始挑战」按钮。
         *
         * M7A `start_level`：`crop=(1546/1920, 962/1080, 343/1920, 62/1080)`。
         * 正常情况下用 OCR 点「开始挑战」三个字更稳，这个区域是兜底。
         */
        val CHAOS_START_BUTTON = NormRect(
            1546f / 1920f, 962f / 1080f, 343f / 1920f, 62f / 1080f,
        )

        /**
         * 领奖弹窗里的**奖励条目列表**（`receive.png` = 「领取」按钮所在列）。
         *
         * M7A `collect_rewards`：`crop=(1081/1920, 171/1080, 500/1920, 736/1080)`。
         */
        val CHAOS_REWARD_LIST = NormRect(
            1081f / 1920f, 171f / 1080f, 500f / 1920f, 736f / 1080f,
        )

        /**
         * 混沌回忆页右上角的**红点**（有未领奖励时出现）。
         *
         * M7A：`crop=(1775/1920, 902/1080, 116/1920, 110/1080)`。
         * ⚠️ 注意它在**右下角**（y 0.835），不是通常意义的右上角 ——
         * 那个位置是页面右下角的「奖励」按钮入口。
         */
        val CHAOS_RED_DOT = NormRect(
            1775f / 1920f, 902f / 1080f, 116f / 1920f, 110f / 1080f,
        )
    }

    /**
     * **外部取消钩子**（由引擎注入，返回 true 表示用户已点停止）。
     *
     * ## 为什么放在这里
     *
     * 导航层有大量 900ms 的 SETTLE_MS 等待和轮询循环。用户点「停止」时
     * 如果正卡在其中一个等待里，任务线程**完全感知不到**，
     * 表现就是"停止有时生效、有时不生效"（用户实测反馈正是如此）。
     *
     * 引擎在创建导航器时注入 `{ !running.get() }`，这里所有等待
     * 都会分段检查，最多 200ms 响应一次。
     */
    @Volatile
    var isCancelled: () -> Boolean = { false }

    /** 用户取消时抛出，由 PowerTask 等调用方统一捕获。 */
    open class StopRequested(message: String = "任务已被用户停止") : RuntimeException(message)

    /** 可中断的等待：分段检查取消信号。 */
    fun sleepInterruptible(totalMs: Long, stepMs: Long = 200L) {
        val end = SystemClock.uptimeMillis() + totalMs
        while (SystemClock.uptimeMillis() < end) {
            if (isCancelled()) throw StopRequested()
            val left = end - SystemClock.uptimeMillis()
            SystemClock.sleep(minOf(stepMs, left).coerceAtLeast(1))
        }
    }

    // ---- 基础操作 ----    // ---- 基础操作 ----

    /**
     * 调试导出的落盘根目录。非空时，导航的每个关键步骤都会导出一帧。
     *
     * 移植期采模板用：虚拟屏的画面抓不到（`screencap -d` 不支持 VD），
     * 而模板必须按**采集分辨率**裁，只能从引擎内部导出。
     */
    @Volatile
    var debugDumpRoot: java.io.File? = null

    /**
     * **全量记录模式**（首次校准用）。
     *
     * ## 为什么需要它
     *
     * 调一个坐标要「改代码 → 构建 → 安装 → 实测」走一轮，十几分钟起步。
     * 手机菜单里十几个图标、指南页、副本列表…… 一个个来是几十轮，
     * 时间开销完全不可接受。
     *
     * 打开这个开关后，引擎**每次取帧判定前都存一张**（按时间戳+序号命名），
     * 用户只需要**正常点一次「开始任务」**，把沿途界面都走一遍，
     * 就能一次性拿到整套帧，然后离线把所有坐标量完。
     *
     * ## 与 [debugDumpRoot] 的区别
     *
     * | | debugDumpRoot | captureAll |
     * |---|---|---|
     * | 触发点 | 代码里显式调 `trace()` 的关键步骤 | **每一次取帧** |
     * | 用途 | 看"点完落在哪" | **一次性建坐标库** |
     * | 量 | 每轮 ≤60 张 | 每轮 ≤300 张 |
     *
     * 两者可以同时开。校准完应关掉，否则会持续写盘。
     */
    @Volatile
    var captureAll: Boolean = false

    /** 全量记录时的帧总数上限（防刷爆磁盘）。 */
    private val captureCount = java.util.concurrent.atomic.AtomicInteger(0)

    /** 本轮已导出帧数（上限保护，避免刷爆磁盘）。 */
    private val dumpCount = java.util.concurrent.atomic.AtomicInteger(0)

    /**
     * 全量记录：每张取到的帧都存一份。
     *
     * 文件名用「序号_时间戳」排序，方便按时间顺序回看整个流程。
     */
    /**
     * 全量记录：每张取到的帧都存一份。
     *
     * ## 环形覆盖，不是一直往后排
     *
     * 原来序号一直加到 [MAX_CAPTURES] 就停，目录里留下 300 张**最早**的帧 ——
     * 真出问题时最需要的是**最后几步**，反而没留下。
     *
     * 现在按 `(n-1) % MAX_CAPTURES + 1` 取槽位，写满一轮后从头覆盖：
     * 目录里永远是**最近的 60 帧**，总量恒定。
     */
    private fun capture(label: String) {
        if (!captureAll) return
        val root = debugDumpRoot ?: return
        val n = captureCount.incrementAndGet()
        val slot = ((n - 1) % MAX_CAPTURES) + 1
        val ts = java.text.SimpleDateFormat("HHmmss_SSS", java.util.Locale.US)
            .format(java.util.Date())
        dumpFrame(root, "cap_%03d_%s_%s".format(slot, ts, label.replace('/', '_')))
    }

    /** 导出一帧（带计数上限）。 */
    fun trace(label: String) {
        val root = debugDumpRoot ?: return
        if (dumpCount.incrementAndGet() > MAX_DUMPS) return
        dumpFrame(root, "%03d_%s".format(dumpCount.get(), label))
    }

    /**
     * **无条件**导出一帧到调试目录（不受 [MAX_DUMPS] 限制）。
     *
     * ## 为什么单独开一个方法
     *
     * [trace] 有计数上限，专门给"采集模板"用 —— 正常跑任务时
     * 不能让它把磁盘写满。
     *
     * 但**出错时**必须留证据：比如启动超时、画面异常，
     * 只有一张当时的截图才能说清到底发生了什么。
     * 这种时候一帧都不能少，所以不走那个上限。
     *
     * @return 导出的文件路径；取帧失败或没有调试目录时返回 null
     */
    fun exportFrame(label: String = "error"): String? {
        val root = debugDumpRoot ?: run {
            Ln.w("$TAG: exportFrame —— 没有调试目录，无法导出")
            return null
        }
        val stamp = java.text.SimpleDateFormat("HHmmss", java.util.Locale.US)
            .format(java.util.Date())
        return dumpFrame(root, "${label}_$stamp")
    }

    /**
     * 把当前帧落盘到 `{root}/debug/screenshots/`，用于**采集模板**。
     *
     * @param root 可写的根目录（特权进程下通常是 `/data/local/tmp/maameow`）
     */
    /**
     * 把当前帧落盘到 `{root}/debug/screenshots/`，用于**采集模板**。
     *
     * ## 存 JPEG，不存 PNG
     *
     * 之前用 PNG：1080p 截图 1.5~2.3 MB/帧，一次任务 274 帧就把日志包
     * 顶到 363 MB。JPEG q85 平均 250 KB，**小 6~8 倍**，而调试要看的是
     * "界面是什么、文字在哪"，压缩噪点完全不影响。
     *
     * @param root 可写的根目录（特权进程下通常是 `/data/local/tmp/maameow`）
     */
    fun dumpFrame(root: java.io.File, label: String): String? {
        if (!auto.takeScreenshot()) {
            Ln.w("$TAG: dumpFrame 取帧失败")
            return null
        }
        val jpg = auto.frameJpeg(DUMP_JPEG_QUALITY) ?: return null
        return runCatching {
            val dir = java.io.File(root, "debug/screenshots").apply { mkdirs() }
            val f = java.io.File(dir, "$label.jpg")
            f.writeBytes(jpg)
            Ln.i("$TAG: 已导出帧 ${f.absolutePath} (${auto.width}x${auto.height}, ${jpg.size / 1024} KB)")
            f.absolutePath
        }.onFailure { Ln.w("$TAG: 导出帧失败: ${it.message}") }.getOrNull()
    }

    /** 抓一帧并转成归一化 BGR Mat。 */
    private fun grabNormalized(): org.opencv.core.Mat? {
        if (!auto.takeScreenshot()) return null
        // 全量记录模式下，每次取帧都留档（首次校准一次性采完整套界面）
        capture("frame")
        val bmp = auto.screenshot ?: return null
        val mat = ImageUtils.bitmapToMat(bmp)
        val (norm, _) = ImageUtils.normalize(mat)
        if (norm !== mat) mat.release()
        return norm
    }

    /** 在归一化帧上查找模板。 */
    private fun findOn(norm: org.opencv.core.Mat, path: String, threshold: Double): ImageUtils.MatchResult? =
        ImageUtils.findTemplate(norm, path, threshold)

    /**
     * 抓一帧并做 OCR，**返回原始帧（未归一化）BGR Mat**。
     *
     * ## 为什么 OCR 不用归一化帧
     *
     * 模板匹配必须在归一化帧上做（模板是按 PC 分辨率裁的）。但 OCR 不一样：
     * 归一化会把 1920x1080 缩到 1080 高，字号变小、笔画糊掉，识别率明显下降。
     * 虚拟屏分辨率本来就固定，直接用原始帧反而更清楚。
     *
     * 调用方拿到 Mat 后自己 `release()`，或者直接用 [withFrameOcr]。
     */
    fun grabFrameMat(): org.opencv.core.Mat? {
        if (!auto.takeScreenshot()) return null
        capture("ocr")
        val bmp = auto.screenshot ?: return null
        return ImageUtils.bitmapToMat(bmp)
    }

    /**
     * 抓帧 → 交给 [block] 做 OCR → 释放帧。
     *
     * 这样调用方不用管 Mat 生命周期，也不容易泄漏（4MB/帧）。
     */
    /**
     * 取一帧，**只对指定区域**跑 OCR。
     *
     * ## 为什么要有它
     *
     * `withFrameOcr` 是把整帧丢给 OCR，全屏 1920×1080 一次要 1~1.5 秒。
     * 而实际业务里绝大多数判断只关心屏幕一小块（比如「点击进入」按钮、
     * 挑战次数那一行），限定区域后面积缩小到 1/20，耗时降到 100ms 级。
     *
     * @param region 裁剪区域（归一化）；传 null 等同全屏
     * @param block 回调，参数是整帧 Mat 和裁剪后的 Rect
     */
    fun <T> withFrameOcrRegion(
        region: NormRect?,
        block: (org.opencv.core.Mat, org.opencv.core.Rect?) -> T,
    ): T? {
        val mat = grabFrameMat() ?: return null
        return try {
            val rect = region?.let {
                val (x, y, w, h) = it.toPixels(mat.cols(), mat.rows())
                org.opencv.core.Rect(x, y, w, h)
            }
            block(mat, rect)
        } finally {
            mat.release()
        }
    }
    fun <T> withFrameOcr(block: (org.opencv.core.Mat) -> T): T? {
        val mat = grabFrameMat() ?: return null
        return try {
            block(mat)
        } finally {
            mat.release()
        }
    }

    /**
     * **按文字找并点击** —— 这是免校准的核心操作。
     *
     * 目标项在第几行、叫什么位置，全都不写死：
     * 直接 OCR 全帧，找到这段文字，点它的中心。
     *
     * @param text 要点的文字（游戏内原文，必须逐字一致）
     * @param crop 只在这块归一化区域里找（限定作用范围，避免点到别处的同名文字）
     * @return 是否命中并点击成功
     */
    fun clickText(
        text: String,
        crop: NormRect? = null,
        maxRetries: Int = 3,
        logDump: Boolean = false,
    ): Boolean {
        repeat(maxRetries) { attempt ->
            val hit = withFrameOcr { mat ->
                val region = crop?.let {
                    val fw = mat.cols()
                    val fh = mat.rows()
                    val (x, y, w, h) = it.toPixels(fw, fh)
                    org.opencv.core.Rect(x, y, w, h)
                }
                if (logDump) OcrEngine.dump(mat, "clickText($text)", region)
                OcrEngine.findText(mat, text, region)
            }
            if (hit != null) {
                Ln.i("$TAG: OCR 命中「${hit.text}」@ (${hit.cx},${hit.cy}) 置信=${"%.2f".format(hit.score)}")
                if (auto.tap(hit.cx, hit.cy)) {
                    sleepInterruptible(SETTLE_MS)
                    return true
                }
            }
            if (attempt < maxRetries - 1) SystemClock.sleep(600)
        }
        Ln.w("$TAG: OCR 未找到文字「$text」（$maxRetries 次）")
        return false
    }

    /** 当前帧里有没有这段文字（不点击）。 */
    fun hasText(text: String, crop: NormRect? = null): Boolean = withFrameOcr { mat ->
        val region = crop?.let {
            val (x, y, w, h) = it.toPixels(mat.cols(), mat.rows())
            org.opencv.core.Rect(x, y, w, h)
        }
        OcrEngine.findText(mat, text, region) != null
    } ?: false

    /**
     * 点文字并**验证界面确实变了**。
     *
     * ## 为什么不能只调 [clickText] 就完事
     *
     * 点下去返回 true 只代表"注入成功"，不代表"点对了地方"。
     * 崩铁里点列表项经常会：
     * - 只高亮，不切页（同一个界面）；
     * - 触发二级面板而不是切页。
     *
     * 所以这里加一层验证：点完之后等一小会儿，再拍一帧，
     * 看**这段文字是不是还在原来的位置**。还在原位置 ⇒ 界面没动
     * ⇒ 视为失败并重试。文字消失了或者挪位置了 ⇒ 界面确实切换了。
     *
     * @param pageChanged true 时要求"界面真的变了"（选类型、开打用）；
     *   false 时只要求命中（点关闭、点确定这类用）
     */
    fun clickTextAndVerify(
        text: String,
        crop: NormRect? = null,
        pageChanged: Boolean = true,
        maxRetries: Int = 3,
        settleMs: Long = SETTLE_MS,
    ): Boolean {
        repeat(maxRetries) { attempt ->
            // ① 取帧 + OCR 一次，拿到目标位置（顺便留帧用于后面的差异比对）
            var frame = grabFrameMat()
            val hit = frame?.let { mat ->
                val region = crop?.let {
                    val (x, y, w, h) = it.toPixels(mat.cols(), mat.rows())
                    org.opencv.core.Rect(x, y, w, h)
                }
                OcrEngine.findText(mat, text, region)
            }
            if (hit == null) {
                frame?.release()
                if (attempt < maxRetries - 1) SystemClock.sleep(400)
                return@repeat
            }
            Ln.i("$TAG: OCR 命中「${hit.text}」@(${hit.cx},${hit.cy}) 置信=${"%.2f".format(hit.score)}")

            if (!auto.tap(hit.cx, hit.cy)) {
                frame?.release()
                return@repeat
            }

            if (!pageChanged) {
                frame?.release()
                SystemClock.sleep(settleMs)
                return true
            }

            // ② 等界面响应 —— **用图像差异，不再跑第二次 OCR**
            //
            // 原来的做法：sleep 900ms 后再完整 OCR 一遍判断界面变没变。
            // 一次 OCR ≈ 1.5s，光等待就 2.4s；而且这个 OCR 结果只用来
            // 看"文字还在不在"，等于用牛刀杀鸡。
            //
            // 现在：轮询取帧做像素差比对（每次几十毫秒），一旦差异超过
            // 阈值立刻返回 —— 界面变得快就快，不必死等固定时间。
            SystemClock.sleep(120)   // 只等一点点，让点击先被系统消费
            val deadline = SystemClock.uptimeMillis() + settleMs * 2
            while (SystemClock.uptimeMillis() < deadline) {
                val now = grabFrameMat()
                val changed = frameChanged(frame, now)
                now?.release()
                if (changed) {
                    frame?.release()
                    return true
                }
                SystemClock.sleep(150)
            }
            frame?.release()
            Ln.w("$TAG: 点了「$text」但界面无变化，第 ${attempt + 1} 次")
            SystemClock.sleep(300)
        }
        return false
    }

    /**
     * **快速判断界面是否变化** —— 比跑 OCR 快一个数量级。
     *
     * ## 为什么需要它
     *
     * [clickTextAndVerify] 原来点完要**再跑一次完整 OCR** 判断界面变没变，
     * 一次 OCR 约 1.5 秒。加上点击前的定位，一次调用就要 4 秒；
     * 失败重试 3 次就是 12 秒 —— 实测光"选副本类型"这一步就花了 44 秒。
     *
     * 而"界面有没有变"这件事，**根本不需要认字**：
     * 把点击前后两帧按小块灰度化对比，差异超过阈值就说明界面变了。
     * 这个操作在 OpenCV 里是纯 C++ 矩阵运算，几毫秒就完事。
     *
     * ## 实现
     *
     * 1. 两帧都缩到 160×90（极快，且天然抗微小抖动）；
     * 2. 转灰度求逐像素绝对差；
     * 3. 平均差 > [threshold] 就认为界面变了。
     *
     * @param before 点击前的帧（调用方持有，别用完就 release）
     * @param threshold 平均像素差阈值。实测：
     *   - 同界面静止：0.5~2
     *   - 转场/切页：15~80
     *   取 8 能稳定区分两者。
     */
    /**
     * 直接点击指定坐标（不做 OCR、不做验证）。
     *
     * 用于调用方**已经自己 OCR 过**、知道要点头哪里的场景 ——
     * 避免再跑一次识别。
     */
    fun clickAt(x: Int, y: Int): Boolean {
        Ln.i("$TAG: 点击 ($x,$y)")
        return auto.tap(x, y)
    }
    fun frameChanged(before: org.opencv.core.Mat?, after: org.opencv.core.Mat?, threshold: Double = 8.0): Boolean {
        if (before == null || after == null) return true
        if (before.empty() || after.empty()) return true
        return runCatching {
            val smallA = org.opencv.core.Mat()
            val smallB = org.opencv.core.Mat()
            val grayA = org.opencv.core.Mat()
            val grayB = org.opencv.core.Mat()
            val diff = org.opencv.core.Mat()
            try {
                val size = org.opencv.core.Size(160.0, 90.0)
                org.opencv.imgproc.Imgproc.resize(before, smallA, size, 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
                org.opencv.imgproc.Imgproc.resize(after, smallB, size, 0.0, 0.0, org.opencv.imgproc.Imgproc.INTER_AREA)
                org.opencv.imgproc.Imgproc.cvtColor(smallA, grayA, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
                org.opencv.imgproc.Imgproc.cvtColor(smallB, grayB, org.opencv.imgproc.Imgproc.COLOR_BGR2GRAY)
                org.opencv.core.Core.absdiff(grayA, grayB, diff)
                val mean = org.opencv.core.Core.mean(diff).`val`[0]
                Ln.i("$TAG: 界面差异 ${"%.1f".format(mean)}（阈值 $threshold）⇒ ${if (mean > threshold) "已变化" else "未变化"}")
                mean > threshold
            } finally {
                listOf(smallA, smallB, grayA, grayB, diff).forEach { runCatching { it.release() } }
            }
        }.getOrDefault(true)
    }

    /** OCR 定位一段文字（只查不点）。 */    /** OCR 定位一段文字（只查不点）。 */
    fun locateText(text: String, crop: NormRect? = null): OcrEngine.OcrResult? = withFrameOcr { mat ->
        val region = crop?.let {
            val (x, y, w, h) = it.toPixels(mat.cols(), mat.rows())
            org.opencv.core.Rect(x, y, w, h)
        }
        OcrEngine.findText(mat, text, region)
    }

    /**
     * **左侧副本类型列表**的横向中心（归一化）。
     *
     * ## ⚠️ 这是修「侵蚀隧洞/历战余响无法导航」的关键
     *
     * 生存索引页是**左右两栏**：
     *
     * ```
     * ┌──────────┬─────────────────┐
     * │ 副本周   │  右侧：材料列表 │
     * │ 列表     │  （三行「进入」）│
     * │ (左边)   │                 │
     * └──────────┴─────────────────┘
     * ```
     *
     * 之前 `swipeUpInList` 用**屏幕正中 0.5** 当 x —— 那落在**右侧材料栏**上，
     * 手指滑了半天左侧列表纹丝不动，于是底部的「侵蚀隧洞」「历战余响」
     * 永远找不到。
     *
     * 实测左栏 x 范围约 0.136~0.356，中心取 **0.25**。
     */
    private val INSTANCE_LIST_X = 0.25f

    /**
     * 在**左侧副本类型列表**里向上滚动（内容上移，露出下面的条目）。
     *
     * 一次滑**一大段**（0.82 → 0.28），避免"慢慢滑"要滑很多次；
     * 滑完直接按固定坐标点，不必精细定位。
     */
    fun swipeUpInList(fromY: Float = 0.82f, toY: Float = 0.28f, durationMs: Long = 220L) {
        auto.swipe(
            (auto.width * INSTANCE_LIST_X).toInt(), (auto.height * fromY).toInt(),
            (auto.width * INSTANCE_LIST_X).toInt(), (auto.height * toY).toInt(),
            durationMs,
        )
        sleepInterruptible(SETTLE_MS)
    }

    /** 向下滚回（手指下滑）。
     *
     * 位置故意跟 [swipeUpInList] 反过来：`fromY` 在上、`toY` 在下。
     */
    fun swipeDownInList(fromY: Float = 0.28f, toY: Float = 0.82f, durationMs: Long = 220L) {
        auto.swipe(
            (auto.width * INSTANCE_LIST_X).toInt(), (auto.height * fromY).toInt(),
            (auto.width * INSTANCE_LIST_X).toInt(), (auto.height * toY).toInt(),
            durationMs,
        )
        sleepInterruptible(SETTLE_MS)
    }

    /** 当前帧 OCR 出来的全部文字（调试/新界面摸底用）。 */
    fun readAllText(crop: NormRect? = null): List<OcrEngine.OcrResult> = withFrameOcr { mat ->
        val region = crop?.let {
            val (x, y, w, h) = it.toPixels(mat.cols(), mat.rows())
            org.opencv.core.Rect(x, y, w, h)
        }
        OcrEngine.run(mat, region)
    } ?: emptyList()

    // ==================== 星际和平指南（靠 OCR 判态）====================

    /**
     * 判断当前是否在「星际和平指南」页面，并返回当前激活的子标签。
     *
     * ## 为什么不用模板
     *
     * 指南页的 5 个 tab 是**纯图标、没有文字**，图标又小又相似
     * （`guide3.png` 在真机上只有 0.648 分）。拿它当锚点必然失败。
     *
     * ## 用页内文字反推
     *
     * 每个子页都有自己的标题文字，直接 OCR 这几个关键词就能判态：
     *
     * | 子标签 | 标题关键词 |
     * |---|---|
     * | 每日实训（第 1 个） | 「每日实训」「活跃度」「进度」 |
     * | 生存索引（第 2 个） | 「生存索引」「拟造花萼」「凝滞虚影」 |
     * | 逐光捡金（第 3 个） | 「逐光捡金」 |
     * | 旷宇纷争（第 4 个） | 「旷宇纷争」「历战余响」 |
     *
     * @return 命中的子标签名；不在指南页返回 null
     */
    fun guideTab(): GuideTab? {
        val texts = readAllText().map { it.normalized() }
        if (texts.isEmpty()) return null
        val all = texts.joinToString("|")
        // 「星际和平指南」是整页标题，必须出现，否则不是这一页
        if (!all.contains("星际和平指南") && !all.contains("每日实训")) {
            // ## 兜底之前先排除主界面
            //
            // 生存索引页确实可能不显示大标题，所以留了"靠特征词兜底"这条路。
            // 但主界面的任务追踪栏上**也会**出现「生存索引」四个字，
            // 于是这个兜底会把主界面判成生存索引页 ——
            // 实测日志里就有一次：引擎在主界面上直接去点 (434,172) 的
            // "子标签"，什么都没有，接着整条链路就乱了。
            //
            // 主界面有它自己的独有文案（跃迁 / 星琼 / 开拓等级），
            // 命中任一条就说明这是大厅，不是指南页。
            val looksLikeMain = MAIN_ONLY_WORDS.any { all.contains(it) }
            if (looksLikeMain) {
                Ln.i("$TAG: 指南判态 —— 出现大厅文案，排除（$all）")
                return null
            }
            if (all.contains("生存索引") || all.contains("凝滞虚影") || all.contains("拟造花萼")) {
                return GuideTab.SURVIVAL
            }
            if (all.contains("模拟宇宙")) return GuideTab.SU
            return null
        }
        return when {
            all.contains("生存索引") || all.contains("凝滞虚影") || all.contains("拟造花萼") ->
                GuideTab.SURVIVAL
            all.contains("逐光捡金") -> GuideTab.RELICS
            all.contains("旷宇纷争") || all.contains("历战余响") -> GuideTab.WAR
            all.contains("模拟宇宙") -> GuideTab.SU
            else -> GuideTab.DAILY   // 有「星际和平指南/每日实训」标题但不是别的，就是每日实训
        }
    }

    /**
     * 星际和平指南的子标签。
     *
     * ## 最左边那格是新手教程，**一直在**
     *
     * 崩铁在 2026-11 的版本里给指南页加了一格「新手教程奖励」放在最左边：
     * 做完了不会消失，只是**再打开指南时游戏会自动跳过它**、落到别的标签上。
     * 格子本身还在，所以位置是固定的 6 格：
     *
     * ```
     * [新手教程] [每日实训] [生存索引] [模拟宇宙] [逐光捡金] [旷宇纷争]
     *      0          1          2          3          4          5
     * ```
     *
     * 老版本按 5 格写死（每日实训 = 0、生存索引 = 1），遇上这版就整体
     * 左错一格 —— 点「生存索引」落到「每日实训」上，点三次画面纹丝不动，
     * 11-03 的清体力就是这么连续失败的。
     *
     * [index] 现在是实测值，但 [switchGuideTab] 仍会逐格验证兜底：
     * 游戏再动标签栏时，写死的数一定还会错，能自己找回来比改一行代码快。
     */
    enum class GuideTab(val label: String, val index: Int) {
        DAILY("每日实训", 1),
        SURVIVAL("生存索引", 2),
        SU("模拟宇宙", 3),
        RELICS("逐光捡金", 4),
        WAR("旷宇纷争", 5),
    }

    /**
     * 指南页顶部 tab 栏的几何参数（**实测值，1920×1080 帧上量的**）。
     *
     * ## 为什么要写死这几个数
     *
     * tab 是**纯图标、无文字**，OCR 点不到；图标又小又相似，
     * 模板匹配也认不准。但 tab 栏是等宽固定控件，量一次就够：
     *
     * | 项 | 实测值 |
     * |---|---|
     * | 第 1 个 tab 高亮区 | x ∈ [208, 357]（宽 150） |
     * | 第 4 个 tab 高亮区 | x ∈ [613, 762]（宽 150） |
     * | tab 栏纵向范围 | y ∈ [130, 215] |
     * | 5 个 tab 均分 | 中心 x = 282, 417, 552, 687, 822 |
     *
     * ## 步长是 135，不是 151
     *
     * 早期版本按"高亮块宽 151"当步长算，第 4 个 tab 会算成 736 ——
     * 实测第 4 个 tab 的中心是 **687**（`(687-282)/3 = 135`）。
     * 高亮块比格位宽 15px（相邻块轻微压边），**不能拿块宽当步长**。
     *
     * 偏 49px 看着还在块里，但叠上"少算一个 tab"的错位之后就点错了整行。
     * 这是全模块**唯一**保留坐标点击的地方（确实无字可读）。
     */
    private object GuideBar {
        /** 第 1 个 tab 的左边界（像素，1920 宽基准）。 */
        const val LEFT = 208f

        /** 相邻 tab 中心间距（像素）。实测 135，见上方说明。 */
        const val TAB_W = 135f

        /** tab 栏中心 y（像素，1080 高基准）。 */
        const val CENTER_Y = 172f

        /**
         * 逐格试探时最多点到第几格。
         *
         * 当前是 6 格（新手教程 / 每日实训 / 生存索引 / 模拟宇宙 / 逐光捡金 /
         * 旷宇纷争），留到 7 是余量 —— 这一段只在写死的 index 全落空时才走到，
         * 多试一格只多花 1.5 秒，少试一格则会漏掉目标。
         */
        const val MAX_TABS = 7

        /** 基准帧尺寸。 */
        const val BASE_W = 1920f
        const val BASE_H = 1080f
    }

    /**
     * 切到指定子标签。
     *
     * ## 为什么要逐格验证，而不是点一下就走
     *
     * tab 栏是**纯图标、没有文字**，OCR 指不到；图标之间又长得像，模板匹配
     * 也认不准，只能按格子点 —— 而格子位置是靠写死的坐标算的，游戏一加
     * 标签就全错。11-03 那次（指南页最左边多了一格新手教程）就是这么崩的：
     * 点「生存索引」落在「每日实训」上，画面纹丝不动，重试三次全落空。
     *
     * 所以点完必须用 [guideTab] 的 OCR 结果验收，不对就换下一格，
     * 命中的位置记进 [guideTabSlot]，同一进程内后面直接用它。
     *
     * @return 切换后确实处于目标标签（**用 OCR 复核**，不看点击返回值）
     */
    fun switchGuideTab(target: GuideTab): Boolean {
        val before = guideTab()
        if (before == target) {
            Ln.i("$TAG: 已在指南子标签「${target.label}」")
            return true
        }

        val candidates = buildList {
            guideTabSlot[target]?.let { add(it) }
            add(target.index)
            // 兜底：标签栏再被改动时（加一格、顺序变了）靠这个自己找回来
            for (slot in 0 until GuideBar.MAX_TABS) add(slot)
        }.filter { it in 0 until GuideBar.MAX_TABS }.distinct()

        for ((index, slot) in candidates.withIndex()) {
            // 第一个候选（缓存或默认索引）最可能就中，保留"防吞点击"的重试；
            // 后面是试探，每格只点一次 —— 反正马上换下一格
            val landed = tapGuideSlot(slot, target, before, allowRetry = index == 0)
            if (landed == target) {
                Ln.i("$TAG: 已切换到「${target.label}」（第 ${slot + 1} 格）")
                return true
            }
            // 点完认不出是哪个标签，可能已经被踹出指南页了。
            // 再往下点就是在别的界面上瞎点。
            if (landed == null) {
                Ln.w("$TAG: 第 ${slot + 1} 格点完已经不在指南页，停止试探")
                return false
            }
        }

        Ln.w("$TAG: 切换指南子标签「${target.label}」失败（${GuideBar.MAX_TABS} 格都试过了）")
        return false
    }

    /**
     * 点第 [slot] 格，返回点完之后判定到的标签。
     *
     * 同一格最多点两次：第一次没换页多半是点击被吞（云游戏的输入延迟明显
     * 比本地大，实测有整次 tap 不生效的情况），再点一次才算数。
     *
     * @param before 点之前停留在哪个标签，用来区分"被吞"和"这格就是当前页"
     * @return 命中目标时返回 [target]；否则返回实际落到的标签；
     *         **null 表示认不出页面**（多半已经被踹出指南页）
     */
    private fun tapGuideSlot(
        slot: Int,
        target: GuideTab,
        before: GuideTab?,
        allowRetry: Boolean = true,
    ): GuideTab? {
        var last: GuideTab? = null
        // 试探阶段不重试：反正下一步就要换格子，被吞一次点击的代价
        // 远小于多跑一次全屏 OCR
        repeat(if (allowRetry) 2 else 1) { attempt ->
            val sx = auto.width / GuideBar.BASE_W
            val sy = auto.height / GuideBar.BASE_H
            val cx = ((GuideBar.LEFT + GuideBar.TAB_W * (slot + 0.5f)) * sx).toInt()
            val cy = (GuideBar.CENTER_Y * sy).toInt()
            Ln.i("$TAG: 点指南第 ${slot + 1} 格 @($cx,$cy)（第 ${attempt + 1} 次）")
            if (!auto.tap(cx, cy)) return last
            sleepInterruptible(SETTLE_MS)

            val landed = guideTab()
            last = landed
            if (landed == target) {
                guideTabSlot[target] = slot
                return target
            }
            // 顺手记下"这格是什么"，后面找别的标签能少试几次
            if (landed != null) guideTabSlot[landed] = slot
            // 认不出页面 —— 不在指南页了，别再点
            if (landed == null) return null
            // 换到了别的标签：这格确实不是目标
            if (landed != before) return landed
            // 停在原地：可能是点击被吞，再给一次机会
            if (attempt == 0) Ln.w("$TAG: 第 ${slot + 1} 格点完没变化，再点一次")
        }
        return last
    }

    /**
     * 点击模板中心。
     *
     * @param path 模板路径
     * @param threshold 阈值
     * @param maxRetries 重试次数（每次重新截图）
     */
    fun clickImage(
        path: String,
        threshold: Double = ELEMENT_THRESHOLD,
        maxRetries: Int = 3,
    ): Boolean {
        repeat(maxRetries) { attempt ->
            val norm = grabNormalized() ?: run {
                SystemClock.sleep(400)
                return@repeat
            }
            val r = findOn(norm, path, threshold)
            val fw = auto.width
            val fh = auto.height
            norm.release()
            if (r != null) {
                // 归一化坐标 -> 实际帧坐标
                val sf = ImageUtils.BASE_HEIGHT.toDouble() / fh
                val cx = ((r.x + r.templateWidth * r.scale / 2) / sf).toInt()
                val cy = ((r.y + r.templateHeight * r.scale / 2) / sf).toInt()
                Ln.i("$TAG: 命中 $path (${"%.3f".format(r.score)} scale=${"%.2f".format(r.scale)}) -> ($cx,$cy)")
                return auto.tap(cx, cy)
            }
            if (attempt < maxRetries - 1) SystemClock.sleep(400)
        }
        Ln.w("$TAG: 未找到 $path（$maxRetries 次）")
        return false
    }

    /** 点击归一化区域中心。 */
    fun clickCrop(rect: NormRect, label: String = ""): Boolean {
        val fw = auto.width
        val fh = auto.height
        val (x, y, w, h) = rect.toPixels(fw, fh)
        val cx = x + w / 2
        val cy = y + h / 2
        Ln.i("$TAG: 点击区域 $label ($cx,$cy) 帧=${fw}x${fh}")
        return auto.tap(cx, cy)
    }

    /**
     * 探测并关掉居中弹窗（识别 + 点「取消」，只需要一次 OCR）。
     *
     * ## 存在的意义：拦住"点确定就退游戏"这一类误操作
     *
     * 这类确认框上有「确定」和「取消」两个按钮，点错就是游戏进程被关、
     * 整条任务链报废，虚拟屏里的游戏还得重新冷启动。这里把「认弹窗」和
     * 「点取消」合成一个动作，**只认安全出口**，绝不代按「确定」。
     *
     * ## 调用点（性能）
     *
     * 导航层已经不再按 BACK（游戏内 BACK 只会弹退出确认框，没有返回上一级的
     * 作用，见 [closeCurrent]），所以这里不再承担"按完 BACK 立刻回查"的职责。
     * 现在还调用它的只有两处真正需要清弹窗的地方：[openPhoneMenu] 入口和
     * 体力不足提示。其余位置不再每轮开头空探一次 —— 一次区域 OCR 是
     * 100~300ms，关十几个界面就是好几秒。
     *
     * ## 为什么只点「取消」
     *
     * 这里的弹窗可能是"补充体力""是否退出游戏""确认放弃挑战"。
     * 无论哪一种，**取消都是安全方向** —— 关掉弹窗等于回到原状态，
     * 调用方随后重新判态即可。「确定」则各有各的后果，绝不能由兜底逻辑代按。
     *
     * @param label 日志标签，方便在 engine.log 里对齐调用点
     * @param force true = 跳过节流，立刻探测（调用方明确知道画面上有弹窗时用）
     */
    fun dismissPopup(label: String = "弹窗", force: Boolean = false): PopupProbe {
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastPopupProbeAt < POPUP_PROBE_THROTTLE_MS) return PopupProbe.NONE
        lastPopupProbeAt = now

        val texts = withFrameOcrRegion(POPUP_REGION) { mat, region ->
            OcrEngine.run(mat, region)
        } ?: return PopupProbe.NONE
        if (texts.isEmpty()) return PopupProbe.NONE

        val joined = texts.joinToString("|") { it.normalized() }
        val isExit = EXIT_CONFIRM_KEYWORDS.any { joined.contains(it) }

        // 退出确认框的两个按钮是「继续游戏 / 退出游戏」，**没有「取消」**。
        //
        // 11-02 日志里连续 10 次都是：认出了「是否确认退出游戏」，却报
        // 「没找到取消按钮，放弃自动处理」—— 因为这里只认"取消/关闭"，
        // 而游戏根本不用这两个词。等于这套自救从来没生效过。
        val cancel = texts.firstOrNull { r ->
            val t = r.normalized()
            t == "取消" || t == "关闭" || t.contains("取消") ||
                t.contains("继续游戏") || t.contains("返回游戏")
        }

        if (isExit) {
            Ln.w("$TAG: ⚠️ $label —— 出现「是否退出游戏」确认框，必须点取消！识别：$joined")
        }

        if (cancel == null) {
            if (isExit) {
                // 认出了危险框却没找到取消按钮 —— 绝不代按，留给上层重试
                Ln.e("$TAG: $label —— 危险确认框里没找到「取消」按钮，放弃自动处理。识别：$joined")
                return PopupProbe.EXIT_CONFIRM
            }
            return PopupProbe.NONE
        }

        // 点之前再确认一次：按钮中心必须落在弹窗区域内，避免误点背景
        if (!auto.tap(cancel.cx, cancel.cy)) {
            Ln.w("$TAG: $label —— 点「取消」注入失败（${cancel.cx},${cancel.cy}）")
            return PopupProbe.NONE
        }
        sleepInterruptible(400)
        Ln.i("$TAG: $label —— 已点「${cancel.text}」@(${cancel.cx},${cancel.cy})")
        return if (isExit) PopupProbe.EXIT_CONFIRM else PopupProbe.CANCELED
    }

    /**
     * 关掉当前弹出的界面，回到主界面。
     *
     * 手游没有 ESC，只能点界面自带的 ✕。
     *
     * ⚠️ **手机菜单必须走专用坐标**：M7A 的两个通用关闭模板
     * （`zh_CN/base/click_close.png` 0.30、`share/plot/close_phone.png` 0.54）
     * 在手游菜单上都**匹配不到**，只靠它们会一直关不掉。
     *
     * ## 只点按钮，不按 BACK
     *
     * 旧实现第一步就按 BACK，理由是"邮件页/菜单页的 ✕ 匹配不到"。后来确认：
     * 游戏内 BACK 唯一的作用是弹出「是否退出游戏」，它并不会正常返回上一级。
     * 所以这笔交易是亏的 —— 判态一旦把主界面漏判成子界面，第一下 BACK 就把
     * 退出框弹出来，还得再写一套"检测退出框并点取消"去收拾。
     *
     * 现在只有两步：
     * 1. 手机菜单 → 点它自己的右上角 ✕；
     * 2. 通用 ✕：第 1 轮只扫右上角（几十毫秒），没关掉就退全屏多尺度匹配。
     *
     * 两步都不成立就返回 false，交给上层重试整条链路。**不再按 BACK，也不再
     * 探测退出框** —— 自己制造的问题不需要处理，因为它根本不会被制造出来。
     *
     * ## 性能（0.5.8 之后重做过一次）
     *
     * | 步骤 | 旧 | 新 |
     * |---|---|---|
     * | 判是不是菜单 | 模板 + 区域 OCR（~1s） | **只查模板**（几十毫秒） |
     * | 找 ✕ | 全屏多尺度匹配 ×2（几百 ms） | 第 1 轮只扫右上角，**下一轮才退全屏** |
     * | 弹窗探测 | 每轮开头 + BACK 之后 | **不需要** |
     * | 等画面回大厅 | 固定 sleep 800ms | **120ms 轮询，命中即返回** |
     */
    fun closeCurrent(maxAttempts: Int = 2): Boolean {
        if (isAt(Screen.MAIN)) return true
        val startedAt = SystemClock.uptimeMillis()

        repeat(maxAttempts) { i ->
            // ① 手机菜单 → 点它自己的右上角 ✕
            //
            // 这里只看电源键模板（几十毫秒）。真在菜单里就一定命中；
            // 漏判的后果只是浪费一次尝试，不会误操作。
            if (isPhoneMenuByTemplate()) {
                Ln.i("$TAG: 在手机菜单，点右上角 ✕ 关闭")
                clickCrop(Hotspot.MENU_CLOSE, "菜单关闭")
                if (awaitMain(SETTLE_MS)) return true
            }

            // ② 通用 ✕
            //
            // 第 1 轮只扫右上角那一小块（几十毫秒），**没关掉才退全屏**。
            // 游戏里绝大多数界面的 ✕ 都在右上角，但也有少数页面把它放在
            // 别处（活动页、结算页），只查角落会漏 —— 所以第二轮起回到
            // 老做法：整帧多尺度匹配。
            if (clickCloseButton(fullScreen = i > 0)) {
                if (awaitMain(SETTLE_MS)) return true
            }

            // ③ 两条路都没关掉，这一轮就到头了 —— 不再按 BACK
            //
            //    以前这里会按 BACK，再立刻探测「是否退出游戏」并点取消，把那套
            //    叫"自救"。但游戏内 BACK 唯一的作用就是弹出退出框：按它等于先
            //    制造一个问题、再回头把问题擦掉，没有任何收益；退出框还可能因为
            //    抓早一帧而漏判，反把「确定」按下去，直接把游戏进程干掉。
            //
            //    第 1 轮只扫右上角，第 2 轮起全屏 —— 两轮都找不到可点的 ✕，
            //    说明这个画面不是靠按钮关掉的（或者压根不在子界面），继续重复
            //    同样的动作只是白等。认输返回，让上层重试整条链路。
            // ③.5 找不到 ✕ 时先确认不是"加载遮罩盖着整屏"
            //
            //    遮罩期间点任何按钮都不会有反应，✕ 自然也找不到。以前这里
            //    直接按 BACK，反而把退出确认框叠上来 —— 那才是真正卡死的原因。
            if (isLoadingOverlay()) {
                Ln.i("$TAG: 画面在加载（画面恢复中…），等它结束再判态")
                if (awaitMain(LOADING_WAIT_MS)) return true
                Ln.w("$TAG: 等了 ${LOADING_WAIT_MS}ms 仍在加载，放弃关闭")
                return isAt(Screen.MAIN)
            }

            // ③.6 还是找不到 ✕ —— 点左上角的「返回」
            //
            //    这里原本是 BACK 的位置。崩铁每个子界面的返回按钮都是同一个
            //    位置（左上角），点它等于按 BACK 想做的事，但不会弹退出框。
            //
            //    只在第一轮点（第二轮已经全屏扫过 ✕，再点也没新信息）。
            if (i == 0) {
                Ln.i("$TAG: 未见可点的 ✕，点左上角「返回」")
                clickCrop(Hotspot.BACK_ARROW, "左上返回")
                if (awaitMain(SETTLE_MS)) return true
                trace("close_back_arrow")
            }

            trace("close_attempt${i + 1}")
            if (i >= 1) {
                Ln.w("$TAG: 角落、全屏、左上返回都没能关掉，放弃（不按 BACK）")
                return isAt(Screen.MAIN)
            }
        }
        val ok = isAt(Screen.MAIN)
        Ln.i(
            "$TAG: closeCurrent 结束（${SystemClock.uptimeMillis() - startedAt}ms，" +
                if (ok) "已回大厅）" else "未回大厅）",
        )
        return ok
    }

    /**
     * 快速判断"是不是在手机菜单"：**只看电源键模板**，几十毫秒。
     *
     * 和 [isPhoneMenu] 的区别：那个在模板没命中时会补一次区域 OCR（~1 秒），
     * 保证不漏判。而这里故意不补 —— 只用在"猜错也不会出事"的地方
     * （例如 [closeCurrent] 的关闭顺序），用速度换一点准确率。
     */
    private fun isPhoneMenuByTemplate(): Boolean =
        findInRegion(MENU_POWER_TEMPLATE, 0.80, CROP_MENU_POWER) != null

    /**
     * 轮询等"回到大厅"，**一命中就返回**。
     *
     * 原来固定 `sleep(800)` 再判一次，界面 300ms 就切完了也要干等到 800ms；
     * 一整套任务要关十几个界面，这些零头加起来就是十几秒。
     *
     * @param timeoutMs 最长等多久；超时后再判最后一次
     */
    private fun awaitMain(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isAt(Screen.MAIN)) return true
            SystemClock.sleep(MAIN_POLL_MS)
        }
        return isAt(Screen.MAIN)
    }

    /**
     * 找关闭按钮（✕）并点它。
     *
     * @param fullScreen false = 只扫右上角 [CROP_CLOSE_CORNER]（几十毫秒）；
     *   true = 整帧多尺度匹配（几百毫秒，就是 0.5.6 及以前的 `clickImage` 做法）。
     *
     * 为什么要有两档：游戏里绝大多数界面的 ✕ 确实在右上角，限制区域能把
     * 一次查找从几百毫秒压到几十毫秒；但活动页、结算页这类页面会把按钮放在
     * 别处，只查角落必然漏 —— 所以 [closeCurrent] 第一轮用快档、没关掉就退全屏。
     *
     * 返回 false 有三种情况：两个模板都没命中；命中了但注入失败
     * （这种情况 [ShellAutomation.tap] 自己已经自愈过一次并打了日志）。
     */
    private fun clickCloseButton(fullScreen: Boolean): Boolean {
        val sf = ImageUtils.BASE_HEIGHT.toDouble() / auto.height
        val region = if (fullScreen) null else CROP_CLOSE_CORNER
        for (tpl in CLOSE_TEMPLATES) {
            val hit = findInRegion(tpl, 0.80, region) ?: continue
            val cx = ((hit.x + hit.templateWidth * hit.scale / 2) / sf).toInt()
            val cy = ((hit.y + hit.templateHeight * hit.scale / 2) / sf).toInt()
            Ln.i(
                "$TAG: 命中关闭按钮 $tpl (${"%.3f".format(hit.score)}" +
                    (if (fullScreen) "，全屏" else "，右上角") + ") -> ($cx,$cy)",
            )
            return auto.tap(cx, cy)
        }
        return false
    }

    /**
     * 当前是否处于某界面。
     *
     * ⚠️ `marker` 为空的界面（指南的 GUIDE3/4/5）**不能用模板判**，
     * 它们的判态在 [guideTab]，这里直接返回 false。
     */
    fun isAt(screen: Screen, threshold: Double = SCREEN_THRESHOLD): Boolean {
        // 手机菜单走 OCR 判态（没有可靠模板，见 [Screen.MENU] 的注释）
        if (screen == Screen.MENU) return isPhoneMenu()

        if (screen.marker.isBlank()) return false

        // ## 优化：能限定区域就限定
        //
        // 原来的做法是拿整帧（1920×1080，200 万像素）做多尺度模板匹配，
        // 一次 1~1.5 秒。而界面锚点（手机图标、邮箱标题…）位置都很固定，
        // 只需要看右上角/左上角的一小块。
        //
        // 实测一次 `gotoMail()` 里 `isAt` 被调用 6~9 次，
        // 光这一步就吃掉 10 秒以上。限定区域后每次几毫秒到几十毫秒。
        //
        // [Screen.searchRegion] 为 null 时保持原来的全屏行为。
        val region = screen.searchRegion
        if (region != null) {
            val hit = findInRegion(screen.marker, threshold, region)
            if (hit != null) {
                Ln.i("$TAG: 当前界面 = ${screen.id} (${"%.3f".format(hit.score)})")
                return true
            }
            return false
        }

        val norm = grabNormalized() ?: return false
        val r = findOn(norm, screen.marker, threshold)
        norm.release()
        if (r != null) {
            Ln.i("$TAG: 当前界面 = ${screen.id} (${"%.3f".format(r.score)})")
            return true
        }
        return false
    }

    /**
     * 识别当前界面。
     *
     * 只试已知的几个界面（不做全量扫描）——
     * PC 端全量扫 56 个界面在手机上要几分钟，这里按优先级短路。
     *
     * ⚠️ **顺序敏感**：菜单必须排在大厅前面。
     * 手机菜单打开时左上角手机图标**可能仍然可见**，
     * 若先判 MAIN 就会把"菜单已打开"误判成"还在大厅"，
     * 导致 `openPhoneMenu` 反复点图标（把菜单开了又关）。
     *
     * 指南的 GUIDE3/4/5 已从模板判态里排除（见 [isAt]），
     * 它们由 [guideTab] 用 OCR 判断。
     */
    fun detectCurrent(threshold: Double = SCREEN_THRESHOLD): Screen? {
        val norm = grabNormalized() ?: return null
        for (s in ORDER) {
            if (s.marker.isBlank()) continue
            val r = findOn(norm, s.marker, threshold)
            if (r != null) {
                Ln.i("$TAG: 识别到 ${s.id} (${"%.3f".format(r.score)})")
                norm.release()
                return s
            }
        }
        norm.release()
        Ln.w("$TAG: 未能识别当前界面")
        return null
    }

    // ---- 导航 ----

    /**
     * 打开手机菜单。
     *
     * ## 定位方式
     *
     * 手机图标**正好就是** `screen/main.png` 这张模板（M7A 拿它当
     * "我在大厅"的判据），所以用模板定位，不写死坐标。
     *
     * ## ⚠️ 找不到模板时**绝不盲点坐标**
     *
     * 手机图标**只在大厅可见**。不在大厅时（已经在菜单里、或在
     * 梦境护照/邮箱等子页面），模板必然找不到。
     *
     * 早期实现在找不到时按兜底坐标 (56,90) 盲点 —— 而那个位置
     * **在手机菜单里正好是左上角的「梦境护照」按钮**，
     * 实测就是这么误点开梦境护照的。而且当时还重试 3 次，
     * 等于连点 3 下。
     *
     * 现在改成：先确保在大厅 → 找不到图标就**直接失败**，
     * 不盲点。把问题留在原地，而不是误触别的功能。
     */
    /**
     * **是不是在手机菜单** —— 用 OCR 认文字，不用模板。
     *
     * ## 为什么不用模板
     *
     * 菜单右侧那一栏（邮件、对话、设置、相机、电源）是**纯图标且半透明**，
     * 背景随大厅场景变化，模板匹配完全不可靠。
     *
     * 而中间网格每一项都**带文字**：商店 / 好友 / 委托 / 旅情事记 /
     * 合成 / 成就 / 短信 / 无名勋礼 / 跃迁 / 角色 / 指南 / 联机玩法 /
     * 导航 / 教学目录。
     *
     * 只要认到其中**任意两个**，就能确认菜单开着 ——
     * 要求两个是为了避免大厅里偶尔出现的相似文字（比如 "委托" 在别处也有）
     * 造成误判。
     *
     * ## 区域限定
     *
     * 只在右侧面板（x 0.58~1.00, y 0.30~1.00）里跑 OCR，
     * 避开左侧立绘和弹幕，既快又准。
     */
    fun isPhoneMenu(): Boolean {
        // ## 先用**模板快检**，没命中再走 OCR
        //
        // 菜单右侧竖栏的「电源键」图标位置固定、外观稳定，
        // 模板匹配几十毫秒就能确认"菜单开着"。
        //
        // 而 OCR 判态要 ~1 秒 —— 实测打开一次手机菜单会调用
        // isPhoneMenu 3 次，白花 2.5 秒。
        if (findInRegion(MENU_POWER_TEMPLATE, 0.80, CROP_MENU_POWER) != null) {
            Ln.i("$TAG: 菜单判态 —— 电源键图标命中")
            return true
        }

        val texts = withFrameOcrRegion(MENU_PANEL_REGION) { mat, region ->
            OcrEngine.run(mat, region)
        } ?: return false

        if (texts.isEmpty()) {
            Ln.i("$TAG: 菜单判态 —— 面板区没认到文字")
            return false
        }

        val joined = texts.joinToString("|") { it.normalized() }
        val hits = MENU_KEYWORDS.count { kw -> joined.contains(kw) }
        Ln.i("$TAG: 菜单判态 —— 命中 $hits 个关键词（需 >=2）: $joined")

        return hits >= 2
    }

    /**
     * 大厅判态：**角色头像**在右上角命中就算在大厅。
     *
     * 这是所有任务共用的"起点确认"（`StarRailTasks.isMainUi` 用的是同一张
     * 模板、同一个区）。放在导航层，是为了让 [gotoRedeem] 这类
     * "不在大厅就没法往下走"的入口能自己先确认一次，而不是假设
     * 上层一定已经把游戏摆回大厅了 —— 兑换码任务每成功一次都会退回
     * 手机菜单，下一个码再进来时状态是**不确定**的。
     */
    fun isAtMainLobby(): Boolean {
        // 主判据：角色头像
        val hit = findInRegion("screen/main_avatar_icon.png", MAIN_AVATAR_THRESHOLD, CROP_MAIN_AVATAR)
        if (hit != null) {
            Ln.i("$TAG: 大厅锚点命中（角色头像 ${"%.3f".format(hit.score)}）")
            return true
        }
        // 补充判据：左上角手机图标
        //
        // 只用来**避免误判成"不在大厅"**：某些活动挂件会把头像挡掉一角，
        // 但手机图标还在。这时如果硬判"不在大厅"，就会去关界面 ——
        // 明明在大厅，却把界面越关越远。所以两者命中其一都算在大厅，
        // 顺序仍然是先头像（更稳）。
        if (findInRegion(Screen.MAIN.marker, ELEMENT_THRESHOLD, CROP_MENU_ICON) != null) {
            Ln.i("$TAG: 大厅锚点命中（手机图标，头像被遮挡）")
            return true
        }
        return false
    }

    /**
     * 确保停在大厅：已经在就是 `true`；不在就先关掉当前界面再确认。
     *
     * 只做"往回退"，不做冷启动 —— 冷启动由上层
     * （`StarRailTasks.ensureMainMenu`）负责，那里还要处理月卡弹窗。
     * 这里轻量是刻意的：兑换码任务里每个码都要走一次这个确认，
     * 重活累积起来就是几十秒。
     */
    fun ensureMainLobby(): Boolean {
        if (isAtMainLobby()) return true

        repeat(2) { round ->
            Ln.w("$TAG: 不在大厅（角色头像未命中，第 ${round + 1} 次），先关掉当前界面")
            if (!closeCurrent()) {
                Ln.w("$TAG: 关不掉当前界面，回不到大厅")
                return false
            }
            sleepInterruptible(BACK_TO_LOBBY_POLL_MS)
            if (isAtMainLobby()) {
                Ln.i("$TAG: 已回到大厅")
                return true
            }
        }
        Ln.w("$TAG: 连续 2 次回退仍不在大厅")
        return false
    }

    fun openPhoneMenu(): Boolean {
        // ## 快判：先只看电源键模板（几十毫秒）
        //
        // 原来这里直接 `isAt(MENU)`，而它在"不在菜单"时会补一次区域 OCR
        // （~1 秒）。每次导航（邮件 / 委托 / 合成 / 指南）都要走这一下，
        // 一天下来白花十几秒。
        //
        // 漏判的代价很小：下面点图标用的是 `screen/main.png` 模板定位，
        // 真的在菜单里时那个图标根本不在屏幕上，找不到就直接失败返回，
        // **不会盲点**（不会点到菜单左上角的「梦境护照」）。
        if (isPhoneMenuByTemplate()) {
            Ln.i("$TAG: 已在手机菜单（电源键模板命中）")
            return true
        }

        // 手机图标只在大厅可见；不在大厅就先回去。
        //
        // 判「在不在大厅」用**角色头像**而不是手机图标本身：图标就是
        // 接下来要找的东西，它被挡住时会导致「判定不在大厅 → 关界面」，
        // 于是越关越远。头像被盖住 = 确实有一层东西挡着，才该退。
        if (!isAtMainLobby()) {
            Ln.w("$TAG: 角色头像未命中（不在大厅），先回大厅再开菜单")
            if (!ensureMainLobby()) {
                Ln.w("$TAG: 回不到大厅，放弃打开手机菜单")
                return false
            }
        }
        trace("before_phone")

        // ## 只在**左上角**找手机图标，不要全屏扫
        //
        // 手机图标位置是固定的（实测左上角 (53,100) 一带）。
        // 之前用 `clickImage` 走全屏多尺度匹配，一次约 1.5 秒，
        // 还要重试 2 次 —— 光"点开手机菜单"就吃掉 6 秒以上，
        // 是整个清体力链路里最慢的一步。
        //
        // 限定到左上角 20%×20% 后，单次只要几十毫秒。
        val iconHit = findInRegion(Screen.MAIN.marker, ELEMENT_THRESHOLD, CROP_MENU_ICON)
        if (iconHit != null) {
            val (ix, iy) = run {
                val sf = ImageUtils.BASE_HEIGHT.toDouble() / auto.height
                ((iconHit.x + iconHit.templateWidth * iconHit.scale / 2) / sf).toInt() to
                    ((iconHit.y + iconHit.templateHeight * iconHit.scale / 2) / sf).toInt()
            }
            Ln.i("$TAG: 手机图标命中 ${"%.3f".format(iconHit.score)} -> ($ix,$iy)")
            if (!auto.tap(ix, iy)) {
                // ⚠️ 注入失败 ≠ 菜单没打开，这两件事必须分开报
                //
                // 旧代码忽略了 tap 的返回值，于是"事件根本没发出去"
                // 也被写成"点了手机图标但未进入菜单"，日志读起来像是
                // 识别问题或游戏卡顿，实际是输入通道的问题。
                Ln.e("$TAG: 手机图标注入失败 —— ${auto.lastInputFailure}")
                return false
            }
        } else if (!clickImage(Screen.MAIN.marker, ELEMENT_THRESHOLD, maxRetries = 1)) {
            Ln.w("$TAG: 手机图标未找到，放弃（不盲点，避免误触菜单里的按钮）")
            return false
        }
        // ⚠️ 菜单是**展开动画**，不是瞬时出现。
        // 原来只等 800ms 就截图判 `isAt(MENU)`，实测抓到的是
        // "面板还没画出来"的中间帧 —— 于是误判成"没打开"，
        // 接着点"指南"就点到了别的东西（实测点到了商店）。
        // 这里保守等到面板真正渲染出来。
        // ## 优化：短间隔轮询，看到就返回
        //
        // 原来固定 `sleep(900ms)` × 3 次 = 至少等 2.7 秒，而且每次
        // 都要跑一次 `isAt(MENU)`（全屏匹配 1.5s）—— 合计约 7 秒。
        //
        // 菜单展开动画实际 300~500ms 就完成，没必要死等。
        // 改成每 150ms 探一次，最多 2 秒；再加上 isAt 现在走区域匹配，
        // 单次只要几十毫秒。
        //
        // ## ⚠️ 看到菜单后还要**稳 1 秒**
        //
        // `isAt(MENU)` 命中只能说明**面板开始画出来了**，里面的
        // 图标可能还在淡入。这时候立刻去点「指南」，手机一卡顿
        // 就会点空（用户实测过：直接 7 秒报"打不开"）。
        //
        // 所以判到菜单后额外等 [MENU_SETTLE_MS] 再返回。
        val menuDeadline = SystemClock.uptimeMillis() + MENU_OPEN_TIMEOUT_MS
        while (SystemClock.uptimeMillis() < menuDeadline) {
            // 这里只看电源键模板：`isAt(MENU)` 在"面板还没画出来"时会补一次
            // 区域 OCR（~1 秒），等于把 150ms 的轮询间隔拖到 1 秒以上，
            // 菜单明明 400ms 就展开完了，却要等到下一次 OCR 才认出来。
            if (isPhoneMenuByTemplate()) {
                Ln.i("$TAG: 菜单已出现，等 ${MENU_SETTLE_MS}ms 让它稳定")
                sleepInterruptible(MENU_SETTLE_MS)
                return true
            }
            sleepInterruptible(MENU_POLL_MS)
        }

        // 轮询全程没命中模板 —— 走一次完整判态（含 OCR），避免模板被挡住时误判
        if (isAt(Screen.MENU)) {
            Ln.i("$TAG: 菜单已出现（OCR 兜底命中）")
            sleepInterruptible(MENU_SETTLE_MS)
            return true
        }

        // ## 注入成功但菜单没出现 —— 无条件再点几次
        //
        // `tap` 返回 true 只说明事件送到了，界面可能毫无反应。11-02 12:13
        // 实测：云游戏刚进大厅，第一次点手机图标被吞，5 秒后再点就正常打开了
        // —— 而当时的重试条件是"先探到遮挡弹窗才重试"，没有弹窗就直接失败，
        // 整个「邮件」任务因此挂掉。
        //
        // 所以改成无条件重试：每轮先探一次居中弹窗（有就掀掉），再点图标。
        // 游戏真卡住时也就多花两三秒，比任务直接失败划算。
        repeat(MENU_OPEN_RETRIES) { attempt ->
            Ln.w("$TAG: 点了手机图标但未进入菜单，第 ${attempt + 1} 次重试")
            if (dismissPopup("openPhoneMenu-retry", force = false) != PopupProbe.NONE) {
                Ln.i("$TAG: 掀掉了一个遮挡弹窗")
            }
            sleepInterruptible(MENU_RETRY_DELAY_MS)

            // 重试前确认还在大厅：这一两秒里画面可能已经切走
            if (!isAtMainLobby()) {
                Ln.w("$TAG: 重试时已不在大厅，放弃打开手机菜单")
                return false
            }
            val again = findInRegion(Screen.MAIN.marker, ELEMENT_THRESHOLD, CROP_MENU_ICON)
            if (again == null) {
                Ln.w("$TAG: 重试时手机图标不在画面上，放弃（不盲点）")
                return false
            }
            val sf = ImageUtils.BASE_HEIGHT.toDouble() / auto.height
            val ax = ((again.x + again.templateWidth * again.scale / 2) / sf).toInt()
            val ay = ((again.y + again.templateHeight * again.scale / 2) / sf).toInt()
            if (!auto.tap(ax, ay)) {
                Ln.w("$TAG: 重试点击注入失败 —— ${auto.lastInputFailure}")
                return@repeat
            }
            val retryDeadline = SystemClock.uptimeMillis() + MENU_OPEN_TIMEOUT_MS
            while (SystemClock.uptimeMillis() < retryDeadline) {
                if (isPhoneMenuByTemplate() || isAt(Screen.MENU)) {
                    Ln.i("$TAG: 第 ${attempt + 1} 次重试打开了菜单")
                    sleepInterruptible(MENU_SETTLE_MS)
                    return true
                }
                sleepInterruptible(MENU_POLL_MS)
            }
        }
        return false
    }

    /**
     * 回到主界面。
     *
     * [closeCurrent] 现在只试"角落 → 全屏"两次就返回，不再靠 BACK 硬退，
     * 所以从深层页面回来需要多给几轮：每轮关掉一层，循环到大厅为止。
     */
    fun backToMain(maxAttempts: Int = 6): Boolean {
        repeat(maxAttempts) {
            if (closeCurrent()) return true
            SystemClock.sleep(200)
        }
        return isAt(Screen.MAIN)
    }

    /**
     * 等待某界面出现。
     *
     * 转场动画 + 加载时间不确定，单次 `isAt` 判定太脆。
     * 每隔 [pollMs] 重新截图判定，直到超时。
     */
    fun waitFor(screen: Screen, timeoutSec: Int, pollMs: Long = 1500L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        while (SystemClock.uptimeMillis() < deadline) {
            if (isAt(screen)) return true
            SystemClock.sleep(pollMs)
        }
        Ln.w("$TAG: 等待界面 ${screen.id} 超时（${timeoutSec}s）")
        return false
    }

    /**
     * 在指定区域内查找模板（只查不点，无副作用）。
     *
     * 用于"判断某元素在不在"（如体力不足提示）。
     *
     * @param region 限定搜索区域；null 时全屏搜索
     */
    // ==================== 轮询（替代"干等"）====================

    /**
     * **轮询等待一个条件成立** —— 替代 `sleep(N)` 干等。
     *
     * ## 为什么需要
     *
     * 原来的代码里到处是"点完等一下"：
     *
     * ```kotlin
     * auto.tap(x, y)
     * sleepInterruptible(2500)     // 等战斗加载
     * ```
     *
     * 问题是**等待时长全凭猜**：给少了会误判（以为没点成功），
     * 给多了就是纯浪费。实测一次清体力里这类等待累计 20 秒以上，
     * 而其中大部分时候界面其实**早就准备好了**。
     *
     * 正确做法是轮询"完成后一定会出现的东西"，出现了立刻返回。
     *
     * ## 用法
     *
     * ```kotlin
     * // 点「开始挑战」后，等战斗界面出现（最多 10 秒）
     * nav.pollUntil(timeoutMs = 10_000) { frame -> isInBattle(frame) }
     * ```
     *
     * @param timeoutMs 最长等待
     * @param pollMs 轮询间隔（默认 250ms —— 足够灵敏，又不会把 CPU 打满）
     * @param region 只在这个区域内判断（**强烈建议传**，否则等于全屏扫描）
     * @param block 判断条件；返回 true 表示"可以继续了"
     * @return 条件是否在超时前成立
     */
    fun pollUntil(
        timeoutMs: Long,
        pollMs: Long = 250L,
        region: NormRect? = null,
        block: (org.opencv.core.Mat) -> Boolean,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) throw StopRequested()
            val frame = grabFrameMat()
            if (frame != null) {
                try {
                    val target = if (region == null) frame else {
                        val (x, y, w, h) = region.toPixels(frame.cols(), frame.rows())
                        // 用 ROI 视图，不复制像素
                        org.opencv.core.Mat(frame, org.opencv.core.Rect(x, y, w, h))
                    }
                    if (block(target)) return true
                } catch (e: StopRequested) {
                    throw e
                } catch (_: Throwable) {
                    // 单次判断出错不当成失败，继续轮询
                } finally {
                    frame.release()
                }
            }
            sleepInterruptible(pollMs)
        }
        return false
    }

    /**
     * 轮询等待某个模板出现在指定区域。
     *
     * 这是最常用的形式：**完成后一定会出现的图标/按钮**。
     *
     * @param path 模板路径（相对 assets/maameow/images/）
     * @param region 限定搜索区域（务必传，否则每次都是全屏匹配）
     */
    fun pollForTemplate(
        path: String,
        threshold: Double = ELEMENT_THRESHOLD,
        region: NormRect? = null,
        timeoutMs: Long = 10_000,
        pollMs: Long = 250L,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isCancelled()) throw StopRequested()
            if (findInRegion(path, threshold, region) != null) return true
            sleepInterruptible(pollMs)
        }
        return false
    }

    /**
     * 轮询等待区域内的画面**稳定下来**（连续两帧差异很小）。
     *
     * 用于"转场结束、画面不再变化"这种场景 —— 比固定等 N 秒精确得多。
     *
     * @param region 监测区域（传 null 表示整屏，开销大，尽量别用）
     * @param stableFrames 连续多少帧稳定就算稳定
     */
    fun pollForStable(
        region: NormRect? = null,
        timeoutMs: Long = 10_000,
        pollMs: Long = 200L,
        stableFrames: Int = 2,
        threshold: Double = 3.0,
    ): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var prev: org.opencv.core.Mat? = null
        var stable = 0
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                if (isCancelled()) throw StopRequested()
                val frame = grabFrameMat()
                if (frame == null) {
                    sleepInterruptible(pollMs)
                    continue
                }
                val cur = if (region == null) frame else {
                    val (x, y, w, h) = region.toPixels(frame.cols(), frame.rows())
                    org.opencv.core.Mat(frame, org.opencv.core.Rect(x, y, w, h)).clone()
                }
                val p = prev
                if (p != null && !frameChanged(p, cur, threshold)) {
                    stable++
                    if (stable >= stableFrames) return true
                } else {
                    stable = 0
                }
                prev?.release()
                prev = cur
                frame.release()
                sleepInterruptible(pollMs)
            }
            return false
        } finally {
            prev?.release()
        }
    }
    fun findInRegion(
        path: String,
        threshold: Double = ELEMENT_THRESHOLD,
        region: NormRect? = null,
    ): ImageUtils.MatchResult? {
        // ## 优化：**真正裁剪**区域后再匹配
        //
        // 原来的做法是"全屏匹配 → 命中点是否在区域内 → 不在就当没找到"。
        // 这在功能上是错的：如果模板在**区域外**也出现（比如同一按钮在
        // 结算页和副本页都有），全屏匹配可能先命中区域外那个，然后因为
        // "不在区域内"被判定为未命中 —— 明明区域内确实有！
        //
        // 而且在性能上也没省：多尺度模板匹配的成本跟**搜索面积**成正比，
        // 全屏(1920×1080) 有 200 万像素，限定到 400×200 的区域只有 8 万，
        // 快 25 倍。
        //
        // 所以这里先裁出区域再匹配，命中点再加上区域的偏移量换算回全屏坐标。
        val full = grabFrameMat() ?: return null
        return try {
            if (region == null) {
                val (norm, _) = ImageUtils.normalize(full)
                try {
                    findOn(norm, path, threshold)
                } finally {
                    if (norm !== full) norm.release()
                }
            } else {
                val (rx, ry, rw, rh) = region.toPixels(full.cols(), full.rows())

                // ## ⚠️ ROI **绝对不能**再 normalize
                //
                // `ImageUtils.normalize` 的语义是"把整帧按高度缩放到 1080"。
                // 但 ROI 只是一小块（比如右上角 192×129 像素）。
                //
                // 之前这里对 ROI 也调了 normalize，于是：
                //   192×129  →  缩放 1080/129 = 8.4 倍  →  1608×1080
                // 整块被放大 8 倍，模板（63×63）自然一个都对不上，
                // 表现就是"主界面明明在，却永远匹配不到角色头像"→ 90 秒超时。
                //
                // 正确做法：**先把整帧归一化，再在归一化后的帧上裁 ROI**。
                // 这样坐标系一致，模板缩放范围也才有意义。
                val (fullNorm, _) = ImageUtils.normalize(full)
                try {
                    val fw = fullNorm.cols()
                    val fh = fullNorm.rows()
                    // 区域坐标按归一化帧重新算
                    val nx = (region.x * fw).toInt().coerceIn(0, fw - 1)
                    val ny = (region.y * fh).toInt().coerceIn(0, fh - 1)
                    val nw = (region.w * fw).toInt().coerceIn(1, fw - nx)
                    val nh = (region.h * fh).toInt().coerceIn(1, fh - ny)

                    val roi = org.opencv.core.Mat(fullNorm, org.opencv.core.Rect(nx, ny, nw, nh))
                    try {
                        val hit = findOn(roi, path, threshold) ?: return null
                        // 命中坐标是 ROI 内坐标，加回偏移即可
                        hit.copy(x = hit.x + nx, y = hit.y + ny)
                    } finally {
                        // bug #5：ROI 是共享 fullNorm 底层数据的头，不释放会一直
                        // 拽住整帧；`isAt(region)` 每调一次漏一次，gotoMail 一次要调 6~9 次
                        roi.release()
                    }
                } finally {
                    if (fullNorm !== full) fullNorm.release()
                }
            }
        } finally {
            full.release()
        }
    }

    /**
     * 向上滚动列表，直到找到目标模板。
     *
     * 对应 M7A 的 `auto.mouse_scroll(12, -1)` + 重新 `find_element`。
     *
     * @param maxScrolls 最多滚几次（防死循环）
     * @return 命中结果（归一化坐标系）；找不到返回 null
     */
    fun scrollUntilFound(
        path: String,
        threshold: Double = ELEMENT_THRESHOLD,
        maxScrolls: Int = 12,
        fromX: Float = 0.5f, fromY: Float = 0.75f,
        toX: Float = 0.5f, toY: Float = 0.35f,
    ): ImageUtils.MatchResult? {
        repeat(maxScrolls + 1) { i ->
            val norm = grabNormalized()
            if (norm != null) {
                val r = findOn(norm, path, threshold)
                norm.release()
                if (r != null) {
                    Ln.i("$TAG: 滚动 ${i} 次后命中 $path")
                    return r
                }
            }
            if (i < maxScrolls) {
                auto.swipe(
                    (fromX * auto.width).toInt(), (fromY * auto.height).toInt(),
                    (toX * auto.width).toInt(), (toY * auto.height).toInt(),
                    durationMs = 320L,
                )
                SystemClock.sleep(500)
            }
        }
        Ln.w("$TAG: 滚动 ${maxScrolls} 次仍未找到 $path")
        return null
    }

    /**
     * 导航到邮件界面。
     *
     * 路径：`main` → 点手机图标 → `menu` → 点信封 → `mail`
     *
     * 对应 M7A 的 `screen.change_to('mail')`，但手游版没有 ESC。
     *
     * ## 为什么邮箱用坐标而不是模板
     *
     * M7A 自己就是**纯坐标点击**（`screens.json` 的 `menu -> mail`）：
     * ```
     * auto.click_element((1846/1920, 253/1080, 43/1920, 37/1080), 'crop')
     * ```
     * 说明邮箱信封没有合适的模板（`share/menu/mail.png` 是 PC 菜单的，
     * 在手游菜单上只有 0.60 分，不可用）。
     *
     * 既然虚拟屏布局 = PC 布局，直接沿用 M7A 的坐标。
     */
    /**
     * 导航到邮件界面。
     *
     * 路径：`main` → 点手机图标 → `menu` → 点信封 → `mail`
     *
     * ## 为什么邮箱用坐标而不是模板
     *
     * M7A 自己就是**纯坐标点击**（`screens.json` 的 `menu -> mail`）。
     * 而 `share/menu/mail.png` 在手游菜单上只有 **0.60** 分（PC 版是
     * 41×31 的小图标，手游上尺寸/位置都不同），达不到 0.80，不可用。
     *
     * ## ⚠️ 坐标**不是** M7A 的原值
     *
     * M7A 给的中心是 (1867, 271)，实测虚拟屏上信封在 **(1859, 334)** ——
     * **Y 高了 63px**。手游菜单右栏比 PC 整体下移，直接抄 M7A 会点空。
     * 见 [Hotspot.MAIL_ICON]。
     *
     * ## 每轮都从大厅重来
     *
     * 失败时**先关掉菜单回大厅**再重试，而不是在原地连点。
     * 原地连点正是早期误触「梦境护照」的原因。
     */
    fun gotoMail(): Boolean {
        if (isAt(Screen.MAIL)) {
            Ln.i("$TAG: 已在邮件界面")
            return true
        }
        repeat(3) { i ->
            if (!openPhoneMenu()) {
                Ln.w("$TAG: 打开手机菜单失败（第 ${i + 1} 次）")
                return false
            }
            // 点邮箱图标（**固定坐标**，菜单右栏的信封是半透明的，
            // 背景随场景变化，模板匹配不可靠）
            Ln.i("$TAG: 点邮箱图标")
            clickCrop(Hotspot.MAIL_ICON, "邮箱")
            trace("after_mail_click")

            // 等邮件页加载（转场 + 列表渲染大约 1 秒），再对比一次左上角信封
            sleepInterruptible(MAIL_LOAD_MS)
            if (isAt(Screen.MAIL)) {
                Ln.i("$TAG: 已进入邮件页（${MAIL_LOAD_MS}ms 后确认）")
                return true
            }

            Ln.w("$TAG: 邮件未打开（第 ${i + 1} 次），关掉菜单回大厅重来")
            if (!closeCurrent()) {
                Ln.w("$TAG: 关不掉当前界面，无法重试")
                return false
            }
        }
        Ln.w("$TAG: 导航到邮件界面失败（3 轮）")
        return false
    }

    /**
     * 打开**兑换码**弹窗。
     *
     * ## 路径（实机截图校准）
     *
     * ```
     * ① 角色头像确认在大厅（和邮件/委托等任务同一步）
     * ② 点左上角手机图标 → 手机菜单（名片页）
     * ③ 点右上角「…」→ 展开面板
     * ④ 点「兑换码」→ 输入框弹窗
     * ```
     *
     * **是两跳，不是一跳。** 早期版本按"手机菜单右侧竖排直接有兑换码"
     * 实现，直接 OCR 找「兑换码」永远找不到 —— 因为那一列条目要等
     * 「…」展开之后才画出来。
     *
     * ## 每一步用什么定位
     *
     * | 步骤 | 手段 | 为什么 |
     * |---|---|---|
     * | 在不在大厅 | 模板 `main_avatar_icon.png`，右上角 10%×12% | 头像在所有子界面位置固定，被盖住就是真被挡住了 |
     * | 大厅里的手机图标 | 模板 `main.png`，限左上角 20% | 图标稳定，限定区域后单次几十毫秒 |
     * | 右上角「…」 | 归一化坐标 (1711,128) | 纯白药丸，模板会因为"都是白块"假阳性 |
     * | 「兑换码」条目 | **区域 OCR**（右半屏），全屏兜底 | 文字比位置稳，面板挪位也不怕 |
     * | 弹窗开没开 | **区域 OCR** 输入框那条横带 | 只读一条带，约 0.3 秒 |
     *
     * 也就是说：**固定布局的部分全用坐标/模板，会变的部分（文字）用区域 OCR**，
     * 没有一处靠"死等固定秒数"来判断成功 —— 都是轮询到特征出现为止。
     *
     * ## 每个码都要重新确认起点
     *
     * 兑换成功后弹窗关闭、界面退回**手机菜单**（不是大厅），所以下一个
     * 码进来时状态是不确定的：可能还在菜单里（直接走 ③），也可能已经
     * 退到名片页或大厅（要重走 ① ②）。所以这里先判"是否已在菜单/弹窗"，
     * 都不在才回大厅，避免多按一次手机图标把菜单又关掉。
     *
     * 设置页那条老路径保留在后面当兜底：万一某个渠道服把它挪回设置里，
     * 不用改代码也能进。
     *
     * ## 找不到的时候
     *
     * 把整屏 OCR 结果 dump 到日志再返回 false —— 用户把日志发过来，
     * 就能直接看到这一版的设置页到底有哪些字、按钮叫什么，
     * 不用再靠猜。
     */
    fun gotoRedeem(): Boolean {
        repeat(3) { round ->
            // ① 起点：弹窗开着 / 手机菜单开着，都算"已经在入口附近"
            if (isRedeemDialogOpen()) {
                Ln.i("$TAG: 已经在兑换码弹窗上，不再走入口")
                return true
            }
            val alreadyInMenu = isPhoneMenuByTemplate()
            if (alreadyInMenu) {
                Ln.i("$TAG: 已在手机菜单里，跳过大厅确认直接找入口")
            } else {
                // ② 角色头像确认在大厅（不在就先退回去）
                if (!ensureMainLobby()) {
                    Ln.w("$TAG: 不在大厅且回不去（第 ${round + 1} 轮），放弃")
                    return false
                }
                // ③ 点手机图标打开菜单
                if (!openPhoneMenu()) {
                    Ln.w("$TAG: 打开手机菜单失败（第 ${round + 1} 轮）")
                    return false
                }
            }

            // ④ 「…」→ 面板 →「兑换码」
            if (openRedeemEntry()) {
                if (awaitRedeemDialog()) {
                    Ln.i("$TAG: 已打开兑换码弹窗（手机 →「…」→ 兑换码）")
                    return true
                }
                Ln.w("$TAG: 点了「兑换码」但弹窗没出现，换条路再试")
            }

            // ⑤ 兜底：老版本把它放在设置页的账户栏
            Ln.i("$TAG: 菜单里没进到兑换码，改走设置页")
            clickCrop(Hotspot.MENU_SETTINGS, "设置")
            sleepInterruptible(SETTINGS_LOAD_MS)

            if (clickText("兑换码", maxRetries = 1) && awaitRedeemDialog()) {
                Ln.i("$TAG: 已打开兑换码弹窗（设置页账户栏）")
                return true
            }

            // ⑥ 设置页「其他」栏
            Ln.i("$TAG: 当前页没有兑换码，切到「其他」栏再找")
            if (clickText("其他", crop = CROP_SETTINGS_TABS, maxRetries = 1)) {
                sleepInterruptible(SETTINGS_LOAD_MS)
                if (clickText("兑换码", maxRetries = 1) && awaitRedeemDialog()) {
                    Ln.i("$TAG: 已打开兑换码弹窗（从「其他」栏进入）")
                    return true
                }
            }

            // ⑦ 三处都没找到 —— 留证据
            Ln.w("$TAG: 没找到「兑换码」入口（第 ${round + 1} 轮），dump 当前界面文字")
            withFrameOcr { mat -> OcrEngine.dump(mat, "兑换码入口未找到") }

            if (!closeCurrent()) {
                Ln.w("$TAG: 回不到大厅，放弃")
                return false
            }
        }
        Ln.w("$TAG: 导航到兑换码界面失败（3 轮）")
        return false
    }

    /**
     * 在手机菜单里点开「兑换码」条目（不含"等弹窗"那一步）。
     *
     * ## 三条路，代价从小到大
     *
     * 1. **面板已经展开**：上一次没进成功、面板还留着 —— 直接点。
     *    先试这条是因为**多按一次「…」反而会把面板收起来**。
     * 2. **点「…」再点**：正常路径，90% 的情况走这里。
     * 3. **全屏 OCR**：万一版本把面板挪到屏幕中间，右半屏裁不到，
     *    用全屏兜一次（约 1 秒，只在异常时付）。
     *
     * 三步都用同一个判据：只认「兑换码」这三个字，不认位置。
     */
    private fun openRedeemEntry(): Boolean {
        // ① 面板已展开
        if (clickText("兑换码", crop = CROP_MENU_PANEL, maxRetries = 1)) {
            Ln.i("$TAG: 面板已展开，直接点「兑换码」")
            return true
        }

        // ② 点右上角「…」展开面板
        Ln.i("$TAG: 点右上角「…」展开面板")
        clickCrop(Hotspot.MENU_MORE, "菜单更多")
        sleepInterruptible(PANEL_OPEN_MS)

        if (clickText("兑换码", crop = CROP_MENU_PANEL, maxRetries = 2)) {
            Ln.i("$TAG: 已点「兑换码」（面板内）")
            return true
        }

        // ③ 面板不在右半屏 —— 全屏兜底
        Ln.w("$TAG: 右半屏没找到「兑换码」，改全屏找")
        if (clickText("兑换码", maxRetries = 1)) {
            Ln.i("$TAG: 已点「兑换码」（全屏兜底）")
            return true
        }
        return false
    }

    /**
     * 等兑换码弹窗画出来。
     *
     * 用**轮询**而不是固定 `sleep(900)`：弹窗是淡入的，慢的时候 900ms
     * 只画了半截，快的时候 300ms 就好了。轮询到特征出现立刻返回，
     * 平均比固定等待快，而且不会因为慢机器误判。
     */
    fun awaitRedeemDialog(timeoutMs: Long = REDEEM_DIALOG_TIMEOUT_MS): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            if (isRedeemDialogOpen()) return true
            SystemClock.sleep(REDEEM_DIALOG_POLL_MS)
        }
        return false
    }

    /**
     * 当前是不是停在**兑换码弹窗**上。
     *
     * 只 OCR 输入框那一条横带（Y 0.36~0.70），不读全屏：
     * - 全屏读会把手机菜单里的「商店 / 好友 / 委托」也读进来，
     *   判据一多就容易误判；
     * - 一条带约 0.3 秒，全屏约 1 秒。
     *
     * 特征取两样：占位文字「请输入兑换码…」，或者右下角的「确认」。
     * 只要占位文字还在，就说明这是一个**空输入框的兑换弹窗**。
     */
    fun isRedeemDialogOpen(): Boolean {
        val texts = readAllText(CROP_REDEEM_DIALOG)
        if (texts.isEmpty()) return false
        return texts.any { t ->
            val s = t.normalized()
            (s.contains("兑换码") && (s.contains("请") || s.contains("输入"))) || s == "确认"
        }
    }

    /**
     * 只读兑换码弹窗那一带的文字（结果横幅 / 成功提示）。
     *
     * 收窄范围的收益不是省时间，是**判对**：全屏扫描会把背景文字也读进来，
     * 万一背景里有「失败」「成功」之类的词，成功/失败的判定就会整个翻过来。
     */
    fun readRedeemResultText(): List<OcrEngine.OcrResult> =
        readAllText(CROP_REDEEM_RESULT).ifEmpty { readAllText() }

    /**
     * 读游戏自带「提示：请求从剪切板粘贴」弹窗的文字。
     *
     * 这是**游戏自己的对话框**（标题「提示」，正文「《崩坏：星穹铁道》请求从
     * 剪切板粘贴，可选择粘贴打开内容」，底部两个按钮「不粘贴」/「粘贴」），
     * 不是 Android 的系统授权框 —— 所以拿不到按钮控件，只能区域 OCR。
     * 只扫弹窗那一块，避免把背后的兑换码弹窗文字（「确认」等）读进来。
     */
    fun readPastePromptText(): List<OcrEngine.OcrResult> = readAllText(CROP_PASTE_PROMPT)

    /**
     * 在兑换码弹窗范围内点一段文字（目前只用来点输入框的占位文字）。
     *
     * 限定范围的原因：占位文字是「请输入兑换码…」，而大厅的任务追踪栏、
     * 手机菜单里都可能出现「兑换码」字样。全屏点会点错地方。
     */
    fun clickRedeemDialogText(text: String): Boolean =
        clickText(text, crop = CROP_REDEEM_DIALOG, maxRetries = 1)

    /** 兑换码弹窗里有没有这段文字（同样只读那条横带）。 */
    fun hasRedeemDialogText(text: String): Boolean = hasText(text, CROP_REDEEM_DIALOG)

    /**
     * 导航到**每日实训**页（指南的默认子标签）。
     *
     * ## 路径和清体力几乎一样
     *
     * ```
     * 大厅 → 点手机 → 点「指南」→ 就到每日实训
     * ```
     *
     * 区别：
     * - 清体力要再切到「生存索引」子标签
     * - **每日实训不用切** —— 指南打开默认就是它（第 0 个 tab）
     *
     * ## 也可能已经在指南页
     *
     * 如果当前就在指南的任一子标签，只需切 tab，不用重走菜单。
     */
    fun gotoGuideDaily(): Boolean {
        // 已在每日实训
        if (guideTab() == GuideTab.DAILY) {
            Ln.i("$TAG: 已在每日实训")
            return true
        }
        // 已在指南页（别的子标签）→ 切 tab
        val onGuide = guideTab()
        if (onGuide != null) {
            Ln.i("$TAG: 已在指南页「${onGuide.label}」，切到每日实训")
            if (switchGuideTab(GuideTab.DAILY)) return true
        }

        repeat(3) { i ->
            if (!openPhoneMenu()) {
                Ln.w("$TAG: 打开手机菜单失败（第 ${i + 1} 次）")
                return false
            }

            // ## 点「指南」后要**轮询等待**，不能单次判定
            //
            // 实测踩过：手机开完菜单立刻点指南，机器卡顿时
            // 指南页还没渲染出来就去 OCR 判态 → 读到空 → 判失败，
            // 整个任务 7 秒就报"打不开每日实训页面"。
            //
            // 现在：点完后每 400ms 探一次，最多等 8 秒。
            Ln.i("$TAG: 点「指南」图标")
            val tapped = clickTextAndVerify("指南", null, pageChanged = false, maxRetries = 2)
            if (!tapped) {
                // OCR 没点到就退回固定坐标（实测指南图标在 (1193,990)）
                Ln.w("$TAG: OCR 没点到「指南」，退回坐标点击")
                clickAt((auto.width * 0.6214f).toInt(), (auto.height * 0.9167f).toInt())
            }

            if (waitForGuideDaily(8)) {
                Ln.i("$TAG: 已到每日实训")
                return true
            }

            Ln.w("$TAG: 点指南后 ${'8'} 秒内没进到每日实训（第 ${i + 1} 轮）")
        }
        Ln.e("$TAG: 无法到达每日实训页")
        return false
    }

    /**
     * 轮询等待「每日实训」页出现。
     *
     * 页面是**浮层**（不是全屏切换），`pageChanged` 那类像素差判断
     * 在这里很不可靠，所以改成直接看 [GuideTab]。
     *
     * @param timeoutSec 最多等几秒
     */
    fun waitForGuideDaily(timeoutSec: Int = 8): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        var lastLog = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            if (guideTab() == GuideTab.DAILY) return true
            // 已经在指南的其它 tab，说明页开了只是 tab 不对 → 切过去
            val tab = guideTab()
            if (tab != null) {
                Ln.i("$TAG: 已进指南页（${tab.label}），切到每日实训")
                return switchGuideTab(GuideTab.DAILY)
            }
            val now = SystemClock.uptimeMillis()
            if (now - lastLog > 2_000) {
                lastLog = now
                Ln.i("$TAG: 等每日实训页…（已等 ${(now - (deadline - timeoutSec * 1000L)) / 1000}s）")
            }
            sleepInterruptible(400)
        }
        return false
    }


    /**
     * 导航到「星际和平指南 - 生存索引」（清体力的副本入口）。
     *
     * 路径：`main` → 点手机图标 → `menu` → 点「指南」→ 切到「生存索引」tab
     *
     * ## 判态靠 OCR，不靠图标模板
     *
     * 指南页打开后**默认停在「每日实训」**，而且 5 个 tab 是纯图标，
     * `guide3.png` 在真机上只有 0.648 分 —— 用它判态永远不会成功。
     *
     * 所以这里改成：
     * 1. `guideTab()` 用页内文字判断当前在哪个子标签；
     * 2. `switchGuideTab(SURVIVAL)` 按索引点 tab（唯一无字可读的地方）；
     * 3. 每步都用 OCR 复核。
     *
     * ## 每轮从大厅重来
     *
     * 失败先关掉回大厅，不在原地连点（原地连点是早期误触「梦境护照」的原因）。
     */
    fun gotoGuide3(): Boolean = gotoGuideTab(GuideTab.SURVIVAL)

    /**
     * 导航到「星际和平指南 - **逐光捡金**」（忘却之庭 / 虚构叙事 / 末日幻影的入口）。
     *
     * 和 [gotoGuide3] 只差一个 tab（第 3 个，index=2）。抽成
     * [gotoGuideTab] 之后两边共用同一套"每轮从大厅重来"的容错逻辑。
     */
    fun gotoGuide4(): Boolean = gotoGuideTab(GuideTab.RELICS)

    /**
     * 导航到指南的任意子标签。
     *
     * 路径：`main` → 点手机图标 → `menu` → 点「指南」→ 切到 [target] tab
     *
     * ## 判态靠 OCR，不靠图标模板
     *
     * 指南页打开后**默认停在「每日实训」**，而且 5 个 tab 是纯图标，
     * `guide3.png` 在真机上只有 0.648 分 —— 用它判态永远不会成功。
     *
     * 所以这里改成：
     * 1. `guideTab()` 用页内文字判断当前在哪个子标签；
     * 2. `switchGuideTab(target)` 按索引点 tab（唯一无字可读的地方）；
     * 3. 每步都用 OCR 复核。
     *
     * ## 每轮从大厅重来
     *
     * 失败先关掉回大厅，不在原地连点（原地连点是早期误触「梦境护照」的原因）。
     */
    private fun gotoGuideTab(target: GuideTab): Boolean {
        // 只判一次态 —— guideTab() 是一次全屏 OCR（0.5~1.5 秒），
        // 原来这里连着调两次，等于对同一帧跑了两遍完全相同的识别
        val onGuide = guideTab()
        if (onGuide == target) {
            Ln.i("$TAG: 已在${target.label}")
            return true
        }
        // 已在指南页（任一子标签），直接切 tab，不用再走一遍菜单
        if (onGuide != null) {
            Ln.i("$TAG: 已在指南页「${onGuide.label}」，直接切到${target.label}")
            if (switchGuideTab(target)) return true
        }

        repeat(3) { i ->
            // 熔断：触控通道已经确认瘸了就别再空转
            //
            // 输入注入连续失败说明问题不在界面识别上，重试只会在原地
            // 耗掉几分钟（日志里那次整整卡了 7 分钟才报错）。
            // 早点返回 false，让上层把准确原因交给用户。
            if (auto.consecutiveInputFailures >= INPUT_DEAD_THRESHOLD) {
                Ln.e(
                    "$TAG: 触控通道连续 ${auto.consecutiveInputFailures} 次注入失败" +
                        "（${auto.lastInputFailure}），放弃导航 —— 重试无意义，先排查输入链路",
                )
                return false
            }
            if (!openPhoneMenu()) {
                Ln.w("$TAG: 打开手机菜单失败（第 ${i + 1} 次）")
                return false
            }
            Ln.i("$TAG: 点「指南」图标")
            // ## 优化：直接 OCR，不再先试模板
            //
            // 实测日志里 `share/menu/guide.png` **每次都匹配不到**
            // （手游菜单的图标尺寸和 PC 版不同），额外多跑一次模板匹配
            // 纯属浪费，还挡住了后面本来能成功的 OCR。
            //
            // 指南是**带文字的卡片**，OCR 认字比模板匹配稳得多，
            // 而且位置随面板布局变也不影响。
            if (!clickTextAndVerify("指南", Hotspot.MENU_ICON_AREA, pageChanged = true, maxRetries = 2)) {
                Ln.w("$TAG: OCR 没点到「指南」，退回坐标点击")
                clickCrop(Hotspot.MENU_GUIDE, "指南")
            }

            // 等指南页出现（任一子标签都算），再切到目标 tab
            if (waitForGuidePage(20)) {
                trace("guide_page_ok")
                if (switchGuideTab(target)) {
                    trace("guide_${target.index}_ok")
                    return true
                }
                Ln.w("$TAG: 已打开指南但切不到${target.label}（第 ${i + 1} 轮）")
            } else {
                Ln.w("$TAG: 指南页未打开（第 ${i + 1} 次）")
            }
            trace("after_guide_click")

            if (!closeCurrent()) {
                Ln.w("$TAG: 关不掉当前界面，无法重试")
                return false
            }
        }
        // 把"点不出去"和"点了没反应"分开报 —— 这两种失败的上层处置完全不同
        val verdict = if (auto.lastInputFailure.isNotEmpty()) {
            "注入失败：${auto.lastInputFailure}"
        } else {
            "注入均成功，界面无响应（疑似被遮挡窗口吞掉输入）"
        }
        Ln.w("$TAG: 导航到${target.label}失败（3 轮）—— $verdict；连续注入失败 ${auto.consecutiveInputFailures} 次")
        return false
    }

    // ==================== 忘却之庭 / 混沌回忆 ====================

    /**
     * 当前是不是停在**混沌回忆页**（逐层挑战页）。
     *
     * ## 两条判据，模板优先、OCR 兜底
     *
     * 模板 `screen/forgottenhall/memory_of_chaos.png` 是 PC 端从
     * 「忘却之庭 / 混沌回忆」标题上抠的 104×47 小块。实测手游那颗
     * **金色漩涡图标比 PC 大约 15%**，同尺度下 CCOEFF 只有 0.5 上下，
     * 只能靠 [ImageUtils] 的多尺度搜索（0.70~1.60）救回来 —— 能中，但虚。
     *
     * 而这两行标题字很大、笔画清楚，OCR 几乎不会失手。所以：
     * **模板中了就是中了（快），模板没中再用 OCR 在左上角认字（稳）**。
     *
     * 这条判据一挂，传送之后的每一步都会走错，所以宁可多花一次区域 OCR。
     */
    fun isMemoryOfChaosPage(): Boolean {
        if (isAt(Screen.MEMORY_OF_CHAOS)) return true
        val hit = withFrameOcrRegion(Hotspot.CHAOS_TITLE) { mat, region ->
            OcrEngine.findText(mat, "混沌回忆", region)
                ?: OcrEngine.findText(mat, "忘却之庭", region)
        }
        if (hit != null) {
            Ln.i("$TAG: 当前界面 = memory_of_chaos（OCR「${hit.text}」）")
            return true
        }
        return false
    }

    /**
     * 在逐光捡金页点开「忘却之庭」，等右侧详情面板出现「混沌回忆」。
     *
     * ## 为什么分成两步（点入口 / 传送）
     *
     * 中间要插一步**「本期有没有刷新」的判断**（读 `12/36`）。如果
     * 已满星就不用传送进去了 —— 而那个判断属于任务逻辑，不该塞进导航层。
     * 所以导航层只做"打开详情"，传送由调用方决定。
     *
     * ## 点击策略
     *
     * 先点左侧列表里的「忘却之庭」；点完右侧详情面板是**异步渲染**的，
     * 所以之后要轮询等「混沌回忆」四个字出现，而不是固定 sleep。
     */
    fun openChaosDetail(): Boolean {
        // 已经在混沌回忆页，说明详情早就打开过了
        if (isMemoryOfChaosPage()) return true
        // 右侧详情已经在显示「混沌回忆」—— 上一次调用点过了，不重复点
        if (locateText("混沌回忆", Hotspot.RELICS_DETAIL) != null) {
            Ln.i("$TAG: 忘却之庭详情已打开")
            return true
        }

        repeat(3) { i ->
            Ln.i("$TAG: 点「忘却之庭」入口（第 ${i + 1} 次）")
            val clicked = clickText("忘却之庭", Hotspot.RELICS_ENTRY_LIST, maxRetries = 3) ||
                clickText("忘却之庭", null, maxRetries = 1)
            if (!clicked) {
                Ln.w("$TAG: 没找到「忘却之庭」入口")
                dumpCurrentScreenText("忘却之庭入口未找到")
                return false
            }

            // 等右侧详情渲染（标题「混沌回忆」出现）
            val deadline = SystemClock.uptimeMillis() + DETAIL_WAIT_MS
            while (SystemClock.uptimeMillis() < deadline) {
                if (isCancelled()) throw StopRequested()
                if (locateText("混沌回忆", Hotspot.RELICS_DETAIL) != null) {
                    Ln.i("$TAG: 已打开忘却之庭详情（混沌回忆）")
                    return true
                }
                SystemClock.sleep(400)
            }
            Ln.w("$TAG: 点了「忘却之庭」但右侧没出现「混沌回忆」（第 ${i + 1} 次）")
        }
        dumpCurrentScreenText("混沌回忆详情未出现")
        return false
    }

    /**
     * 读混沌回忆的**星数进度**（如 `12/36`）。
     *
     * @return `(当前星数, 总星数)`；读不到返回 null
     *
     * ## 为什么要限定区域
     *
     * 逐光捡金页上还有虚构叙事 / 末日幻影，它们的进度也是 `x/12`、`x/36`
     * 这种格式。全屏读会把别的模式的数字读进来，于是"本期已满星"
     * 会被误判成"没满"（或反过来）。所以只读右侧详情面板。
     *
     * 详情面板读不到才退回全屏（面板被弹窗盖住等异常情况）。
     */
    fun readChaosStars(total: Int = 36): Pair<Int, Int>? {
        val texts = readAllText(Hotspot.RELICS_DETAIL).ifEmpty { readAllText() }
        for (t in texts) {
            val s = t.normalized()
            // OCR 常把 `12/36` 读成 `12/36`、`12 /36`、`★12/36`
            val m = STAR_PROGRESS_RE.find(s) ?: continue
            val cur = m.groupValues[1].toIntOrNull() ?: continue
            val tot = m.groupValues[2].toIntOrNull() ?: continue
            if (tot != total) continue
            Ln.i("$TAG: 混沌回忆进度 = $cur/$tot（原文「${t.text}」）")
            return cur to tot
        }
        Ln.w("$TAG: 没读到混沌回忆的星数进度")
        return null
    }

    /**
     * 点「传送」，等真正进到混沌回忆页。
     *
     * ## 为什么点完还要再看一眼
     *
     * 传送会播一段转场（约 3~5 秒），之后**可能**先弹一个
     * 「点击空白处继续」的过场框（M7A 里那段 `click_message_box`）。
     * 所以这里：点传送 → 等 → 看到过场框就点掉 → 再判页面。
     */
    fun teleportToChaos(timeoutMs: Long = CHAOS_TELEPORT_TIMEOUT_MS): Boolean {
        if (isMemoryOfChaosPage()) return true

        repeat(2) { attempt ->
            Ln.i("$TAG: 点「传送」（第 ${attempt + 1} 次）")
            val clicked = clickText("传送", Hotspot.RELICS_DETAIL, maxRetries = 3) ||
                clickText("传送", null, maxRetries = 1)
            if (!clicked) {
                Ln.w("$TAG: 没找到「传送」按钮")
                dumpCurrentScreenText("传送按钮未找到")
                return false
            }

            sleepInterruptible(SETTLE_MS)
            val deadline = SystemClock.uptimeMillis() + timeoutMs
            while (SystemClock.uptimeMillis() < deadline) {
                if (isCancelled()) throw StopRequested()
                if (isMemoryOfChaosPage()) {
                    Ln.i("$TAG: 已进入混沌回忆页")
                    return true
                }
                // 过场框：点掉它，让流程继续
                if (dismissBlankTapPrompt()) continue
                SystemClock.sleep(700)
            }
            Ln.w("$TAG: 传送后 ${timeoutMs / 1000}s 内没进到混沌回忆页（第 ${attempt + 1} 次）")
        }
        dumpCurrentScreenText("传送后未进入混沌回忆")
        return false
    }

    /**
     * 混沌回忆关卡栏**左右滑动**。
     *
     * @param toRight true = 手指向右滑 ⇒ 内容右移 ⇒ 露出**更靠前**的层
     *   （第 1、2 层）；false = 手指向左滑 ⇒ 露出更靠后的层。
     *
     * 这里必须用滑动而不是"点箭头"：关卡栏没有箭头按钮，只有拖拽滚动。
     */
    fun swipeChaosLevelBar(toRight: Boolean, durationMs: Long = 320L): Boolean {
        val (x, y, w, h) = Hotspot.CHAOS_LEVEL_BAR.toPixels(auto.width, auto.height)
        if (w <= 0 || h <= 0) return false
        val cy = y + h / 2
        val left = x + (w * 0.30f).toInt()
        val right = x + (w * 0.70f).toInt()
        val (from, to) = if (toRight) left to right else right to left
        val ok = auto.swipe(from, cy, to, cy, durationMs, steps = 10)
        SystemClock.sleep(600)
        return ok
    }

    /**
     * 点掉「点击空白处继续」这类**过场提示框**。
     *
     * @return true = 确实点掉了（调用方应立刻重新判态）
     *
     * 崩铁的加载/过场提示框只有底部一行字，位置不固定，所以做区域 OCR
     * 找文字本身再点它 —— 比写死坐标稳。
     */
    fun dismissBlankTapPrompt(): Boolean {
        val hit = withFrameOcrRegion(CROP_BLANK_TAP_PROMPT) { mat, region ->
            BLANK_TAP_TEXTS.firstNotNullOfOrNull { t -> OcrEngine.findText(mat, t, region) }
        } ?: return false
        Ln.i("$TAG: 点过场提示「${hit.text}」@(${hit.cx},${hit.cy})")
        auto.tap(hit.cx, hit.cy, durationMs = 100L)
        SystemClock.sleep(SETTLE_MS)
        return true
    }

    /**
     * 画面是不是停在「画面恢复中…」这类加载遮罩上。
     *
     * 11-02 10:59 实测：点完「退出关卡」后游戏进入加载，遮罩盖着整屏 ——
     * 判态认不出大厅，也永远找不到可点的 ✕。旧代码就在这个状态下按 BACK
     * 去"自救"，每次都把「是否退出游戏」叠上来，连按 6 轮全部落空。
     *
     * 遮罩期间点什么都没反应，唯一正确的动作是等。
     */
    fun isLoadingOverlay(): Boolean =
        withFrameOcrRegion(CROP_LOADING_OVERLAY) { mat, region ->
            OcrEngine.findText(mat, "画面恢复", region)
        } != null

    /** 把当前整屏 OCR 结果写进日志 —— 卡住时的唯一有效证据。 */
    fun dumpCurrentScreenText(tag: String) {
        runCatching {
            val texts = readAllText()
            if (texts.isEmpty()) {
                Ln.w("$TAG: [$tag] 全屏 OCR 一条文字都没认出来")
            } else {
                Ln.w(
                    "$TAG: [$tag] 全屏 OCR ${texts.size} 条：" +
                        texts.joinToString(" | ") { "${it.normalized()}@(${it.cx},${it.cy})" },
                )
            }
        }.onFailure { Ln.w("$TAG: [$tag] 全屏 OCR 失败：${it.message}") }
    }

    /** 画面变化后的高频观察窗口（毫秒）。页面淡入 + 加载一般 1~2 秒。 */
    private val GUIDE_WATCH_MS = 2_000L

    /** 观察窗口外的强制复查间隔（毫秒）。 */
    private val GUIDE_IDLE_CHECK_MS = 1_000L

    /**
     * 等指南页出现（任意子标签）。
     *
     * ## 门控缺陷（0.6.0beta 实测踩中，11-01 08:07 那次）
     *
     * 旧实现是「画面变了才跑一次 OCR，跑完立刻把基准帧换成当前帧」。
     * 日志原文：
     *
     * ```
     * 08:07:39.365 点击区域 指南 (1193,990)
     * 08:07:39.788 界面差异 26.0（阈值 8.0）⇒ 已变化   ← 唯一的 OCR 机会
     * 08:07:40.944 界面差异 0.2（阈值 8.0）⇒ 未变化   ← 此后一直静止
     * …… 整整 20 秒没有一次 OCR ……
     * 08:07:59.664 指南页未打开（第 3 次）
     * ```
     *
     * 那一次 OCR 正好踩在淡入动画的中间帧上，`guideTab()` 读到的是
     * 半透明过渡画面 → 返回 null；随后页面加载完成、画面静止，
     * `frameChanged` 恒为 false，OCR 再也不跑，硬等到超时。
     *
     * 而同一时刻导出的 `026_after_guide_click.jpg` 里，指南页已经
     * 完全打开、停在「每日实训」标签、活跃度 200 清晰可见 ——
     * 页面早就到位了，是判定逻辑自己闭上了眼睛。
     *
     * ## 修法（与 `PowerTask.waitForLeaveGuide()` 对齐）
     *
     * 1. 画面一变化就进入 2 秒高频观察期，观察期内**每轮都判**
     * 2. 观察期外**每秒强制复查一次**，不再依赖画面变化
     *
     * 代价是页面没打开时每秒多一次全屏 OCR（约 1.5s），但比
     * 白等 20 秒后误报失败要划算得多。
     */
    private fun waitForGuidePage(timeoutSec: Int, pollMs: Long = 500L): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        var baseline: org.opencv.core.Mat? = null
        var lastCheck = 0L
        var watchingUntil = 0L
        try {
            while (SystemClock.uptimeMillis() < deadline) {
                val frame = grabFrameMat()
                if (frame == null) {
                    SystemClock.sleep(200)
                    continue
                }
                val base = baseline
                if (base == null) {
                    baseline = frame.clone()
                    frame.release()
                    SystemClock.sleep(pollMs)
                    continue
                }
                val now = SystemClock.uptimeMillis()
                if (frameChanged(base, frame)) {
                    // 画面在动 → 开一个观察窗，窗内每轮都判
                    watchingUntil = now + GUIDE_WATCH_MS
                    base.release()
                    baseline = frame.clone()
                }
                frame.release()
                val shouldCheck = now < watchingUntil || now - lastCheck >= GUIDE_IDLE_CHECK_MS
                if (shouldCheck) {
                    lastCheck = now
                    if (guideTab() != null) return true
                }
                SystemClock.sleep(pollMs)
            }
            return false
        } finally {
            baseline?.release()
        }
    }
}
