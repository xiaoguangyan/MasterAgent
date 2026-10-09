/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ModelConfigLoader —— 模型配置加载：从 JSON 文本 / JVM classpath 资源加载 ModelConfig。
 *       缺省（文件缺失 / 解析失败）回退 ModelConfig() 默认值，保证测试与离线可跑。
 *       Android 侧从 assets 读字节后经 fromString 解析（资源不随 jar 并入 APK）。
 */

package com.xiaoguang.masteragent.app

import com.xiaoguang.masteragent.core.bus.ModelConfig
import kotlinx.serialization.json.Json

object ModelConfigLoader {

    private val json = Json { ignoreUnknownKeys = true }

    /** 从 JSON 文本解析；空 / 异常回退默认配置 */
    fun fromString(text: String): ModelConfig =
        runCatching { json.decodeFromString<ModelConfig>(text) }.getOrDefault(ModelConfig())

    /** 从 classpath 资源加载（JVM）；资源缺失回退默认配置 */
    fun fromClasspath(resource: String): ModelConfig {
        val text = runCatching {
            ModelConfigLoader::class.java.classLoader
                ?.getResourceAsStream(resource)
                ?.bufferedReader()
                ?.use { it.readText() }
        }.getOrNull()
        return text?.let { fromString(it) } ?: ModelConfig()
    }
}
