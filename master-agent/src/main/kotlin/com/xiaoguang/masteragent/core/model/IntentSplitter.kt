/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：IntentSplitter —— 意图拆分：把一句话拆为 0..N 个原子意图（N<=3）。
 *       规则分句为兜底（标点 + 保守连词），大模型拆分（M-intent，Prompt A1-split）为慢道主路径；
 *       慢道返回结构化 JSON 时采用，否则回退规则分句。只拆已注册域，未注册内容标 INTENT_UNKNOWN。
 */

package com.xiaoguang.masteragent.core.model

import com.xiaoguang.masteragent.core.bus.IntentCandidate
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject

object IntentSplitter {

    /** 已注册意图域（PRD §5.2）：未注册内容不拆，标 INTENT_UNKNOWN */
    val REGISTERED_DOMAINS = setOf("climate", "window", "seat", "media", "navigation", "energy")

    /**
     * 意图拆分提示词（PRD §19.1 A1-split）：一句话拆为 0..N 个原子意图（N<=3），
     * 只拆已注册意图，顺序依赖用 depends_on 表达，多意图按执行顺序给 seq，仅输出 JSON。
     */
    const val PROMPT_A1_SPLIT =
        "你是整车智能中枢的「意图拆分器」。把用户的一句话拆为 0..N 个原子意图（N<=3）。" +
            "只拆已注册意图：climate(空调/温度)、window(车窗)、seat(座椅)、media(媒体)、navigation(导航)、energy(能源)。" +
            "未注册内容（如「点杯咖啡」等车外服务）不要拆成意图。若意图间存在顺序依赖，用 depends_on 表达前序 seq；" +
            "多意图按执行顺序给 seq。仅输出 JSON，格式：" +
            "{\"intents\":[{\"seq\":1,\"intent_id\":\"climate\",\"slots\":{},\"confidence\":0.9,\"depends_on\":[]}],\"status\":\"EXECUTE\"}"

    /** 标点分隔（中英混排） */
    private val SEGMENT_SPLIT_REGEX = Regex("[，。！？；、,!?;]+")

    /**
     * 保守连词分隔：仅拆明确表并列/顺承的连词。
     * 刻意不含「还有/也/再」——「还有多少续航」「再冷一点」会误拆。
     */
    private val CONJUNCTION_REGEX = Regex("然后|接着|同时|并且|顺便|之后")

    /** 规则分句：标点 → 连词 两级切分，去空 trim；无分隔符时返回原句单段 */
    fun splitSegments(utterance: String): List<String> {
        val out = mutableListOf<String>()
        for (part in utterance.split(SEGMENT_SPLIT_REGEX)) {
            for (sub in part.split(CONJUNCTION_REGEX)) {
                val s = sub.trim()
                if (s.isNotEmpty()) out.add(s)
            }
        }
        return out.ifEmpty { listOf(utterance.trim()) }
    }

    /**
     * 解析大模型拆分的结构化 JSON（A1-split 输出）。
     * 解析失败 / 空 / 无 intents 返回 null（由调用方回退规则分句）；intent_id 未注册 → routeHint=null（跳过）。
     */
    fun parseLlmIntents(jsonText: String): List<IntentCandidate>? {
        if (jsonText.isBlank()) return null
        return try {
            val arr = Json.parseToJsonElement(jsonText.trim()).jsonObject["intents"]?.jsonArray ?: return null
            val list = arr.mapNotNull { el ->
                val o = el as? JsonObject ?: return@mapNotNull null
                val id = (o["intent_id"] as? JsonPrimitive)?.contentOrNull
                if (id.isNullOrBlank()) return@mapNotNull null
                val domain = id.takeIf { it in REGISTERED_DOMAINS }
                val seq = (o["seq"] as? JsonPrimitive)?.intOrNull ?: 1
                val confidence = (o["confidence"] as? JsonPrimitive)?.doubleOrNull ?: 0.0
                val slots = (o["slots"] as? JsonObject)
                    ?.mapValues { (_, v) -> (v as? JsonPrimitive)?.contentOrNull ?: "" }
                    ?: emptyMap()
                val dependsOn = (o["depends_on"] as? kotlinx.serialization.json.JsonArray)
                    ?.mapNotNull { (it as? JsonPrimitive)?.intOrNull } ?: emptyList()
                IntentCandidate(seq = seq, confidence = confidence, slots = slots, routeHint = domain, dependsOn = dependsOn)
            }
            list.ifEmpty { null }
        } catch (_: Throwable) {
            null
        }
    }
}
