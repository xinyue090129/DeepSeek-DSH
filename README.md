# DSH Launcher

DeepSeek Harness (`dsh`) 的 Linux 桌面管理器。

## 架构

```
┌─────────────────────────────┐        HTTP         ┌──────────────────────┐
│  Tauri 壳（Rust + WebKit）   │  ───────────────▶   │  Java 后端（独立进程） │
│  负责窗口、渲染、自动拉起后端  │   127.0.0.1:7080    │  全部业务逻辑          │
└─────────────────────────────┘                     └──────────────────────┘
```

**为什么这样拆：** 本机 CachyOS + niri 下 Java 的 Swing 主题（Metal / Nimbus）会把窗口渲染成纯白，而 Tauri 走 WebKitGTK 渲染完全正常。于是把「画界面」交给 Tauri，把「办事」留给 Java，两边用本地 HTTP 通信。

Java 后端通过 `com.sun.net.httpserver` 暴露接口，零第三方依赖，也能用 curl 直接调试。

## 快速开始

```bash
./dsh-launcher.sh          # 启动（Tauri 会自动拉起 Java 后端）
./scripts/build-tauri.sh   # 改完代码后完整重建
```

应用菜单里搜「DSH 启动器」也能打开。

## 目录

```
dsh-launcher.sh              启动脚本
dsh-launcher.desktop         桌面入口
dsh-launcher.png             图标
backend/
  src/com/dshlauncher/Backend.java   后端全部源码（单文件）
  dsh-backend.jar                    后端产物（18K）
gui/
  src/index.html / style.css / app.js  前端（原生，无框架，深色控制中心主题）
  src-tauri/                          Tauri 壳（Rust）
    src/lib.rs                        拉起后端 + 生命周期管理
    target/release/dsh-launcher       最终可执行文件（3.4M）
scripts/
  build-tauri.sh              完整构建
docs/CHANGELOG.md             迭代记录
```

> 界面版式来自 `DSHA-Linux-UI`（no-scroll 深色控制中心版），已整窗适配视口高度，桌面端不出现整页滚动条。
> 早期 Java Swing / AWT 界面与 `dsh-launcher.jar` 已全部移除。

## 后端接口

| 方法 | 路径 | 说明 |
|---|---|---|
| GET | `/api/health` | 健康检查 |
| GET | `/api/instances` | 实例列表 |
| POST | `/api/instances/add` | 新增实例 |
| POST | `/api/instances/update` | 更新实例 |
| POST | `/api/instances/delete` | 删除实例 |
| POST | `/api/instances/start` | 启动 |
| POST | `/api/instances/stop` | 停止 |
| POST | `/api/instances/restart` | 重启 |
| GET | `/api/instances/logs?id=&since=` | 增量拉日志 |
| GET/POST | `/api/profile` | 读写 profile 补丁 |
| GET | `/api/env` | 环境检测 |
| GET | `/api/update` | 版本检查 |
| POST | `/api/open` | Edge 应用模式打开 |

调试示例：

```bash
curl -s http://127.0.0.1:7080/api/health
curl -s http://127.0.0.1:7080/api/instances | python3 -m json.tool
```

## 安全红线（不可违背）

- **只启动、只检测、只聚焦**。从不 kill 任何非自己派生的进程。
- 端口被占用时**只提示，不抢占**。想避开就开「自动端口」（`--port 0` 由系统分配）。
- 停止只终止本后端拉起的那个进程。

## 数据位置

| 内容 | 路径 |
|---|---|
| 实例配置 | `~/.config/dsh-launcher/instances.json` |
| profile 补丁 | `~/.dsh/profiles/<profile>/cordis.patch.yml` |
| 保存备份 | 同目录 `cordis.patch.yml.bak-<时间戳>` |

## 依赖

- **Rust** ≥ 1.77（`rustup`，工具链 stable）
- **Tauri CLI** v2（`cargo install tauri-cli`）
- **JDK/JRE** ≥ 21（后端 + `jar` 打包）
- **WebKitGTK 4.1**、GTK3、libsoup3、librsvg

本机状态：已全部装好（Rust 1.99.1、Tauri CLI 2.12.4、JDK 21.0.12、WebKitGTK 2.54.2）。
