/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ModelConfigLoader 单元测试 —— 从 classpath 加载 model-config.json，断言 PRD §2.11 模型表与阈值。
 */

package com.xiaoguang.masteragent.app

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class ModelConfigTest {

    @Test
    fun `从 classpath 加载模型配置`() {
        val cfg = ModelConfigLoader.fromClasspath("model-config.json")
        assertEquals("Qwen/Qwen2.5-14B-Instruct", cfg.model("m_intent")?.name)
        assertEquals("Qwen2.5-1.5B-Instruct", cfg.model("m_intent_edge")?.name)
        assertEquals("deepseek-ai/DeepSeek-V4-Pro", cfg.model("m_planner")?.name)
        assertEquals(0.80, cfg.slowLane.executeThreshold, 0.0001)
        assertEquals(0.85, cfg.slowLane.slowTriggerThreshold, 0.0001)
    }

    @Test
    fun `模型规格对齐大模型标准配置属性`() {
        val cfg = ModelConfigLoader.fromClasspath("model-config.json")
        val mIntent = cfg.model("m_intent")!!
        assertEquals("14B", mIntent.size)
        assertEquals(0.2, mIntent.temperature, 0.0001)
        assertEquals(0.9, mIntent.topP, 0.0001)
        assertEquals(1024, mIntent.maxTokens)
        assertEquals(32768, mIntent.contextLength)
        assertEquals("cloud", mIntent.deploy)
        assertEquals("vLLM", mIntent.backend)

        val planner = cfg.model("m_planner")!!
        assertEquals("32B", planner.size)
        assertEquals(0.1, planner.temperature, 0.0001)
        assertEquals(4096, planner.maxTokens)

        val edge = cfg.model("m_intent_edge")!!
        assertEquals("edge", edge.deploy)
        assertEquals("INT4", edge.quantization)
    }

    @Test
    fun `资源缺失回退默认配置`() {
        val cfg = ModelConfigLoader.fromClasspath("missing.json")
        assertEquals(0.80, cfg.slowLane.executeThreshold, 0.0001)
        assertNull(cfg.model("m_intent"))
    }
}
