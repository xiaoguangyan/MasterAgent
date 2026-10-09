/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：RealtimeStore —— L0/L2 实时车态：StateFlow 订阅即推、写入 update{} 原子。
 */

package com.xiaoguang.masteragent.core.context

import com.xiaoguang.masteragent.core.bus.IStateSource
import com.xiaoguang.masteragent.core.bus.RealtimeState
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class RealtimeStore(initial: RealtimeState = RealtimeState()) : IStateSource {

    private val _state = MutableStateFlow(initial)

    /** 对外只读状态流 */
    val state: StateFlow<RealtimeState> = _state.asStateFlow()

    /** 原子更新（StateFlow 内部 CAS，线程安全） */
    fun update(block: (RealtimeState) -> RealtimeState) {
        _state.value = block(_state.value)
    }

    override fun observe(): StateFlow<RealtimeState> = state
}
