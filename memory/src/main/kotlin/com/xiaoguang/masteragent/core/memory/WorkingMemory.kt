/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：WorkingMemory —— 工作记忆：本轮会话临时记忆，会话结束随会话清理（O(1)）。
 */

package com.xiaoguang.masteragent.core.memory

import com.xiaoguang.masteragent.core.bus.MemoryItem

class WorkingMemory {
    private val map = LinkedHashMap<String, MemoryItem>()

    fun put(key: String, value: MemoryItem) {
        map[key] = value
    }

    fun get(query: String): List<MemoryItem> =
        map.values.filter { it.content.contains(query) }

    fun reset() = map.clear()
}
