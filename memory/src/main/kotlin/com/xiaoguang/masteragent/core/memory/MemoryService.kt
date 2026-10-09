/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：MemoryService —— 记忆编排：记(remember) → 理(adjudicate 四态裁定) → 忆(recall)。
 *       敏感拦截 100%（红线）；一键清除端云同步。
 *       L2 标准级：自动重排（新近+频次）、点时间回查 recallAt、后台巩固 consolidate、记忆溯源 provenance。
 */

package com.xiaoguang.masteragent.core.memory

import com.xiaoguang.masteragent.core.bus.Adjudication
import com.xiaoguang.masteragent.core.bus.IMemoryService
import com.xiaoguang.masteragent.core.bus.ITraceCollector
import com.xiaoguang.masteragent.core.bus.LifecycleState
import com.xiaoguang.masteragent.core.bus.MemoryItem
import com.xiaoguang.masteragent.core.bus.Stage
import com.xiaoguang.masteragent.core.bus.Status
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.bus.TraceSource

class MemoryService(
    private val working: WorkingMemory = WorkingMemory(),
    private val episodic: EpisodicStore = EpisodicStore(),
    private val longTerm: LongTermStore = LongTermStore(),
    private val trace: ITraceCollector? = null,
) : IMemoryService {

    /** 访问频次（FR-RET-07 自动重排：新近 + 频次） */
    private val accessCount = HashMap<String, Int>()

    override suspend fun remember(item: MemoryItem) {
        val t0 = System.currentTimeMillis()
        try {
            if (isSensitive(item.content)) throw SensitiveMemoryBlocked(item.content)
            val verdict = adjudicate(item)
            when (verdict) {
                // ADD/UPDATE 均走版本化写入（store 内部失效旧版本 + 版本号 +1），FR-STO-03/FR-UPD-02
                Adjudication.ADD -> { longTerm.store(item); episodic.store(item) }
                Adjudication.UPDATE -> { longTerm.store(item); episodic.store(item) }
                Adjudication.DELETE -> longTerm.markInvalidated(item)
                Adjudication.NOOP -> Unit
            }
            trace?.emit(
                TraceEvent(
                    traceId = item.memoryId, messageId = item.memoryId,
                    source = TraceSource.MEMORY, stage = Stage.RESULT, status = Status.OK,
                    elapsedMs = System.currentTimeMillis() - t0, title = "记忆沉淀",
                    detail = "verdict=$verdict id=${item.memoryId} v=${item.version} content=${item.content.take(24)}",
                    input = "content=${item.content.take(24)}", output = "verdict=$verdict id=${item.memoryId} v=${item.version}",
                ),
            )
        } catch (e: Throwable) {
            trace?.emit(
                TraceEvent(
                    traceId = item.memoryId, messageId = item.memoryId,
                    source = TraceSource.MEMORY, stage = Stage.RESULT, status = Status.ERROR,
                    elapsedMs = System.currentTimeMillis() - t0, title = "记忆沉淀",
                    detail = "blocked=${e.message}",
                    input = "content=${item.content.take(24)}", output = "blocked=${e.message}",
                ),
            )
            throw e
        }
    }

    override suspend fun recall(query: String, topK: Int): List<MemoryItem> {
        val t0 = System.currentTimeMillis()
        val cand = (working.get(query) + episodic.query(query) + longTerm.search(query, topK))
            .distinctBy { it.memoryId }
        // 自动重排（FR-RET-07）：频次优先，其次新近（recordTime 降序）
        val ranked = cand.sortedWith(
            compareByDescending<MemoryItem> { accessCount[it.memoryId] ?: 0 }
                .thenByDescending { it.recordTime },
        )
        val hits = ranked.take(topK)
        hits.forEach { accessCount[it.memoryId] = (accessCount[it.memoryId] ?: 0) + 1 }
        trace?.emit(
            TraceEvent(
                source = TraceSource.MEMORY, stage = Stage.INTENT, status = Status.OK,
                elapsedMs = System.currentTimeMillis() - t0, title = "记忆召回",
                detail = "query=${query.take(24)} hits=${hits.size}/$topK rerank=频次+新近",
                input = "query=${query.take(24)} topK=$topK", output = "hits=${hits.size}/$topK",
            ),
        )
        return hits
    }

    /** 点时间回查（FR-TMP-04）：返回 asOfMs 时刻有效的当时事实 */
    override suspend fun recallAt(query: String, topK: Int, asOfMs: Long): List<MemoryItem> {
        val t0 = System.currentTimeMillis()
        val hits = longTerm.searchAt(query, topK, asOfMs)
        trace?.emit(
            TraceEvent(
                source = TraceSource.MEMORY, stage = Stage.INTENT, status = Status.OK,
                elapsedMs = System.currentTimeMillis() - t0, title = "点时间回查",
                detail = "query=${query.take(24)} asOf=$asOfMs hits=${hits.size}/$topK",
                input = "query=${query.take(24)} asOf=$asOfMs", output = "hits=${hits.size}/$topK",
            ),
        )
        return hits
    }

    /** 后台巩固（FR-UPD-05，L2，离关键路径）：去重合并 + 高频条目置信度提升，返回处理条数 */
    override suspend fun consolidate(): Int {
        val t0 = System.currentTimeMillis()
        var n = 0
        // 1) 去重合并：同 content 多条记忆仅保留最新一条，其余失效（FR-UPD-01 强化）
        longTerm.all().groupBy { it.content }.forEach { (_, items) ->
            if (items.size > 1) {
                val keep = items.maxByOrNull { it.recordTime }!!
                items.filter { it.memoryId != keep.memoryId }.forEach { dup ->
                    longTerm.markInvalidated(dup); n++
                }
            }
        }
        // 2) 置信度提升：高频访问条目提升至 0.9 上限（FR-UPD-03 弱模型巩固）
        for ((id, count) in accessCount) {
            if (count >= FREQUENT_THRESHOLD) { longTerm.boostConfidence(id, 0.9); n++ }
        }
        trace?.emit(
            TraceEvent(
                source = TraceSource.MEMORY, stage = Stage.RESULT, status = Status.OK,
                elapsedMs = System.currentTimeMillis() - t0, title = "后台巩固",
                detail = "processed=$n（去重合并 + 置信度提升）",
                input = "sleep-time 触发", output = "processed=$n",
            ),
        )
        return n
    }

    /** 记忆溯源（FR-OBS-02）：完整版本链 */
    override fun provenance(memoryId: String): List<MemoryItem> = longTerm.history(memoryId)

    override suspend fun clear(): Int {
        val n = longTerm.clear()
        working.reset()
        episodic.clear()
        accessCount.clear()
        trace?.emit(
            TraceEvent(
                source = TraceSource.MEMORY, stage = Stage.RESULT, status = Status.OK,
                title = "记忆清除", detail = "cleared=$n",
                input = "clear all", output = "cleared=$n",
            ),
        )
        return n
    }

    /** 四态裁定：按 memoryId 主键判重 + 显式删除意图 */
    private fun adjudicate(item: MemoryItem): Adjudication = when {
        item.state == LifecycleState.DELETED || item.state == LifecycleState.INVALIDATED -> Adjudication.DELETE
        else -> {
            val existing = longTerm.find(item.memoryId)
            when {
                existing == null -> Adjudication.ADD
                existing.content == item.content -> Adjudication.NOOP
                else -> Adjudication.UPDATE
            }
        }
    }

    private fun isSensitive(content: String): Boolean =
        SENSITIVE_KEYWORDS.any { content.contains(it) }

    companion object {
        /** 敏感关键词（红线：拦截率 100%） */
        val SENSITIVE_KEYWORDS = listOf("密码", "身份证", "银行卡", "口令")

        /** 巩固触发的高频访问阈值 */
        const val FREQUENT_THRESHOLD = 2
    }
}

/** 敏感记忆拦截异常 */
class SensitiveMemoryBlocked(val content: String) : IllegalStateException("敏感记忆拦截: $content")
