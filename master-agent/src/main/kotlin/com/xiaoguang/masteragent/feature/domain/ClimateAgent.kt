/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ClimateAgent —— 空调域 Agent：解析温度槽位，输出执行块。
 */

package com.xiaoguang.masteragent.feature.domain

import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType

class ClimateAgent : IDomainAgent {

    override val id = "climate"
    override val relationType = RelationType.COMMAND

    override suspend fun handle(msg: IntentMessage): ExecutionBlock {
        val temp = "\\d+".toRegex().find(msg.input.utterance)?.value ?: "26"
        return ExecutionBlock(
            domainAgent = id,
            result = mapOf("reply" to "已为您打开空调，温度 $temp℃", "action" to "set_ac", "temp" to temp),
        )
    }
}
