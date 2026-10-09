/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：FastPathParser —— 规则快道意图解析：关键词 → 域 + 槽位 + 基础置信度。
 *       端侧本地闭环，不依赖任何大模型 / 开源框架；命中即出域，未命中 domain=null 走兜底。
 */

package com.xiaoguang.masteragent.core.model

/** 解析结果：命中域 + 基础置信度 + 归一化槽位 */
data class ParsedIntent(
    val domain: String?,
    val baseConfidence: Double,
    val slots: Map<String, String> = emptyMap(),
)

class FastPathParser {

    /** 关键词规则表：每条规则 { 域, 关键词集合, 基础置信度 } */
    private val rules = listOf(
        Rule("climate", setOf("空调", "冷", "热", "温度", "暖风", "制冷", "制热"), 0.90),
        // 低置信度模糊表达：命中仅得 0.70（< 0.85 慢道阈值），用于演示「快道不足 → 触发慢道大模型补强」
        Rule("climate", setOf("闷", "凉快", "暖和"), 0.70),
        Rule("window", setOf("车窗", "窗户", "开窗", "关窗"), 0.90),
        Rule("seat", setOf("座椅", "副驾", "主驾", "按摩", "靠背", "加热"), 0.90),
        Rule("media", setOf("音乐", "播放", "歌", "媒体", "音量", "电台"), 0.85),
        Rule("navigation", setOf("导航", "去", "回家", "公司", "路线", "地址"), 0.85),
        // 能源生态域（迭代 2）：续航 / 能耗 / 充电 / 节能
        Rule("energy", setOf("续航", "能耗", "电量", "充电", "节能", "里程"), 0.85),
    )

    /** 解析：返回首个命中规则的域；未命中返回 domain=null、置信度 0 */
    fun parse(utterance: String): ParsedIntent {
        val rule = rules.firstOrNull { r -> r.keywords.any { utterance.contains(it) } } ?: return ParsedIntent(null, 0.0)
        val slots = extractSlots(rule.domain, utterance)
        return ParsedIntent(domain = rule.domain, baseConfidence = rule.baseConfidence, slots = slots)
    }

    /** 槽位抽取：从文本中提取动作 / 温度等简单槽位（MVP 极简） */
    private fun extractSlots(domain: String, utterance: String): Map<String, String> {
        val slots = mutableMapOf<String, String>()
        if (utterance.contains("关")) slots["action"] = "close" else slots["action"] = "open"
        if (domain == "climate") {
            "\\d+".toRegex().find(utterance)?.let { slots["temp"] = it.value }
        }
        return slots
    }

    private data class Rule(val domain: String, val keywords: Set<String>, val baseConfidence: Double)
}
