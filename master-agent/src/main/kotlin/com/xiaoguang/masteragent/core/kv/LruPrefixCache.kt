/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：LRU 前缀缓存（PrefixCache 实现）—— 命中计数预热 + 超预算淘汰 LRU 尾部。
 */

package com.xiaoguang.masteragent.core.kv

import com.xiaoguang.masteragent.core.bus.KvBlock
import com.xiaoguang.masteragent.core.bus.PrefixCache
import com.xiaoguang.masteragent.core.bus.Tier

class LruPrefixCache(private val budgetBytes: Long = DEFAULT_BUDGET) : PrefixCache {

    private data class Node(val kv: KvBlock, var hits: Long = 0)

    /** LinkedHashMap 保持插入序即 LRU 序 */
    private val map = LinkedHashMap<String, Node>()
    private var usedBytes = 0L

    override fun get(prefix: String): KvBlock? {
        val node = map[prefix] ?: return null
        // 命中 → 移到 LRU 头部 + 命中计数
        map.remove(prefix)
        node.hits++
        map[prefix] = node
        return node.kv
    }

    override fun put(prefix: String, kv: KvBlock, tier: Tier) {
        val node = Node(kv.copy(tier = tier))
        usedBytes += kv.sizeBytes
        map[prefix] = node
        // 超预算 → 淘汰最低 tier 的 LRU 尾部（MVP 简化为淘汰最旧）
        while (usedBytes > budgetBytes && map.size > 1) evictLru()
    }

    private fun evictLru() {
        val oldest = map.entries.first()
        map.remove(oldest.key)
        usedBytes -= oldest.value.kv.sizeBytes
    }

    override fun hitRate(tier: Tier): Double {
        val nodes = map.values
        if (nodes.isEmpty()) return 0.0
        val hits = nodes.sumOf { it.hits }
        val total = hits + nodes.size
        return if (total == 0L) 0.0 else hits.toDouble() / total
    }

    override fun size(): Int = map.size

    companion object {
        const val DEFAULT_BUDGET: Long = 64L * 1024 * 1024
    }
}
