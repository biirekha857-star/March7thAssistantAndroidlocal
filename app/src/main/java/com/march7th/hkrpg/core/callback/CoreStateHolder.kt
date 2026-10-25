package com.march7th.hkrpg.core.callback

import com.march7th.hkrpg.domain.state.TaskExecutionState

interface CoreStateHolder {
    fun currentRunState(): TaskExecutionState

    fun reportRunState(state: TaskExecutionState)

    /**
     * 回调侧请求中止整条任务队列
     *
     * core 的 Stop 动作只结束当前任务链，队列里后续任务照跑；掉线这类场景要整体停
     * 实现须异步执行，不能阻塞 core 回调线程
     */
    fun requestStopFromCallback()
}
