/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：MasterAgent —— L2 Master 主控：意图组合 → 仲裁 → 规划 → 执行 → 结果。
 *       全链路分阶段旁路打点（含正常与错误）+ 检查点断点续跑；模型提案与网关执行严格分离。
 *       上下文/记忆以「观察者」接入（副作用不回流入本次结果），各自埋点 trace，供表示层可视化。
 *       每个环节事件均带 input/output 字段：表示层渲染为「输入 → 环节 → 输出」流程图节点。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.AiBBus
import com.xiaoguang.masteragent.core.bus.ArbitrationBlock
import com.xiaoguang.masteragent.core.bus.Channel
import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.ICheckpointStore
import com.xiaoguang.masteragent.core.bus.IContextManager
import com.xiaoguang.masteragent.core.bus.IMemoryService
import com.xiaoguang.masteragent.core.bus.ITraceCollector
import com.xiaoguang.masteragent.core.bus.IntentBlock
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.MemoryItem
import com.xiaoguang.masteragent.core.bus.ResultBlock
import com.xiaoguang.masteragent.core.bus.Route
import com.xiaoguang.masteragent.core.bus.Stage
import com.xiaoguang.masteragent.core.bus.Status
import com.xiaoguang.masteragent.core.bus.Step
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.bus.TraceSource
import com.xiaoguang.masteragent.core.bus.toRecord

