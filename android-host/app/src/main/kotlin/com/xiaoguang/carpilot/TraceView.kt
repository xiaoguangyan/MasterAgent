/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：TraceView —— 车机端表示层：只消费 contract 的 TraceEvent，把一条消息在 master-agent 的「整条数据流」渲染成竖向流程图。
 *       每个环节 = 一张节点卡片：左侧状态色条 + 标题 + 来源/阶段/通道/耗时(毫秒)/状态 + 「详情」行 + 「输入」区 + 「输出」区；节点间以 ↓ 箭头相连。
 *       大模型调用：输入（提示词）先行，输出（生成结果）以「打字机」流式逐字揭示（「大模型调用」与「大模型流式输出」合并为一张 LLM 节点），
 *       节点「详情」行标注调用的哪个大模型（如 Qwen/Qwen2.5-14B-Instruct），并回填该次大模型调用的真实耗时。
 *       渲染由 MainActivity 以 snapshot 列表驱动（确定性、可靠），与 master-agent 完全解耦（不 import 任何内部实现）。
 */

package com.xiaoguang.carpilot

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.xiaoguang.masteragent.core.bus.Channel
import com.xiaoguang.masteragent.core.bus.Status
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.bus.TraceSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * 无死角 trace 流程图面板：render(events) 一次性渲染一条消息的完整数据流，自动滚到顶部。
 * 节点数有界（MAX_NODES），避免超长链路无限堆积。
 */
class TraceView(context: Context) : ScrollView(context) {

