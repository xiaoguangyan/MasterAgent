/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Executor —— 执行器：按 DAG 顺序调度域 Agent，聚合工具调用与结果（MVP 单步串行）。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.PlanningBlock
import com.xiaoguang.masteragent.core.bus.ToolCall

class Executor(private val agents: Map<String, IDomainAgent>) {

    suspend fun execute(msg: IntentMessage, plan: PlanningBlock): ExecutionBlock {
        val nodes = plan.plan.dag
        val calls = mutableListOf<ToolCall>()
        val replies = mutableListOf<String>()
        var domain: String? = null

        for (node in nodes) {
            val agent = agents[node.agent] ?: throw NoDomainAgentException(node.agent)
            domain = node.agent
            calls.add(ToolCall(id = "tc-${node.id}", agent = node.agent, tool = node.tool))
            val result = agent.handle(msg).result
            replies.add(result["reply"] ?: "已执行")
        }
        // 多意图串行执行：逐域聚合回复；单意图退化为单条
        val joined = if (replies.size > 1) replies.joinToString("；") else replies.firstOrNull() ?: "已执行"
        return ExecutionBlock(domainAgent = domain, toolCalls = calls, safetyGate = "PASS", result = mapOf("reply" to joined))
    }
}
