<div align="center">

# DSHA · 控制中心

**DeepSeek Harness 的 Linux 桌面启动器**

一个窗口管完 dsh 的启动、端口、日志与工作台

<sub>Tauri 2 · Rust · Java · 原生 Web，零前端框架</sub>

</div>

---

## 它是什么

`dsh`（`@deepseek-ai/dsh`）是 DeepSeek 官方的 agent harness。它默认跑在终端里，每次要敲命令、记端口、从滚动的日志里翻 `?token=` 那一长串地址。

DSHA 把这些收进一个桌面窗口：

- 点一下启动，**端口和 token 自动解析**，工作台直接在应用内打开
- 端口可自己指定，被占用了会告诉你，也能自动回退
- 关掉窗口，**dsh 跟着一起退出**，不留孤儿进程

---

## 特性

| | |
|---|---|
| **一键启停** | 启动 / 停止 / 重启 / 安全启动（跳过全部补丁） |
| **端口可配** | 1024–65535 手动指定，保存前实时预检占用情况 |
| **冲突回退** | 固定端口被占用时按设置自动改用随机端口；关闭则明确报错停下 |
| **token 解析** | 从 dsh 输出里抓完整 `?token=…` 地址，裸端口不再 401 |
| **应用内工作台** | 独立 WebView 窗口，不弹外部浏览器，复用窗口时自动换新 token |
| **单实例保护** | 同一 profile 已有 dsh 在跑就拒绝启动，避免「会话已被占用」 |
| **实时日志** | 增量拉取、token 打码、自动滚底、可回顶部 |
| **环境自检** | dsh / node / npm / 浏览器 / 输入法 / 中文字体一次看清 |
| **深色界面** | 整窗适配视口，桌面端不出现整页滚动条 |

---

## 架构

```
┌──────────────────────────────┐   HTTP    ┌───────────────────────────┐
│  Tauri 壳（Rust + WebKitGTK） │ ────────▶ │  Java 后端（独立进程）      │
│  窗口 · 渲染 · 拉起/回收后端   │  127.0.0.1 │  进程管理 · 日志 · 配置      │
│  应用内工作台窗口             │  :7080..99│  端口探测 · 环境检测         │
└──────────────────────────────┘           └───────────────────────────┘
```

**为什么这么拆：** 本机 CachyOS + niri 下，Java 的 Swing 主题（Metal / Nimbus）会把窗口渲染成纯白一片，而 Tauri 走 WebKitGTK 完全正常。于是把「画界面」交给 Tauri，把「干活」留给 Java，两边用本地 HTTP 说话。

前端是原生 HTML/CSS/JS，**没有框架、没有构建链**——改完刷新就生效。后端用 JDK 自带的 `com.sun.net.httpserver`，**零第三方依赖**，手写 JSON，一个文件装下全部逻辑。

---

## 快速开始

```bash
# 启动（Tauri 会自动拉起 Java 后端）
./dsh-launcher.sh

# 改完代码后完整重建
./scripts/build-tauri.sh
```

应用菜单里搜 **DSHA 控制中心** 也能打开。

首次使用建议先看一眼「环境检测」，确认 dsh 和 node 都在。

---

## 界面

```
┌────────────────────────────────────────────────────────┐
│  DSHA                                    活动 ◐ ⓘ      │
├──────────┬─────────────────────────────────────────────┤
│          │  CONTROL CENTER           ● 运行中          │
│  ◈ DSHA  │  DSHA 控制中心                              │
│  ⌕ 仪表盘│  ┌──────────────┐  ┌────────┐ ┌────────┐   │
│  ♙ 项目  │  │  ●  ONLINE   │  │  端口   │ │ 运行时  │   │
│          │  │   :38545     │  │ 38545  │ │ ACTIVE │   │
│          │  │  ↗ 进入工作台 │  └────────┘ └────────┘   │
│          │  └──────────────┘                           │
│  ⚙ 设置  │  ── 快捷入口 ────────────────────────────    │
│          │  ── 启动日志 ─────────────────────────────   │
│          │  [12:01:03] 正在启动 local-deepseek …       │
│          │  [12:01:03] $ dsh --profile web --port 0    │
└──────────┴─────────────────────────────────────────────┘
```

