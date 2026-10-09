/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：IntentComposer —— 意图组合（多意图）：整句拆分 → 逐子句解析 → 多候选 → 置信度融合。
 *       大模型拆分节点（M-intent，云端慢道 chat，Prompt A1-split）为慢道主路径，规则分句为兜底；
 *       逐子句规则解析时，置信度不足仍走端侧 inferLocal 慢道补强，再融合 base/llm/ctx/slot 四项信号。
 *       各步骤旁路埋点（trace）：意图拆分 → 快道规则解析 → 快慢思考 → 大模型调用 → 置信度融合。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.Channel
import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.IModelGateway
import com.xiaoguang.masteragent.core.bus.ITraceCollector
import com.xiaoguang.masteragent.core.bus.IntentBlock
import com.xiaoguang.masteragent.core.bus.IntentCandidate
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.ModelConfig
import com.xiaoguang.masteragent.core.bus.Route
import com.xiaoguang.masteragent.core.bus.Stage
import com.xiaoguang.masteragent.core.bus.Status
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.bus.TraceSource
import com.xiaoguang.masteragent.core.model.ConfidenceFusion
import com.xiaoguang.masteragent.core.model.FastPathParser
import com.xiaoguang.masteragent.core.model.IntentSplitter

class IntentComposer(
    private val fastPathParser: FastPathParser,
    private val slowPathLLM: IModelGateway?,
    private val confidenceFusion: ConfidenceFusion,
    private val trace: ITraceCollector? = null,
    private val modelConfig: ModelConfig = ModelConfig(),
) {

    suspend fun compose(msg: IntentMessage): IntentBlock {
        val h = msg.header
        val u = msg.input.utterance

        // 1) 意图拆分：分句 → 逐子句解析（大模型拆分优先，规则分句兜底）
        val candidates = splitAndCompose(h, u, msg)

        val known = candidates.filter { it.routeHint != null }
        if (known.isEmpty()) {
            trace?.emit(
                TraceEvent(
                    traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                    source = TraceSource.MASTER, stage = Stage.INTENT, channel = Channel.FAST,
                    status = Status.DEGRADED, title = "意图拆分",
                    detail = "全部子句未命中已注册域 → 规则兜底 NO_MATCH → FALLBACK",
                    input = u, output = "domain=NONE → NO_MATCH",
                ),
            )
            return IntentBlock(
                status = Route.FALLBACK,
                reasonCode = "NO_MATCH",
                nl = "未命中任何领域关键词，走规则兜底",
                needsRag = isKnowledgeQuestion(u),
                intents = emptyList(),
            )
        }

        val domains = known.mapNotNull { it.routeHint }.distinct()
        val zone = msg.input.perception["zone"]
        return IntentBlock(
            status = Route.EXECUTE,
            reasonCode = "OK",
            nl = "识别为「${domains.joinToString("、")}」意图" + (zone?.let { "，声源=$it" } ?: ""),
            needsRag = isKnowledgeQuestion(u),
            intents = candidates,
        )
    }

    /** 拆分 + 逐子句组装候选：大模型拆分成功直接采用，否则规则逐子句解析 */
    private suspend fun splitAndCompose(h: Header, u: String, msg: IntentMessage): List<IntentCandidate> {
        val segments = IntentSplitter.splitSegments(u)

        // 1) 大模型拆分节点（M-intent 云端慢道）：多子句且慢道可用时调用
        if (segments.size > 1 && slowPathLLM != null) {
            val mIntentName = modelConfig.model("m_intent")?.name ?: "M-intent"
            trace?.emit(
                TraceEvent(
                    traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                    source = TraceSource.MASTER, stage = Stage.INTENT, channel = Channel.SLOW,
                    status = Status.OK, title = "意图拆分（M-intent）",
                    detail = "调用 $mIntentName（Prompt A1-split）拆分整句",
                    input = "chat(A1-split, \"${u.take(24)}\")", output = "",
                ),
            )
            val t0 = System.currentTimeMillis()
            val llmCandidates = IntentSplitter.parseLlmIntents(slowPathLLM.chat(IntentSplitter.PROMPT_A1_SPLIT, u))
            val llmOut = llmCandidates
                ?.joinToString(" ") { "seq=${it.seq} domain=${it.routeHint ?: "UNKNOWN"} conf=${f2(it.confidence)}" }
                ?: "（慢道未返回结构化结果 → 回退规则分句）"
            trace?.emit(
                TraceEvent(
                    traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                    source = TraceSource.MASTER, stage = Stage.INTENT, channel = Channel.SLOW,
                    status = if (llmCandidates == null) Status.DEGRADED else Status.OK,
                    elapsedMs = System.currentTimeMillis() - t0, title = "意图拆分结果",
                    detail = llmOut, input = "", output = llmOut,
                ),
            )
            if (llmCandidates != null) {
                // 大模型拆分成功：seq 重排 + 补齐子句文本，直接采用
                return llmCandidates.sortedBy { it.seq }.mapIndexed { i, c ->
                    c.copy(seq = i + 1, text = c.text.ifBlank { segments.getOrElse(i) { "" } })
                }
            }
        }

        // 2) 规则逐子句解析 + 融合（兜底 / 单意图路径）
        val out = mutableListOf<IntentCandidate>()
        for ((i, seg) in segments.withIndex()) {
            out.add(composeSegment(h, seg, i + 1, msg))
        }
        return out
    }

    /** 单子句组合：规则解析 → 慢道补强（端侧 inferLocal）→ 置信度融合 → 候选 */
    private suspend fun composeSegment(h: Header, seg: String, seq: Int, msg: IntentMessage): IntentCandidate {
        val p = fastPathParser.parse(seg)
        if (p.domain == null) {
            trace?.emit(
                TraceEvent(
                    traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                    source = TraceSource.MASTER, stage = Stage.INTENT, channel = Channel.FAST,
                    status = Status.DEGRADED, title = "快道规则解析",
                    detail = "seq=$seq text=$seg domain=NONE base=0.00 → INTENT_UNKNOWN",
                    input = seg, output = "domain=NONE",
                ),
            )
            return IntentCandidate(seq = seq, confidence = 0.0, routeHint = null, text = seg)
        }

        val fastOut = "seq=$seq text=$seg domain=${p.domain} base=${f2(p.baseConfidence)} slots=${p.slots}"
        trace?.emit(
            TraceEvent(
                traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                source = TraceSource.MASTER, stage = Stage.INTENT, channel = Channel.FAST,
                status = Status.OK, title = "快道规则解析",
                detail = fastOut, input = seg, output = fastOut,
            ),
        )

        // 上下文 / 槽位相关度 + 多模感知
        val perception = msg.input.perception
        val zone = perception["zone"]
        val ctx = if (msg.input.contextRef != null || perception.isNotEmpty()) 0.9 else 0.7
        val slot = if (p.slots.isNotEmpty()) 1.0 else 0.7

        // 快慢思考：慢道阈值从配置读取
        val trigger = modelConfig.slowLane.slowTriggerThreshold
        var llm: Double? = null
        if (p.baseConfidence < trigger && slowPathLLM != null) {
            val edgeName = modelConfig.model("m_intent_edge")?.name ?: "端侧 SLM"
            trace?.emit(
                TraceEvent(
                    traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                    source = TraceSource.MASTER, stage = Stage.INTENT, channel = Channel.SLOW,
                    status = Status.OK, title = "快慢思考",
                    detail = "seq=$seq base=${f2(p.baseConfidence)} < $trigger → 触发慢道，调用 $edgeName（端侧 QNN）",
                    input = "base=${f2(p.baseConfidence)} 阈值=$trigger", output = "走慢道：调用 $edgeName（端侧 QNN）补强",
                ),
            )
            val llmBlock = slowPathLLM.inferLocal(seg)
            val llmTop = llmBlock.intents.firstOrNull()
            llm = llmTop?.confidence
        } else if (p.baseConfidence < trigger) {
            trace?.emit(
                TraceEvent(
                    traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                    source = TraceSource.MASTER, stage = Stage.INTENT, channel = Channel.FAST,
                    status = Status.OK, title = "快慢思考",
                    detail = "seq=$seq base=${f2(p.baseConfidence)} 快道命中，不触发慢道（阈值 $trigger）",
                    input = "base=${f2(p.baseConfidence)} 阈值=$trigger", output = "走快道：跳过慢道大模型",
                ),
            )
        }

        // 置信度融合
        val fused = confidenceFusion.fuse(p.baseConfidence, llm, ctx, slot)
        val formula = if (llm == null) {
            "seq=$seq score=${f2(fused.score)} = (0.30·base + 0.15·ctx + 0.15·slot) / 0.60"
        } else {
            "seq=$seq score=${f2(fused.score)} = 0.30·base(${f2(p.baseConfidence)}) + 0.40·llm(${f2(llm)}) + 0.15·ctx(${f2(ctx)}) + 0.15·slot(${f2(slot)})"
        }
        trace?.emit(
            TraceEvent(
                traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                source = TraceSource.MASTER, stage = Stage.INTENT, status = Status.OK,
                title = "置信度融合", detail = formula, input = "base=${f2(p.baseConfidence)} llm=${llm?.let { f2(it) } ?: "-"}", output = formula,
            ),
        )

        val slots = p.slots + (zone?.let { mapOf("zone" to it) } ?: emptyMap())
        return IntentCandidate(
            seq = seq,
            confidence = fused.score,
            confidenceBreakdown = fused.breakdown,
            slots = slots,
            routeHint = p.domain,
            text = seg,
        )
    }

    /** 知识问答检测（FR-INT-07 RAG）：命中疑问句式则置 needsRag，走知识检索 */
    private fun isKnowledgeQuestion(u: String): Boolean =
        KNOWLEDGE_KEYWORDS.any { u.contains(it) }

    private fun f2(v: Double) = "%.2f".format(v)

    companion object {
        /** 知识问答疑问词（触发 RAG） */
        val KNOWLEDGE_KEYWORDS = listOf("什么是", "为什么", "怎么", "如何", "介绍", "原理", "什么意思", "教程")
    }
}
