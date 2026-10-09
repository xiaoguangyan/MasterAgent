/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：模型配置契约 —— 统一收敛 PRD §2.11 模型表 + 慢道阈值 + 大模型标准推理参数，
 *       一处声明、端/云各自一个配置文件读取。模型规格字段对齐大模型标准配置属性
 *       （temperature / top_p / max_tokens / context_length 等，与 vLLM / OpenAI 兼容）。
 *       端侧加载 model-config.json（JVM classpath / Android assets），云侧沿用 application.yml 同构声明。
 */

package com.xiaoguang.masteragent.core.bus

import kotlinx.serialization.Serializable

/** 模型配置：慢道阈值 + PRD §2.11 全量模型表（key 见 models 字典） */
@Serializable
data class ModelConfig(
    val version: String = "1.2.0",
    val slowLane: SlowLane = SlowLane(),
    val models: Map<String, ModelSpec> = emptyMap(),
) {
    /** 按 key 取模型规格（如 m_intent / m_intent_edge / m_planner / m_arbiter / asr_edge / asr_cloud / embedding） */
    fun model(key: String): ModelSpec? = models[key]
}

/** 慢道策略：模式 + 三道阈值（执行/澄清/触发慢道补强） */
@Serializable
data class SlowLane(
    val mode: String = "rule",
    val executeThreshold: Double = 0.80,
    val confirmThreshold: Double = 0.40,
    val slowTriggerThreshold: Double = 0.85,
)

/**
 * 单个模型规格：对齐大模型标准配置属性（与 vLLM / OpenAI 兼容 / HF generation config 命名一致）。
 * 字段分四组：① 模型标识与规模；② 标准采样/推理参数；③ 标准部署与连接；④ 职责。
 * 非生成式模型（ASR / Embedding）不适用采样参数，可省略（走默认值）。
 */
@Serializable
data class ModelSpec(
    // —— ① 模型标识与规模 ——
    /** 模型名 / model id（如 Qwen2.5-14B-Instruct） */
    val name: String = "",
    /** 参数规模（PRD §2.11 尺寸列：14B / 1.5B / 32B / 0.5B / ~24M） */
    val size: String = "",

    // —— ② 标准采样 / 推理参数 ——
    /** 采样温度（temperature，越低越确定；意图理解 0.2 / 深度推理 0.1） */
    val temperature: Double = 0.2,
    /** 核采样 top_p（top_p） */
    val topP: Double = 0.9,
    /** 最大生成长度（max_tokens） */
    val maxTokens: Int = 1024,
    /** 上下文窗口长度（context_length） */
    val contextLength: Int = 8192,
    /** 是否流式输出（stream） */
    val stream: Boolean = false,

    // —— ③ 标准部署与连接 ——
    /** 量化（quantization：INT4 / INT8 / FP16） */
    val quantization: String = "",
    /** 部署形态（deploy：cloud 云端 / edge 端侧） */
    val deploy: String = "",
    /** 推理引擎/后端（backend：vLLM / Qualcomm QNN） */
    val backend: String = "",
    /** 推理端点（endpoint，OpenAI 兼容 /v1，cloud 用；端侧本地推理留空） */
    val endpoint: String = "",
    /** API 密钥（api_key，cloud 用，部署时注入；端侧本地推理留空） */
    val apiKey: String = "",

    // —— ④ 职责 ——
    /** 职责说明（PRD §2.11 组件列，如 意图理解/规划/仲裁/ASR/Embedding） */
    val role: String = "",
)
