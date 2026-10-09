/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：AIB 总线 —— 承载 IntentMessage 在五层间流转的唯一载体（HLD §10.5）。
 *       发布/订阅 + 冷流背压；单会话串行、多会话隔离（按 session_id + stage 分通道）。
 */

package com.xiaoguang.masteragent.core.bus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class AiBBus {

    /** 主题表：session_id + stage 分区，各自持有独立的共享流 */
    private val topics = LinkedHashMap<String, MutableSharedFlow<IntentMessage>>()

    private fun flowFor(topic: String): MutableSharedFlow<IntentMessage> =
        topics.getOrPut(topic) { MutableSharedFlow(extraBufferCapacity = 64) }

    /** 发布：挂起发射，下游消费不足时触发背压 */
    suspend fun publish(msg: IntentMessage) {
        val topic = "${msg.header.sessionId}:${msg.header.stage.name}"
        flowFor(topic).emit(msg)
    }

    /** 订阅：返回冷流（背压），供域 Agent / 可视化界面消费 */
    fun subscribe(sessionId: String, stage: Stage): Flow<IntentMessage> =
        flowFor("$sessionId:${stage.name}").asSharedFlow()

    /** 主题数量（供观测/诊断） */
    fun topicCount(): Int = topics.size
}
