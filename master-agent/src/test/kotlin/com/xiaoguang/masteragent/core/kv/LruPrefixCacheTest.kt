/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：LRU 前缀缓存单元测试 —— 命中 / 预算淘汰。
 */

package com.xiaoguang.masteragent.core.kv

import com.xiaoguang.masteragent.core.bus.KvBlock
import com.xiaoguang.masteragent.core.bus.Tier
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

class LruPrefixCacheTest {

    @Test
    fun `get returns value and bumps hit rate`() {
        val cache = LruPrefixCache()
        cache.put("p0", KvBlock(prefix = "p0", payload = "系统前缀", sizeBytes = 100), Tier.P0)
        val hit = cache.get("p0")
        assertEquals("系统前缀", hit?.payload)
        assert(cache.hitRate(Tier.P0) > 0.0)
    }

    @Test
    fun `missing key returns null`() {
        val cache = LruPrefixCache()
        assertNull(cache.get("not-exist"))
    }

    @Test
    fun `over budget evicts oldest entry`() {
        // 预算 250 字节（可容 2 条），塞入 3 条 100 字节 → 最旧条目被淘汰
        val cache = LruPrefixCache(budgetBytes = 250)
        cache.put("a", KvBlock(prefix = "a", payload = "A", sizeBytes = 100), Tier.P2)
        cache.put("b", KvBlock(prefix = "b", payload = "B", sizeBytes = 100), Tier.P2)
        cache.put("c", KvBlock(prefix = "c", payload = "C", sizeBytes = 100), Tier.P2)
        assertNull(cache.get("a"))
        assertEquals("B", cache.get("b")?.payload)
        assertEquals("C", cache.get("c")?.payload)
    }
}
