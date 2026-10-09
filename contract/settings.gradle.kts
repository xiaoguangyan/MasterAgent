/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：契约工程（共享 SPI + 数据结构）设置 —— 独立 Gradle 工程，可单独构建。
 *       它是记忆 / 上下文 / Master Agent 之间的“必要调用”契约层。
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
        // 共享离线镜像：workspace 根 offline-m2（JUnit 等测试依赖，保证离线可复现构建）
        maven { url = uri("${rootDir}/../offline-m2") }
        google()
        mavenCentral()
    }
}

rootProject.name = "contract"
