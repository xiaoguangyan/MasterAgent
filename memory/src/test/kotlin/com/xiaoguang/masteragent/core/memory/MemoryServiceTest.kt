/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：MemoryService 单元测试 —— 四态裁定 / 敏感拦截 / 一键清除。
 */

package com.xiaoguang.masteragent.core.memory

import com.xiaoguang.masteragent.core.bus.MemoryItem
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class MemoryServiceTest {

    private fun item(id: String, content: String, confidence: Double = 1.0) = MemoryItem(
        memoryId = id, factTime = System.currentTimeMillis(), recordTime = System.currentTimeMillis(),
        content = content, confidence = confidence,
    )

    @Test
    fun `remember stores and recalls`() = runBlocking {
        val svc = MemoryService()
        svc.remember(item("m1", "用户偏好 26 度空调"))
        val hits = svc.recall("空调", 5)
        assertEquals(1, hits.size)
        assertEquals("用户偏好 26 度空调", hits[0].content)
    }

    @Test
    fun `identical content is NOOP`() = runBlocking {
        val svc = MemoryService()
        svc.remember(item("m1", "用户偏好 26 度空调"))
        svc.remember(item("m1", "用户偏好 26 度空调"))
        assertEquals(1, svc.recall("空调", 5).size)
    }

    @Test
    fun `changed content supersedes old`() = runBlocking {
        val svc = MemoryService()
        svc.remember(item("m1", "用户偏好 26 度空调"))
        svc.remember(item("m1", "用户偏好 24 度空调"))
        val hits = svc.recall("空调", 5)
        assertEquals(1, hits.size)
        assertEquals("用户偏好 24 度空调", hits[0].content)
    }

    @Test
    fun `sensitive content is blocked`() {
        val svc = MemoryService()
        // 注意：方法体用块体（而非 = runBlocking 表达式体），
        // 否则 assertThrows 的返回值会把方法推断成非 void，导致 JUnit 静默跳过本测试。
        assertThrows(SensitiveMemoryBlocked::class.java) {
            runBlocking { svc.remember(item("m1", "银行卡密码是 123456")) }
        }
    }

    @Test
    fun `clear removes all`() = runBlocking {
        val svc = MemoryService()
        svc.remember(item("m1", "偏好 A"))
        svc.remember(item("m2", "偏好 B"))
        assertEquals(2, svc.clear())
        assertTrue(svc.recall("偏好", 5).isEmpty())
    }
}
