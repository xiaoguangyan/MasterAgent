/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Android 车机接入演示 —— 装配 master-agent + 注入端侧模型网关 SPI，跑一条意图指令。
 *       含中文语音输入（讯飞 SparkChain 在线语音听写 ASR），文本 / 语音双通道喂给主控。
 *       界面：上部「历史记录」存档每条已完成指令（点击可回放该条完整数据流）；下部「当前数据流」只渲染当前这条指令的全链路流程图。
 *       当前数据流由 snapshot 列表确定性渲染（与实时流解耦，保证始终可见）。
 */

package com.xiaoguang.carpilot

import android.Manifest
import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Bundle
import android.util.Log
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.xiaoguang.carpilot.voice.IAsrGateway
import com.xiaoguang.carpilot.voice.SparkChainAsrGateway
import com.xiaoguang.masteragent.app.Assembly
import com.xiaoguang.masteragent.app.ModelConfigLoader
import com.xiaoguang.masteragent.core.bus.Header
import com.xiaoguang.masteragent.core.bus.IModelGateway
import com.xiaoguang.masteragent.core.bus.Input
import com.xiaoguang.masteragent.core.bus.IntentBlock
import com.xiaoguang.masteragent.core.bus.IntentCandidate
import com.xiaoguang.masteragent.core.bus.IntentMessage
import com.xiaoguang.masteragent.core.bus.ModelConfig
import com.xiaoguang.masteragent.core.bus.Route
import com.xiaoguang.masteragent.core.bus.TraceEvent
import com.xiaoguang.masteragent.core.model.IntentSplitter
import com.xiaoguang.carpilot.net.StreamingChatClient
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 端侧模型网关 SPI 实现（插槽），模型名/地址由 model-config.json 统一配置（PRD §2.11）。
 * 真实车机：端侧快道替换为 Qualcomm Hexagon NPU + QNN 量化后的 1.5B SLM（m_intent_edge）；
 *          云端慢道 chat() 为真实流式调用（SSE），逐 token 回调 onDelta。
 */
class QnnModelGateway(
    private val modelConfig: ModelConfig,
    /** 流式输出回调：每次收到大模型增量 token 时触发（IO 线程回调，调用方自行切 UI） */
    private val onDelta: (String) -> Unit = {},
) : IModelGateway {
    private val edgeModel = modelConfig.model("m_intent_edge")?.name ?: "Qwen2.5-1.5B-Instruct"

    // ★ 大模型调用位置标记（来自 model-config.json / PRD §2.11）
    //   使用哪个大模型：m_intent = 意图理解/意图拆分 M-intent（Prompt A1/A1-split）
    //   模型名称 name    ：Qwen/Qwen2.5-14B-Instruct
    //   模型地址 endpoint ：https://api.siliconflow.cn/v1/chat/completions（硅基流动，OpenAI 兼容 SSE）
    //   鉴权密钥 apiKey   ：Bearer <部署时注入>；temperature / maxTokens 对齐大模型标准配置属性
    private val llmModel = modelConfig.model("m_intent")?.name ?: "Qwen/Qwen2.5-14B-Instruct"
    private val llmEndpoint = modelConfig.model("m_intent")?.endpoint ?: ""
    private val llmApiKey = modelConfig.model("m_intent")?.apiKey ?: ""
    private val llmTemperature = modelConfig.model("m_intent")?.temperature ?: 0.2
    private val llmMaxTokens = modelConfig.model("m_intent")?.maxTokens ?: 1024

    /** 云端慢道 M-intent：真实流式调用；未配置端点 / 异常 → 返回空串回退规则分句（离线可跑） */
    override suspend fun chat(system: String, user: String): String {
        Log.d(TAG, "调用大模型 model=$llmModel endpoint=$llmEndpoint user=${user.take(24)}")
        if (llmEndpoint.isBlank()) return ""
        return try {
            StreamingChatClient.chatStream(
                endpoint = llmEndpoint,
                apiKey = llmApiKey,
                model = llmModel,
                system = system,
                user = user,
                temperature = llmTemperature,
                maxTokens = llmMaxTokens,
                onDelta = onDelta,
            )
        } catch (e: Throwable) {
            Log.w(TAG, "大模型流式调用失败，回退规则分句", e)
            ""
        }
    }

    override suspend fun inferLocal(input: String): IntentBlock {
        // 模拟端侧 SLM（QNN）：关键词 → 候选域 + 置信度（真实部署替换为 edgeModel 的 QNN 1.5B 推理）
        Log.d(TAG, "inferLocal($edgeModel) input=${input.take(24)}")
        val domain = when {
            input.contains("空调") || input.contains("冷") || input.contains("热") ||
                input.contains("闷") || input.contains("凉快") || input.contains("暖和") -> "climate"
            input.contains("窗") -> "window"
            input.contains("音乐") || input.contains("歌") || input.contains("播放") -> "media"
            input.contains("导航") || input.contains("回家") || input.contains("去") -> "navigation"
            else -> null
        }
        return if (domain == null) {
            IntentBlock(status = Route.FALLBACK, reasonCode = "NO_MATCH", intents = emptyList())
        } else {
            IntentBlock(status = Route.EXECUTE, reasonCode = "OK", intents = listOf(
                IntentCandidate(seq = 1, confidence = 0.92, routeHint = domain, slots = mapOf("action" to "open")),
            ))
        }
    }

    companion object {
        private const val TAG = "QnnGateway"
    }
}

