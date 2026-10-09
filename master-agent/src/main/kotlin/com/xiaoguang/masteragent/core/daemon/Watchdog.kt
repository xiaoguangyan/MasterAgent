/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Watchdog —— 看门狗：周期性探测执行超时 / 卡死，超过阈值触发续跑。
 */

package com.xiaoguang.masteragent.core.daemon

import com.xiaoguang.masteragent.core.bus.CheckpointRecord
import com.xiaoguang.masteragent.core.bus.ICheckpointStore
import kotlinx.coroutines.delay

class Watchdog(
    private val checkpoint: ICheckpointStore,
    private val probeIntervalMs: Long = PROBE_INTERVAL_MS,
    private val stallThresholdMs: Long = STALL_THRESHOLD_MS,
) {
    /** 已标记续跑的消息 ID 集合（幂等） */
    private val resumed = mutableSetOf<String>()

    /**
     * 探测循环：由协程驱动；发现停滞（超时未推进）且未续跑过的消息时触发 onStall 回调。
     * 调用方负责在 onStall 中执行 resumeUnfinished。
     */
    suspend fun watch(onStall: suspend (CheckpointRecord) -> Unit) {
        while (true) {
            val now = System.currentTimeMillis()
            checkpoint.listUnfinished().forEach { r ->
                if (now - r.updatedAt > stallThresholdMs && resumed.add(r.messageId)) {
                    onStall(r)
                }
            }
            delay(probeIntervalMs)
        }
    }

    companion object {
        const val PROBE_INTERVAL_MS = 50L
        const val STALL_THRESHOLD_MS = 100L
    }
}
