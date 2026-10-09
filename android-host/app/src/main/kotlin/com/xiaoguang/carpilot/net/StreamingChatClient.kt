/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：StreamingChatClient —— OpenAI 兼容 /v1/chat/completions 流式客户端（SSE）。
 *       用系统内置 HttpURLConnection 实现，零外部依赖（不引 OkHttp，离线可构建）；
 *       请求体与 SSE 增量解析复用 kotlinx.serialization（契约已 api 透传）。
 *       POST stream=true → 逐行解析 `data: {…}` → 提取 choices[0].delta.content → 回调 onDelta（逐 token 流式）。
 */

package com.xiaoguang.carpilot.net

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

object StreamingChatClient {

    /**
     * 流式对话（OpenAI 兼容 chat/completions，SSE）。
     * @param endpoint    推理端点（如 https://api.siliconflow.cn/v1/chat/completions）
     * @param apiKey      API 密钥（Bearer，部署时注入）
     * @param model       模型名（如 Qwen/Qwen2.5-14B-Instruct）
     * @param system      系统提示词（意图拆分 Prompt A1-split）
     * @param user        用户输入
     * @param temperature 采样温度
     * @param maxTokens   最大生成长度
     * @param onDelta     每次收到增量 token 时回调（逐字流式；在 IO 线程回调，调用方自行切 UI 线程）
     * @return 完整拼接的生成文本（供上层解析结构化结果）
     * @throws IllegalStateException 网络 / 鉴权 / 非 2xx 时抛出（由调用方降级回退规则）
     */
    suspend fun chatStream(
        endpoint: String,
        apiKey: String,
        model: String,
        system: String,
        user: String,
        temperature: Double,
        maxTokens: Int,
        onDelta: (String) -> Unit,
    ): String = withContext(Dispatchers.IO) {
        val body = buildJsonObject {
            put("model", model)
            put("stream", true)
            put("temperature", temperature)
            put("max_tokens", maxTokens)
            put(
                "messages",
                buildJsonArray {
                    add(buildJsonObject { put("role", "system"); put("content", system) })
                    add(buildJsonObject { put("role", "user"); put("content", user) })
                },
            )
        }.toString()

        val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            connectTimeout = 10_000
            readTimeout = 60_000
            doOutput = true
            setRequestProperty("Content-Type", "application/json")
            setRequestProperty("Authorization", "Bearer $apiKey")
            setRequestProperty("Accept", "text/event-stream")
        }

        try {
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            if (code !in 200..299) {
                val err = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: "HTTP $code"
                throw IllegalStateException("流式接口异常 $code: ${err.take(200)}")
            }

            val sb = StringBuilder()
            conn.inputStream.bufferedReader(Charsets.UTF_8).useLines { lines ->
                for (line in lines) {
                    if (!line.startsWith("data:")) continue
                    val payload = line.removePrefix("data:").trim()
                    if (payload == "[DONE]") break
                    val delta = parseDelta(payload)
                    if (delta.isNotEmpty()) {
                        sb.append(delta)
                        onDelta(delta)
                    }
                }
            }
            sb.toString()
        } finally {
            conn.disconnect()
        }
    }

    /** 从 SSE `data: {...}` 提取 choices[0].delta.content；非法/非内容块返回空串 */
    private fun parseDelta(payload: String): String = try {
        val obj = Json.parseToJsonElement(payload).jsonObject
        val choice = obj["choices"]?.jsonArray?.firstOrNull()?.jsonObject ?: return ""
        val delta = choice["delta"]?.jsonObject ?: return ""
        delta["content"]?.jsonPrimitive?.contentOrNull ?: ""
    } catch (_: Throwable) {
        ""
    }
}
