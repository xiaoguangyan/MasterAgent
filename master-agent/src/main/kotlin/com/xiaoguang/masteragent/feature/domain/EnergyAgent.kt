/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：EnergyAgent —— 能源生态域 Agent（迭代 2）：续航 / 能耗上报与节能模式控制。
 *       relationType = REPORT：能源域为「上报/查询」归属，演示归属关系 + 权限审计（只读工具）。
 */

package com.xiaoguang.masteragent.feature.domain

import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType

class EnergyAgent : IDomainAgent {

    override val id = "energy"
    override val relationType = RelationType.REPORT

    override suspend fun handle(msg: IntentMessage): ExecutionBlock {
        val u = msg.input.utterance
        val reply = if (u.contains("节能")) {
            "已开启节能模式，预计续航提升 8%"
        } else {
            "当前续航 320km，能耗 15.2kWh/100km"
        }
        return ExecutionBlock(
            domainAgent = id,
            result = mapOf("reply" to reply, "action" to "check_energy"),
        )
    }
}
