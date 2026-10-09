/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Planner + PlanPolicy —— 规划（先规划后执行）：域 → 工具映射，生成单步 DAG（MVP）。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.ArbitrationBlock
import com.xiaoguang.masteragent.core.bus.Plan
import com.xiaoguang.masteragent.core.bus.PlanNode
import com.xiaoguang.masteragent.core.bus.PlanningBlock

/** 规划策略：域 → 工具名 */
class PlanPolicy {
    fun toolFor(domain: String): String = when (domain) {
        "climate" -> "set_ac"
        "window" -> "set_window"
        "seat" -> "set_seat"
        "media" -> "play_media"
        "navigation" -> "start_nav"
        "energy" -> "check_energy"
        else -> "unknown"
    }
}

class Planner(private val policy: PlanPolicy = PlanPolicy()) {

    /** 规划：仲裁有序域列表 → 多节点 DAG（串行执行，节点顺序即 seq 顺序；单意图退化为单节点） */
    fun plan(arb: ArbitrationBlock): PlanningBlock {
        val domains = arb.domains.ifEmpty { arb.domainAgent?.let { listOf(it) } ?: emptyList() }
        if (domains.isEmpty()) return PlanningBlock(mode = "PLAN_THEN_EXECUTE", plan = Plan())
        val nodes = domains.mapIndexed { i, d -> PlanNode(id = "n${i + 1}", agent = d, tool = policy.toolFor(d)) }
        return PlanningBlock(mode = "PLAN_THEN_EXECUTE", plan = Plan(dag = nodes))
    }
}
