# 迭代记录

## v2.5 — 2026-11-07 · 工作台改为应用内窗口

**需求**：进入工作台不要跳到外部浏览器。

**改动**
- Tauri 新增命令 `open_workbench(url)`：用 `WebviewWindowBuilder` 建一个
  标签为 `workbench` 的独立窗口加载工作台，尺寸 1280×860（最小 900×600），
  标题「DSH 工作台」，背景色与主题一致。
- 窗口已存在时不重复创建，改为 `navigate()` 到新地址并聚焦、还原最大化。
  这点很关键：**每次启动 dsh 的 token 都会变**，旧窗口必须换地址。
- 前端统一走 `openWorkbench()`：拿得到 Tauri IPC 就用应用内窗口，
  拿不到（纯浏览器调试）才退回 `/api/open`。
- 主按钮、地址卡片、自动打开三处调用点全部替换。
- 文案统一改为「应用内打开」，设置项说明同步更新。

## v2.4 — 2026-11-07 · 启动流程对齐 DSHA 官方

官方 DSHA 的启动契约是「**装完进入主页，Web 在后台启动，就绪后进入 Web UI**」，
不再需要用户手动点启动。启动器照此对齐：

**打开即自动启动**
- 启动器打开后自动在后台拉起 dsh，无需手动点「启动 DSHA」。
- 只在「本轮会话首次打开 + 实例处于停止状态」时触发一次；
  用户主动停止过的实例不会被反复拉起。

**就绪后自动进入工作台**
- 自动启动后开始监听状态，一旦 `running` 且拿到**带 token 的完整地址**，
  就自动在应用内窗口打开工作台。最多等约 2 分钟，超时停止轮询避免空转。
- 手动点主按钮启动时走同一套监听逻辑，行为一致。
- 点「停止」会立即撤销监听，不会出现「停了又被自动打开」。

**新增两个设置开关**（默认都开，可关掉换回手动模式）
- `打开启动器时自动启动`（`dsha.autoStart`）
- `就绪后自动进入工作台`（`dsha.autoOpen`）
- 存在 localStorage，不写进 `instances.json`。

## v2.3 — 2026-11-07 · 修复 dsh 路径定位失败

**症状**：点「启动 DSHA」直接报
`找不到可执行的 dsh：/home/xinyue/.dsh/.npm-global/bin/dsh`

**根因**：旧实现用 `user.home + "/.npm-global/bin/dsh"` 拼路径。
从 dsh 派生的子进程里 `HOME` 会被改写指向数据目录 `~/.dsh`，
`user.home` 跟着变成 `~/.dsh`，于是拼出 `~/.dsh/.npm-global/bin/dsh`。
实际安装位置是 `~/.npm-global/bin/dsh`。环境检测用 `which("dsh")` 走 PATH，
同样可能因为 PATH 里没有 npm 全局目录而误报「未安装」。

**修复**：新增 `resolveDsh()`，按顺序真实探测，不再盲目拼接
1. `DSH_BIN` 环境变量覆盖（显式指定，最高优先级）
2. `/etc/passwd` 里的登录家目录（避开被改写的 `HOME`）+ 当前 `HOME`，
   分别探测 `.npm-global/bin/dsh`、`.local/bin/dsh`、`bin/dsh`
3. 在合并后的 PATH 里逐目录查找 `dsh`（覆盖 `/usr/local/bin`、nvm 等安装方式）
4. 都找不到才回退到默认路径，并在日志里列出已尝试的位置

同时新增 `mergedPath()`：给 dsh 子进程的 PATH 补齐 `~/.npm-global/bin`
和 `~/.local/bin`（去重），不再破坏原有 PATH。
`/api/env` 的 dsh 检测改用 `DSH_BIN` 解析结果，`Files.exists` 改为 `Files.isExecutable`。

**验证**：在 `HOME=~/.dsh`（复现原始故障）与正常 `HOME` 两种场景下，
`DSH_BIN` 均解析为 `/home/xinyue/.npm-global/bin/dsh`。

