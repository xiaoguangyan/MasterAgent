/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：IntentMessage 各阶段块 —— 意图 / 槽位 / 仲裁 / 规划 / 执行（对齐 PRD §9.14）。
 */

package com.xiaoguang.masteragent.core.bus

import kotlinx.serialization.Serializable

/** 置信度分解：base / llm / ctx / slot 四项来源 */
@Serializable
data class ConfidenceBreakdown(
    val base: Double = 0.0,
    val llm: Double = 0.0,
    val ctx: Double = 0.0,
    val slot: Double = 0.0,
)

/** 候选意图 */
@Serializable
data class IntentCandidate(
    val seq: Int = 1,
    val confidence: Double = 0.0,
    val confidenceBreakdown: ConfidenceBreakdown = ConfidenceBreakdown(),
    val slots: Map<String, String> = emptyMap(),
    val routeHint: String? = null,
    val dependsOn: List<Int> = emptyList(),
    /** 该候选对应的子句文本（多意图拆解后逐条溯源；routeHint=null 时用于「未支持子句」提示） */
    val text: String = "",
)

/** 意图块：状态 + 原因码 + 候选意图 + 安全标记 + 自然语言解释 + RAG 标记 */
@Serializable
data class IntentBlock(
    val status: Route = Route.EXECUTE,
    val reasonCode: String = "",
    /** reason_code 成对的自然语言解释（FR-OBS-02：code 与 nl 成对输出） */
    val nl: String = "",
    val intents: List<IntentCandidate> = emptyList(),
    val safety: SafetyBlock = SafetyBlock(),
    /** 是否需知识问答 RAG 引用（FR-INT-07） */
    val needsRag: Boolean = false,
)

/** 安全标记 */
@Serializable
data class SafetyBlock(val dangerous: Boolean = false)

/** 槽位块：已填 / 缺失 / 归一化 */
@Serializable
data class SlotBlock(
    val filled: Map<String, String> = emptyMap(),
    val missing: List<String> = emptyList(),
    val normalized: Map<String, String> = emptyMap(),
)

/** 仲裁块：唯一路由（domain_agent）+ 场景 + 评分 + 置信度 */
@Serializable
data class ArbitrationBlock(
    val domainAgent: String? = null,
    val eventType: String? = null,
    val scene: String = "DRIVING",
    val score: Double = 0.0,
    val confidence: Double = 0.0,
    val route: Route = Route.EXECUTE,
    /** 多意图有序可执行域列表（按 seq 排序，供规划建多节点 DAG；单意图时仅一个元素） */
    val domains: List<String> = emptyList(),
)

/** 规划节点（DAG 顶点） */
@Serializable
data class PlanNode(
    val id: String,
    val agent: String,
    val tool: String,
    val dependsOn: List<String> = emptyList(),
)

/** 执行计划：有向无环图（MVP 单步） */
@Serializable
data class Plan(val dag: List<PlanNode> = emptyList())

/** 规划块 */
@Serializable
data class PlanningBlock(
    val mode: String = "PLAN_THEN_EXECUTE",
    val plan: Plan = Plan(),
)

/** 工具调用 */
@Serializable
data class ToolCall(
    val id: String,
    val agent: String,
    val tool: String,
    val args: Map<String, String> = emptyMap(),
)

/** 执行块：域 Agent 产出 + 安全网关判定 + 结果 */
@Serializable
data class ExecutionBlock(
    val domainAgent: String? = null,
    val toolCalls: List<ToolCall> = emptyList(),
    val safetyGate: String = "PASS",
    val result: Map<String, String> = emptyMap(),
)

/** 执行块 → 结果块（回复取自域 Agent 的 result.reply） */
fun ExecutionBlock.toResult(): ResultBlock {
    val reply = result["reply"] ?: "已执行"
    return ResultBlock(action = result, reply = reply)
}
