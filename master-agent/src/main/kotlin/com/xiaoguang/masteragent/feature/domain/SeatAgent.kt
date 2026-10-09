/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：SeatAgent —— 座椅域 Agent：输出座椅调节执行块。
 */

package com.xiaoguang.masteragent.feature.domain

import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType

class SeatAgent : IDomainAgent {

    override val id = "seat"
    override val relationType = RelationType.COMMAND

    override suspend fun handle(msg: IntentMessage): ExecutionBlock =
        ExecutionBlock(domainAgent = id, result = mapOf("reply" to "已为您调整座椅", "action" to "set_seat"))
}