## v2.2 — 2026-11-07 · 端口可配 + 关闭即停 dsh

**Web 端口可配置（对齐 DSHA 的做法）**
- 设置里可指定 dsh 工作台端口（1024–65535），留空或勾「自动分配」则由系统分配。
- 新增「端口冲突时自动回退」：固定端口被占用时自动改用随机端口，只在日志里说明，
  **绝不结束任何进程**。
- 新增 `GET /api/port-check?port=`：保存前实时校验端口可用性，界面直接提示占用/可用。
- 实例配置新增 `portFallback` 字段，随 `instances.json` 持久化。

**修复：关闭启动器没有关掉 dsh**
根因是 Tauri 用 `Child::kill()`（SIGKILL）直接杀后端，JVM 的 shutdown hook 不执行，
后端派生的 node 进程就成了孤儿。现在三道保障：
1. Tauri 关窗改发 **SIGTERM**，等后端自己退（最多 4s），超时才强杀；
2. 后端 shutdown hook 用 `killTree()`：子孙快照 → 整组 TERM → 等 2s → 整组 KILL → 逐个强杀；
3. dsh 优先用 `setsid` 独立进程组，可 `kill -- -PGID` 整组带走；
   环境不允许 exec `setsid` 时自动回退直接启动并记录日志。
- 另加 `RunEvent::Exit` 兜底，防止只监听到窗口销毁而漏掉退出路径。

**修复：端口顺延后前端连不上**
后端会在 7080–7099 里顺延端口，前端却写死 7080，一旦顺延界面就白屏。
现在通过 IPC（`backend_port` 命令 + `withGlobalTauri`）把真实端口注入前端，
CSP 同步放开 7080–7089。

**修复：工作台地址丢了 token**
dsh 输出的是 `http://127.0.0.1:端口/?token=xxx`，旧实现只解析端口，
拼出的裸地址访问返回 401。现在解析并保存完整 URL（含 token），
「进入工作台」才能真的进去；日志区对 token 打码显示。

**其它**
- 启动前校验 dsh / npx 是否存在，报错文案直接给安装命令。
- 工作目录不存在时回退家目录，避免 exec 失败。
- 支持 `DSH_HOME` 覆盖家目录，便于特殊环境调试。

---

## v2.1 — 2026-11-07 · 界面换成 DSHA 深色控制中心版

**界面重做**
- 采用 `DSHA-Linux-UI`（no-scroll 版）作为新界面：深色主题、圆形品牌标、
  左侧胶囊形导航（DSHA / 仪表盘 / 项目）、右侧 READY + 运行环境卡、
  底部「安全启动 / 恢复选项」。
- 桌面端整窗适配视口高度（`100vh` 网格 + 弹性日志区），不再出现整页滚动条。
- 移除了 Google Fonts 的 `@import`，改走系统字体，避免 WebView 里 CSP 拦截与离线失效。
- 窗口改为 1180×720（最小 900×560），标题「DSHA · 控制中心」，底色 `#080a0d`。
- 许可 CSP 放开到 `fonts.googleapis.com` / `fonts.gstatic.com`，为后续换字体留口子。

**清理**
- 删除 `src/com/dshlauncher/Main.java`、`out/`、根目录旧 `dsh-launcher.jar`
- 删除失效的 `scripts/build.sh`（旧 Swing 构建）
- 后端拉起改为 7080→7099 自动顺延，避免上一个残留进程把启动卡死

**已知**
- 新界面目前是纯前端交互原型（启动/停止为模拟状态）。
- 接后端那版（含实例编辑、真实日志、环境检测弹窗）已写完但按你的要求撤下，
  需要时再启用。

---

## v2.0 — 2026-11-07 · 迁移到 Tauri + Java 后端

