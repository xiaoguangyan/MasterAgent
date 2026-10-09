/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Memory L2 标准级单元测试 —— 版本链/失效非覆盖、点时间回查、自动重排、后台巩固、记忆溯源。
 */

package com.xiaoguang.masteragent.core.memory

import com.xiaoguang.masteragent.core.bus.LifecycleState
import com.xiaoguang.masteragent.core.bus.MemoryItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryL2Test {

    private fun item(id: String, content: String, recordTime: Long, confidence: Double = 1.0) =
        MemoryItem(memoryId = id, factTime = recordTime, recordTime = recordTime, content = content, confidence = confidence)

    /** 无 TTL 长期存储：点时间/版本链用例用合成时间戳，不触发 30 天过期淘汰 */
    private fun svc() = MemoryService(longTerm = LongTermStore(ttlMs = Long.MAX_VALUE))

    @Test
    fun `版本链：更新失效旧版本且物理保留历史`() = runBlocking {
        val svc = svc()
        svc.remember(item("m1", "6 月用油车", recordTime = 1000))
        svc.remember(item("m1", "9 月换电车", recordTime = 2000))
        val chain = svc.provenance("m1")
        assertEquals(2, chain.size)
        assertEquals(2L, chain[0].version)          // 新在前
        assertEquals(1L, chain[1].version)
        assertEquals(LifecycleState.SUPERSEDED, chain[1].state)  // 旧版本失效非覆盖
    }

    @Test
    fun `点时间回查：asOf 命中当时事实`() = runBlocking {
        val svc = svc()
        svc.remember(item("m1", "6 月用油车", recordTime = 1000))
        svc.remember(item("m1", "9 月换电车", recordTime = 2000))
        // 7 月（1500）返回油车；10 月（2500）返回电车
        assertEquals("6 月用油车", svc.recallAt("车", 5, asOfMs = 1500)[0].content)
        assertEquals("9 月换电车", svc.recallAt("车", 5, asOfMs = 2500)[0].content)
    }

    @Test
    fun `自动重排：高频条目优先于新近`() = runBlocking {
        val svc = svc()
        svc.remember(item("m1", "空调 26 度", recordTime = 100))
        svc.remember(item("m2", "空调 22 度", recordTime = 200))
        svc.recall("26", 5)                       // 仅命中 m1，频次 +1
        val hits = svc.recall("空调", 5)           // 二者均命中
        assertEquals("m1", hits[0].memoryId)      // 频次 m1=1 > m2=0，频次优先于新近
    }

    @Test
    fun `后台巩固：去重合并同内容条目`() = runBlocking {
        val svc = svc()
        svc.remember(item("m1", "重复内容", recordTime = 100))
        svc.remember(item("m2", "重复内容", recordTime = 200))
        assertTrue(svc.consolidate() >= 1)
        // 去重后仅剩最新一条 VALID
        assertEquals(1, svc.recall("重复内容", 5).size)
    }

    @Test
    fun `后台巩固：高频条目置信度提升`() = runBlocking {
        val svc = svc()
        svc.remember(item("m1", "空调", recordTime = 100, confidence = 0.5))
        svc.recall("空调", 5)
        svc.recall("空调", 5)                       // 频次达到阈值
        svc.consolidate()
        assertEquals(0.9, svc.recall("空调", 5)[0].confidence)
    }

    @Test
    fun `记忆溯源：返回完整版本链`() = runBlocking {
        val svc = svc()
        svc.remember(item("m1", "v1", recordTime = 100))
        svc.remember(item("m1", "v2", recordTime = 200))
        val chain = svc.provenance("m1")
        assertEquals(2, chain.size)
        assertEquals("v2", chain[0].content)
        assertEquals("v1", chain[1].content)
    }
}
