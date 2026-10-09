/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：RuleModelGateway —— 规则模型网关（端侧快道实现 IModelGateway）。
 *       真实部署可替换为 SA8295P 端侧 1.5B SLM（QNN 推理）；chat() 为云端慢道，端侧 MVP 占位。
 */

package com.xiaoguang.masteragent.core.model

import com.xiaoguang.masteragent.core.bus.IModelGateway
import com.xiaoguang.masteragent.core.bus.IntentBlock
import com.xiaoguang.masteragent.core.bus.IntentCandidate
import com.xiaoguang.masteragent.core.bus.Route

class RuleModelGateway(private val parser: FastPathParser) : IModelGateway {

    /** 云端慢道大模型：端侧 MVP 不接入，占位返回空串 */
    override suspend fun chat(system: String, user: String): String = ""

    /** 端侧本地推理：规则快道 → 候选意图块 */
    override suspend fun inferLocal(input: String): IntentBlock {
        val p = parser.parse(input)
        if (p.domain == null) {
            return IntentBlock(status = Route.FALLBACK, reasonCode = "NO_MATCH", intents = emptyList())
        }
        val candidate = IntentCandidate(
            seq = 1,
            confidence = p.baseConfidence,
            slots = p.slots,
            routeHint = p.domain,
        )
        return IntentBlock(status = Route.EXECUTE, reasonCode = "OK", intents = listOf(candidate))
    }
}