**换栈原因**
v1.0 的 Java Swing 版在本机 CachyOS + niri 环境下窗口渲染成纯白。经排查确认：
- 纯 AWT 窗口能正常显示（白底黑字中文都对）
- `MetalLookAndFeel` 和 `NimbusLookAndFeel` 两种主题都是纯白
- 与字体无关（离屏 paint 到 BufferedImage 完全正常）
- 与 niri 的全局 `opacity 0.9 + blur` 规则有关，但给该窗口加例外规则后仍然白屏

结论：Swing 主题层在此环境下不可用。改用 Tauri（WebKitGTK 渲染）画界面，
Java 只当后端进程，两边用本地 HTTP 通信。一次跑通。

**架构**
- `gui/` — Tauri 2 + 原生 HTML/CSS/JS（无前端框架、无构建链）
- `backend/` — Java 独立进程，`com.sun.net.httpserver` 暴露 REST 接口
- Tauri 启动时自动拉起后端 jar，窗口销毁时回收子进程

**界面**（与 v1.0 线框一致）
- 顶部：设置 / 更新 / 托盘
- 左侧：实例列表（状态灯 · 名称 · 端口 · 运行模式）+ 添加实例
- 右侧：详情卡（名称、状态、地址、端口、运行模式、启动参数、环境变量）
- 控制行：启动 / 停止 / 重启 / 打开界面 / 删除
- 底部四标签：日志面板 / 配置编辑 / 补丁管理 / 环境检测

**能力**
- 多实例，`npm`（全局 dsh）与 `local`（npx 源码目录）两种模式
- 自动端口：`--port 0` 由系统分配，从日志正则解析实际端口，前端 700ms 增量轮询
- 环境变量按 `KEY=VALUE` 逐行注入
- 日志分级着色：就绪绿、警告黄、错误红、命令与 info 蓝
- 状态机：stopped / starting / running / error
- 配置编辑：读写 profile 补丁层，保存前自动备份
- 补丁管理：每行一个 `--patch` overlay
- 环境检测：dsh / node / npm / edge / niri / 中文字体 + 端口占用
- 打开界面：Edge `--app` 独立窗口

**已修复的坑**
- Tauri 图标必须是 RGBA，RGB 的 PNG 会让 `generate_context!` 宏 panic
- WebView 的 origin 是 `tauri://localhost`，跨域访问本地 HTTP 必须显式响应
  `Access-Control-Allow-Methods/Headers` 并处理 OPTIONS 预检，否则 fetch 直接失败
- CachyOS 是 Arch 系，包管理器为 pacman，Rust 走 `rustup`

**产物**
- `backend/dsh-backend.jar` — 18K
- `gui/src-tauri/target/release/dsh-launcher` — 3.5M

---

## v1.0 — 2026-11-07 · Java Swing 版（已删除）

从无头启动器（检测端口 → 开 Edge 窗口）重写为图形管理器。
源码 `src/com/dshlauncher/Main.java` 与构建脚本 `scripts/build.sh`
已随 v2.1 清理删除，仅在本文件留档。

**界面**
- 顶部标题栏：设置 / 更新 / 托盘
- 左侧实例列表：状态灯 + 名称 + 端口 + 运行模式，`+ 添加实例`
- 右侧详情卡：名称、状态、地址、端口、运行模式、启动参数、环境变量
- 控制行：启动 / 停止 / 重启 / 打开界面 / 删除
- 底部四标签：日志面板 / 配置编辑 / 补丁管理 / 环境检测

## 待办 / 候选

- [ ] 系统托盘图标常驻（当前「托盘」是弹出菜单）
- [ ] 日志持久化到文件并按实例分开
- [ ] 实例导入导出（JSON 片段复制粘贴）
- [ ] 一键更新：`npm i -g @deepseek-ai/dsh` 带进度输出
- [ ] 配置编辑支持 YAML 语法高亮与结构校验
- [ ] 打包成 AppImage / deb 分发（`tauri build`）
- [ ] 后端端口 7080 可配置，冲突时自动顺延

