/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Master Agent 迭代 2 / L2 标准级单元测试 —— 能源生态域 / reason_code-nl 成对 / RAG /
 *       声源定位 / 多模态输出 / 指代消解 / 多轮澄清 / 归属权限审计 / 隐私合规 /
 *       命令生命周期 / 热修复 / 故障注入与异常降级。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.AiBBus
import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.HotfixPackage
import com.xiaoguang.masteragent.core.bus.IDomainAgent
import com.xiaoguang.masteragent.core.bus.Input
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.RelationType
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
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MasterAgentL2Test {

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

    private fun msg(u: String, perception: Map<String, String> = emptyMap()) = IntentMessage(
        header = Header(messageId = "m-$u", traceId = "t", sessionId = "s"),
        input = Input(utterance = u, perception = perception),
    )

    @Test
    fun `能源生态域：续航查询命中并执行`() = runBlocking {
        val out = fullMaster().run(msg("还有多少续航"))
        assertEquals(Route.EXECUTE, out.arbitration?.route)
        assertEquals("energy", out.arbitration?.domainAgent)
        assertTrue(out.result?.reply?.contains("续航") == true)
    }

    @Test
    fun `reason_code 与 nl 成对输出`() = runBlocking {
        val composer = IntentComposer(FastPathParser(), null, ConfidenceFusion())
        val intent = composer.compose(msg("打开空调"))
        assertEquals("OK", intent.reasonCode)
        assertTrue(intent.nl.contains("climate"))
    }

    @Test
    fun `知识问答触发 RAG 标记`() = runBlocking {
        val composer = IntentComposer(FastPathParser(), null, ConfidenceFusion())
        val intent = composer.compose(msg("什么是热泵空调"))
        assertTrue(intent.needsRag)
    }

    @Test
    fun `声源定位：zone 影响仲裁场景`() = runBlocking {
        val out = fullMaster().run(msg("打开空调", perception = mapOf("zone" to "副驾")))
        assertEquals("PASSENGER", out.arbitration?.scene)
    }

    @Test
    fun `多模态输出：结果含分段与模态`() = runBlocking {
        val out = fullMaster().run(msg("打开空调"))
        assertTrue((out.result?.segments?.size ?: 0) >= 1)
        assertTrue(out.result?.modalities?.contains("tts") == true)
    }

    @Test
    fun `指代消解：指代词回指上一轮域`() {
        val resolver = AnaphoraResolver()
        assertEquals("climate", resolver.resolve("再冷一点", "climate"))
        assertNull(resolver.resolve("打开车窗", "climate"))
        assertNull(resolver.resolve("再冷一点", null))
    }

    @Test
    fun `多轮澄清：确认词回填挂起意图`() {
        val tracker = ClarificationTracker()
        tracker.setPending("climate", "打开空调")
        assertEquals(ClarificationTracker.Resolution.CONFIRM, tracker.resolve("是"))
        assertEquals(ClarificationTracker.Resolution.DENY, tracker.resolve("取消"))
        assertEquals(ClarificationTracker.Resolution.IGNORE, tracker.resolve("播放音乐"))
    }

    @Test
    fun `归属关系审计：上报域越权拦截`() {
        val audit = RelationAudit()
        assertEquals(AuditVerdict.ALLOW, audit.audit(RelationType.REPORT, "check_energy"))
        assertEquals(AuditVerdict.BLOCK, audit.audit(RelationType.REPORT, "set_ac"))
        assertEquals(AuditVerdict.ALLOW, audit.audit(RelationType.COMMAND, "set_ac"))
    }

    @Test
    fun `隐私合规：PII 脱敏与敏感拦截`() {
        assertEquals("我的手机***", PrivacyFilter.sanitize("我的手机13800138000"))
        assertEquals("[敏感内容已过滤]", PrivacyFilter.sanitize("银行卡密码 123"))
        assertTrue(PrivacyFilter.isSensitive("我的密码是123"))
    }

    @Test
    fun `命令生命周期：下发取消挂起恢复`() {
        val lc = CommandLifecycle()
        assertEquals(CommandState.RUNNING, lc.issue("c1"))
        assertEquals(CommandState.SUSPENDED, lc.suspend("c1"))
        assertEquals(CommandState.RESUMED, lc.resume("c1"))
        assertEquals(CommandState.CANCELLED, lc.cancel("c1"))
    }

    @Test
    fun `热修复：签名校验与回退`() = runBlocking {
        val hm = HotfixManager()
        assertFalse(hm.apply(HotfixPackage("v1", "", "p")).ok)
        assertTrue(hm.apply(HotfixPackage("v1", "sig1", "p")).ok)
        assertTrue(hm.apply(HotfixPackage("v2", "sig2", "p")).ok)
        assertEquals("v2", hm.currentVersion())
        assertTrue(hm.rollback("v1").ok)
        assertEquals("v1", hm.currentVersion())
        assertFalse(hm.rollback("v9").ok)
    }

    @Test
    fun `故障注入：可编程故障标记`() {
        val injector = FaultInjector()
        injector.fail("climate")
        assertTrue(injector.shouldFail("climate"))
        injector.healthy("climate")
        assertFalse(injector.shouldFail("climate"))
    }

    @Test
    fun `域 Agent 缺失：异常降级为 ERROR 兜底不崩溃`() = runBlocking {
        val master = MasterAgent(
            bus = AiBBus(),
            composer = IntentComposer(FastPathParser(), null, ConfidenceFusion()),
            arbiter = Arbiter(MatrixScorer()),
            planner = Planner(PlanPolicy()),
            executor = Executor(emptyMap()),   // 无任何域 Agent → 触发 NoDomainAgentException
            gateway = SafetyGateway(),
            checkpoint = CheckpointStore(),
            trace = ExecutionTraceCollector(),
        )
        val out = master.run(msg("打开空调"))
        assertEquals("ERROR", out.result?.fallback)
        assertTrue(out.result?.reply?.contains("执行异常") == true)
    }
}
