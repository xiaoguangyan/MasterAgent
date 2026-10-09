/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Arbiter —— 仲裁：score = 0.6·矩阵 + 0.4·置信度（JEPA β=0 纯监督）。
 *       路由：置信度 ≥0.80 执行 / 0.40–0.79 澄清确认 / <0.40 兜底降级（阈值从模型配置读取）。
 *       多意图：取全部已知候选（routeHint!=null）按 seq 排序成有序域列表 domains，主域为首个；
 *       未注册候选（routeHint==null）不参与执行，交由上游「跳过并提示」。
 *       旁路埋点（trace）：场景矩阵评分 + 仲裁公式，供表示层无死角可视化。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.ArbitrationBlock
import com.xiaoguang.masteragent.core.bus.ITraceCollector
import com.xiaoguang.masteragent.core.bus.IntentBlock
import com.xiaoguang.masteragent.core.bus.ModelConfig
import com.xiaoguang.masteragent.core.bus.Route
import com.xiaoguang.masteragent.core.bus.Stage
import com.xiaoguang.masteragent.core.bus.Status
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.bus.TraceSource

class Arbiter(
    private val matrixScorer: MatrixScorer = MatrixScorer(),
    private val trace: ITraceCollector? = null,
    private val modelConfig: ModelConfig = ModelConfig(),
) {

    fun arbitrate(intent: IntentBlock, scene: String = "DRIVING"): ArbitrationBlock {
        // 多意图：已知候选按 seq 排序；主域 = 首个已知候选
        val known = intent.intents.filter { it.routeHint != null }.sortedBy { it.seq }
        if (known.isEmpty()) {
            trace?.emit(
                TraceEvent(
                    source = TraceSource.MASTER, stage = Stage.ARBITRATE, status = Status.DEGRADED,
                    title = "场景矩阵评分",
                    detail = "domain=NONE（全部子句未注册）→ FALLBACK",
                    input = "domain=NONE", output = "matrix=-",
                ),
            )
            return ArbitrationBlock(domainAgent = null, eventType = null, scene = scene, score = 0.0, confidence = 0.0, route = Route.FALLBACK, domains = emptyList())
        }

        val primary = known.first()
        val domain = primary.routeHint
        val confidence = primary.confidence
        val domains = known.mapNotNull { it.routeHint }
        val matrix = matrixScorer.score(domain)
        val score = 0.6 * matrix + 0.4 * (confidence * 100.0)
        val route = routeOf(confidence)

        trace?.emit(
            TraceEvent(
                source = TraceSource.MASTER, stage = Stage.ARBITRATE, status = Status.OK,
                title = "场景矩阵评分",
                detail = "domains=[${domains.joinToString(",")}] matrix=${f1(matrix)}",
                input = "domains=[${domains.joinToString(",")}]", output = "matrix=${f1(matrix)}",
            ),
        )
        val formula = "score=${f1(score)} = 0.6·matrix(${f1(matrix)}) + 0.4·conf(${f2(confidence)}·100)"
        trace?.emit(
            TraceEvent(
                source = TraceSource.MASTER, stage = Stage.ARBITRATE, status = Status.OK,
                title = "仲裁公式",
                detail = "$formula  route=$route（≥${modelConfig.slowLane.executeThreshold} 执行 / ${modelConfig.slowLane.confirmThreshold}–${modelConfig.slowLane.executeThreshold} 澄清 / <${modelConfig.slowLane.confirmThreshold} 兜底）",
                input = "matrix=${f1(matrix)} confidence=${f2(confidence)}", output = "$formula route=$route",
            ),
        )

        return ArbitrationBlock(
            domainAgent = domain,
            eventType = domain,
            scene = scene,
            score = score,
            confidence = confidence,
            route = route,
            domains = domains,
        )
    }

    private fun routeOf(confidence: Double): Route = when {
        confidence >= modelConfig.slowLane.executeThreshold -> Route.EXECUTE
        confidence >= modelConfig.slowLane.confirmThreshold -> Route.CONFIRM
        else -> Route.FALLBACK
    }

    private fun f1(v: Double) = "%.1f".format(v)
    private fun f2(v: Double) = "%.2f".format(v)
}
