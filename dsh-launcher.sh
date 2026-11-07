#!/bin/sh
# DSHA 控制中心启动器（Tauri 版）
# Tauri 壳会自动拉起 Java 后端，无需手动启动

HERE=$(cd "$(dirname "$0")" && pwd)
BIN="$HERE/gui/src-tauri/target/release/dsh-launcher"

if [ ! -x "$BIN" ]; then
    echo "找不到可执行文件：$BIN"
    echo "请先构建：$HERE/scripts/build-tauri.sh"
    exit 1
fi

if ! command -v java >/dev/null 2>&1; then
    echo "未找到 java，后端需要 JRE 21+：sudo pacman -S jre-openjdk"
    exit 1
fi

if [ ! -f "$HERE/backend/dsh-backend.jar" ]; then
    echo "找不到后端 jar：$HERE/backend/dsh-backend.jar"
    echo "请先构建：$HERE/scripts/build-tauri.sh"
    exit 1
fi

exec "$BIN" "$@"
