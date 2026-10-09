/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：WindowAgent —— 车窗域 Agent：根据指令方向（开/关）输出执行块。
 */

package com.xiaoguang.masteragent.feature.domain

import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType

class WindowAgent : IDomainAgent {

    override val id = "window"
    override val relationType = RelationType.COMMAND

    override suspend fun handle(msg: IntentMessage): ExecutionBlock {
        val close = msg.input.utterance.contains("关")
        val reply = if (close) "已为您关闭车窗" else "已为您打开车窗"
        val action = if (close) "close" else "open"
        return ExecutionBlock(domainAgent = id, result = mapOf("reply" to reply, "action" to "set_window", "open" to action))
    }
}
