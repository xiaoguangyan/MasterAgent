/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：RingBuffer —— L1 滑动窗口：定长环形缓冲，满则覆盖最旧条目，读写均 O(1)。
 */

package com.xiaoguang.masteragent.core.context

import com.xiaoguang.masteragent.core.bus.IntentMessage

class RingBuffer(private val capacity: Int = DEFAULT_CAPACITY) {

    private val buffer = ArrayDeque<IntentMessage>()

    /** 追加：满则覆盖最旧条目，无内存增长 */
    fun append(msg: IntentMessage) {
        if (buffer.size >= capacity) buffer.removeFirst()
        buffer.addLast(msg)
    }

    /** 最近 N 轮 */
    fun window(): List<IntentMessage> = buffer.toList()

    /** 点时间窗口（FR-CTX-09）：仅返回 tsMs ≤ asOfMs 的条目 */
    fun windowAt(asOfMs: Long): List<IntentMessage> =
        buffer.filter { it.header.tsMs <= asOfMs }

    fun size(): Int = buffer.size

    companion object {
        const val DEFAULT_CAPACITY = 8
    }
}
