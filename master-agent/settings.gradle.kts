/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Master Agent 工程设置 —— 独立 Gradle 工程，可单独构建。
 *       组合引用契约 / 上下文 / 记忆三独立工程源码（必要的调用），各工程仍可独立构建。
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

rootProject.name = "master-agent"

// 必要的调用：主控依赖契约 / 上下文 / 记忆三独立工程源码（组合构建）
includeBuild("../contract")
includeBuild("../context")
includeBuild("../memory")
