/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Context L2 标准级单元测试 —— 声明式切片（字段白名单/角色权限/脱敏/token预算/记忆注入）、
 *       回退链、点时间快照。
 */

package com.xiaoguang.masteragent.core.context

import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.IMemoryService
import com.xiaoguang.masteragent.core.bus.Input
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.MemoryItem
import com.xiaoguang.masteragent.core.bus.RealtimeState
import com.xiaoguang.masteragent.core.bus.SliceSpec
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class ContextL2Test {

    private fun msg(id: String, utterance: String, tsMs: Long = 0L) = IntentMessage(
        header = Header(messageId = id, traceId = "t-$id", sessionId = "s1", tsMs = tsMs),
        input = Input(utterance = utterance),
    )

    private fun ctx(vararg msgs: IntentMessage, realtime: RealtimeState = RealtimeState()) = ContextManager(
        ring = RingBuffer(capacity = 16).apply { msgs.forEach { append(it) } },
        realtime = RealtimeStore(initial = realtime),
    )

    /** 契约桩：context 工程不依赖 memory 工程，用桩实现 IMemoryService */
    private class FakeMemory(private val items: List<MemoryItem>) : IMemoryService {
        override suspend fun remember(item: MemoryItem) {}
        override suspend fun recall(query: String, topK: Int): List<MemoryItem> = items.take(topK)
        override suspend fun clear(): Int = 0
        override suspend fun recallAt(query: String, topK: Int, asOfMs: Long): List<MemoryItem> = items.take(topK)
        override suspend fun consolidate(): Int = 0
        override fun provenance(memoryId: String): List<MemoryItem> = emptyList()
    }

    @Test
    fun `声明式切片：字段白名单只保留指定字段`() = runBlocking {
        val mgr = ctx(msg("m1", "打开空调"))
        val slice = mgr.slice(SliceSpec(layers = listOf("L2"), fields = mapOf("L2" to listOf("temp"))))
        assertEquals(listOf("temp"), slice.fields.map { it.key })
        assertEquals("L2", slice.fields[0].layer)
    }

    @Test
    fun `字段权限：副驾角色过滤驾驶控制字段`() = runBlocking {
        val mgr = ctx(msg("m1", "查询状态"))
        val slice = mgr.slice(SliceSpec(layers = listOf("L2"), role = "passenger"))
        val keys = slice.fields.map { it.key }.toSet()
        assertFalse("speed" in keys, "副驾不可见 speed")
        assertFalse("gear" in keys, "副驾不可见 gear")
        assertFalse("seat" in keys, "副驾不可见 seat")
        assertTrue("temp" in keys && "window" in keys && "ac" in keys)
    }

    @Test
    fun `脱敏：手机号身份证掩码`() = runBlocking {
        val mgr = ctx(msg("m1", "我的手机13800138000"))
        val slice = mgr.slice(SliceSpec(layers = listOf("L1")))
        val ring = slice.fields.first { it.key == "ring" }
        assertTrue(ring.redacted)
        assertFalse(ring.value.contains("13800138000"))
        assertTrue(ring.value.contains("***"))
    }

    @Test
    fun `token预算：超限裁剪尾部字段并溯源`() = runBlocking {
        val mgr = ctx(msg("m1", "打开空调"))
        val slice = mgr.slice(SliceSpec(layers = listOf("L2"), tokenBudget = 5))
        assertTrue(slice.tokenCount <= 5)
        assertTrue(slice.redacted.isNotEmpty(), "尾部字段应被裁剪并溯源")
        assertTrue(slice.fields.size < 6, "L2 共 6 字段，预算 5 必裁剪部分")
    }

    @Test
    fun `记忆注入：注入不超过5条长期记忆`() = runBlocking {
        val items = (1..8).map { MemoryItem(memoryId = "m$it", factTime = 0, recordTime = 0, content = "记忆$it") }
        val mgr = ContextManager(memory = FakeMemory(items))
        mgr.push(msg("m1", "空调偏好"))
        val slice = mgr.slice(SliceSpec())
        assertTrue(slice.injectedMemories.isNotEmpty())
        assertTrue(slice.injectedMemories.size <= 5, "注入条数须 ≤5")
    }

    @Test
    fun `点时间快照：只回放asOf时刻前条目`() = runBlocking {
        val mgr = ctx(
            msg("m1", "第一条", tsMs = 1000),
            msg("m2", "第二条", tsMs = 2000),
            msg("m3", "第三条", tsMs = 3000),
        )
        val w = mgr.snapshotAt(2000)
        assertEquals(2, w.layers.l1.windowN)
        assertEquals(listOf("第一条", "第二条"), w.layers.l1.ring)
    }

    @Test
    fun `回退链：L2源缺失降级到L1环形窗口`() = runBlocking {
        val slice = SliceEngine.slice(
            spec = SliceSpec(layers = listOf("L2")),
            ringWindow = listOf(msg("m1", "打开空调")),
            realtime = null,
            memory = null,
        )
        val fb = slice.fields.first { it.key == "fallback" }
        assertTrue(fb.value.contains("打开空调"))
        assertTrue(slice.provenance.contains("fallback=L2->L1"))
    }
}
