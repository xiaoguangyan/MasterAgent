#!/bin/bash
# 版权所有：xiaoguang.yan（8518960@qq.com）
# 描述：把四个端侧工程按依赖顺序发布到本地 Maven 仓库（~/.m2/repository），供 Android 车机工程依赖引入。
# 用法：./publish-local.sh

set -euo pipefail

HERE="$(cd "$(dirname "$0")" && pwd)"

# JDK 17（本机未注册到 /usr/libexec/java_home 时，显式指定 homebrew openjdk@17）
if [ -z "${JAVA_HOME:-}" ] && [ -d "/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home" ]; then
  export JAVA_HOME="/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home"
fi

# 优先用本地已缓存的 gradle 二进制（离线），否则回退各工程 gradlew
GRADLE="${GRADLE:-}"
CACHED="$HOME/.gradle/wrapper/dists/gradle-9.6.0-bin/3yr5qo1p853jn7m5o1j0u2qkd/gradle-9.6.0/bin/gradle"
if [ -z "$GRADLE" ] && [ -x "$CACHED" ]; then
  GRADLE="$CACHED"
fi

run_gradle() {
  local dir="$1"
  (cd "$HERE/$dir" && \
    if [ -n "$GRADLE" ]; then "$GRADLE" publishToMavenLocal --offline --no-daemon --console=plain; \
    else ./gradlew publishToMavenLocal --offline --no-daemon --console=plain; fi)
}

for p in contract context memory master-agent; do
  echo "==== 发布 $p ===="
  run_gradle "$p"
done

echo "==== 完成，制品位于 ~/.m2/repository/com/xiaoguang/masteragent ===="
