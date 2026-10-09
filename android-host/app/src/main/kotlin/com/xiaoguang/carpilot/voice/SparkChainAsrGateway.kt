/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：讯飞 SparkChain 在线语音听写（ASR）适配器 —— 云端 iat 听写，识别任意语音内容。
 *       产出文本喂给 Assembly.master.run(msg)。需联网 + iat 三元组（非 AIKit 离线命令词凭据）。
 * 注意：录音 16k/16bit/单声道 PCM；静音 1.2s 自动结束一次识别；仅 arm64-v8a / armeabi-v7a。
 *       每次识别使用独立的会话状态（deferred/回调/active 标志），非阻塞读；AudioRecord 实例进程内
 *       复用（只 start/stop 不 release），避免「多轮语音后重建实例采到静音」与阻塞读卡死录音线程。
 */

package com.xiaoguang.carpilot.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.util.Log
import com.iflytek.sparkchain.core.SparkChain
import com.iflytek.sparkchain.core.SparkChainConfig
import com.iflytek.sparkchain.core.asr.ASR
import com.iflytek.sparkchain.core.asr.AsrCallbacks
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.abs

/**
 * 讯飞 SparkChain 在线语音听写（ASR）网关。
 *
 * @param appContext 应用上下文（初始化 SDK 用）
 * @param appId      讯飞控制台「语音听写 iat」服务的 appid（三元组之一）
 * @param apiKey     讯飞 iat 服务 apiKey
 * @param apiSecret  讯飞 iat 服务 apiSecret
 */
