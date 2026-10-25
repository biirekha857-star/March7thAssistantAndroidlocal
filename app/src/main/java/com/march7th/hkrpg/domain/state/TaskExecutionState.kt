package com.march7th.hkrpg.domain.state

enum class TaskExecutionState {
    IDLE,      // 空闲
    STARTING,  // 启动中
    RUNNING,   // 运行中
    STOPPING,  // 停止中
    ERROR      // 错误
}