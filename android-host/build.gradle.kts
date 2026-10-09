/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Android 车机宿主工程根构建 —— 声明 AGP / Kotlin 插件版本（apply false，由 :app 应用）。
 */

plugins {
    id("com.android.application") version "8.7.2" apply false
    id("org.jetbrains.kotlin.android") version "2.1.20" apply false
}
