/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：跨模块 SPI 契约（HLD §12 / LLD §7）。
 *       所有模型 / 存储 / 组件均经 SPI 插件化接入，架构保持极致简洁、松耦合可替换。
 */

package com.xiaoguang.masteragent.core.bus

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** 域 Agent SPI：25 域按需实现，可插拔 */
interface IDomainAgent {
    val id: String
    val relationType: RelationType
    suspend fun handle(msg: IntentMessage): ExecutionBlock
}

/** 模型网关 SPI：端侧快道 inferLocal / 云端慢道 chat，插件化可替换 */
interface IModelGateway {
    suspend fun chat(system: String, user: String): String
    suspend fun inferLocal(input: String): IntentBlock
}

/** 记忆服务 SPI */
interface IMemoryService {
    suspend fun remember(item: MemoryItem)
    suspend fun recall(query: String, topK: Int): List<MemoryItem>
    suspend fun clear(): Int

    /** 点时间回查（FR-TMP-04）：返回 validFrom ≤ asOfMs < validTo 的当时事实 */
    suspend fun recallAt(query: String, topK: Int, asOfMs: Long): List<MemoryItem>

    /** 后台巩固（FR-UPD-05，L2）：离关键路径合并/提升，返回处理条数 */
    suspend fun consolidate(): Int

    /** 记忆溯源（FR-OBS-02）：返回该 memoryId 的完整版本链 */
    fun provenance(memoryId: String): List<MemoryItem>
}

/** 上下文服务 SPI（对外统一命名，见 PRD §7.1） */
interface IContextManager {
    suspend fun push(msg: IntentMessage): ContextRef
    suspend fun snapshot(budget: Int): ContextWindow

    /** 声明式切片（FR-SLC-01）：权限/脱敏/预算裁剪/记忆注入，产物 ContextSlice */
    suspend fun slice(spec: SliceSpec): ContextSlice

    /** 点时间栈快照（FR-CTX-09，L2）：返回 asOfMs 时刻的上下文栈 */
    suspend fun snapshotAt(asOfMs: Long): ContextWindow
}

/** 检查点存储 SPI */
interface ICheckpointStore {
    fun save(record: CheckpointRecord)
    fun load(messageId: String): CheckpointRecord?
    fun listUnfinished(): List<CheckpointRecord>
}

/** 执行链路旁路采集 SPI（零侵入）：旁路打点 + 富事件发射 + 实时订阅（供表示层解耦消费） */
interface ITraceCollector {
    suspend fun <T> span(step: Step, channel: Channel, status: Status, block: suspend () -> T): T

    /** 发射一条富 trace 事件（非阻塞；掉队丢弃最旧） */
    fun emit(event: TraceEvent)

    /** 实时事件流（表示层订阅渲染，解耦接缝） */
    fun observe(): Flow<TraceEvent>

    /** 有界历史快照（表示层初始渲染 / 补全） */
    fun snapshot(): List<TraceEvent>
}

/** 热修复 SPI（迭代 2） */
interface IHotfixManager {
    suspend fun apply(pkg: HotfixPackage): ApplyResult
    suspend fun rollback(version: String): ApplyResult
}

/** KV 前缀缓存 SPI（core:kv 提供 LRU 实现） */
interface IKvCache {
    fun put(prefix: String, kv: KvBlock, tier: Tier)
    fun get(prefix: String): KvBlock?
    fun hitRate(tier: Tier): Double
}

/** 前缀缓存（契约接口，实现见 core:kv LruPrefixCache） */
interface PrefixCache {
    fun get(prefix: String): KvBlock?
    fun put(prefix: String, kv: KvBlock, tier: Tier)
    fun hitRate(tier: Tier): Double
    fun size(): Int
}

/** 状态源 SPI：实时车态唯一真源（订阅即推） */
interface IStateSource {
    fun observe(): StateFlow<RealtimeState>
}
