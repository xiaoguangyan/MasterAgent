/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：IntentSplitter 单元测试 —— 规则分句（标点/连词/防误拆）与大模型拆分 JSON 解析。
 */

package com.xiaoguang.masteragent.core.model

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class IntentSplitterTest {

    @Test
    fun `连词拆分多意图`() {
        assertEquals(listOf("打开空调", "播放音乐"), IntentSplitter.splitSegments("打开空调然后播放音乐"))
    }

    @Test
    fun `标点拆分多意图`() {
        assertEquals(listOf("打开空调", "导航回家"), IntentSplitter.splitSegments("打开空调，导航回家"))
    }

    @Test
    fun `还有多少续航不误拆`() {
        assertEquals(listOf("还有多少续航"), IntentSplitter.splitSegments("还有多少续航"))
    }

    @Test
    fun `再冷一点不误拆`() {
        assertEquals(listOf("再冷一点"), IntentSplitter.splitSegments("再冷一点"))
    }

    @Test
    fun `大模型拆分 JSON 解析出多候选`() {
        val json = """{"intents":[{"seq":1,"intent_id":"climate","slots":{"temp":"24"},"confidence":0.9,"depends_on":[]},{"seq":2,"intent_id":"media","slots":{},"confidence":0.85,"depends_on":[]}],"status":"EXECUTE"}"""
        val cs = IntentSplitter.parseLlmIntents(json)!!
        assertEquals(2, cs.size)
        assertEquals("climate", cs[0].routeHint)
        assertEquals("media", cs[1].routeHint)
        assertEquals(mapOf("temp" to "24"), cs[0].slots)
    }

    @Test
    fun `大模型拆分：未注册意图标 UNKNOWN`() {
        val json = """{"intents":[{"seq":1,"intent_id":"coffee","slots":{},"confidence":0.9,"depends_on":[]}]}"""
        val cs = IntentSplitter.parseLlmIntents(json)!!
        assertEquals(1, cs.size)
        assertNull(cs[0].routeHint)
    }

    @Test
    fun `大模型拆分：非法或空输入回退 null`() {
        assertNull(IntentSplitter.parseLlmIntents("not-json"))
        assertNull(IntentSplitter.parseLlmIntents(""))
        assertNull(IntentSplitter.parseLlmIntents("""{"intents":[]}"""))
    }
}
