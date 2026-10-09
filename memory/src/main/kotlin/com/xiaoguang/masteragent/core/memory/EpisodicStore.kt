/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：EpisodicStore —— 短期记忆（L1）：近期事件序列，追加 + TTL 过期淘汰。
 */

package com.xiaoguang.masteragent.core.memory

import com.xiaoguang.masteragent.core.bus.MemoryItem

class EpisodicStore(private val ttlMs: Long = DEFAULT_TTL_MS) {

    private val items = LinkedHashMap<String, MemoryItem>()

    fun store(item: MemoryItem) {
        items[item.memoryId] = item
        evict()
    }

    fun query(range: String): List<MemoryItem> =
        items.values.filter { it.content.contains(range) }

    /** 一键清除：返回清除条数（企业治理，端云同步） */
    fun clear(): Int {
        val n = items.size
        items.clear()
        return n
    }

    private fun evict() {
        val now = System.currentTimeMillis()
        items.entries.removeAll { now - it.value.recordTime > ttlMs }
    }

    companion object {
        const val DEFAULT_TTL_MS: Long = 7 * 24 * 3600_000L  // 7 天
    }
}
