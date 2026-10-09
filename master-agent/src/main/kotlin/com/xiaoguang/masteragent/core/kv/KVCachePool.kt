/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：KV Cache 池 —— 两级 KV 复用（推理级 O(n²)→O(n) + 前缀级），前缀分级 P0-P3。
 */

package com.xiaoguang.masteragent.core.kv

import com.xiaoguang.masteragent.core.bus.IKvCache
import com.xiaoguang.masteragent.core.bus.KvBlock
import com.xiaoguang.masteragent.core.bus.PrefixCache
import com.xiaoguang.masteragent.core.bus.Tier

class KVCachePool(private val cache: PrefixCache) : IKvCache {
    override fun put(prefix: String, kv: KvBlock, tier: Tier) = cache.put(prefix, kv, tier)
    override fun get(prefix: String): KvBlock? = cache.get(prefix)
    override fun hitRate(tier: Tier): Double = cache.hitRate(tier)
}
