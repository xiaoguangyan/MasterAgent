/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：Android 车机宿主 :app —— 依赖本地 Maven 发布的 master-agent + 契约，装配并跑意图指令。
 */

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.xiaoguang.carpilot"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.xiaoguang.carpilot"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.2.0"

        // 讯飞 Aikit 离线命令词 SDK 仅带 arm64-v8a / armeabi-v7a 的 .so，只打 arm64（车机为 64 位）。
        // x86_64 模拟器装不上——请用 arm64 系统镜像的模拟器（Apple Silicon 支持）或车机真机。
        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    // 固定 Java 字节码目标为 17，与 Kotlin jvmTarget 一致，避免 JDK 25 校验不一致
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

}

// 固定 Kotlin 字节码目标为 JVM 17（与 compileOptions 对齐）
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 本地 Maven 引入的 master-agent 四工程（先跑 ../publish-local.sh）
    implementation("com.xiaoguang.masteragent:master-agent:1.2.0")
    // 契约：直接用 IntentMessage / IModelGateway 等 SPI 类型（master-agent 以 api 透传，这里显式声明更清晰）
    implementation("com.xiaoguang.masteragent:contract:1.2.0")
    // 协程（Android 调度器，编译期需显式引入）
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // 讯飞 Aikit 离线命令词 SDK（libs/AIKit.aar，arm64 .so 内含）
    implementation(files("libs/AIKit.aar"))

    // 讯飞 SparkChain SDK（在线语音听写 ASR，libs/SparkChain.aar + libs/Codec.aar，arm64 .so 内含）
    implementation(files("libs/SparkChain.aar"))
    implementation(files("libs/Codec.aar"))
}
