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

    /** 菜单网格里的关键词，命中 2 个即判定在菜单。 */
    private val MENU_KEYWORDS = listOf(
        "商店", "好友", "委托", "旅情事记", "合成", "成就",
        "短信", "无名勋礼", "跃迁", "角色", "指南", "联机玩法",
        "导航", "教学目录",
    )

    private const val MENU_OPEN_TIMEOUT_MS = 2_000L

    /** 菜单轮询间隔。 */
    private const val MENU_POLL_MS = 150L

    /** 打开邮件页的最长等待。 */
    /** 点完邮件图标后等多久再判页面（转场 + 列表渲染）。 */
    private const val MAIL_LOAD_MS = 1_000L

    private const val MAIL_OPEN_TIMEOUT_MS = 2_500L

    /** 邮件页轮询间隔。 */
    private const val MAIL_POLL_MS = 150L
        private const val MENU_SETTLE_CHECKS = 3

        /** 单轮调试导出上限。 */
        private const val MAX_DUMPS = 60

        /** 全量记录上限（一次校准通常几十张就够）。 */
        private const val MAX_CAPTURES = 300
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
         * 手机菜单右上角的 **✕ 关闭**。
         *
         * 实测 bbox = (1794,5)-(1851,62)，57×57，中心 **(1822, 34)**。
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
            1794f / 1920f, 5f / 1080f, 57f / 1920f, 57f / 1080f,
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
    private fun capture(label: String) {
        if (!captureAll) return
        val root = debugDumpRoot ?: return
        val n = captureCount.incrementAndGet()
        if (n > MAX_CAPTURES) return
        val ts = java.text.SimpleDateFormat("HHmmss_SSS", java.util.Locale.US)
            .format(java.util.Date())
        dumpFrame(root, "cap_%04d_%s_%s".format(n, ts, label.replace('/', '_')))
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
    fun dumpFrame(root: java.io.File, label: String): String? {
        if (!auto.takeScreenshot()) {
            Ln.w("$TAG: dumpFrame 取帧失败")
            return null
        }
        val png = auto.framePng() ?: return null
        return runCatching {
            val dir = java.io.File(root, "debug/screenshots").apply { mkdirs() }
            val f = java.io.File(dir, "$label.png")
            f.writeBytes(png)
            Ln.i("$TAG: 已导出帧 ${f.absolutePath} (${auto.width}x${auto.height})")
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
            // 生存索引页也可能不显示大标题，用它的特征词兜底
            if (all.contains("生存索引") || all.contains("凝滞虚影") || all.contains("拟造花萼")) {
                return GuideTab.SURVIVAL
            }
            return null
        }
        return when {
            all.contains("生存索引") || all.contains("凝滞虚影") || all.contains("拟造花萼") ->
                GuideTab.SURVIVAL
            all.contains("逐光捡金") -> GuideTab.RELICS
            all.contains("旷宇纷争") || all.contains("历战余响") -> GuideTab.WAR
            else -> GuideTab.DAILY   // 有「星际和平指南/每日实训」标题但不是别的，就是每日实训
        }
    }

    /**
     * 星际和平指南的子标签。顺序与顶部 tab 从左到右一致。
     */
    enum class GuideTab(val label: String, val index: Int) {
        DAILY("每日实训", 0),
        SURVIVAL("生存索引", 1),
        RELICS("逐光捡金", 2),
        WAR("旷宇纷争", 3),
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
     * | 第 1 个 tab 高亮区 | x ∈ [208, 358]（宽 151） |
     * | tab 栏纵向范围 | y ∈ [130, 215] |
     * | 5 个 tab 均分 | 中心 x = 283, 434, 585, 736, 887 |
     *
     * 所以**每个 tab 的等宽步长是 151 像素**，起点 208。
     * 这是全模块**唯一**保留坐标点击的地方（确实无字可读）。
     */
    private object GuideBar {
        /** 第 1 个 tab 的左边界（像素，1920 宽基准）。 */
        const val LEFT = 208f

        /** 单个 tab 宽度（像素）。 */
        const val TAB_W = 151f

        /** tab 栏中心 y（像素，1080 高基准）。 */
        const val CENTER_Y = 172f

        /** 基准帧尺寸。 */
        const val BASE_W = 1920f
        const val BASE_H = 1080f
    }

    /**
     * 切到指定子标签。
     *
     * @return 切换后确实处于目标标签（**用 OCR 复核**，不看点击返回值）
     */
    fun switchGuideTab(target: GuideTab): Boolean {
        if (guideTab() == target) {
            Ln.i("$TAG: 已在指南子标签「${target.label}」")
            return true
        }
        repeat(3) { attempt ->
            // 按基准坐标折算到当前帧（虚拟屏固定 1920×1080，折算比恒为 1）
            val sx = auto.width / GuideBar.BASE_W
            val sy = auto.height / GuideBar.BASE_H
            val cx = ((GuideBar.LEFT + GuideBar.TAB_W * (target.index + 0.5f)) * sx).toInt()
            val cy = (GuideBar.CENTER_Y * sy).toInt()
            Ln.i("$TAG: 点指南子标签「${target.label}」@($cx,$cy)（第 ${attempt + 1} 次）")
            auto.tap(cx, cy)
            sleepInterruptible(SETTLE_MS)
            val now = guideTab()
            if (now == target) {
                Ln.i("$TAG: 已切换到「${target.label}」")
                return true
            }
            Ln.w("$TAG: 切换后仍在「${now?.label ?: "未知页"}」，重试")
        }
        Ln.w("$TAG: 切换指南子标签「${target.label}」失败")
        return false
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
     * 关掉当前弹出的界面，回到主界面。
     *
     * 手游没有 ESC，只能点界面自带的 ✕。
     *
     * ⚠️ **手机菜单必须走专用坐标**：M7A 的两个通用关闭模板
     * （`zh_CN/base/click_close.png` 0.30、`share/plot/close_phone.png` 0.54）
     * 在手游菜单上都**匹配不到**，只靠它们会一直关不掉。
     */
    fun closeCurrent(maxAttempts: Int = 4): Boolean {
        if (isAt(Screen.MAIN)) return true
        repeat(maxAttempts) { i ->
            // ① 先按 BACK —— 最通用的返回方式
            //    手游上绝大多数面板/子界面都响应系统返回键。
            //    实测邮件页、菜单页的 ✕ 都匹配不到 M7A 的关闭模板
            //    （click_close 0.35 / close_phone 0.54），所以 BACK 是主力。
            Ln.i("$TAG: 尝试 BACK 键返回（第 ${i + 1} 次）")
            auto.pressKey(android.view.KeyEvent.KEYCODE_BACK)
            sleepInterruptible(SETTLE_MS)
            if (isAt(Screen.MAIN)) return true

            // ② 手机菜单 → 点它自己的右上角 ✕
            if (isAt(Screen.MENU)) {
                Ln.i("$TAG: 在手机菜单，点右上角 ✕ 关闭")
                clickCrop(Hotspot.MENU_CLOSE, "菜单关闭")
                sleepInterruptible(SETTLE_MS)
                if (isAt(Screen.MAIN)) return true
            }
            // ③ 通用 ✕（多数弹窗/子界面右上角）
            if (clickImage("zh_CN/base/click_close.png", 0.80, maxRetries = 1)) {
                sleepInterruptible(SETTLE_MS)
                if (isAt(Screen.MAIN)) return true
            }
            // ④ 手机界面的 ✕（M7A 模板，手游上不一定命中，作为兜底）
            if (clickImage("share/plot/close_phone.png", 0.80, maxRetries = 1)) {
                sleepInterruptible(SETTLE_MS)
                if (isAt(Screen.MAIN)) return true
            }
            trace("close_attempt${i + 1}")
        }
        return isAt(Screen.MAIN)
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
    fun openPhoneMenu(): Boolean {
        if (isAt(Screen.MENU)) {
            Ln.i("$TAG: 已在手机菜单")
            return true
        }
        // 手机图标只在大厅可见；不在大厅就先回去
        if (!isAt(Screen.MAIN)) {
            Ln.w("$TAG: 不在大厅，先返回再开菜单")
            if (!closeCurrent()) {
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
            auto.tap(ix, iy)
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
            if (isAt(Screen.MENU)) {
                Ln.i("$TAG: 菜单已出现，等 ${MENU_SETTLE_MS}ms 让它稳定")
                sleepInterruptible(MENU_SETTLE_MS)
                return true
            }
            sleepInterruptible(MENU_POLL_MS)
        }
        Ln.w("$TAG: 点了手机图标但未进入菜单")
        return false
    }

    /**
     * 回到主界面。
     *
     * 直接委托 [closeCurrent]（它知道手机菜单要用专用 ✕ 关）。
     */
    fun backToMain(maxAttempts: Int = 6): Boolean = closeCurrent(maxAttempts)

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
    fun gotoGuide3(): Boolean {
        // 已经在目标页
        if (guideTab() == GuideTab.SURVIVAL) {
            Ln.i("$TAG: 已在生存索引")
            return true
        }
        // 已在指南页（任一子标签），直接切 tab，不用再走一遍菜单
        val onGuide = guideTab()
        if (onGuide != null) {
            Ln.i("$TAG: 已在指南页「${onGuide.label}」，直接切到生存索引")
            if (switchGuideTab(GuideTab.SURVIVAL)) return true
        }

        repeat(3) { i ->
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

            // 等指南页出现（任一子标签都算），再切到生存索引
            if (waitForGuidePage(20)) {
                trace("guide_page_ok")
                if (switchGuideTab(GuideTab.SURVIVAL)) {
                    trace("guide3_ok")
                    return true
                }
                Ln.w("$TAG: 已打开指南但切不到生存索引（第 ${i + 1} 轮）")
            } else {
                Ln.w("$TAG: 指南页未打开（第 ${i + 1} 次）")
            }
            trace("after_guide_click")

            if (!closeCurrent()) {
                Ln.w("$TAG: 关不掉当前界面，无法重试")
                return false
            }
        }
        Ln.w("$TAG: 导航到生存索引失败（3 轮）")
        return false
    }

    /** 等指南页出现（任意子标签）。 */
    private fun waitForGuidePage(timeoutSec: Int, pollMs: Long = 300L): Boolean {
        // ## 优化：先比像素，变了才跑 OCR
        //
        // 原来是每 1.2 秒直接跑一次 `guideTab()`（全屏 OCR，约 1.5s），
        // 光等一个页面就要 2.7 秒起步。
        //
        // 现在每 300ms 取一帧比像素差（几十毫秒），只有画面真的变了
        // 才值得做一次 OCR 确认 —— 页面没切过来时完全不碰 OCR。
        val deadline = SystemClock.uptimeMillis() + timeoutSec * 1000L
        var baseline: org.opencv.core.Mat? = null
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
                if (!frameChanged(base, frame)) {
                    frame.release()
                    SystemClock.sleep(pollMs)
                    continue
                }
                frame.release()
                base.release()
                baseline = null
                // 画面变了 → 做一次 OCR 确认是不是指南页
                if (guideTab() != null) return true
                SystemClock.sleep(pollMs)
            }
            return false
        } finally {
            baseline?.release()
        }
    }
}
