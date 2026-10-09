/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：记忆工程设置 —— 独立 Gradle 工程，可单独构建。
 *       仅依赖契约工程（组合构建 includeBuild），代码与上下文 / Master Agent 分离。
 */

pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositories {
        maven { url = uri("${rootDir}/../offline-m2") }
        google()
        mavenCentral()
    }
}

rootProject.name = "memory"

// 必要的调用：记忆依赖共享契约工程源码
includeBuild("../contract")
