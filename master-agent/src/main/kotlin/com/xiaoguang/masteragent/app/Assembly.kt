/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Assembly —— 依赖装配：手工构造对象图（无 DI 框架，架构保持极致简洁）。
 *       可注入端侧模型网关（IModelGateway）：真实车机接 Qualcomm Hexagon NPU + QNN 端侧 SLM。
 */

package com.xiaoguang.masteragent.app

import com.xiaoguang.masteragent.core.bus.AiBBus
import com.xiaoguang.masteragent.core.bus.IContextManager
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.IMemoryService
import com.xiaoguang.masteragent.core.bus.IModelGateway
import com.xiaoguang.masteragent.core.bus.ITraceCollector
import com.xiaoguang.masteragent.core.bus.ModelConfig
import com.xiaoguang.masteragent.core.context.ContextManager
import com.xiaoguang.masteragent.core.daemon.CheckpointStore
import com.xiaoguang.masteragent.core.memory.MemoryService
import com.xiaoguang.masteragent.core.model.ConfidenceFusion
import com.xiaoguang.masteragent.core.model.FastPathParser
import com.xiaoguang.masteragent.core.observe.ExecutionTraceCollector
import com.xiaoguang.masteragent.feature.domain.ClimateAgent
import com.xiaoguang.masteragent.feature.domain.EnergyAgent
import com.xiaoguang.masteragent.feature.domain.MediaAgent
import com.xiaoguang.masteragent.feature.domain.NavigationAgent
import com.xiaoguang.masteragent.feature.domain.SeatAgent
import com.xiaoguang.masteragent.feature.domain.WindowAgent
import com.xiaoguang.masteragent.feature.master.Arbiter
import com.xiaoguang.masteragent.feature.master.Executor
import com.xiaoguang.masteragent.feature.master.IntentComposer
import com.xiaoguang.masteragent.feature.master.MasterAgent
import com.xiaoguang.masteragent.feature.master.MatrixScorer
import com.xiaoguang.masteragent.feature.master.PlanPolicy
import com.xiaoguang.masteragent.feature.master.Planner
import com.xiaoguang.masteragent.feature.master.SafetyGateway

class Assembly(
    /** 端侧模型网关 SPI（可空：为空时仅走快道规则，不接慢道 LLM） */
    private val modelGateway: IModelGateway? = null,
    /** 执行链路 trace 采集器（共享实例注入 master/context/memory，表示层经 observe() 订阅） */
    val trace: ITraceCollector = ExecutionTraceCollector(),
    /** 模型配置（PRD §2.11 模型表 + 慢道阈值，默认从 classpath model-config.json 加载） */
    private val modelConfig: ModelConfig = ModelConfigLoader.fromClasspath("model-config.json"),
) {

    val bus = AiBBus()

    /** 记忆服务（记/理/忆 + 敏感拦截 + 一键清除） */
    val memory: IMemoryService = MemoryService(trace = trace)

    /** 上下文服务（L0-L4 分层） */
    val context: IContextManager = ContextManager(memory = memory, trace = trace)

    /** Master 主控：意图组合 → 仲裁 → 规划 → 执行 → 结果 */
    val master: MasterAgent

    init {
        val parser = FastPathParser()
        val composer = IntentComposer(parser, slowPathLLM = modelGateway, confidenceFusion = ConfidenceFusion(), trace = trace, modelConfig = modelConfig)
        val arbiter = Arbiter(MatrixScorer(), trace = trace, modelConfig = modelConfig)
        val planner = Planner(PlanPolicy())
        val agents: Map<String, IDomainAgent> = mapOf(
            "climate" to ClimateAgent(),
            "window" to WindowAgent(),
            "seat" to SeatAgent(),
            "media" to MediaAgent(),
            "navigation" to NavigationAgent(),
            "energy" to EnergyAgent(),
        )
        val executor = Executor(agents)
        val gateway = SafetyGateway()
        val checkpoint = CheckpointStore()

        master = MasterAgent(
            bus = bus,
            composer = composer,
            arbiter = arbiter,
            planner = planner,
            executor = executor,
            gateway = gateway,
            checkpoint = checkpoint,
            trace = trace,
            context = context,
            memory = memory,
        )
    }
}
