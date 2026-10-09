/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Slice —— 声明式上下文切片契约（FR-SLC）：切片请求（DSL 参数化）与切片产物。
 *       上下文服务对外 SPI（IContextManager.slice）的入参/出参，跨模块共享，故收敛到契约层。
 */

package com.xiaoguang.masteragent.core.bus

import kotlinx.serialization.Serializable

/**
 * 声明式切片请求（FR-SLC-01）：以 DSL 声明「要哪些层/字段 + 权限角色 + token 预算」，
 * 非过程式拼接；同一 spec 幂等、产物确定。
 */
@Serializable
data class SliceSpec(
    /** 需要的层（缺省 L0/L1/L2） */
    val layers: List<String> = listOf("L0", "L1", "L2"),
    /** 按层声明的字段白名单，如 "L2" -> ["speed", "temp"]；空表示该层全量 */
    val fields: Map<String, List<String>> = emptyMap(),
    /** 请求角色：driver / passenger（字段级权限过滤依据，FR-SLC-02） */
    val role: String = "driver",
    /** token 预算上限（FR-SLC-04），超限裁剪 */
    val tokenBudget: Int = 8000,
)

/** 单字段切片项：层 + 键 + 值 + 是否被脱敏（FR-SLC-03） */
@Serializable
data class SliceField(
    val layer: String,
    val key: String,
    val value: String,
    val redacted: Boolean = false,
)

/**
 * 上下文切片产物（FR-SLC）：权限过滤 → 敏感脱敏 → token 预算裁剪 → 长期记忆注入（L3，≤5 条）→ 溯源。
 */
@Serializable
data class ContextSlice(
    val sliceRef: String = "",
    val fields: List<SliceField> = emptyList(),
    val tokenCount: Int = 0,
    val budget: Int = 8000,
    /** 注入的长期记忆 id（FR-SLC-05 / FR-CTX-05，≤5 条） */
    val injectedMemories: List<String> = emptyList(),
    /** 被脱敏/裁剪字段名（FR-SLC-03 / FR-SLC-07 溯源） */
    val redacted: List<String> = emptyList(),
    /** 切片溯源说明（FR-SLC-07：为何如此 —— 来源/脱敏字段/注入记忆） */
    val provenance: String = "",
)
