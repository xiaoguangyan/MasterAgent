/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：MasterAgent 主链路单元测试 —— 快道执行 / 兜底降级 / 断点续跑。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.AiBBus
import com.xiaoguang.masteragent.core.bus.ExecutionBlock
import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.Input
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType
import com.xiaoguang.masteragent.core.bus.Route
import com.xiaoguang.masteragent.core.daemon.CheckpointStore
import com.xiaoguang.masteragent.core.model.ConfidenceFusion
import com.xiaoguang.masteragent.core.model.FastPathParser
import com.xiaoguang.masteragent.core.observe.ExecutionTraceCollector
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MasterAgentRunTest {

    /** 内存版气候域 Agent（测试桩，避免跨模块依赖） */
    private val climateStub = object : IDomainAgent {
        override val id = "climate"
        override val relationType = RelationType.COMMAND
        override suspend fun handle(msg: IntentMessage): ExecutionBlock =
            ExecutionBlock(domainAgent = id, result = mapOf("reply" to "已为您打开空调"))
    }

    private fun build(): MasterAgent {
        val bus = AiBBus()
        val parser = FastPathParser()
        val composer = IntentComposer(parser, null, ConfidenceFusion())
        val master = MasterAgent(
            bus = bus,
            composer = composer,
            arbiter = Arbiter(MatrixScorer()),
            planner = Planner(PlanPolicy()),
            executor = Executor(mapOf("climate" to climateStub)),
            gateway = SafetyGateway(),
            checkpoint = CheckpointStore(),
            trace = ExecutionTraceCollector(),
        )
        return master
    }

    private fun msg(u: String) = IntentMessage(
        header = Header(messageId = "m-$u", traceId = "t1", sessionId = "s1"),
        input = Input(utterance = u),
    )

    @Test
    fun `climate command executes`() = runBlocking {
        val out = build().run(msg("打开空调"))
        assertEquals(Route.EXECUTE, out.arbitration?.route)
        assertEquals("climate", out.arbitration?.domainAgent)
        assertTrue(out.result?.reply?.contains("空调") == true)
    }

    @Test
    fun `unknown utterance falls back`() = runBlocking {
        val out = build().run(msg("帮我升空"))
        assertEquals(Route.FALLBACK, out.arbitration?.route)
        assertEquals("FALLBACK", out.result?.fallback)
    }

    @Test
    fun `resume unfinished checkpoints`() = runBlocking {
        val master = build()
        master.run(msg("打开空调"))
        assertEquals(0, master.resumeUnfinished()) // 正常走完无未完成
    }
}
