/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：上下文工程 —— L0-L4 分层 + 环形滑动窗口 + 实时车态（StateFlow）。
 */

plugins {
    kotlin("jvm") version "2.1.20"
    `java-library`
    `maven-publish`
}

group = "com.xiaoguang.masteragent"
version = "1.2.0"

// 固定字节码目标为 JVM 17：避免用 JDK 25（Android Studio 内置 JBR）跑 Gradle 时，
// compileJava(25) 与 compileKotlin(23) 目标不一致导致构建失败。
kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

dependencies {
    api("com.xiaoguang.masteragent:contract:1.2.0")
    // api：RealtimeStore 公开 API 暴露 StateFlow，需以 compile 作用域发布给消费方
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.2")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher:1.10.2")
}

tasks.test {
    useJUnitPlatform()
}

// 发布到本地 Maven 仓库（~/.m2），供 Android 车机工程依赖引入
publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
}
