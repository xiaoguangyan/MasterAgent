/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：MediaAgent —— 媒体域 Agent：输出媒体播放执行块。
 */

package com.xiaoguang.masteragent.feature.domain

import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType

class MediaAgent : IDomainAgent {

    override val id = "media"
    override val relationType = RelationType.COMMAND

    override suspend fun handle(msg: IntentMessage): ExecutionBlock =
        ExecutionBlock(domainAgent = id, result = mapOf("reply" to "已为您播放音乐", "action" to "play_media"))
}
