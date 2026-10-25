package com.march7th.hkrpg.starrail

/**
 * 副本类型表（清体力用）。
 *
 * 对应 M7A `tasks/power/power.py` 的 `instances_power` +
 * `attempts_per_run_max` 两张表，以及 `tasks/power/instance.py`
 * 里按类型分支的判断。
 *
 * ## 为什么不能只靠 `PowerInstance` 枚举
 *
 * `PowerInstance` 是**外壳侧**的枚举（要序列化进 DataStore、
 * 要渲染成 UI 选项），它只承载"用户能看到的选择"。
 * 而这里需要的是**内核侧运行参数**：单次体力、连打上限、
 * 默认副本名。两者职责不同，分开更不容易互相污染。
 *
 * ## `defaultInstanceName` 怎么来的
 *
 * 崩铁「生存索引」选中副本类型后，右侧列表里每个副本都有自己的
 * 名字（如「拟造花萼（金）」下是「藏珍之蕾·毁灭之径」这类）。
 * 首次进入时无法知道用户要刷哪个子本，所以给一个**最常用**的默认值，
 * 并且允许外壳通过 `instanceName` 参数覆盖。
 */
internal object PowerInstanceTable {

    /**
     * 一条副本类型定义。
     *
     * @param wireName 游戏内 UI 上的原文（OCR 匹配目标，必须逐字一致，
     *   括号是全角 `（）`）
     * @param powerPerRun 单次挑战消耗的开拓力
     * @param maxAttemptsPerRun 一次「连续挑战」最多几次
     * @param defaultInstanceName 列表里的默认子副本名；空串表示
     *   "进列表后点第一个能点的"（由 [PowerTask] 兜底处理）
     */
    data class Entry(
        val wireName: String,
        val powerPerRun: Int,
        val maxAttemptsPerRun: Int,
        val defaultInstanceName: String,
    )

    private val ALL = listOf(
        // ⚠️ defaultInstanceName 必须是**游戏里真实存在的副本名**，
        // 且要是右侧列表项文字的**子串**（OCR 用 contains 匹配）。
        //
        // 「拟造花萼（金）」右侧列表实测内容（1920×1080 生存索引帧）：
        //   回忆之蕾（二相乐园）、以太之蕾（鸽川区）、藏珍之蕾（鸽川区）…
        //
        // ## 全部留空 —— 改成「跟随游戏内的培养目标」
        //
        // 崩铁的「培养目标」会把**该角色缺少的材料副本置顶到列表第一位**
        // （角色详情 → 培养目标，设置后生效）。
        //
        // 所以这里**不需要写死任何副本名**：
        // 把 defaultInstanceName 留空，[PowerTask] 就会点列表里
        // 第一个可进入的副本 —— 而它就是培养目标要的那个。
        //
        // 好处：
        // - 换角色/换材料**不用改代码**，游戏端改了目标就自动跟随；
        // - 不依赖 OCR 认副本名（认错一个字的概率更低）；
        // - 覆盖所有副本类型（凝滞虚影的命途、侵蚀隧洞的套装同理置顶）。
        //
        // 前提：用户需要在游戏里设置过培养目标。没设的话就是原有的
        // 默认排序，点第一个也能动，只是不一定是想要的材料。
        Entry("拟造花萼（金）", 10, 24, ""),
        Entry("拟造花萼（赤）", 10, 24, ""),
        Entry("凝滞虚影", 30, 8, ""),
        Entry("侵蚀隧洞", 40, 6, ""),
        Entry("饰品提取", 40, 6, ""),
        Entry("历战余响", 30, 3, ""),
    )

    fun of(wireName: String): Entry? = ALL.firstOrNull { it.wireName == wireName }

    fun all(): List<Entry> = ALL
}
