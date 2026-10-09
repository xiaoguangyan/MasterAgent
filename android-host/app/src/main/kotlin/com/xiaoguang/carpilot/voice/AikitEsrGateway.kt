/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：讯飞 Aikit 离线命令词（ESR）适配器 —— 能力 ID e75f07b62，16k/16bit/单声道 PCM。
 *       命令词由 FSA 语法动态生成，产出文本喂给 Assembly.master.run(msg)。
 * 注意：仅支持 arm64-v8a / armeabi-v7a（x86_64 模拟器不可用，需 arm64 模拟器或车机真机）。
 */

package com.xiaoguang.carpilot.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Build
import android.util.Log
import com.iflytek.aikit.core.AiAudio
import com.iflytek.aikit.core.AiHandle
import com.iflytek.aikit.core.AiHelper
import com.iflytek.aikit.core.AiListener
import com.iflytek.aikit.core.AiRequest
import com.iflytek.aikit.core.AiResponse
import com.iflytek.aikit.core.AiStatus
import com.iflytek.aikit.core.BaseLibrary
import com.iflytek.aikit.core.CoreListener
import com.iflytek.aikit.core.ErrType
import com.iflytek.aikit.core.LogLvl
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.nio.charset.Charset
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 讯飞 Aikit 离线命令词识别（ESR）网关。
 *
 * @param appContext 应用上下文（初始化 SDK 用）
 * @param appId      讯飞开放平台控制台的应用 ID
 * @param apiKey     讯飞 API Key
 * @param apiSecret  讯飞 API Secret
 * @param commands   命令词列表（需与 master-agent 快道规则对齐）
 */
