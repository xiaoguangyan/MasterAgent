/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：TraceEvent —— 跨模块执行链路 trace 事件契约（旁路采集，不改变业务返回值）。
 *       由 master-agent / context / memory 埋点产出，经 ITraceCollector.observe() 供表示层订阅渲染，
 *       实现「数据流可视化」与「表示层 / 核心解耦」：消费方只依赖本契约类型，不触核心实现。
 */

package com.xiaoguang.masteragent.core.bus

import kotlinx.serialization.Serializable

/**
 * 一条执行链路 trace 事件。
 *
 * @param seq        全局单调序号（跨子系统按时间统一排序）
 * @param tsMs       事件时间戳
 * @param traceId    溯源标识（master-agent 事件填；context/memory 无消息上下文时可空）
 * @param sessionId  会话标识
 * @param messageId  消息标识
 * @param source     产出子系统：MASTER / CONTEXT / MEMORY / CHECKPOINT / BUS
 * @param stage      所属管线阶段（INPUT/INTENT/ARBITRATE/PLAN/EXECUTE/RESULT，供分组）
 * @param channel    快慢通道（FAST/SLOW）
 * @param status     执行状态（OK / ERROR / DEGRADED）
 * @param elapsedMs  本阶段耗时（毫秒）
 * @param title      人读标题，如「意图组合」「上下文写入」「记忆召回」
 * @param detail     该阶段数据摘要（紧凑 key=value，兼容旧渲染）
 * @param input      该环节的「输入」（表示层渲染为节点左侧/上方输入区；大模型调用时为提示词）
 * @param output     该环节的「输出」（表示层渲染为节点右侧/下方输出区；大模型调用时为生成结果）
 */
@Serializable
data class TraceEvent(
    val seq: Long = 0L,
    val tsMs: Long = 0L,
    val traceId: String = "",
    val sessionId: String = "",
    val messageId: String = "",
    val source: String = TraceSource.MASTER,
    val stage: Stage = Stage.INPUT,
    val channel: Channel = Channel.FAST,
    val status: Status = Status.OK,
    val elapsedMs: Long = 0L,
    val title: String = "",
    val detail: String = "",
    val input: String = "",
    val output: String = "",
)

/** trace 来源子系统常量 */
object TraceSource {
    const val MASTER = "MASTER"          // 主控管线各阶段
    const val CONTEXT = "CONTEXT"        // 上下文（push / snapshot）
    const val MEMORY = "MEMORY"          // 记忆（recall / remember / clear）
    const val CHECKPOINT = "CHECKPOINT"  // 检查点保存
    const val BUS = "BUS"                // 总线发布
}