---

## 后端接口

Java 后端可以脱离界面单独跑，方便调试：

```bash
java -jar backend/dsh-backend.jar --port 7080
```

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查，附带外部 dsh 探测结果 |
| GET | `/api/instances` | 实例列表 |
| POST | `/api/instances/add` | 新增实例 |
| POST | `/api/instances/update` | 更新实例（端口 / 自动端口 / 回退策略） |
| POST | `/api/instances/delete` | 删除实例 |
| POST | `/api/instances/start` | 启动 |
| POST | `/api/start-safe` | 安全启动（跳过全部补丁） |
| POST | `/api/instances/stop` | 停止（只结束本后端派生的进程树） |
| POST | `/api/instances/restart` | 重启 |
| GET | `/api/instances/logs?id=&since=` | 增量拉日志 |
| GET/POST | `/api/profile` | 读写 profile 补丁 |
| GET | `/api/env` | 环境检测 |
| GET | `/api/update` | 版本检查 |
| GET | `/api/port-check?port=` | 端口占用预检 |
| POST | `/api/open` | 用外部浏览器打开（应用内窗口走 IPC） |

```bash
curl -s http://127.0.0.1:7080/api/health | python3 -m json.tool
curl -s "http://127.0.0.1:7080/api/port-check?port=3080"
```

---

## 安全红线

这几条是硬约束，改代码时不可违背：

- **只启动、只检测、只聚焦。** 从不结束任何非自己派生的进程。
- **端口被占用只提示，不抢占。** 想避开就开「自动端口」，由系统分配。
- **停止只终止本后端拉起的进程树。** 优先 `setsid` 独立进程组，`kill -- -PGID` 整组带走。
- **关窗即清理。** SIGTERM → 等 4 秒 → 兜底 SIGKILL；后端 shutdown hook 再兜一层。

---

## 目录

```
dsh-launcher.sh              启动脚本
dsh-launcher.desktop         桌面入口
dsh-launcher.png             图标

backend/
  src/com/dshlauncher/
    Backend.java             后端全部源码（单文件，约 1800 行）

gui/
  src/
    index.html               界面结构
    style.css                深色主题
    app.js                   全部交互逻辑
  src-tauri/
    src/lib.rs               Tauri 壳：拉起后端、生命周期、工作台窗口
    Cargo.toml
    tauri.conf.json          窗口尺寸 · CSP · 打包配置

scripts/
  build-tauri.sh             Java 编译打包 → cargo build --release
docs/
  CHANGELOG.md               迭代记录
```

---

## 数据位置

| 内容 | 路径 |
|---|---|
| 实例配置 | `~/.config/dsh-launcher/instances.json` |
| profile 补丁 | `~/.dsh/profiles/<profile>/cordis.patch.yml` |
| 保存备份 | 同目录 `cordis.patch.yml.bak-<时间戳>` |

---

## 依赖

| 组件 | 版本 | 用途 |
|---|---|---|
| Rust | ≥ 1.77 | Tauri 壳 |
| Tauri CLI | v2 | 构建 / 打包 |
| JDK / JRE | ≥ 21 | 后端运行与 `jar` 打包 |
| WebKitGTK | 4.1 | Tauri 渲染后端 |
| GTK3 · libsoup3 · librsvg | — | WebKitGTK 依赖 |

```bash
# Arch / CachyOS
sudo pacman -S rust jdk-openjdk webkit2gtk-4.1 gtk3 libsoup3 librsvg
cargo install tauri-cli
```

---

## 本机验证状态

| 项 | 版本 |
|---|---|
| Rust | 1.99.1 |
| Tauri CLI | 2.12.4 |
| JDK | 21.0.12 |
| WebKitGTK | 2.54.2 |
| dsh | `@deepseek-ai/dsh` 0.2.2-rc.1 |

---

<div align="center">
<sub>界面版式源自 <code>DSHA-Linux-UI</code>（no-scroll 深色控制中心）</sub>
</div>
