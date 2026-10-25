package com.march7th.hkrpg.core

/**
 * MaaCore 回调消息类型
 * 与 C++ 端 AsstMsg 枚举保持一致
 */
enum class AsstMsg(val value: Int) {
    /* ============ Global Info ============ */

    /** 内部错误 */
    InternalError(0),

    /** 初始化失败 */
    InitFailed(1),

    /** 连接相关信息 */
    ConnectionInfo(2),

    /** 全部任务完成 */
    AllTasksCompleted(3),

    /** 外部异步调用信息 */
    AsyncCallInfo(4),

    /** 实例已销毁 */
    Destroyed(5),

    /* ============ TaskChain Info ============ */

    /** 任务链执行/识别错误 */
    TaskChainError(10000),

    /** 任务链开始 */
    TaskChainStart(10001),

    /** 任务链完成 */
    TaskChainCompleted(10002),

    /** 任务链额外信息 */
    TaskChainExtraInfo(10003),

    /** 任务链手动停止 */
    TaskChainStopped(10004),

    /* ============ SubTask Info ============ */

    /** 原子任务执行/识别错误 */
    SubTaskError(20000),

    /** 原子任务开始 */
    SubTaskStart(20001),

    /** 原子任务完成 */
    SubTaskCompleted(20002),

    /** 原子任务额外信息 */
    SubTaskExtraInfo(20003),

    /** 原子任务手动停止 */
    SubTaskStopped(20004),

    /* ============ Report ============ */

    /** 上报请求 */
    ReportRequest(30000);

    companion object {
        private val map = entries.associateBy { it.value }

        fun fromValue(value: Int): AsstMsg? = map[value]
    }
}
