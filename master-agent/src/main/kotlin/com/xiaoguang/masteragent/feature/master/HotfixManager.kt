/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：HotfixManager —— 热修复（迭代 2）：IModelGateway 热修复 SPI 的内存实现。
 *       apply 校验签名 → 应用；rollback 回退到已应用版本；版本链物理保留。
 *       真实部署替换为 OTA 差分包 + 签名校验 + 原子切换。
 */

package com.xiaoguang.masteragent.feature.master

import com.xiaoguang.masteragent.core.bus.ApplyResult
import com.xiaoguang.masteragent.core.bus.HotfixPackage
import com.xiaoguang.masteragent.core.bus.IHotfixManager

class HotfixManager : IHotfixManager {

    /** 版本链（version → 包，物理保留供回退） */
    private val applied = LinkedHashMap<String, HotfixPackage>()

    private var current: HotfixPackage? = null

    override suspend fun apply(pkg: HotfixPackage): ApplyResult {
        if (pkg.signature.isBlank()) {
            return ApplyResult(ok = false, version = pkg.version, message = "签名缺失，拒绝应用")
        }
        applied[pkg.version] = pkg
        current = pkg
        return ApplyResult(ok = true, version = pkg.version, message = "热修复已应用 v${pkg.version}")
    }

    override suspend fun rollback(version: String): ApplyResult {
        val target = applied[version]
            ?: return ApplyResult(ok = false, version = version, message = "版本不存在: $version")
        current = target
        return ApplyResult(ok = true, version = version, message = "已回滚至 v$version")
    }

    /** 当前生效版本 */
    fun currentVersion(): String? = current?.version
}