    private val container = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(12, 12, 12, 12)
    }

    // 大模型流式输出：render 时「大模型调用」与「大模型流式输出」合并为一张 LLM 节点，输出区打字机揭示
    private var streamJob: Job? = null
    private val streamScope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        isFillViewport = true
        addView(container, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
    }

    /** 渲染一条消息的完整数据流（当前消息或历史回放，二者同一入口） */
    fun render(events: List<TraceEvent>) {
        streamJob?.cancel()
        container.removeAllViews()

        // 未显式测耗时的步骤：用相邻事件时间戳间隔近似补全，保证「每一步都有毫秒级耗时」标注
        val timed = events.mapIndexed { i, e ->
            if (e.elapsedMs > 0) e
            else if (i == 0) e.copy(elapsedMs = 0L)
            else e.copy(elapsedMs = (e.tsMs - events[i - 1].tsMs).coerceAtLeast(0L))
        }

        var llmNode: NodeView? = null
        for (e in timed) {
            when {
                // 大模型调用：先建节点（输入=提示词，输出占位「生成中…」）
                e.title == "大模型调用" || e.title == "意图拆分（M-intent）" ->
                    llmNode = appendNode(e)
                // 大模型流式输出：把生成结果流式揭示到上一个 LLM 节点，并回填该次大模型调用的真实耗时
                e.title == "大模型流式输出" || e.title == "意图拆分结果" -> {
                    val target = llmNode
                    llmNode = null
                    if (target != null) {
                        typewriter(target.out, e.output)
                        target.setElapsed(e.elapsedMs)
                    } else {
                        appendNode(e)
                    }
                }
                else -> appendNode(e)
            }
        }
        // 整条流程自顶向下展示：滚到顶部，让用户从头看到尾
        post { fullScroll(FOCUS_UP) }
    }

    /** 新建一个流程图节点（输入 → 环节 → 输出），返回可后续更新耗时/输出的节点视图 */
    private fun appendNode(e: TraceEvent): NodeView {
        // 有界：超过上限丢弃最旧一「节点 + 箭头」两个子视图
        while (container.childCount >= MAX_NODES * 2) container.removeViewAt(0)

        val color = statusColor(e.status)
        var metaView: TextView? = null
        var outView: TextView? = null
        val node = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            // 左侧状态色条（节点视觉锚点）
            addView(View(context).apply {
                setBackgroundColor(color)
                layoutParams = LinearLayout.LayoutParams(dp(4), LinearLayout.LayoutParams.MATCH_PARENT)
            })
            // 节点主体
            val body = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(12), dp(8), dp(12), dp(10))
                setBackgroundColor(Color.rgb(0xFA, 0xFA, 0xFA))
            }
            body.addView(TextView(context).apply {
                text = "#${e.seq}  ${e.title}"
                textSize = 13f
                typeface = Typeface.DEFAULT_BOLD
                setTextColor(color)
            })
            val meta = TextView(context).apply {
                text = metaLine(e)
                textSize = 10f
                setTextColor(Color.rgb(0x8A, 0x8A, 0x8A))
            }
            body.addView(meta)
            metaView = meta
            // 详情行：大模型调用等步骤在此标注「调用的哪个大模型」；与输出重复时省略
            if (e.detail.isNotBlank() && e.detail != e.output) {
                body.addView(TextView(context).apply {
                    text = "◈ ${e.detail}"
                    textSize = 11f
                    typeface = Typeface.MONOSPACE
                    setTextColor(Color.rgb(0x45, 0x5A, 0x8B))
                    setPadding(0, dp(3), 0, 0)
                })
            }
            // 输入区
            body.addView(TextView(context).apply {
                text = "输入  ${e.input.ifBlank { "—" }}"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(0x42, 0x42, 0x42))
                setPadding(0, dp(6), 0, 0)
            })
            // 输出区（大模型调用时输出为空，先占位「生成中…」）
            val tv = TextView(context).apply {
                text = "输出  ${e.output.ifBlank { if (e.title == "大模型调用" || e.title == "意图拆分（M-intent）") "生成中…" else "—" }}"
                textSize = 12f
                typeface = Typeface.MONOSPACE
                setTextColor(Color.rgb(0x21, 0x21, 0x21))
                setPadding(0, dp(2), 0, 0)
            }
            body.addView(tv)
            outView = tv
            addView(body, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        }
        container.addView(node)
        container.addView(arrow())
        return NodeView(e, metaView!!, outView!!)
    }

    /** 节点内可后续更新的子视图引用（大模型节点：流式揭示输出 + 回填真实耗时） */
    private inner class NodeView(private val ev: TraceEvent, val meta: TextView, val out: TextView) {
        fun setElapsed(ms: Long) {
            meta.text = metaLine(ev.copy(elapsedMs = ms))
        }
    }

    /** 节点元信息行：来源 · 阶段 · 快慢道 · 耗时(毫秒) · 状态 */
    private fun metaLine(e: TraceEvent): String =
        "[${srcLabel(e.source)}]  ${e.stage.name} · ${channelLabel(e.channel)} · ${e.elapsedMs}ms · ${statusLabel(e.status)}"

    /** 打字机流式揭示：把大模型生成结果逐字写入 LLM 节点输出区 */
    private fun typewriter(view: TextView, fullText: String) {
        streamJob?.cancel()
        streamJob = streamScope.launch {
            val step = maxOf(1, fullText.length / 60) // 每步揭示字符数（按长度自适应，控制总时长）
            var pos = 0
            while (pos < fullText.length) {
                pos = minOf(fullText.length, pos + step)
                view.text = "输出  ${fullText.substring(0, pos)}${if (pos < fullText.length) "▌" else ""}"
                delay(24)
            }
        }
    }

    /** 节点间箭头 */
    private fun arrow(): TextView = TextView(context).apply {
        text = "↓"
        textSize = 14f
        gravity = Gravity.CENTER_HORIZONTAL
        setTextColor(Color.rgb(0xB0, 0xB0, 0xB0))
        setPadding(0, dp(2), 0, dp(2))
    }

    /** 状态色：OK 绿 / DEGRADED 黄 / ERROR 红 */
    private fun statusColor(status: Status): Int = when (status) {
        Status.OK -> Color.rgb(0x2E, 0x7D, 0x32)
        Status.DEGRADED -> Color.rgb(0xB5, 0x7A, 0x00)
        Status.ERROR -> Color.rgb(0xC6, 0x28, 0x28)
    }

    private fun statusLabel(status: Status): String = when (status) {
        Status.OK -> "正常"
        Status.DEGRADED -> "降级"
        Status.ERROR -> "异常"
    }

    private fun channelLabel(channel: Channel): String = when (channel) {
        Channel.FAST -> "快道"
        Channel.SLOW -> "慢道"
    }

    private fun srcLabel(source: String): String = when (source) {
        TraceSource.MASTER -> "主控"
        TraceSource.CONTEXT -> "上下文"
        TraceSource.MEMORY -> "记忆"
        TraceSource.CHECKPOINT -> "检查点"
        TraceSource.BUS -> "总线"
        else -> source
    }

    private fun dp(v: Int): Int = (context.resources.displayMetrics.density * v).toInt()

    override fun onDetachedFromWindow() {
        streamScope.cancel()
        super.onDetachedFromWindow()
    }

    companion object {
        private const val MAX_NODES = 100
    }
}
