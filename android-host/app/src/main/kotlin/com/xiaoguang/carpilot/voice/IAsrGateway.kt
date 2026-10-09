/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：本地语音识别（ASR）网关 SPI —— 车机端侧语音输入抽象，与 IModelGateway 同风格。
 */

package com.xiaoguang.carpilot.voice

/**
 * 本地 ASR 网关：录音 + 端侧识别，产出中文文本 utterance。
 * 实现者：讯飞 MSC / 百度离线 / Qualcomm QNN 语音，任选其一。
 * 产出文本直接喂给 Assembly.master.run(msg)。
 */
interface IAsrGateway {
    /** 一次语音识别（含录音），返回识别文本；超时 / 失败 / 取消返回 null */
    suspend fun recognize(timeoutMs: Long = 15_000): String?
}
