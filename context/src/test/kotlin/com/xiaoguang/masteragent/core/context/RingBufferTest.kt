/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：RingBuffer 单元测试 —— 环形覆盖 O(1)。
 */

package com.xiaoguang.masteragent.core.context

import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.Input
import com.xiaoguang.masteragent.core.bus.IntentMessage
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class RingBufferTest {

    private fun msg(n: Int) = IntentMessage(
        header = Header(messageId = "m$n", traceId = "t$n", sessionId = "s1"),
        input = Input(utterance = "指令$n"),
    )

    @Test
    fun `overwrites oldest when full`() {
        val ring = RingBuffer(capacity = 3)
        ring.append(msg(1)); ring.append(msg(2)); ring.append(msg(3)); ring.append(msg(4))
        val window = ring.window()
        assertEquals(3, window.size)
        assertEquals("指令2", window.first().input.utterance)
        assertEquals("指令4", window.last().input.utterance)
    }
}