class SparkChainAsrGateway(
    private val appContext: Context,
    private val appId: String,
    private val apiKey: String,
    private val apiSecret: String,
) : IAsrGateway {

    companion object {
        private const val TAG = "SparkAsr"
        private const val SAMPLE_RATE = 16000
        private const val BUFFER_SIZE = 1280          // 40ms 一帧（16k * 2 字节 * 0.04s）
        private const val SILENCE_STOP_MS = 1200       // 连续静音超此值认为说完，自动结束
        private const val SILENCE_PEAK = 500           // 16bit PCM 峰值低于此值视为静音
        private const val MAX_RECORD_MS = 8000         // 录制时长上限：防环境噪声导致永不静音，超时强制收尾等最终结果
        private const val SDK_BUDGET_MS = 8_000        // SDK 初始化 + start 的额外超时预算（联网鉴权可能较慢）
    }

    /** 最近一次识别失败的原因（recognize 返回 null 时供 UI 展示） */
    @Volatile var lastError: String? = null

    /** 本次会话是否检测到有效语音（供结束时区分「没说话」vs「识别失败/超时」） */
    @Volatile private var heardSpeech = false

    /** 本次会话录音的最大 PCM 峰值（供「无语音」提示展示，区分「麦克风全静音」vs「音量过低」） */
    @Volatile private var maxPeak = 0

    // SDK 初始化（进程内一次）
    private val initLock = Any()
    @Volatile private var sdkInited = false

    // 单飞锁：上一次识别未结束时直接拒绝，避免重叠会话
    private val busy = AtomicBoolean(false)

    // 进程内复用的唯一 AudioRecord：模拟器（及部分 HAL）对「每次识别 release 后重建 AudioRecord」支持不稳定，
    // 多轮语音后新建实例会采到静音 →「未检测到有效语音」。故只建一次，start/stop 复用，不 release。
    private val recLock = Any()
    private var sharedRec: AudioRecord? = null

    /** 从结果提取文本：优先 bestMatchText（dwa 修正后整句），为空则回退拼接 transcriptions[].segments[].text */
    private fun extractText(r: ASR.ASRResult): String {
        val best = r.bestMatchText?.trim().orEmpty()
        if (best.isNotEmpty()) return best
        val sb = StringBuilder()
        r.transcriptions?.forEach { t ->
            t.segments?.forEach { s -> s.text?.let { sb.append(it) } }
        }
        return sb.toString().trim()
    }

    override suspend fun recognize(timeoutMs: Long): String? {
        // 单飞：上一次识别未结束时直接拒绝
        if (!busy.compareAndSet(false, true)) {
            lastError = "识别进行中，请稍候"
            return null
        }
        return try {
            // 总超时把 SDK 初始化（联网鉴权）+ start + 听写一并包住：
            // 否则 init/start 阻塞（无网络/凭据失效）会让界面“卡住”。
            withTimeoutOrNull(timeoutMs + SDK_BUDGET_MS) { recognizeInternal(timeoutMs) }
                ?: run { if (lastError == null) lastError = "识别超时（请检查网络或 iat 服务）"; null }
        } finally {
            busy.set(false)
        }
    }

    private suspend fun recognizeInternal(timeoutMs: Long): String? = withContext(Dispatchers.Default) {
        lastError = null
        heardSpeech = false
        maxPeak = 0

        if (!ensureSdkInit()) {
            lastError = "SDK 初始化失败（检查 iat 三元组 appId/apiKey/apiSecret，且已在控制台开通语音听写）"
            return@withContext null
        }

        // 每次识别新建独立会话状态：deferred + 回调 + active 标志全部局部化，
        // 杜绝上一会话残留回调把本次会话的 deferred 提前完成（二次识别失效的根因之一）。
        val active = AtomicBoolean(true)
        val deferred = CompletableDeferred<String?>()

        val cb = object : AsrCallbacks {
            override fun onResult(asrResult: ASR.ASRResult?, o: Any?) {
                asrResult ?: return
                val status = asrResult.status
                val text = extractText(asrResult)
                Log.i(TAG, "onResult status=$status sid=${asrResult.sid} text=$text")
                if (status == 2) {                     // 2 = 最后一块结果（最终文本）
                    active.set(false)
                    if (!deferred.isCompleted) deferred.complete(text)
                }
            }

            override fun onError(asrError: ASR.ASRError?, o: Any?) {
                Log.e(TAG, "onError code=${asrError?.code} msg=${asrError?.errMsg}")
                lastError = "识别出错 code=${asrError?.code} ${asrError?.errMsg ?: ""}".trim()
                active.set(false)
                if (!deferred.isCompleted) deferred.complete(null)
            }

            override fun onBeginOfSpeech() {
                Log.i(TAG, "onBeginOfSpeech：云端检测到语音开始")
            }

            override fun onEndOfSpeech() {
                Log.i(TAG, "onEndOfSpeech：云端检测到语音结束")
            }
        }

        val asr = ASR()
        asr.registerCallbacks(cb)
        asr.language("zh_cn")     // 语种：中文
        asr.domain("iat")         // 应用领域：日常用语
        asr.accent("mandarin")    // 方言：普通话
        asr.vinfo(true)           // 返回子句起止端点
        asr.dwa("wpgs")           // 中文动态修正，返回完整修正结果

        val ret = asr.start("sess-${System.currentTimeMillis()}")
        if (ret != 0) {
            lastError = "识别开启失败 code=$ret"
            return@withContext null
        }

        val recThread = Thread({ recordLoop(asr, deferred, active) }, "spark-asr-record").apply { isDaemon = true; start() }

        val result = try {
            withTimeoutOrNull(timeoutMs) { deferred.await() }
        } finally {
            active.set(false)
            try { asr.stop(true) } catch (_: Throwable) {}   // 清理会话（true=立即结束）
            // 等录音线程退出并确认麦克风已交还系统，避免下一轮识别抢不到麦而采到静音
            recThread.join(2000)
        }

        if (result.isNullOrBlank() && lastError == null) {
            // 三种「无结果」分门别类，替换笼统的「未识别到内容」：
            // ① 没检测到语音；② 会话超时（通常网络不通，iat 在线听写需联网）；③ 云端返回空文本
            lastError = when {
                !heardSpeech -> "未检测到有效语音（录音峰值最高 $maxPeak，请检查麦克风 / 模拟器「虚拟麦克风」设置）"
                result == null -> "识别超时（请确认设备已联网：iat 在线听写需网络）"
                else -> "云端未返回有效内容（语音可能不清晰，请重试）"
            }
        }
        result
    }

    // ---- 内部：SDK 初始化 ----

    private fun ensureSdkInit(): Boolean {
        if (sdkInited) return true
        synchronized(initLock) {
            if (sdkInited) return true
            val config = SparkChainConfig.builder()
            config.appID(appId).apiKey(apiKey).apiSecret(apiSecret)
            val ret = SparkChain.getInst().init(appContext, config)
            sdkInited = ret == 0
            if (!sdkInited) Log.e(TAG, "SparkChain init 失败 ret=$ret")
            return sdkInited
        }
    }

    // ---- 内部：录音写入 + 静音检测 ----

    private fun recordLoop(asr: ASR, deferred: CompletableDeferred<String?>, active: AtomicBoolean) {
        // 复用进程内唯一的 AudioRecord：模拟器（及部分 HAL）对「每次识别 release 后重建 AudioRecord」支持不稳定，
        // 多轮语音后新建实例会采到静音 →「未检测到有效语音」。故只建一次，start/stop 复用（见 acquireRecorder）。
        val rec = acquireRecorder()
        if (rec == null) {
            Log.e(TAG, "AudioRecord 初始化失败")
            lastError = "录音设备初始化失败（麦克风未就绪或被占用，请检查权限/占用）"
            active.set(false)
            if (!deferred.isCompleted) deferred.complete(null)
            return
        }
        try {
            rec.startRecording()
        } catch (t: Throwable) {
            Log.e(TAG, "startRecording 失败", t)
            lastError = "录音启动失败（麦克风被占用，请稍候重试）"
            active.set(false)
            if (!deferred.isCompleted) deferred.complete(null)
            return
        }
        val data = ByteArray(BUFFER_SIZE)
        Log.i(TAG, "录音已启动（16k/16bit/单声道），开始说话…")

        var silentMs = 0
        val recordStartMs = System.currentTimeMillis()
        try {
            while (active.get()) {
                // 非阻塞读：模拟器麦克风无数据时，阻塞式 read() 会卡死本线程；改为轮询，
                // active=false 即可随时退出，finally 里 stop 交还录音（实例复用，不 release）。
                val n = rec.read(data, 0, BUFFER_SIZE, AudioRecord.READ_NON_BLOCKING)
                if (n == 0) {
                    Thread.sleep(10)   // 尚无音频帧
                    // 兜底：即使 read 一直无数据，录制达到上限也要退出，避免无限轮询
                    if (System.currentTimeMillis() - recordStartMs >= MAX_RECORD_MS) break
                    continue
                }
                if (n < 0) { Log.w(TAG, "录音读取错误 n=$n"); break }
                val chunkMs = n * 1000 / (SAMPLE_RATE * 2)
                val elapsedMs = System.currentTimeMillis() - recordStartMs

                val peak = peakAmplitude(data, n)
                if (peak > maxPeak) maxPeak = peak
                if (peak >= SILENCE_PEAK) {
                    if (!heardSpeech) Log.i(TAG, "检测到语音（峰值=$peak）")
                    heardSpeech = true
                    silentMs = 0
                } else if (heardSpeech) {
                    silentMs += chunkMs
                }

                val chunk = if (n == BUFFER_SIZE) data else data.copyOf(n)
                if (active.get()) {
                    val wret = asr.write(chunk)
                    if (wret != 0) { Log.e(TAG, "write 失败 ret=$wret"); break }
                }

                // 结束条件：①听到过声音后静音超阈值；②录制达上限（防环境噪声导致永不静音）
                if ((heardSpeech && silentMs >= SILENCE_STOP_MS) || elapsedMs >= MAX_RECORD_MS) {
                    Log.i(TAG, "结束录音：静音=${silentMs}ms 时长=${elapsedMs}ms 峰值=${maxPeak}")
                    break
                }
            }
        } catch (t: Throwable) {
            Log.e(TAG, "录音异常", t)
            active.set(false)
            if (!deferred.isCompleted) deferred.complete(null)
        } finally {
            // 只 stop 不 release：复用实例，避免「每次识别 release 后重建 AudioRecord」导致多轮采到静音
            try { rec.stop() } catch (_: Throwable) {}
            Log.i(TAG, "麦克风已停止：峰值=$maxPeak")
        }

        // 麦克风已 stop（实例仍复用），再通知 SDK 音频结束（stop(false) 可能阻塞等云端最终结果，但已不占麦克风）
        if (active.get()) {
            try {
                val sret = asr.stop(false)
                if (sret != 0) Log.w(TAG, "stop 返回 ret=$sret")
            } catch (_: Throwable) {}
        }
    }

    /** 进程内复用唯一 AudioRecord：首次创建，之后 start/stop 复用，不 release（见 recordLoop 注释） */
    private fun acquireRecorder(): AudioRecord? {
        synchronized(recLock) {
            val existing = sharedRec
            if (existing != null && existing.state == AudioRecord.STATE_INITIALIZED) return existing
            if (existing != null) {
                try { existing.release() } catch (_: Throwable) {}
                sharedRec = null
            }
            val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
            val recBuf = maxOf(minBuf, BUFFER_SIZE)
            val rec = AudioRecord(
                MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, recBuf,
            )
            if (rec.state != AudioRecord.STATE_INITIALIZED) {
                try { rec.release() } catch (_: Throwable) {}
                return null
            }
            sharedRec = rec
            return rec
        }
    }

    /** 16bit PCM 峰值振幅（用于静音检测） */
    private fun peakAmplitude(data: ByteArray, n: Int): Int {
        var peak = 0
        var i = 0
        while (i + 1 < n) {
            val low = data[i].toInt() and 0xFF
            val high = data[i + 1].toInt() and 0xFF
            val sample = ((low or (high shl 8))).toShort().toInt()
            val a = abs(sample)
            if (a > peak) peak = a
            i += 2
        }
        return peak
    }
}
