#!/bin/sh
# 打发布包：便携 tar.gz（二进制 + jar + 启动脚本 + 桌面入口 + 图标）
#
# 为什么用便携包而不是 deb/AppImage：
#   Tauri 壳按「可执行文件同目录找 dsh-backend.jar」的逻辑定位后端，
#   便携包把两者放在同一层最直观，也不依赖发行版的 Java 包名。
#
# 用法：./scripts/package.sh [版本号]
set -e

HERE=$(cd "$(dirname "$0")/.." && pwd)
cd "$HERE"

VER="${1:-1.0.0}"
NAME="dsha-v${VER}-linux-x64"
OUT="$HERE/dist"
STAGE="$OUT/$NAME"

BIN="gui/src-tauri/target/release/dsh-launcher"
JAR="backend/dsh-backend.jar"

for f in "$BIN" "$JAR" "dsh-launcher.sh" "dsh-launcher.desktop" "dsh-launcher.png"; do
    if [ ! -f "$f" ]; then
        echo "缺少 $f，请先运行 ./scripts/build-tauri.sh" >&2
        exit 1
    fi
done

echo ">>> 准备 $NAME"
rm -rf "$STAGE"
mkdir -p "$STAGE"

# 二进制改名成 dsha-bin，启动脚本单独叫 dsha
cp "$BIN" "$STAGE/dsha-bin"
chmod +x "$STAGE/dsha-bin"
cp "$JAR" "$STAGE/dsh-backend.jar"
cp "dsh-launcher.png" "$STAGE/dsha.png"

# 便携版启动脚本：直接调同目录的 dsha
cat > "$STAGE/dsha" <<'LAUNCH'
#!/bin/sh
# DSHA 控制中心（便携版）
# Tauri 壳会自动拉起同目录的 dsh-backend.jar，无需手动启动后端
HERE=$(cd "$(dirname "$0")" && pwd)

if [ ! -x "$HERE/dsha-bin" ]; then
    echo "找不到可执行文件：$HERE/dsha-bin" >&2
    exit 1
fi
if ! command -v java >/dev/null 2>&1; then
    echo "未找到 java，后端需要 JRE 21+：sudo pacman -S jre-openjdk" >&2
    exit 1
fi
if [ ! -f "$HERE/dsh-backend.jar" ]; then
    echo "找不到后端 jar：$HERE/dsh-backend.jar" >&2
    exit 1
fi

exec "$HERE/dsha-bin" "$@"
LAUNCH
chmod +x "$STAGE/dsha"

# 真正的二进制叫 dsha-bin，避免和启动脚本同名
cp "$BIN" "$STAGE/dsha-bin"
chmod +x "$STAGE/dsha-bin"

# 桌面入口：用相对路径不可行，发布包里给一个安装脚本
cat > "$STAGE/install-desktop.sh" <<'INSTALL'
#!/bin/sh
# 把菜单入口注册到当前用户（无需 root）
HERE=$(cd "$(dirname "$0")" && pwd)
DIR="$HOME/.local/share/applications"
mkdir -p "$DIR"
sed "s|Exec=.*|Exec=$HERE/dsha|; s|Icon=.*|Icon=$HERE/dsha.png|" \
    "$HERE/dsh-launcher.desktop" > "$DIR/dsha.desktop"
chmod +x "$DIR/dsha.desktop"
command -v update-desktop-database >/dev/null 2>&1 && update-desktop-database "$DIR" 2>/dev/null || true
echo "已注册菜单入口：$DIR/dsha.desktop"
INSTALL
chmod +x "$STAGE/install-desktop.sh"

cp "dsh-launcher.desktop" "$STAGE/dsh-launcher.desktop"

# 说明文件
cat > "$STAGE/README.txt" <<'TXT'
DSHA 控制中心 v1.0.0（便携版）
================================

运行
  ./dsha

注册菜单入口（可选）
  ./install-desktop.sh

依赖
  JRE 21+            必须
  WebKitGTK 4.1      必须（Tauri 渲染后端）

  Arch / CachyOS:
    sudo pacman -S jre-openjdk webkit2gtk-4.1 gtk3 libsoup3 librsvg
  Debian / Ubuntu:
    sudo apt install default-jre libwebkit2gtk-4.1-0 libgtk-3-0 librsvg2-2

文件
  dsha                  启动脚本
  dsha-bin              Tauri 主程序
  dsh-backend.jar       Java 后端
  dsha.png              图标
  install-desktop.sh    注册菜单入口
  dsh-launcher.desktop  桌面入口模板

配置与数据
  实例配置  ~/.config/dsh-launcher/instances.json
  profile   ~/.dsh/profiles/<profile>/cordis.patch.yml
TXT

echo ">>> 打包 tar.gz"
cd "$OUT"
tar -czf "$NAME.tar.gz" "$NAME"
cd "$HERE"

echo ">>> 计算校验和"
cd "$OUT"
sha256sum "$NAME.tar.gz" > "$NAME.tar.gz.sha256"
cd "$HERE"

ls -lh "$OUT/$NAME.tar.gz" "$OUT/$NAME.tar.gz.sha256"
echo ">>> 完成：$OUT/$NAME.tar.gz"
