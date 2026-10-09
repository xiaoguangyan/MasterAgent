/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：CommandLifecycle —— 下发取消 / 挂起 / 恢复（迭代 2）：按命令 id 维护执行态机。
 *       issue 下发 → RUNNING；suspend 挂起 → SUSPENDED；resume 恢复 → RESUMED；cancel 取消 → CANCELLED。
 *       取消 / 挂起后由执行侧据此中断，避免已下发指令不可控。
 */

package com.xiaoguang.masteragent.feature.master

enum class CommandState { RUNNING, SUSPENDED, RESUMED, CANCELLED }

class CommandLifecycle {

    private val states = LinkedHashMap<String, CommandState>()

    /** 下发新命令 */
    fun issue(id: String): CommandState = set(id, CommandState.RUNNING)

    /** 挂起命令 */
    fun suspend(id: String): CommandState = set(id, CommandState.SUSPENDED)

    /** 恢复命令 */
    fun resume(id: String): CommandState = set(id, CommandState.RESUMED)

    /** 取消命令 */
    fun cancel(id: String): CommandState = set(id, CommandState.CANCELLED)

    fun stateOf(id: String): CommandState? = states[id]

    private fun set(id: String, state: CommandState): CommandState {
        states[id] = state
        return state
    }
}
