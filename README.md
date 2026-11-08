<div align="center">

# DSHA · 控制中心

**DeepSeek Harness 的 Linux 桌面启动器**

一个窗口管完 dsh 的启动、端口、日志、插件与工作台

<sub>Tauri 2 · Rust · Java · 原生 Web，零前端框架</sub>

![version](https://img.shields.io/badge/version-1.0.0-3b7dd8)
![platform](https://img.shields.io/badge/platform-Linux-2ba471)

</div>

---

## 它是什么

`dsh`（`@deepseek-ai/dsh`）是 DeepSeek 官方的 agent harness。它默认跑在终端里，每次要敲命令、记端口、从滚动的日志里翻 `?token=` 那一长串地址。

DSHA 把这些收进一个桌面窗口：

- 点一下启动，**端口和 token 自动解析**，工作台直接在应用内打开
- 端口可自己指定，被占用了会告诉你，也能自动回退
- 关掉窗口，**dsh 跟着一起退出**，不留孤儿进程
- 插件安装、升级、启停都在界面里完成，**但绝不自动替你装**

---

## 特性

### 控制中心

| | |
|---|---|
| **一键启停** | 启动 / 停止 / 重启 / 安全启动（跳过全部补丁） |
| **端口可配** | 1024–65535 手动指定，保存前实时预检占用情况 |
| **冲突回退** | 固定端口被占用时按设置自动改用随机端口；关闭则明确报错停下 |
| **token 解析** | 从 dsh 输出里抓完整 `?token=…` 地址，裸端口不再 401 |
| **应用内工作台** | 独立 WebView 窗口，不弹外部浏览器，复用窗口时自动换新 token |
| **单实例保护** | 同一 profile 已有 dsh 在跑就拒绝启动，避免「会话已被占用」 |
| **实时日志** | 增量拉取、token 打码、自动滚底、可回顶部 |

### 插件中心

| | |
|---|---|
| **清单来自磁盘** | 直接读 profile 的 `package.json`、`node_modules` 与补丁里的 `disabled` 标记 |
| **安装 / 升级** | 输入包名或 Git 地址，确认后执行一次 `pnpm add --save-exact`，失败自动回滚 |
| **启停开关** | 改 `cordis.patch.yml` 里的 `disabled`，重启实例生效 |
| **不自动安装** | 启动、重启、轮询、环境检测等一切自动流程都不碰 npm / pnpm |

### 设置页

三组九项，**能真生效的才做成可编辑**：

| 分组 | 条目 |
|---|---|
| 常规 | 基础设置 / DSH 设置 / 运行环境 |
| 服务与扩展 | Web UI / 网络设置 / 插件设置 |
| 数据与信息 | 数据与备份 / 安全设置 / 关于 |

- **可改**：实例名、profile、启动模式、附加参数、工作目录、环境变量、补丁、Web 端口、自动分配、端口回退 —— 全部走 `/api/instances/update` 真写入
- **只读**：Java 版本、会话类型、运行端口、dsh/node/edge/niri 实际路径、配置文件位置、进程与端口策略 —— 显示真实值，不给假开关
- **本机偏好**：主题、自动启动、自动进入工作台 —— 存 `localStorage`

支持搜索过滤、`Ctrl+K` 聚焦搜索、`Esc` 返回列表。

---

## 架构

```
┌──────────────────────────────┐   HTTP    ┌───────────────────────────┐
│  Tauri 壳（Rust + WebKitGTK） │ ────────▶ │  Java 后端（独立进程）      │
│  窗口 · 渲染 · 拉起/回收后端   │  127.0.0.1 │  进程管理 · 日志 · 配置      │
│  应用内工作台窗口             │  :7080..99│  端口探测 · 环境检测 · 插件  │
└──────────────────────────────┘           └───────────────────────────┘
```

**为什么这么拆：** 本机 CachyOS + niri 下，Java 的 Swing 主题（Metal / Nimbus）会把窗口渲染成纯白一片，而 Tauri 走 WebKitGTK 完全正常。于是把「画界面」交给 Tauri，把「干活」留给 Java，两边用本地 HTTP 说话。

前端是原生 HTML/CSS/JS，**没有框架、没有构建链**——改完刷新就生效。后端用 JDK 自带的 `com.sun.net.httpserver`，**零第三方依赖**，手写 JSON，一个文件装下全部逻辑。

---

## 安装

### 方式一：下载发布包（推荐）

从 [Releases](https://github.com/xinyue090129/DeepSeek-DSH/releases) 下载最新 `dsha-v1.0.0-linux-x64.tar.gz`，解压后：

```bash
tar -xzf dsha-v1.0.0-linux-x64.tar.gz
cd dsha-v1.0.0
./dsh-launcher.sh
```

需要系统已装 **JRE 21+** 与 **WebKitGTK 4.1**（见下方依赖）。

### 方式二：从源码构建

```bash
git clone https://github.com/xinyue090129/DeepSeek-DSH.git
cd DeepSeek-DSH

# 一条命令：Java 编译打包 → cargo build --release
./scripts/build-tauri.sh

./dsh-launcher.sh
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
│  ⌕ 插件  │  ┌──────────────┐  ┌────────┐ ┌────────┐   │
│  ♙ 设置  │  │  ●  ONLINE   │  │  端口   │ │ 运行时  │   │
│          │  │   :38545     │  │ 38545  │ │ ACTIVE │   │
│          │  │  ↗ 进入工作台 │  └────────┘ └────────┘   │
│          │  └──────────────┘                           │
│          │  ── 快捷入口 ────────────────────────────    │
│          │  ── 启动日志 ─────────────────────────────   │
│          │  [12:01:03] 正在启动 local-deepseek …       │
│          │  [12:01:03] $ dsh --profile web --port 0    │
└──────────┴─────────────────────────────────────────────┘
```

三个视图：**控制中心** / **插件中心** / **设置**，侧边栏切换。

---

## 后端接口

Java 后端可以脱离界面单独跑，方便调试：

```bash
java -jar backend/dsh-backend.jar --port 7080
```

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查，附带外部 dsh 探测结果、`pluginAutoInstall:false` |
| GET | `/api/instances` | 实例列表 |
| POST | `/api/instances/add` | 新增实例 |
| POST | `/api/instances/update` | 更新实例（端口 / 自动端口 / 回退策略 / profile / 环境变量等） |
| POST | `/api/instances/delete` | 删除实例 |
| POST | `/api/instances/start` | 启动 |
| POST | `/api/start-safe` | 安全启动（跳过全部补丁） |
| POST | `/api/instances/stop` | 停止（只结束本后端派生的进程树） |
| POST | `/api/instances/restart` | 重启 |
| GET | `/api/instances/logs?id=&since=` | 增量拉日志 |
| GET | `/api/plugins` | 插件清单（读磁盘，只读） |
| POST | `/api/plugins/install` | 安装插件（`pnpm add`，失败回滚） |
| POST | `/api/plugins/uninstall` | 卸载插件 |
| POST | `/api/plugins/toggle` | 启停插件（改 `cordis.patch.yml`） |
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
- **插件不自动安装。** 启动、重启、轮询、环境检测都不碰 npm / pnpm；只有用户在界面上显式点击时，才调用 `/api/plugins/install`、`/api/plugins/uninstall` 或 `/api/plugins/toggle`。

---

## 目录

```
dsh-launcher.sh              启动脚本
dsh-launcher.desktop         桌面入口
dsh-launcher.png             图标

backend/
  src/com/dshlauncher/
    Backend.java             后端全部源码（单文件，约 1700 行）

gui/
  src/
    index.html               界面结构（三个视图 + 两个弹窗）
    style.css                深色主题（含浅色覆盖）
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
| 界面偏好 | `localStorage`（主题、自动启动） |

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

## 已知限制

- **Linux 专用。** 依赖 WebKitGTK 与 niri，未做 Windows / macOS 适配。
- **单实例单 profile 编辑。** 多实例可并存，但界面一次聚焦一个。
- **插件安装受网络影响。** `pnpm add` 走默认 registry，失败会自动回滚 `package.json` 与 `pnpm-lock.yaml`。

---

## 更新日志

见 [docs/CHANGELOG.md](docs/CHANGELOG.md)。当前版本 **v1.0.0** 汇总了 v2.9 之前的全部迭代。

---

<div align="center">
<sub>界面版式源自 <code>DSHA-Linux-UI</code>（no-scroll 深色控制中心）· 设置页结构参考 <code>DSHA-PC-Settings</code></sub>
</div>