class MasterAgent(
    private val bus: AiBBus,
    private val composer: IntentComposer,
    private val arbiter: Arbiter,
    private val planner: Planner,
    private val executor: Executor,
    private val gateway: SafetyGateway,
    private val checkpoint: ICheckpointStore,
    private val trace: ITraceCollector,
    private val context: IContextManager? = null,
    private val memory: IMemoryService? = null,
) {

    /** 主流程：单条消息全链路处理，返回带完整阶段产出与结果的消息 */
    suspend fun run(msg: IntentMessage): IntentMessage {
        val h = msg.header
        val start = now()
        val u = msg.input.utterance

        // 0) 输入
        trace.emit(em(h, Stage.INPUT, "收到指令", "utterance=$u", input = u, output = "进入主控管线 channel=${h.channel.name}"))

        return try {
            var cur = msg

            // 1) 上下文写入（L1 环形 + L2 车态；由 ContextManager 自埋点 trace，此处只调用）
            if (context != null) context.push(cur)

            // 2) 意图组合
            var t = now()
            val intent = composer.compose(cur)
            cur = cur.copy(intent = intent)
            checkpoint.save(cur.toRecord(Stage.INTENT, Stage.INTENT))
            trace.emit(
                em(
                    h, Stage.INTENT, "意图组合", intentDetail(intent),
                    elapsed = now() - t, input = u, output = intentDetail(intent),
                ),
            )
            trace.emit(em(h, Stage.INTENT, "检查点保存", "stage=INTENT", source = TraceSource.CHECKPOINT, input = "意图组合结果", output = "已落检查点"))

            // 3) 记忆召回（只 trace、不注入置信度，保证结果不变；由 MemoryService 自埋点）
            if (memory != null) memory.recall(cur.input.utterance, topK = 3)

            // 4) 仲裁（声源定位 zone → 场景，增强仲裁）
            t = now()
            val arb = arbiter.arbitrate(intent, sceneOf(cur.input.perception["zone"]))
            cur = cur.copy(arbitration = arb)
            checkpoint.save(cur.toRecord(Stage.ARBITRATE, Stage.ARBITRATE))
            val arbOut = "route=${arb.route} score=${f1(arb.score)} confidence=${f2(arb.confidence)} domain=${arb.domainAgent ?: "-"}"
            trace.emit(
                em(
                    h, Stage.ARBITRATE, "仲裁", arbOut,
                    elapsed = now() - t, input = intentDetail(intent), output = arbOut,
                ),
            )
            trace.emit(em(h, Stage.ARBITRATE, "检查点保存", "stage=ARBITRATE", source = TraceSource.CHECKPOINT, input = "仲裁结果", output = "已落检查点"))

            // 5) 路由分派
            val result = when (arb.route) {
                Route.EXECUTE -> {
                    trace.emit(em(h, Stage.ARBITRATE, "路由", "EXECUTE 直接执行 domain=${arb.domainAgent ?: "-"}", input = "route=EXECUTE", output = "进入规划→执行 domain=${arb.domainAgent ?: "-"}"))
                    executePath(cur, arb)
                }
                Route.CONFIRM -> cur.confirm().also {
                    trace.emit(em(h, Stage.ARBITRATE, "路由", "CONFIRM 澄清确认（置信度 0.40–0.79）", status = Status.DEGRADED, input = "route=CONFIRM", output = "澄清确认，交由用户二选一"))
                }
                Route.FALLBACK -> cur.fallback().also {
                    trace.emit(em(h, Stage.ARBITRATE, "路由", "FALLBACK 兜底降级（置信度 <0.40）", status = Status.DEGRADED, input = "route=FALLBACK", output = "规则兜底，降级回复"))
                }
            }

            // 6) 记忆沉淀（记本次意图；隐私合规：沉淀前 PII 脱敏；失败不阻断主流程）
            if (memory != null) {
                val item = MemoryItem(
                    memoryId = "mem-${h.messageId}",
                    factTime = now(),
                    recordTime = now(),
                    content = PrivacyFilter.sanitize(cur.input.utterance),
                )
                try { memory.remember(item) } catch (_: Throwable) {}
            }

            // 7) 上下文快照（L0–L4；由 ContextManager 自埋点 trace）
            if (context != null) context.snapshot(budget = 8000)

            // 8) 总线发布
            bus.publish(result)
            val topic = "${h.sessionId}:${result.header.stage.name}"
            trace.emit(em(h, Stage.RESULT, "总线发布", "topic=$topic", source = TraceSource.BUS, input = "最终结果", output = "发布到 topic=$topic"))

            // 9) 终态 + 结果
            checkpoint.save(result.toRecord(Stage.RESULT, Stage.RESULT))
            trace.emit(em(h, Stage.RESULT, "检查点保存", "stage=RESULT", source = TraceSource.CHECKPOINT, input = "终态结果", output = "已落检查点"))
            val reply = result.result?.reply ?: "-"
            trace.emit(
                em(
                    h, Stage.RESULT, "处理完成",
                    "route=${result.arbitration?.route?.name ?: "-"} reply=$reply",
                    elapsed = now() - start, input = "route=${result.arbitration?.route?.name ?: "-"}", output = "reply=$reply",
                ),
            )

            result
        } catch (e: Throwable) {
            trace.emit(em(h, Stage.RESULT, "执行异常", "msg=${e.message}", status = Status.ERROR, input = u, output = "异常：${e.message}"))
            msg.copy(result = ResultBlock(reply = "执行异常：${e.message}", fallback = "ERROR"))
        }
    }

    /** 执行路径：规划 → 执行 → 安全网关 → 结果 */
    private suspend fun executePath(cur: IntentMessage, arb: ArbitrationBlock): IntentMessage {
        val h = cur.header
        val domain = arb.domainAgent ?: "-"

        // 规划
        var t = now()
        val plan = planner.plan(arb)
        var c = cur.copy(planning = plan)
        checkpoint.save(c.toRecord(Stage.PLAN, Stage.PLAN))
        val tool = plan.plan.dag.firstOrNull()?.tool ?: "-"
        trace.emit(em(h, Stage.PLAN, "规划", "mode=${plan.mode} agent=$domain tool=$tool", elapsed = now() - t, input = "domain=$domain", output = "mode=${plan.mode} tool=$tool"))
        trace.emit(em(h, Stage.PLAN, "检查点保存", "stage=PLAN", source = TraceSource.CHECKPOINT, input = "规划结果", output = "已落检查点"))

        // 执行
        t = now()
        val exec = executor.execute(c, plan)
        val tools = exec.toolCalls.joinToString { it.tool }
        val execReply = exec.result["reply"] ?: "-"
        trace.emit(em(h, Stage.EXECUTE, "执行", "agent=${exec.domainAgent} tools=${tools.ifBlank { "-" }} reply=$execReply", elapsed = now() - t, input = "tool=${tools.ifBlank { "-" }} agent=${exec.domainAgent}", output = "reply=$execReply"))

        // 安全网关
        t = now()
        val gate = gateway.check(exec.toolCalls)
        val guarded = when (gate.verdict) {
            GateVerdict.PASS -> exec.copy(safetyGate = "PASS")
            GateVerdict.CONFIRM -> exec.copy(
                safetyGate = "CONFIRM",
                result = mapOf("reply" to "操作需二次确认：${exec.domainAgent ?: ""}"),
            )
            GateVerdict.BLOCK -> exec.copy(
                safetyGate = "BLOCK",
                result = mapOf("reply" to "操作已被安全网关拦截：${exec.domainAgent ?: ""}"),
            )
        }
        val guardStatus = when (gate.verdict) {
            GateVerdict.PASS -> Status.OK
            GateVerdict.CONFIRM -> Status.DEGRADED
            GateVerdict.BLOCK -> Status.ERROR
        }
        trace.emit(em(h, Stage.EXECUTE, "安全网关", "verdict=${guarded.safetyGate} ${gate.reason}", status = guardStatus, elapsed = now() - t, input = "tools=${tools.ifBlank { "-" }}", output = "verdict=${guarded.safetyGate} ${gate.reason}"))

        // 未注册子句（routeHint=null）跳过执行，最终回复里提示「暂不支持」
        val unsupported = c.intent?.intents
            ?.filter { it.routeHint == null }
            ?.mapNotNull { it.text.takeIf { t -> t.isNotBlank() } } ?: emptyList()
        c = c.copy(execution = guarded, result = toResultBlock(guarded, unsupported))
        checkpoint.save(c.toRecord(Stage.EXECUTE, Stage.EXECUTE))
        trace.emit(em(h, Stage.EXECUTE, "检查点保存", "stage=EXECUTE", source = TraceSource.CHECKPOINT, input = "执行结果", output = "已落检查点"))
        return c
    }

    /** 断点续跑：将未完成检查点推进至 RESULT，返回续跑条数 */
    suspend fun resumeUnfinished(): Int {
        val unfinished = checkpoint.listUnfinished()
        unfinished.forEach { r ->
            trace.span(Step.RESUME, Channel.FAST, Status.DEGRADED) {
                checkpoint.save(r.copy(lastCompleted = Stage.RESULT, updatedAt = System.currentTimeMillis()))
            }
        }
        return unfinished.size
    }

    // ---- 内部：trace 事件构造与摘要 ----

    /** 构造带消息溯源的 trace 事件（表示层按 traceId/sessionId/messageId 分组） */
    private fun em(
        h: Header,
        stage: Stage,
        title: String,
        detail: String = "",
        status: Status = Status.OK,
        source: String = TraceSource.MASTER,
        elapsed: Long = 0L,
        input: String = "",
        output: String = "",
    ): TraceEvent = TraceEvent(
        traceId = h.traceId,
        sessionId = h.sessionId,
        messageId = h.messageId,
        source = source,
        stage = stage,
        channel = h.channel,
        status = status,
        elapsedMs = elapsed,
        title = title,
        detail = detail,
        input = input,
        output = output,
    )

    /** 意图块摘要（供 trace detail 展示）：逐候选列出 seq/域/置信度/槽位（多意图可见） */
    private fun intentDetail(intent: IntentBlock): String {
        if (intent.intents.isEmpty()) return "status=${intent.status} reason=${intent.reasonCode}"
        return intent.intents.joinToString(" | ") { c ->
            "seq=${c.seq} domain=${c.routeHint ?: "UNKNOWN"} conf=${f2(c.confidence)} slots=${c.slots}"
        }
    }

    private fun f1(v: Double) = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)
    private fun now() = System.currentTimeMillis()

    /** 声源定位 zone → 场景（仲裁增强：主驾 DRIVING / 副驾 PASSENGER / 后排 REAR） */
    private fun sceneOf(zone: String?): String = when (zone) {
        "副驾" -> "PASSENGER"
        "后排", "rear" -> "REAR"
        else -> "DRIVING"
    }

    /** 结果块装配（多模态输出 + 分段应答，FR-UI-01 / FR-UI-02）；未注册子句追加「暂不支持」提示 */
    private fun toResultBlock(exec: ExecutionBlock, unsupported: List<String> = emptyList()): ResultBlock {
        var reply = exec.result["reply"] ?: "已执行"
        if (unsupported.isNotEmpty()) {
            reply += "；暂不支持：" + unsupported.joinToString("、") { "「$it」" }
        }
        val segments = reply.split(Regex("(?<=[。！？；，])")).filter { it.isNotBlank() }
        return ResultBlock(
            action = exec.result,
            reply = reply,
            segments = segments,
            modalities = listOf("text", "tts"),
        )
    }
}