/** 一条已完成指令的历史存档：摘要 + 该条完整数据流（供回放） */
private data class HistoryEntry(
    val utterance: String,
    val route: String,
    val reply: String,
    val events: List<TraceEvent>,
)

class MainActivity : Activity() {

    // 模型配置：从 assets/model-config.json 加载（PRD §2.11 模型表 + 慢道阈值）；缺失/异常回退默认
    private val modelConfig by lazy { loadModelConfig() }

    // 大模型流式输出的完整累积文本（本次调用），供实时显示 + 复制数据流报告
    private val streamBuffer = StringBuilder()

    // 大模型流式输出回调：每次收到增量 token 追加到 streamBuffer（供复制数据流报告全文）
    private val onLlmDelta: (String) -> Unit = { delta -> streamBuffer.append(delta) }

    // 全局单例装配：注入端侧模型网关（含流式回调）+ 模型配置；App 进程内只装配一次
    private val assembly by lazy {
        Assembly(modelGateway = QnnModelGateway(modelConfig, onLlmDelta), modelConfig = modelConfig)
    }

    // 最近一次调用的关键信息（供复制数据流报告）
    private var lastUtterance: String = ""
    private var lastRoute: String = ""
    private var lastReply: String = ""
    private var lastEvents: List<TraceEvent> = emptyList()

    /** 从 assets 读 model-config.json 并解析；失败回退默认配置（保证离线可跑） */
    private fun loadModelConfig(): ModelConfig {
        return try {
            val text = assets.open("model-config.json").bufferedReader().use { it.readText() }
            ModelConfigLoader.fromString(text)
        } catch (e: Throwable) {
            Log.w(TAG, "加载 model-config.json 失败，使用默认模型配置", e)
            ModelConfig()
        }
    }

    // 本地 ASR 网关：讯飞 SparkChain 在线语音听写（识别任意语音内容；凭据见下方 IAT_* 常量）
    // 注意：必须 by lazy —— 构造期拿 applicationContext 会 NPE（Activity 尚未 attach），延迟到首次调用时再构造。
    private val asrGateway by lazy {
        SparkChainAsrGateway(
            appContext = applicationContext,
            appId = IAT_APP_ID,
            apiKey = IAT_API_KEY,
            apiSecret = IAT_API_SECRET,
        )
    }

