/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：FaultInjector —— 故障注入（迭代 2）：可编程注入指定域的执行故障，用于验证
 *       MasterAgent 异常路径的降级兜底（不崩溃、返回 ERROR 兜底、错误 trace 可见）。
 */

package com.xiaoguang.masteragent.feature.master

class FaultInjector {

    /** 注入故障的域集合 */
    private val failures = mutableSetOf<String>()

    fun fail(domain: String) {
        failures.add(domain)
    }

    fun healthy(domain: String) {
        failures.remove(domain)
    }

    fun shouldFail(domain: String): Boolean = domain in failures
}
