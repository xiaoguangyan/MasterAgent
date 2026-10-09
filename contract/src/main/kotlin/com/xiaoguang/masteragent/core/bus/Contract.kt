/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：跨模块共享的契约类型 —— 记忆 / 上下文 / KV / 检查点。
 *       这些类型出现在 SPI 接口签名中，因此统一收敛到 core:bus（契约层）。
 */

package com.xiaoguang.masteragent.core.bus

/** 记忆 7 态生命周期 */
enum class LifecycleState { CANDIDATE, VALID, SUPERSEDED, INVALIDATED, EXPIRED, DELETED, DISCARDED }

/** 四态裁定：新增 / 更新 / 删除 / 无操作 */
enum class Adjudication { ADD, UPDATE, DELETE, NOOP }

/** 分级隐私标记（FR-PRV-01）：私有 / 共享 / 敏感 */
enum class PrivacyLevel { PRIVATE, SHARED, SENSITIVE }

/**
 * 记忆条目：双时态溯源（事实时间 factTime=event_time / 记录时间 recordTime=transaction_time）+ 有效期区间 + 版本化 + 隐私 + 来源。
 * 说明：L1 为 KV 记忆，不含向量；L2 起引入 embedding + 向量索引（bge-small-zh-v1.5）。
 * 　　validFromMs/validToMs 表达「事实有效期」（FR-TMP-02）：to=null 表示当前仍有效；
 * 　　version 单调递增（FR-STO-03）；privacy 分级（FR-PRV-01）；sourceRef 溯源（FR-OBS-02）。
 */
data class MemoryItem(
    val memoryId: String,
    val factTime: Long,
    val recordTime: Long,
    val content: String,
    val confidence: Double = 1.0,
    val state: LifecycleState = LifecycleState.VALID,
    val version: Long = 1L,
    val validFromMs: Long = 0L,
    val validToMs: Long? = null,
    val privacy: PrivacyLevel = PrivacyLevel.PRIVATE,
    val sourceRef: String = "",
)

/** 实时车态（L2 状态唯一真源） */
data class RealtimeState(
    val speed: Double = 0.0,
    val temp: Int = 26,
    val seat: String = "主驾",
    val gear: String = "P",
    val window: String = "closed",
    val ac: Boolean = false,
)

/** 上下文引用 */
data class ContextRef(val ref: String)

/** 上下文分层（L0–L4） */
data class Layer0(val rounds: Int = 1, val items: List<String> = emptyList())
data class Layer1(val windowN: Int = 0, val ring: List<String> = emptyList())
data class Layer2(val realtime: RealtimeState = RealtimeState())
data class Layer3(val tokenBudget: Int = 8000, val used: Int = 0)
data class Layer4(val version: String = "v1", val snapshotRef: String? = null)

data class Layers(
    val l0: Layer0 = Layer0(),
    val l1: Layer1 = Layer1(),
    val l2: Layer2 = Layer2(),
    val l3: Layer3 = Layer3(),
    val l4: Layer4 = Layer4(),
)

/** 驱逐摘要：下沉短期 / 长期条数 */
data class EvictSummary(val toShortTerm: Int = 0, val toLongTerm: Int = 0)

/** 上下文窗口（snapshot 产出） */
data class ContextWindow(
    val contextRef: String = "",
    val layers: Layers = Layers(),
    val evicted: EvictSummary = EvictSummary(),
)

/** KV 前缀分级 P0–P3 */
enum class Tier { P0, P1, P2, P3 }

/**
 * KV 块：前缀缓存的最小单元。
 * MVP 用文本负载占位；真实部署替换为量化后的 KV 张量（KV8 / KV4）。
 */
data class KvBlock(
    val prefix: String,
    val tier: Tier = Tier.P2,
    val payload: String = "",
    val sizeBytes: Int = 0,
)

/** 检查点记录：守护断点续跑，payload 为序列化后的已产出结果摘要 */
data class CheckpointRecord(
    val messageId: String,
    val sessionId: String,
    val stage: Stage,
    val lastCompleted: Stage,
    val payload: String = "",
    val updatedAt: Long = 0L,
)

/** 热修复包（迭代 2） */
data class HotfixPackage(val version: String, val signature: String, val payload: String)

/** 热修复应用结果 */
data class ApplyResult(val ok: Boolean, val version: String, val message: String)
