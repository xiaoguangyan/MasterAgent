/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：CheckpointStore —— 检查点存储（守护断点续跑）。MVP 内存实现；真实部署落盘 / 同步云侧。
 */

package com.xiaoguang.masteragent.core.daemon

import com.xiaoguang.masteragent.core.bus.CheckpointRecord
import com.xiaoguang.masteragent.core.bus.ICheckpointStore
import com.xiaoguang.masteragent.core.bus.Stage

class CheckpointStore : ICheckpointStore {

    private val store = LinkedHashMap<String, CheckpointRecord>()

    override fun save(record: CheckpointRecord) {
        store[record.messageId] = record
    }

    override fun load(messageId: String): CheckpointRecord? = store[messageId]

    override fun listUnfinished(): List<CheckpointRecord> =
        store.values.filter { it.lastCompleted != Stage.RESULT }
}
