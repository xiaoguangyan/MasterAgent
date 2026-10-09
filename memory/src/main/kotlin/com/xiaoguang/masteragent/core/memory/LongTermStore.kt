/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：LongTermStore —— 长期记忆（L1 端侧固化 KV 兜底；L3 起云端 Milvus/MySQL）。
 *       L2 标准级：版本化写入（version 单调递增 + 版本链物理保留，失效非覆盖）、
 *       双时态点时间回查（validFrom/validTo as_of 查询）、TTL 过期淘汰、置信度巩固提升。
 *       MVP 用内存 KV 模拟固化存储，真实部署替换为 Room/RocksDB。
 */

package com.xiaoguang.masteragent.core.memory

import com.xiaoguang.masteragent.core.bus.LifecycleState
import com.xiaoguang.masteragent.core.bus.MemoryItem

class LongTermStore(private val ttlMs: Long = DEFAULT_TTL_MS) {

    /** 当前生效版本（memoryId → 最新条目） */
    private val current = LinkedHashMap<String, MemoryItem>()

    /** 版本链（memoryId → 历史版本，新在前），物理保留供点时间回查 / 溯源 */
    private val versions = LinkedHashMap<String, MutableList<MemoryItem>>()

    /**
     * 版本化写入：新条目有效；已有条目则「失效旧版本（物理保留）→ 版本号 +1 → 追加新版本」。
     * FR-STO-03（版本化）/ FR-UPD-02（失效非覆盖）。
     */
    fun store(item: MemoryItem) {
        val existing = current[item.memoryId]
        val toStore = if (existing == null) {
            item.copy(
                version = 1L,
                validFromMs = if (item.validFromMs > 0L) item.validFromMs else item.recordTime,
            )
        } else {
            // 旧版本封口：validToMs = 新记录时间，状态置 SUPERSEDED（物理保留，不覆盖）
            val superseded = existing.copy(
                validToMs = item.recordTime,
                state = LifecycleState.SUPERSEDED,
            )
            // 版本链同步替换为封口后的副本（失效非覆盖，溯源/点时间回查可见历史状态）
            versions[item.memoryId]?.let { list ->
                val idx = list.indexOfFirst { it.version == existing.version }
                if (idx >= 0) list[idx] = superseded
            }
            item.copy(
                version = existing.version + 1L,
                validFromMs = if (item.validFromMs > 0L) item.validFromMs else item.recordTime,
            )
        }
        current[item.memoryId] = toStore
        versions.getOrPut(item.memoryId) { mutableListOf() }.add(0, toStore)
        evictExpired()
    }

    /** 当前生效条目（仅 VALID） */
    fun find(memoryId: String): MemoryItem? =
        current[memoryId]?.takeIf { it.state == LifecycleState.VALID }

    /** 完整版本链（新→旧，FR-OBS-02 溯源） */
    fun history(memoryId: String): List<MemoryItem> = versions[memoryId]?.toList() ?: emptyList()

    /** 点时间查询（FR-TMP-04）：validFrom ≤ asOf < validTo */
    fun findAt(memoryId: String, asOfMs: Long): MemoryItem? =
        versions[memoryId]?.firstOrNull { it.activeAt(asOfMs) }

    /** 关键字检索（当前生效，仅 VALID） */
    fun search(query: String, topK: Int): List<MemoryItem> =
        current.values
            .filter { it.state == LifecycleState.VALID && it.content.contains(query) }
            .take(topK)

    /** 点时间检索（FR-TMP-04）：asOf 时刻有效、含关键词、按 id 去重 */
    fun searchAt(query: String, topK: Int, asOfMs: Long): List<MemoryItem> =
        versions.values.flatten()
            .filter { it.content.contains(query) && it.activeAt(asOfMs) }
            .distinctBy { it.memoryId }
            .take(topK)

    /** 条目在 asOfMs 时刻是否有效（validFrom ≤ asOf < validTo，to=null 表示仍有效） */
    private fun MemoryItem.activeAt(asOfMs: Long): Boolean {
        val to = validToMs
        return validFromMs <= asOfMs && (to == null || asOfMs < to)
    }

    /** 全部当前条目（含非 VALID，供后台巩固） */
    fun all(): List<MemoryItem> = current.values.toList()

    /** 显式失效（DELETE 裁定）：封口 validToMs、置 INVALIDATED（物理保留） */
    fun markInvalidated(item: MemoryItem) {
        current[item.memoryId]?.let {
            current[item.memoryId] = it.copy(state = LifecycleState.INVALIDATED, validToMs = item.recordTime)
        }
    }

    /** 显式取代（UPDATE 裁定，兼容旧调用）：封口 + SUPERSEDED */
    fun markSuperseded(item: MemoryItem) {
        current[item.memoryId]?.let {
            current[item.memoryId] = it.copy(state = LifecycleState.SUPERSEDED, validToMs = item.recordTime)
        }
    }

    /** 置信度巩固提升（FR-UPD-03）：将某条目置信度提升至不低于给定值（模拟弱模型巩固） */
    fun boostConfidence(memoryId: String, to: Double) {
        current[memoryId]?.let {
            if (it.confidence < to) current[memoryId] = it.copy(confidence = to)
        }
    }

    /** 一键清除：返回清除条数（企业治理，端云同步） */
    fun clear(): Int {
        val n = current.size
        current.clear()
        versions.clear()
        return n
    }

    /** TTL 过期淘汰（FR-FGT-01）：超过 ttl 的 VALID 条目置 EXPIRED 并封口 */
    private fun evictExpired() {
        val now = System.currentTimeMillis()
        current.entries.forEach { (id, item) ->
            if (item.state == LifecycleState.VALID && now - item.recordTime > ttlMs) {
                current[id] = item.copy(state = LifecycleState.EXPIRED, validToMs = now)
            }
        }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 30 * 24 * 3600_000L  // 30 天
    }
}
