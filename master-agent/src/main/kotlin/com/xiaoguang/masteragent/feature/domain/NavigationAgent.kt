/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：NavigationAgent —— 导航域 Agent：解析目的地关键词，输出导航执行块。
 */

package com.xiaoguang.masteragent.feature.domain

import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType

class NavigationAgent : IDomainAgent {

    override val id = "navigation"
    override val relationType = RelationType.COMMAND

    override suspend fun handle(msg: IntentMessage): ExecutionBlock {
        val dest = when {
            msg.input.utterance.contains("回家") -> "家"
            msg.input.utterance.contains("公司") -> "公司"
            else -> "目的地"
        }
        return ExecutionBlock(
            domainAgent = id,
            result = mapOf("reply" to "已为您规划前往「$dest」的路线", "action" to "start_nav", "dest" to dest),
        )
    }
}
