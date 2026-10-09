/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ContextManager —— L0-L4 分层路由编排：push() 写入、snapshot(budget) 组装、驱逐下沉记忆。
 *       L1 端侧本地闭环；L2 起经 IMemoryService 下沉长期记忆（云侧）。
 */

package com.xiaoguang.masteragent.core.context

import com.xiaoguang.masteragent.core.bus.ContextRef
import com.xiaoguang.masteragent.core.bus.ContextSlice
import com.xiaoguang.masteragent.core.bus.ContextWindow
import com.xiaoguang.masteragent.core.bus.EvictSummary
import com.xiaoguang.masteragent.core.bus.IContextManager
import com.xiaoguang.masteragent.core.bus.IMemoryService
import com.xiaoguang.masteragent.core.bus.ITraceCollector
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.Layer0
import com.xiaoguang.masteragent.core.bus.Layer1
import com.xiaoguang.masteragent.core.bus.Layer2
import com.xiaoguang.masteragent.core.bus.Layer3
import com.xiaoguang.masteragent.core.bus.Layer4
import com.xiaoguang.masteragent.core.bus.Layers
import com.xiaoguang.masteragent.core.bus.RealtimeState
import com.xiaoguang.masteragent.core.bus.SliceSpec
import com.xiaoguang.masteragent.core.bus.Stage
import com.xiaoguang.masteragent.core.bus.Status
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.bus.TraceSource
import java.util.UUID

class ContextManager(
    private val ring: RingBuffer = RingBuffer(),
    private val realtime: RealtimeStore = RealtimeStore(),
    private val memory: IMemoryService? = null,
    private val trace: ITraceCollector? = null,
) : IContextManager {

    override suspend fun push(msg: IntentMessage): ContextRef {
        val t0 = System.currentTimeMillis()
        ring.append(msg)                              // L1 环形追加 O(1)
        realtime.update { merge(it, msg) }            // L2 状态原子更新
        val ref = ContextRef("ctx-" + shortId())
        val h = msg.header
        val s = realtime.state.value
        trace?.emit(
            TraceEvent(
                traceId = h.traceId, sessionId = h.sessionId, messageId = h.messageId,
                source = TraceSource.CONTEXT, stage = Stage.INPUT, status = Status.OK,
                elapsedMs = System.currentTimeMillis() - t0, title = "上下文写入 L1/L2",
                detail = "ref=${ref.ref} ring=${ring.size()} temp=${s.temp} seat=${s.seat}",
                input = "utterance=${msg.input.utterance.take(24)}", output = "ref=${ref.ref} ring=${ring.size()} temp=${s.temp} seat=${s.seat}",
            ),
        )
        return ref
    }

    override suspend fun snapshot(budget: Int): ContextWindow {
        val t0 = System.currentTimeMillis()
        val w = buildWindow(ring.window(), budget)
        trace?.emit(
            TraceEvent(
                source = TraceSource.CONTEXT, stage = Stage.RESULT, status = Status.OK,
                elapsedMs = System.currentTimeMillis() - t0, title = "上下文快照 L0-L4",
                detail = "l0=${w.layers.l0.items.size} l1=${w.layers.l1.windowN} l2temp=${w.layers.l2.realtime.temp} l3budget=$budget",
                input = "budget=$budget", output = "l0=${w.layers.l0.items.size} l1=${w.layers.l1.windowN} l2temp=${w.layers.l2.realtime.temp} l3budget=$budget",
            ),
        )
        return w
    }

    /** 点时间栈快照（FR-CTX-09）：仅回放 asOfMs 时刻已入栈的条目；L2 实时车态为当前态 */
    override suspend fun snapshotAt(asOfMs: Long): ContextWindow {
        val t0 = System.currentTimeMillis()
        val items = ring.windowAt(asOfMs)
        val w = buildWindow(items, items.size)
        trace?.emit(
            TraceEvent(
                source = TraceSource.CONTEXT, stage = Stage.RESULT, status = Status.OK,
                elapsedMs = System.currentTimeMillis() - t0, title = "点时间快照",
                detail = "asOf=$asOfMs l1=${w.layers.l1.windowN}",
                input = "asOf=$asOfMs", output = "asOf=$asOfMs l1=${w.layers.l1.windowN}",
            ),
        )
        return w
    }

    /** 声明式切片（FR-SLC-01）：权限/脱敏/预算裁剪/记忆注入/回退链，产物 ContextSlice */
    override suspend fun slice(spec: SliceSpec): ContextSlice {
        val t0 = System.currentTimeMillis()
        val slice = SliceEngine.slice(spec, ring.window(), realtime.state.value, memory)
        trace?.emit(
            TraceEvent(
                source = TraceSource.CONTEXT, stage = Stage.RESULT, status = Status.OK,
                elapsedMs = System.currentTimeMillis() - t0, title = "声明式切片",
                detail = slice.provenance,
                input = "spec=${spec.layers.joinToString(",")} role=${spec.role} budget=${spec.tokenBudget}",
                output = "kept=${slice.fields.size} injected=${slice.injectedMemories.size}",
            ),
        )
        return slice
    }

    /** 组装 L0-L4 分层窗口（snapshot / snapshotAt 共用） */
    private fun buildWindow(items: List<IntentMessage>, budget: Int): ContextWindow = ContextWindow(
        contextRef = "ctx-" + shortId(),
        layers = Layers(
            l0 = Layer0(rounds = 1, items = items.takeLast(1).map { it.input.utterance }),
            l1 = Layer1(windowN = items.size, ring = items.map { it.input.utterance }),
            l2 = Layer2(realtime = realtime.state.value),
            l3 = Layer3(tokenBudget = budget, used = items.size),
            l4 = Layer4(),
        ),
        evicted = EvictSummary(),
    )

    /** 从感知字段合并到实时车态（字段缺失保留原值） */
    private fun merge(state: RealtimeState, msg: IntentMessage): RealtimeState {
        val p = msg.input.perception
        return state.copy(
            temp = p["temp"]?.toIntOrNull() ?: state.temp,
            seat = p["seat"] ?: state.seat,
            speed = p["speed"]?.toDoubleOrNull() ?: state.speed,
        )
    }

    private fun shortId(): String = UUID.randomUUID().toString().take(8)
}