    private lateinit var input: EditText
    private lateinit var output: TextView
    private lateinit var traceView: TraceView
    private lateinit var historyContainer: LinearLayout
    private lateinit var currentLabel: TextView

    // 历史存档（新→旧），供点击回放
    private val history = mutableListOf<HistoryEntry>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        input = EditText(this).apply { hint = "输入指令，如：打开空调 / 车里有点闷" }
        val send = Button(this).apply { text = "发送指令" }
        val mic = Button(this).apply { text = "语音输入" }
        // 紧凑小按钮：复制数据流（短标签 + 小字号 + 去默认最小宽高）
        val copy = Button(this).apply {
            text = "复制数据流"
            textSize = 12f
            minHeight = 0
            minWidth = 0
            setPadding(dp(8), dp(4), dp(8), dp(4))
        }
        output = TextView(this).apply { text = "就绪" }
        currentLabel = TextView(this).apply { text = "— 当前数据流 —" }
        historyContainer = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // 无死角 trace 流程图面板：由 snapshot 列表确定性渲染当前消息的完整数据流
        traceView = TraceView(this)

        send.setOnClickListener { onSend(input.text.toString()) }
        mic.setOnClickListener { onMic() }
        copy.setOnClickListener { copyCallReport() }

        setContentView(
            LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(24, 24, 24, 24)
                addView(input)
                // 按钮并排，压缩纵向空间，保证下方左右两块面板有足够高度
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL
                        addView(send, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
                        addView(mic, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 2f))
                        addView(copy, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
                    },
                )
                addView(output)
                // 左右布局：左 = 当前数据流（主），右 = 历史记录（侧栏，点击回放）
                addView(
                    LinearLayout(this@MainActivity).apply {
                        orientation = LinearLayout.HORIZONTAL

                        // 左：当前数据流
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(currentLabel)
                                addView(traceView, LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f))
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 2f),
                        )

                        // 分隔线
                        addView(
                            View(this@MainActivity).apply { setBackgroundColor(Color.rgb(0xDD, 0xDD, 0xDD)) },
                            LinearLayout.LayoutParams(dp(1), LinearLayout.LayoutParams.MATCH_PARENT),
                        )

                        // 右：历史记录
                        addView(
                            LinearLayout(this@MainActivity).apply {
                                orientation = LinearLayout.VERTICAL
                                addView(sectionLabel("历史记录（点击回放）"))
                                addView(
                                    ScrollView(this@MainActivity).apply {
                                        addView(historyContainer, LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT,
                                        ))
                                    },
                                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                                )
                            },
                            LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1f),
                        )
                    },
                    LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 0, 1f),
                )
            }
        )
    }

    private fun onMic() {
        // 录音属运行时危险权限（API 23+），先申请再识别
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startRecognize()
        } else {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), RC_RECORD_AUDIO)
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == RC_RECORD_AUDIO && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) {
            startRecognize()
        } else {
            val msg = if (shouldShowRequestPermissionRationale(Manifest.permission.RECORD_AUDIO)) {
                "未授予录音权限，无法语音输入"
            } else {
                "录音权限被永久拒绝，请在 设置→应用→权限 里手动开启麦克风"
            }
            output.text = msg
            Toast.makeText(this, msg, Toast.LENGTH_LONG).show()
        }
    }

    private fun startRecognize() {
        // 立即给反馈，避免长耗时识别期间界面像“卡住”
        output.text = "正在聆听，请说话…"
        // 关键：suspend 走 Default 调度器，禁止主线程 runBlocking（会 ANR）
        CoroutineScope(Dispatchers.Default).launch {
            val text = asrGateway.recognize()
            Log.d(TAG, "语音识别结果: text=$text")
            if (text.isNullOrBlank()) {
                runOnUiThread {
                    val msg = asrGateway.lastError ?: "未识别到内容"
                    output.text = "语音：$msg"
                    Toast.makeText(this@MainActivity, msg, Toast.LENGTH_LONG).show()
                }
            } else {
                runOnUiThread { input.setText(text) }
                onSend(text)
            }
        }
    }

    private fun onSend(utterance: String) {
        if (utterance.isBlank()) return
        // 清空本次大模型流式输出缓存（新指令从头累积）
        streamBuffer.setLength(0)
        // 关键：suspend 走 Default 调度器，禁止主线程 runBlocking（会 ANR）
        CoroutineScope(Dispatchers.Default).launch {
            val beforeSeq = assembly.trace.snapshot().lastOrNull()?.seq ?: 0L // seq 单调递增，作水位（对历史驱逐稳健）
            val msg = IntentMessage(
                header = Header(messageId = "m-${utterance.hashCode()}", traceId = "trace-1", sessionId = "session-1"),
                input = Input(utterance = utterance),
            )
            val out = assembly.master.run(msg)
            val events = assembly.trace.snapshot()
                .filter { it.seq > beforeSeq }                        // 本次 run 产出的完整数据流（确定性）
                .mapIndexed { i, e -> e.copy(seq = (i + 1).toLong()) } // 每轮数据流标号从 #1 起，不延续全局 seq
            val route = out.arbitration?.route?.name ?: "-"
            val reply = out.result?.reply ?: "-"
            runOnUiThread {
                output.text = "$route | $reply"
                currentLabel.text = "— 当前数据流（「$utterance」）—"
                traceView.render(events)
                // 记录最近一次调用（供 .md 导出）
                lastUtterance = utterance
                lastRoute = route
                lastReply = reply
                lastEvents = events
                // 存档进历史（新→旧），供点击回放
                history.add(0, HistoryEntry(utterance, route, reply, events))
                while (history.size > MAX_HISTORY) history.removeAt(history.size - 1)
                refreshHistory()
            }
        }
    }

    /** 点击历史条目：回放该条完整数据流到当前面板 */
    private fun replay(entry: HistoryEntry) {
        output.text = "回放：${entry.route} | ${entry.reply}"
        currentLabel.text = "— 回放（「${entry.utterance}」）—"
        traceView.render(entry.events)
    }

    /** 重建历史列表（每行可点击回放） */
    private fun refreshHistory() {
        historyContainer.removeAllViews()
        if (history.isEmpty()) {
            historyContainer.addView(TextView(this).apply { text = "（暂无）"; textSize = 12f })
            return
        }
        history.forEach { entry ->
            historyContainer.addView(TextView(this).apply {
                text = "「${entry.utterance}」 → ${entry.route} | ${entry.reply}"
                textSize = 12f
                setPadding(0, dp(6), 0, dp(6))
                setOnClickListener { replay(entry) }
            })
        }
    }

    /** 生成「整个调用过程」.md 报告（模型信息 + 请求 + 流式输出 + 主控结果 + 全链路 trace） */
    private fun buildCallReportMarkdown(): String {
        val mIntent = modelConfig.model("m_intent")
        val now = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.CHINA).format(Date())
        val sb = StringBuilder()
        sb.appendLine("# 车机大模型调用过程报告")
        sb.appendLine()
        sb.appendLine("- 生成时间：$now")
        sb.appendLine("- 模型配置版本：${modelConfig.version}")
        sb.appendLine()
        sb.appendLine("## 1. 使用的大模型（调用位置标记）")
        sb.appendLine()
        sb.appendLine("| 项 | 值 |")
        sb.appendLine("|---|---|")
        sb.appendLine("| 组件 | M-intent（意图理解/意图拆分，Prompt A1/A1-split） |")
        sb.appendLine("| 模型名称 name | `${mIntent?.name ?: "-"}` |")
        sb.appendLine("| 模型地址 endpoint | `${mIntent?.endpoint ?: "-"}` |")
        sb.appendLine("| 推理后端 backend | `${mIntent?.backend ?: "-"}`（OpenAI 兼容 SSE） |")
        sb.appendLine("| 部署形态 deploy | `${mIntent?.deploy ?: "-"}` |")
        sb.appendLine("| 采样温度 temperature | `${mIntent?.temperature ?: "-"}` |")
        sb.appendLine("| 最大长度 maxTokens | `${mIntent?.maxTokens ?: "-"}` |")
        sb.appendLine("| 流式 stream | `${mIntent?.stream ?: "-"}` |")
        sb.appendLine()
        sb.appendLine("## 2. 本次请求")
        sb.appendLine()
        sb.appendLine("- 用户输入：`$lastUtterance`")
        sb.appendLine("- 系统提示词（意图拆分 A1-split）：")
        sb.appendLine()
        sb.appendLine("```text")
        sb.appendLine(IntentSplitter.PROMPT_A1_SPLIT)
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("## 3. 大模型流式输出（逐 token）")
        sb.appendLine()
        sb.appendLine("```text")
        sb.appendLine(streamBuffer.toString().ifBlank { "（未触发慢道 / 未返回内容，已回退规则分句）" })
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("## 4. 主控结果")
        sb.appendLine()
        sb.appendLine("- 路由 route：`$lastRoute`")
        sb.appendLine("- 回复 reply：`$lastReply`")
        sb.appendLine()
        sb.appendLine("## 5. 全链路 trace 数据流")
        sb.appendLine()
        for (e in lastEvents) {
            val meta = "${e.source} · ${e.stage.name} · ${e.channel.name} · ${e.status.name}" +
                (if (e.elapsedMs > 0) " · ${e.elapsedMs}ms" else "")
            sb.appendLine("- #${e.seq} `${e.title}` [$meta]")
            if (e.input.isNotBlank()) sb.appendLine("  - 输入：${e.input}")
            if (e.output.isNotBlank()) sb.appendLine("  - 输出：${e.output}")
        }
        sb.appendLine()
        sb.appendLine("## 6. 调用链说明")
        sb.appendLine()
        sb.appendLine("```text")
        sb.appendLine("INPUT → CONTEXT.push → COMPOSE（分句 → 逐子句解析 → 融合）→ MEMORY.recall → ARBITRATE → PLAN → EXECUTE → SafetyGateway → RESULT → MEMORY.remember → CONTEXT.snapshot → BUS")
        sb.appendLine("```")
        sb.appendLine()
        sb.appendLine("大模型节点：IntentComposer.splitAndCompose 多子句时调用 `IModelGateway.chat(PROMPT_A1_SPLIT, u)` → QnnModelGateway → StreamingChatClient（SSE stream=true）→ 逐 token 回调 onDelta → 实时刷新 UI。")
        return sb.toString()
    }

    /** 复制 .md 到剪贴板 */
    private fun copyToClipboard(text: String) {
        val cm = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        cm.setPrimaryClip(ClipData.newPlainText("调用过程.md", text))
    }

    /** 复制数据流：把整个调用过程 .md 写入剪贴板 */
    private fun copyCallReport() {
        copyToClipboard(buildCallReportMarkdown())
        Toast.makeText(this, "已复制数据流到剪贴板", Toast.LENGTH_SHORT).show()
    }

    private fun sectionLabel(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 12f
        setPadding(0, dp(8), 0, dp(4))
    }

    private fun dp(v: Int): Int = (resources.displayMetrics.density * v).toInt()

    companion object {
        private const val TAG = "Carpilot"
        private const val RC_RECORD_AUDIO = 1001
        private const val MAX_HISTORY = 50

        // 讯飞 SparkChain 在线语音听写凭据：控制台 console.xfyun.cn/services/iat「语音听写」栏的三元组
        // （与 AIKit 离线命令词凭据不同，需单独开通「语音听写」服务后填写；听写需联网）
        private const val IAT_APP_ID = ""
        private const val IAT_API_KEY = ""
        private const val IAT_API_SECRET = ""
    }
}
