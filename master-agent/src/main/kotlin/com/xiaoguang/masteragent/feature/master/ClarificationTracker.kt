/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ClarificationTracker —— 多轮澄清（迭代 2）：仲裁落入 CONFIRM 时挂起待澄清意图，
 *       下一轮按确认/否认词解析，确认则回填挂起域，否认则取消。
 */

package com.xiaoguang.masteragent.feature.master

class ClarificationTracker {

    /** 待澄清意图（单会话单挂起） */
    data class Pending(val domain: String?, val utterance: String)

    /** 用户应答解析结果 */
    enum class Resolution { CONFIRM, DENY, IGNORE }

    private var pending: Pending? = null

    fun pending(): Pending? = pending

    fun setPending(domain: String?, utterance: String) {
        pending = Pending(domain, utterance)
    }

    fun clear() {
        pending = null
    }

    /** 解析用户应答：确认词 → CONFIRM；否认词 → DENY；否则 IGNORE（当作新指令） */
    fun resolve(reply: String): Resolution = when {
        CONFIRM_WORDS.any { reply.contains(it) } -> Resolution.CONFIRM
        DENY_WORDS.any { reply.contains(it) } -> Resolution.DENY
        else -> Resolution.IGNORE
    }

    companion object {
        val CONFIRM_WORDS = setOf("是", "对", "确认", "好的", "可以", "嗯", "行")
        val DENY_WORDS = setOf("不", "取消", "算了", "不是", "别", "错")
    }
}
