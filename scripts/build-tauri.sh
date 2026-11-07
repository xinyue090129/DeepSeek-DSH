#!/bin/sh
# 一键构建：Java 后端 jar + Tauri 前端
set -e

HERE=$(cd "$(dirname "$0")/.." && pwd)
cd "$HERE"

export PATH="$HOME/.cargo/bin:$PATH"

echo ">>> [1/4] 编译 Java 后端"
rm -rf backend/out
mkdir -p backend/out
javac -encoding UTF-8 -d backend/out backend/src/com/dshlauncher/Backend.java

echo ">>> [2/4] 打包 dsh-backend.jar"
jar --create --file backend/dsh-backend.jar \
    --main-class com.dshlauncher.Backend \
    -C backend/out .
ls -lh backend/dsh-backend.jar

echo ">>> [3/4] 编译 Tauri 前端（cargo build）"
cd gui/src-tauri
cargo build --release 2>&1 | tail -20

echo ">>> [4/4] 收尾"
cd "$HERE"
BIN="gui/src-tauri/target/release/dsh-launcher"
if [ -f "$BIN" ]; then
    ls -lh "$BIN"
    echo "启动方式：$HERE/$BIN"
else
    echo "未找到产物，请检查上面的编译输出"
    exit 1
fi

echo ">>> 完成"
