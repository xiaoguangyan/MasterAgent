/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Android 车机宿主工程设置 —— 独立 Android 工程，依赖本地 Maven 发布的 master-agent 四工程。
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
        mavenLocal()      // 本地发布的 master-agent 四工程（先跑 ../publish-local.sh）
        google()
        mavenCentral()
    }
}

rootProject.name = "android-host"
include(":app")
