/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ErrorCode —— Master 统一错误码 + 域 Agent 缺失异常。
 */

package com.xiaoguang.masteragent.feature.master

/** Master 统一错误码 */
enum class ErrorCode(val code: Int, val msg: String) {
    OK(0, "成功"),
    NO_DOMAIN_AGENT(1002, "无匹配域 Agent"),
    SAFETY_BLOCKED(1003, "安全网关拦截"),
    EXECUTION_FAILED(1004, "执行失败"),
}

/** 无匹配域 Agent 异常（规划到未注册域时抛出） */
class NoDomainAgentException(val domain: String) : IllegalStateException("无匹配域 Agent: $domain")