class AikitEsrGateway(
    private val appContext: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
    private val commands: List<String> = DEFAULT_COMMANDS,
) : IAsrGateway {

    companion object {
        private const val TAG = "AikitEsr"
        private const val ABILITY_ID = "e75f07b62"   // 离线中英命令词能力 ID
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 1280          // 40ms 一帧（16k * 2 字节 * 0.04s）
        private const val FSA_INDEX = 0

        // 默认命令词：与 master-agent 快道规则（空调/车窗/座椅/媒体/导航）对齐
        val DEFAULT_COMMANDS = listOf(
            "打开空调", "关闭空调",
            "打开车窗", "关闭车窗",
            "打开座椅加热", "关闭座椅加热",
            "播放音乐", "暂停音乐",
            "打开导航", "关闭导航",
        )
    }

    /** 最近一次识别失败的原因（recognize 返回 null 时供 UI 展示） */
    @Volatile var lastError: String? = null

    // ---- SDK 鉴权状态（首次需联网拉协议，之后离线可用）----
    private val authDeferred = CompletableDeferred<Boolean>()

    @Volatile private var authCode: Int = -1

    private val coreListener = object : CoreListener {
        override fun onAuthStateChange(type: ErrType?, code: Int) {
            Log.i(TAG, "鉴权回调 type=$type code=$code")
            if (type == ErrType.AUTH) {
                authCode = code
                authDeferred.complete(code == 0)
            }
        }
    }

    // ---- ESR 会话状态（单会话串行，MVP 足够）----
    @Volatile private var pendingDeferred: CompletableDeferred<String?>? = null
    @Volatile private var pendingResult: StringBuilder? = null
    private val sessionActive = AtomicBoolean(false)
    // 单飞锁：上一次识别未结束时直接拒绝，避免重叠 start 触发 18310（会话已存在）
    private val busy = AtomicBoolean(false)

    private val esrListener = object : AiListener {
        override fun onResult(handleID: Int, outputData: List<AiResponse>?, usrContext: Any?) {
            outputData ?: return
            for (resp in outputData) {
                if (resp.key.contains("plain")) {              // 每段话最终结果（GBK）
                    val text = String(resp.value, Charset.forName("GBK"))
                    Log.i(TAG, "识别中间结果 plain=$text")
                    pendingResult?.append(text)
                }
                if (resp.status == 2) {                        // 2 = 识别结束
                    Log.i(TAG, "识别结束 status=2，结果=${pendingResult}")
                    sessionActive.set(false)
                    pendingDeferred?.complete(pendingResult?.toString()?.trim())
                }
            }
        }

        override fun onEvent(i: Int, i1: Int, list: List<AiResponse>?, o: Any?) {}

        override fun onError(i: Int, i1: Int, s: String?, o: Any?) {
            Log.e(TAG, "ESR 错误 ability=$i code=$i1 msg=$s")
            sessionActive.set(false)
            pendingDeferred?.complete(null)
        }
    }

    private val workDir: File by lazy { File(appContext.filesDir, "iflytek").apply { mkdirs() } }

    @Volatile private var sdkStarted = false
    @Volatile private var engineReady = false
    @Volatile private var fsaLoaded = false

    override suspend fun recognize(timeoutMs: Long): String? {
        // 单飞：上一次识别未结束时直接拒绝，避免重叠 start 触发 18310（会话已存在）
        if (!busy.compareAndSet(false, true)) {
            lastError = "识别进行中，请稍候"
            return null
        }
        return try {
            recognizeInternal(timeoutMs)
        } finally {
            busy.set(false)
        }
    }

    private suspend fun recognizeInternal(timeoutMs: Long): String? = withContext(Dispatchers.Default) {
        lastError = null

        // 1) ABI 检查：SDK 只有 arm 的 .so，x86_64 模拟器直接不加载，避免 UnsatisfiedLinkError
        if (!isArmSupported()) {
            lastError = "仅支持 arm64 车机（x86_64 模拟器不可用，请用 arm64 模拟器或真机）"
            return@withContext null
        }

        // 2) 初始化 SDK（拷贝资源 + initEntry），并等待鉴权
        ensureInitializedOnce()
        val authed = withTimeoutOrNull(30_000) { authDeferred.await() } ?: false
        if (!authed) {
            lastError = when (authCode) {
                18700 -> "鉴权初始化失败（多为网络不通：模拟器未联网/域名解析失败，联网后重试）"
                else -> "SDK 鉴权失败 code=$authCode（检查 appId/apiKey/apiSecret 是否为该 AIKit 能力专用）"
            }
            return@withContext null
        }

        // 3) 引擎初始化 + 加载命令词 FSA
        if (!ensureEngine()) { lastError = "引擎初始化失败"; return@withContext null }
        if (!ensureFsaLoaded()) { lastError = "命令词资源加载失败"; return@withContext null }

        // 4) 开始会话
        val handle = startSession() ?: run { lastError = "会话启动失败"; return@withContext null }

        val deferred = CompletableDeferred<String?>()
        pendingDeferred = deferred
        pendingResult = StringBuilder()
        sessionActive.set(true)

        val recThread = Thread({ recordLoop(handle) }, "aikit-esr-record").apply { isDaemon = true; start() }

        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            sessionActive.set(false)
            try { AiHelper.getInst().end(handle) } catch (_: Throwable) {}
            pendingDeferred = null
            pendingResult = null
        }

        if (result.isNullOrBlank()) lastError = "未识别到命令词"
        result
    }

    // ---- 内部：初始化 / 引擎 / 资源 ----

    private fun isArmSupported(): Boolean =
        Build.SUPPORTED_ABIS.any { it == "arm64-v8a" || it == "armeabi-v7a" }

    private fun ensureInitializedOnce() {
        if (sdkStarted) return
        synchronized(this) {
            if (sdkStarted) return
            sdkStarted = true
            copyResources()
            AiHelper.getInst().setLogInfo(LogLvl.VERBOSE, 1, File(workDir, "aeeLog.txt").absolutePath)
            AiHelper.getInst().registerListener(coreListener)
            val params = BaseLibrary.Params.builder()
                .appId(appId)
                .apiKey(apiKey)
                .apiSecret(apiSecret)
                .workDir(workDir.absolutePath)
                .build()
            Thread({ AiHelper.getInst().initEntry(appContext, params) }, "aikit-init").start()
        }
    }

    private fun ensureEngine(): Boolean {
        if (engineReady) return true
        synchronized(this) {
            if (engineReady) return true
            AiHelper.getInst().registerListener(ABILITY_ID, esrListener)
            val b = AiRequest.builder()
                .param("decNetType", "fsa")
                .param("punishCoefficient", 0.0)
                .param("wfst_addType", 0)          // 0 中文，1 英文
            val ret = AiHelper.getInst().engineInit(ABILITY_ID, b.build())
            if (ret != 0) { Log.e(TAG, "engineInit 失败 ret=$ret"); return false }
            engineReady = true
            return true
        }
    }

    private fun ensureFsaLoaded(): Boolean {
        if (fsaLoaded) return true
        synchronized(this) {
            if (fsaLoaded) return true
            val fsa = ensureFsaFile() ?: return false
            val b = AiRequest.builder().customText("FSA", fsa.absolutePath, FSA_INDEX)
            val ret = AiHelper.getInst().loadData(ABILITY_ID, b.build())
            if (ret != 0) { Log.e(TAG, "loadData 失败 ret=$ret"); return false }
            fsaLoaded = true
            return true
        }
    }

    private fun startSession(): AiHandle? {
        val ret = AiHelper.getInst().specifyDataSet(ABILITY_ID, "FSA", intArrayOf(FSA_INDEX))
        if (ret != 0) { Log.e(TAG, "specifyDataSet 失败 ret=$ret"); return null }

        val b = AiRequest.builder()
            .param("languageType", 0)          // 0 中文
            .param("vadEndGap", 60)            // 子句分割间隔，中文建议 60
            .param("vadOn", true)              // 打开 VAD（静音自动断句）
            .param("beamThreshold", 20)        // 中文建议 20
            .param("hisGramThreshold", 3000)
            .param("vadLinkOn", false)
            .param("vadSpeechEnd", 80)         // VAD 后端点
            .param("vadResponsetime", 1000)    // VAD 前端点
            .param("postprocOn", false)
            .param("vadEnergyThreshold", 9)
            .param("vadThreshold", 0.1332)

        val handle = AiHelper.getInst().start(ABILITY_ID, b.build(), null) ?: return null
        if (handle.code != 0) { Log.e(TAG, "start 失败 code=${handle.code}"); return null }
        return handle
    }

    // ---- 内部：录音写入 ----

    private fun recordLoop(handle: AiHandle) {
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val recBuf = maxOf(minBuf, BUFFER_SIZE)
        val rec = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf,
        )
        if (rec.state != AudioRecord.STATE_INITIALIZED) {
            Log.e(TAG, "AudioRecord 初始化失败")
            sessionActive.set(false)
            pendingDeferred?.complete(null)
            return
        }
        rec.startRecording()
        val data = ByteArray(BUFFER_SIZE)
        var status = AiStatus.BEGIN
        Log.i(TAG, "录音线程已启动（16k/16bit/单声道），等待说话…")
        var firstChunk = true
        try {
            while (sessionActive.get()) {
                val n = rec.read(data, 0, BUFFER_SIZE)
                if (n <= 0) { Log.w(TAG, "录音读取结束 n=$n"); break }
                // 诊断：首帧打振幅，全 0 说明麦克风未采集到声音（模拟器麦克风未接主机）
                if (firstChunk) {
                    firstChunk = false
                    var peak = 0
                    for (i in 0 until n step 2) {
                        val amp = kotlin.math.abs(((data[i].toInt() and 0xFF)) or ((data[i + 1].toInt() shl 8)))
                        if (amp > peak) peak = amp
                    }
                    Log.i(TAG, "首帧 $n 字节，峰值振幅=$peak（若为 0，麦克风可能未接主机）")
                }
                val chunk = if (n == BUFFER_SIZE) data else data.copyOf(n)
                writeAudio(handle, chunk, status)
                status = AiStatus.CONTINUE
            }
            writeAudio(handle, ByteArray(0), AiStatus.END)   // 尾帧（仅会话仍活跃时写入）
        } catch (t: Throwable) {
            Log.e(TAG, "录音异常", t)
            sessionActive.set(false)
            pendingDeferred?.complete(null)
        } finally {
            try { rec.stop() } catch (_: Throwable) {}
            rec.release()
        }
    }

    private fun writeAudio(handle: AiHandle, data: ByteArray, status: AiStatus) {
        // 会话已结束时跳过写入，防 18305（aeeWriteInternal handle is null）
        if (!sessionActive.get()) return
        val aiAudio = AiAudio.get("audio").data(data).status(status).valid()
        val b = AiRequest.builder().payload(aiAudio)
        val ret = AiHelper.getInst().write(b.build(), handle)
        if (ret == 0) {
            AiHelper.getInst().read(ABILITY_ID, handle)
        } else {
            Log.e(TAG, "write 失败 ret=$ret")
        }
    }

    // ---- 内部：资源与 FSA ----

    /** 拷贝离线识别模型（assets/iflytek/CNENESR → workDir/CNENESR），引擎从 workDir 读取 */
    private fun copyResources() {
        try {
            val dstDir = File(workDir, "CNENESR").apply { mkdirs() }
            for (name in RESOURCE_FILES) {
                val dst = File(dstDir, name)
                if (dst.exists()) continue
                appContext.assets.open("iflytek/CNENESR/$name").use { input ->
                    dst.outputStream().use { input.copyTo(it) }
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "拷贝离线资源失败", t)
        }
    }

    /** 按命令词列表生成 FSA 语法文件（GBK 编码，CRLF 换行） */
    private fun ensureFsaFile(): File? {
        val f = File(workDir, "cn_fsa.txt")
        return try {
            // 注意：讯飞 FSA 解析器要求 CRLF(\r\n) 换行，LF 会报 generate fsa lattice error；
            //       每次重写（覆盖旧文件），避免残留 LF 版本
            val content = buildString {
                append("#FSA 1.0;\r\n")
                append("0\t1\t<esr>\r\n")
                append(";\r\n")
                append("<esr>:").append(commands.joinToString("|")).append(";\r\n")
            }
            f.writeBytes(content.toByteArray(Charset.forName("GBK")))
            f
        } catch (t: Throwable) {
            Log.e(TAG, "写 FSA 失败", t)
            null
        }
    }

    private val RESOURCE_FILES = listOf(
        "e75f07b62_MLP_VAD_CN.bin_1.0.0.0",
        "e75f07b62_MLP_VAD_EN.bin_1.0.0.0",
        "e75f07b62_MLP_XN_CN.bin_1.0.0.0",
        "e75f07b62_MLP_XN_EN.bin_1.0.0.0",
        "e75f07b62_WFST_CN.bin_1.0.0.0",
        "e75f07b62_WFST_EN.bin_1.0.0.0",
    )
}
