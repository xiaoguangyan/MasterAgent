/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ExecutionTraceCollector —— 执行链路采集器：旁路打点，不改变业务返回值（零侵入）。
 *       内置有界历史 + 实时流，经 ITraceCollector.observe() 供表示层订阅渲染（只依赖 contract 的 TraceEvent）。
 */

package com.xiaoguang.masteragent.core.observe

import com.xiaoguang.masteragent.core.bus.Channel
import com.xiaoguang.masteragent.core.bus.ITraceCollector
import com.xiaoguang.masteragent.core.bus.Status
import com.xiaoguang.masteragent.core.bus.Step
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.bus.TraceSource
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow

class ExecutionTraceCollector(private val capacity: Int = DEFAULT_CAPACITY) : ITraceCollector {

    private val seq = AtomicLong(0)

    /** 有界历史（初始渲染 / 补全用） */
    private val history = ArrayDeque<TraceEvent>()

    /** 实时流：掉队丢弃最旧，保证不阻塞埋点线程 */
    private val flow = MutableSharedFlow<TraceEvent>(
        extraBufferCapacity = BUFFER_CAPACITY,
        onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )

    /** 采集：执行 block，记录耗时与状态，原样返回结果 */
    override suspend fun <T> span(step: Step, channel: Channel, status: Status, block: suspend () -> T): T {
        val start = System.currentTimeMillis()
        return try {
            block().also {
                emit(TraceEvent(source = TraceSource.MASTER, channel = channel, status = status, elapsedMs = System.currentTimeMillis() - start, title = step.name))
            }
        } catch (e: Throwable) {
            emit(TraceEvent(source = TraceSource.MASTER, channel = channel, status = Status.ERROR, elapsedMs = System.currentTimeMillis() - start, title = step.name, detail = e.message ?: e.javaClass.simpleName))
            throw e
        }
    }

    /** 发射富事件：盖序号 + 时间戳，入历史 + 实时流 */
    override fun emit(event: TraceEvent) {
        val stamped = event.copy(seq = seq.incrementAndGet(), tsMs = System.currentTimeMillis())
        if (history.size >= capacity) history.removeFirst()
        history.addLast(stamped)
        flow.tryEmit(stamped)
    }

    override fun observe(): Flow<TraceEvent> = flow.asSharedFlow()

    override fun snapshot(): List<TraceEvent> = history.toList()

    companion object {
        const val DEFAULT_CAPACITY = 256
        const val BUFFER_CAPACITY = 512
    }
}
