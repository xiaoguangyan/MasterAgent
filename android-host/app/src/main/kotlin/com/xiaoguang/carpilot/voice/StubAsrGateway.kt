/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：ASR 网关桩实现 —— 未接入厂商 SDK 前，语音按钮走此桩，返回 null（不打断文本演示）。
 */

package com.xiaoguang.carpilot.voice

/**
 * 桩实现：未接讯飞 SDK 前使用。
 * 已接入真实 SDK，MainActivity 现用 AikitEsrGateway（见同包），本桩留作降级/参考。
 */
class StubAsrGateway : IAsrGateway {
    override suspend fun recognize(timeoutMs: Long): String? = null
}
