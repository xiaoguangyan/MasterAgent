/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Master Agent 多意图拆解端到端单元测试 —— 连词拆分多域串行执行 / 未注册子句跳过并提示 / 全未注册兜底。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.AiBBus
import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.Input
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.Route
import com.xiaoguang.masteragent.core.daemon.CheckpointStore
import com.xiaoguang.masteragent.core.model.ConfidenceFusion
import com.xiaoguang.masteragent.core.model.FastPathParser
import com.xiaoguang.masteragent.core.observe.ExecutionTraceCollector
import com.xiaoguang.masteragent.feature.domain.ClimateAgent
import com.xiaoguang.masteragent.feature.domain.EnergyAgent
import com.xiaoguang.masteragent.feature.domain.MediaAgent
import com.xiaoguang.masteragent.feature.domain.NavigationAgent
import com.xiaoguang.masteragent.feature.domain.SeatAgent
import com.xiaoguang.masteragent.feature.domain.WindowAgent
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MasterAgentMultiIntentTest {

    private fun fullMaster(): MasterAgent {
        val agents: Map<String, IDomainAgent> = mapOf(
            "climate" to ClimateAgent(),
            "window" to WindowAgent(),
            "seat" to SeatAgent(),
            "media" to MediaAgent(),
            "navigation" to NavigationAgent(),
            "energy" to EnergyAgent(),
        )
        return MasterAgent(
            bus = AiBBus(),
            composer = IntentComposer(FastPathParser(), null, ConfidenceFusion()),
            arbiter = Arbiter(MatrixScorer()),
            planner = Planner(PlanPolicy()),
            executor = Executor(agents),
            gateway = SafetyGateway(),
            checkpoint = CheckpointStore(),
            trace = ExecutionTraceCollector(),
        )
    }

    private fun msg(u: String) = IntentMessage(
        header = Header(messageId = "m-$u", traceId = "t", sessionId = "s"),
        input = Input(utterance = u),
    )

    @Test
    fun `多意图：连词拆分两个域并串行执行`() = runBlocking {
        val out = fullMaster().run(msg("打开空调然后播放音乐"))
        assertEquals(Route.EXECUTE, out.arbitration?.route)
        assertEquals(listOf("climate", "media"), out.arbitration?.domains)
        assertEquals(2, out.planning?.plan?.dag?.size)
        val reply = out.result?.reply ?: ""
        assertTrue(reply.contains("空调") && reply.contains("音乐"), "reply=$reply")
    }

    @Test
    fun `混合意图：已知执行 + 未注册子句跳过并提示`() = runBlocking {
        val out = fullMaster().run(msg("打开空调然后点杯咖啡"))
        assertEquals(Route.EXECUTE, out.arbitration?.route)
        assertEquals(listOf("climate"), out.arbitration?.domains)
        val reply = out.result?.reply ?: ""
        assertTrue(reply.contains("空调"), "reply=$reply")
        assertTrue(reply.contains("暂不支持"), "reply=$reply")
        assertTrue(reply.contains("点杯咖啡"), "reply=$reply")
    }

    @Test
    fun `全部未注册：整句兜底 FALLBACK`() = runBlocking {
        val out = fullMaster().run(msg("点杯咖啡"))
        assertEquals(Route.FALLBACK, out.arbitration?.route)
    }
}
