/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：IntentMessage —— 端云统一消息契约（JSON Schema 端侧 kotlinx.serialization / 云侧 Jackson 对齐）。
 *       承载 L1 交互感知 → L2 Master → L3 域 Agent 五层间流转的唯一实体。
 */

package com.xiaoguang.masteragent.core.bus

import kotlinx.serialization.Serializable

/** 消息头：标识 + 溯源 + 路由 + 优先级 */
@Serializable
data class Header(
    val messageId: String,
    val traceId: String,
    val sessionId: String,
    val schemaVersion: String = "1.0",
    val tsMs: Long = 0L,
    val source: String = "PERCEPTION",
    val stage: Stage = Stage.INPUT,
    val channel: Channel = Channel.FAST,
    val priority: Int = 3,
)

/** 输入：语音文本 + 感知字段 + 上下文引用 + 可选项 */
@Serializable
data class Input(
    val utterance: String,
    val perception: Map<String, String> = emptyMap(),
    val contextRef: String? = null,
    val options: Map<String, String> = emptyMap(),
)

/** ASR 块：转写文本 + 置信度 + 模型标识 */
@Serializable
data class AsrBlock(
    val text: String,
    val confidence: Double = 0.0,
    val asrModel: String = "paraformer",
)

/** 结果块：动作 + 回复 + 兜底标识 + 分段应答 + 多模态输出 */
@Serializable
data class ResultBlock(
    val action: Map<String, String> = emptyMap(),
    val reply: String = "",
    val fallback: String? = null,
    /** 分段应答（FR-UI-01）：reply 拆分为若干段，供表示层逐段呈现 */
    val segments: List<String> = emptyList(),
    /** 多模态输出（FR-UI-02）：text / tts / image / gesture 等 */
    val modalities: List<String> = emptyList(),
)

/**
 * IntentMessage 主契约：一个消息贯穿意图理解 → 仲裁 → 规划 → 执行 → 结果全过程，
 * 各阶段产出分别写入 intent / arbitration / planning / execution / result 字段。
 */
@Serializable
data class IntentMessage(
    val header: Header,
    val input: Input,
    val asr: AsrBlock? = null,
    val intent: IntentBlock? = null,
    val slots: SlotBlock? = null,
    val arbitration: ArbitrationBlock? = null,
    val planning: PlanningBlock? = null,
    val execution: ExecutionBlock? = null,
    val result: ResultBlock? = null,
    val ext: Map<String, String> = emptyMap(),
) {
    /** 置信度 40–79：进入澄清确认（CONFIRM） */
    fun confirm(): IntentMessage = copy(
        arbitration = arbitration?.copy(route = Route.CONFIRM),
        result = ResultBlock(reply = "请确认您的意图：${input.utterance}", fallback = "CONFIRM"),
    )

    /** 置信度 < 40：兜底降级（FALLBACK） */
    fun fallback(): IntentMessage = copy(
        arbitration = arbitration?.copy(route = Route.FALLBACK),
        result = ResultBlock(reply = "抱歉，我暂时没有理解您的指令", fallback = "FALLBACK"),
    )
}

/** 将消息转换为检查点记录（守护断点续跑用，payload 为已产出结果的压缩摘要） */
fun IntentMessage.toRecord(stage: Stage, lastCompleted: Stage, payload: String = ""): CheckpointRecord =
    CheckpointRecord(
        messageId = header.messageId,
        sessionId = header.sessionId,
        stage = stage,
        lastCompleted = lastCompleted,
        payload = payload,
        updatedAt = System.currentTimeMillis(),
    )
