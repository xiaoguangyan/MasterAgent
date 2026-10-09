# Android 车机宿主工程（android-host）

> 版权所有：xiaoguang.yan（8518960@qq.com）

演示如何在 Android 车机 App 里引入并调用 Master Agent（端侧整车智能中枢）。

## 技术栈

- AGP 8.7.2，Gradle 8.10.2，Kotlin 2.1.20，JDK 17
- 依赖本地 Maven 发布的 master-agent 四工程（方案 A）

## 接入方式（方案 A：本地 Maven）

master-agent 是纯 Kotlin/JVM 库，四工程各自发布成 JAR，车机 App 以坐标依赖引入：

```bash
# 1) 在仓库根目录先发布四工程到 ~/.m2（按依赖顺序 contract → context → memory → master-agent）
./publish-local.sh

# 2) 打开本工程（android-host/），Android Studio 里 Sync + Run
```

依赖关系（App 只依赖两个坐标，其余由 master-agent 以 api 透传）：

```
com.xiaoguang.masteragent:master-agent:1.0.0
com.xiaoguang.masteragent:contract:1.0.0
org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0
```

## 关键接入点

1. **装配**：`Assembly(modelGateway = ...)` —— 构造函数注入端侧模型网关 SPI（可空，缺省走快道规则）。
2. **注入 SPI**：`QnnModelGateway` 实现 `IModelGateway`，真实车机替换为 Qualcomm Hexagon NPU + QNN 的 1.5B 端侧 SLM。
3. **调用**：`assembly.master.run(msg)` 是 suspend，走 `Dispatchers.Default`，**禁止主线程 runBlocking**。

## IDE 指导（Android Studio）

1.  `../publish-local.sh`（否则 mavenLocal 找不到 master-agent）。
2. `File → Open` 选择 `android-host/` 目录，等 Sync 完成。
3. 若本机未装 compileSdk 35，把 `app/build.gradle.kts` 的 `compileSdk`/`targetSdk` 改成已装版本（如 36）。
4. 运行 `app` 到模拟器 / 真机，输入「打开空调」「播放音乐」等指令查看 `EXECUTE | <回复>`。

## 语音接入（讯飞 SparkChain 在线听写）

中文语音走讯飞 SparkChain **在线语音听写（iat）**，点击「语音输入（听写）」后识别**任意语音内容**，识别文本回填输入框，点「发送指令」再交给 master-agent 处理。SDK 已集成：`app/libs/SparkChain.aar` + `app/libs/Codec.aar`（arm64 `.so` 内含）。产出文本直接喂 `assembly.master.run(msg)`。
0. **准备**：申请讯飞应用，开通听写服务，demo采用讯飞SparkChain；
1. **填凭据**：`MainActivity` 里 `IAT_APP_ID` / `IAT_API_KEY` / `IAT_API_SECRET` 填讯飞控制台 `console.xfyun.cn/services/iat`「语音听写」栏的**三元组**——注意与 AIKit 离线命令词凭据**不是同一套**，需单独开通「语音听写」服务。
2. **必须联网**：听写走云端 WebAPI，运行期间全程需网络。
3. **说话方式**：点「语音输入（听写）」→ 开始说话 → 停顿约 1.2 秒自动结束识别 → 文本回填输入框 → 点「发送指令」。
4. **架构**：ASR 属宿主层，`SparkChainAsrGateway` 实现 `IAsrGateway`（SPI），产出 `utterance` 喂主控，不触碰 master-agent 核心。（离线命令词 `AikitEsrGateway` 仍保留，待商务授权后可用。）

> **ABI 提醒（重要）**：讯飞 SDK 只带 arm64-v8a / armeabi-v7a 的 `.so`，本工程只打 arm64。x86_64 模拟器**装不上**——你的 Mac 是 Apple Silicon，请建 **arm64 系统镜像的模拟器**（Android Studio → Device Manager → 选 arm64 镜像），或直接车机真机（SA8295P/SA8797P）验证。

> **识别不出内容**：先看 logcat 的 `SparkAsr` 标签——`录音已启动…` 后的峰值振幅若为 0，是麦克风未接主机；`onError code=…` 是云端返回（多为三元组/网络问题）；`SDK 初始化失败` 是三元组未填或服务未开通。

## 模型配置
参见master agent下Readme.md模型用于意图、槽位等处理


## 目录

```
android-host/
├── settings.gradle.kts          # mavenLocal + google + mavenCentral
├── build.gradle.kts             # AGP / Kotlin 版本声明
├── gradle.properties
└── app/
    ├── build.gradle.kts         # 依赖 master-agent / contract / coroutines + 讯飞 SparkChain / Aikit
    ├── libs/SparkChain.aar      # 讯飞 SparkChain SDK（在线听写，arm64 .so 内含）
    ├── libs/Codec.aar           # 讯飞 SparkChain 音频编解码依赖
    ├── libs/AIKit.aar           # 讯飞 Aikit SDK（离线命令词，arm64 .so 内含，待授权）
    └── src/main/
        ├── AndroidManifest.xml  # RECORD_AUDIO / INTERNET 权限
        ├── assets/iflytek/      # 讯飞离线命令词模型（运行时拷到 filesDir/iflytek）
        └── kotlin/com/xiaoguang/carpilot/
            ├── MainActivity.kt  # 装配 + 调用 + 文本/语音双通道
            └── voice/           # IAsrGateway（SPI） + SparkChainAsrGateway（在线听写） + AikitEsrGateway（命令词） + StubAsrGateway（桩）
```
## 配置内容
1、MainActivity.kt配置讯飞`IAT_APP_ID` / `IAT_API_KEY` / `IAT_API_SECRET` ，项目demo中采用讯飞，可更换其他组件