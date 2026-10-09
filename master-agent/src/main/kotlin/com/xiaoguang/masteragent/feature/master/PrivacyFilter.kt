/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：PrivacyFilter —— 隐私合规（迭代 2）：沉淀 / 日志前对语音文本做 PII 脱敏与敏感拦截。
 *       敏感关键词命中 → 整条替换为占位；否则掩码手机号 / 身份证号。
 */

package com.xiaoguang.masteragent.feature.master

object PrivacyFilter {

    private val PHONE_RE = Regex("1[3-9]\\d{9}")
    private val ID_RE = Regex("\\d{17}[0-9Xx]")

    /** 敏感关键词（红线） */
    private val SENSITIVE_KEYWORDS = listOf("密码", "身份证", "银行卡", "口令")

    /** 是否命中敏感关键词 */
    fun isSensitive(text: String): Boolean = SENSITIVE_KEYWORDS.any { text.contains(it) }

    /** PII 掩码：手机号 / 身份证号 → *** */
    fun maskPii(text: String): String = text.replace(ID_RE, "***").replace(PHONE_RE, "***")

    /**
     * 隐私清洗（沉淀前调用）：敏感命中整条替换；否则 PII 掩码。
     */
    fun sanitize(text: String): String =
        if (isSensitive(text)) "[敏感内容已过滤]" else maskPii(text)
}
