/*
 * 版权所有：xiaoguang.yan（8518960@qq.com）
 * 描述：记忆工程 —— 工作 / 情景 / 长期记忆 + 四态裁定 + 敏感拦截 + 一键清除。
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
