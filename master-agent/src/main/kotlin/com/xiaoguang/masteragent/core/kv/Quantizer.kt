/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：量化器 —— KV8 / KV4 分级量化（P0/P1 用 KV8 保质量，P3 用 KV4 省显存）。
 *       MVP 用字节数折算占位；真实部署替换为 Qualcomm QNN 量化策略。
 */

package com.xiaoguang.masteragent.core.kv

import com.xiaoguang.masteragent.core.bus.KvBlock

enum class QuantLevel { KV8, KV4 }

class Quantizer {
    fun quantize(block: KvBlock, level: QuantLevel): KvBlock = block.copy(
        sizeBytes = if (level == QuantLevel.KV8) block.sizeBytes else block.sizeBytes / 2,
    )
}
