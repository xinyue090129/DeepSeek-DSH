# 迭代记录

## v2.12 — 2026-11-08 · 修复关启动器关不掉 dsh

**问题**：关掉启动器后 dsh 进程（及其 node 子进程树）仍留在后台，
继续占着工作台端口，下次启动时后端因端口被占顺延，导致「启动不了 + 没日志」。

**根因**：后端回收逻辑挂在 `on_window_event` 的 `Destroyed` 事件上，
但主窗口和工作台窗口销毁都会触发它，任何一个先关都会把后端子进程
`take()` 走，真正该关时 child 已是 `None`，后端和 dsh 全部漏杀；
且未处理 `CloseRequested`，点 × 默认只关窗口、不一定退出应用。

**改动**（`gui/src-tauri/src/lib.rs`）
- 主窗口 `CloseRequested` 时显式 `app.exit(0)`：关主窗 = 退出整个应用。
- 后端回收统一到 `RunEvent::ExitRequested | Exit`，用 `take()` 保证幂等，
  只清理一次，不再被多窗口销毁事件干扰。

---

## v2.11 — 2026-11-08 · 删除「官方可选扩展」区块 + 窗口尺寸不固定

- **插件中心**：整个移除「官方可选扩展」展示区块（HTML / JS 渲染 / 数据装载），
  后端 `/api/plugins` 接口不动，仅前端不再展示。
- **窗口尺寸**：主窗口与工作台窗口去掉固定默认尺寸和最小尺寸限制，
  由窗口管理器决定初始大小，可自由缩放。
- **图标瘦身**：根目录 `dsh-launcher.png` 由 5.4M 压缩到 125K
  （256×256 RGBA，`-strip` 元数据）， portable 包 / desktop 文件引用不变；
  `gui/src-tauri/icons/` 内三枚 Tauri 图标不变。
- **README**：移除「官方扩展发现」功能行（界面已删除该区块）。

---

## v2.10 — 2026-11-08 · 日志颜色统一

**改动**：控制台日志不再按 error / warn / success / muted 分类着色，
全部统一为一种颜色（深色主题 `#d1d9e2`，浅色主题 `#263445`）。

- `app.js` 的 `logClass()` 逻辑保留不动，DOM 上仍带分类类名，
  将来想恢复分类只需改 CSS。
- 标题栏的「N 行 / LIVE」徽标不属于日志行，颜色保持原样。

---

## v2.9 — 2026-11-08 · 未运行时「停止」给提示、「重启」改为启动

**问题**：实例没在跑的时候，「停止」「重启」两个按钮都是灰的、点不动，
看着像坏了。

**改动**
- 两个按钮不再因 `stopped` 而禁用，只有 `busy`（正在执行别的动作）时才禁用。
- **停止**：状态不是 `running` / `starting` 时，点击给出提示
  「没有正在运行的进程，无需停止」，不改发请求。
- **重启**：状态不是 `running` / `starting` 时，没有东西可重启，
  点击直接改走启动流程（`/api/instances/start`），提示「当前未运行，正在启动 xxx…」。
  **按钮文字和图标保持原样（`↻重启` / `□停止`），界面不做任何变化。**
- `running` / `starting` 下行为与之前完全一致：停止发 `/api/instances/stop`
  （`starting` 时可中断启动），重启发 `/api/instances/restart`。

**验证**（jsdom）
- 静态：两个按钮的 `textContent` 仍是 `↻重启` / `□停止`，HTML 未改动。
- `stopped` / `error`：两者都 `disabled === false`，停止→提示、重启→start。
- `running` / `starting`：停止→stop、重启→restart。
- `busy`：两者都禁用。

---

## v2.8 — 2026-11-07 · 设置页整页接入（真数据）

把 `~/下载/DSHA-PC-Settings-B-优化版` 那套纯前端原型接进启动器。
原型 README 自己注明「尚未接入 DSHA 后端」，这里按它的视觉结构重做，但数据全部换成真的。

**结构**
原来是 `#settingsModal` 一个弹窗（只有端口 / 自动分配 / 回退 / 自动启动四个开关）。
现在换成整页三视图：`console` / `plugins` / `settings`，由 `showView()` 统一切换。
设置页内部是「分类列表 → 点进详情 → 返回」的单列流（沿用原型的交互），
侧边栏「设置」和左下角「⚙ 设置」都进同一个页面。

**分类（3 组 9 项）**
| 分组 | 条目 |
|---|---|
| 常规 | 基础设置 / DSH 设置 / 运行环境 |
| 服务与扩展 | Web UI / 网络设置 / 插件设置 |
| 数据与信息 | 数据与备份 / 安全设置 / 关于 |

**数据原则：能真生效的才做成可编辑**
- **有真实读写接口**（走 `/api/instances/update`）：实例名称、profile、启动模式、
  附加参数、工作目录、环境变量、补丁路径、Web 端口、自动分配端口、端口回退。
  保存失败会把控件拨回原值并提示。
- **只读展示**（界面上显示真实值，不给假开关）：Java 版本、会话类型、
  当前运行端口、dsh / node / edge / niri 的实际路径、配置文件位置、进程与端口策略。
- **本机偏好**：主题、打开时自动启动、就绪后自动进入工作台 —— 存 `localStorage`。

**行类型**
`settingRow()` 支持 `info / switch / select / text / number / action`，
`number` 带 `check()` 做防抖校验（端口 1024–65535 + `/api/port-check` 占用检测），
非法值不发请求、直接回退。

**顺手修掉的三个旧账**
1. `app.js` 里 `parseButton` / `installForm` / `sourceSelect` 各被绑定了**两次** ——
   v2.6 改成真安装后，旧的「复制来源」版本没删干净，后绑定的覆盖了先绑定的，
   导致点「确认安装」走的是复制分支、根本没调用 `/api/plugins/install`。已删除旧块。
2. 多处注释与文案还写着「启动器只读插件清单，永不安装」，与 v2.6 的实际行为矛盾，已更正口径为
   「不自动安装，用户显式点才安装」。
3. `}  $("pasteButton")` 两行粘在一起，已拆开。

**验证**（`.buildtmp/settings_test2.js`，jsdom）
不 eval 整个 `app.js` —— 里面 `logLoop()` 是 `while(true)` 长轮询，在 Node 里会把事件循环占满，
`setTimeout` 回调永远轮不到（这是踩过的坑）。改为抽设置页代码段单独跑，12 组断言全过：

- 3 分组 9 条目全渲染，无空态误显示
- 搜索过滤、「插件」命中 1 条、无结果出空态
- 主题切浅色 → `body.light` + `localStorage` 都真的改
- 改实例名 → 真发 `/api/instances/update` 且带 `id`/`name`
- 端口 80 → 拦下、不发请求、提示范围、控件回退 3080
- 端口 3088 → 真发请求，`port` 按字符串传
- 「打开插件页」按钮 → 切到 `plugins` 并触发 `loadPlugins()`
- 关于页取到真实 dsh 路径；env 缺失项显示「未找到」
- 设了 `HTTPS_PROXY` 后网络设置识别为「已配置」

**样式**
新增 `.settings-*` 系列（搜索框、分组标题、卡片、行、图标 9 种配色、开关、输入、
操作按钮、提示、返回按钮），沿用控制中心 `--panel` / `--border-strong` / `--accent` 等变量，
`#settingsView` 自带滚动容器和滚动条配色（同 `#pluginView` 的处理），浅色主题逐项覆盖。
JS 用到的 16 个类 CSS 全部有定义，花括号 421/421 平衡。

---

## v2.7 — 2026-11-07 · 修复插件页无法滚动

**问题**：插件页内容一多，下方内容被裁掉，鼠标滚轮也滚不动。

**根因**
桌面断点（≥701px）下 `html, body { overflow: hidden }`、`.main-content` 也是
`overflow: hidden`，控制台靠 `.dashboard-grid` 的 flex 撑满高度所以没事；
但 `#pluginView` 是纯流式内容，没有自己的滚动容器，超出部分直接被裁。
`grep pluginView style.css` 当时是 0 命中 —— 这个视图从来没被单独定义过。

**改动**
- `#pluginView` 加 `flex:1 1 auto; min-height:0; overflow-y:auto; overflow-x:hidden`，
  并 `scrollbar-gutter: stable` 避免滚动条出现/消失时内容横向跳动。
- 滚动条配色：深色主题 `rgba(255,255,255,.13)`，浅色主题
  `body.light` 下单独覆盖成 `rgba(70,86,105,.24)`，都用 `content-box` 裁边做圆角细条。
- `<701px` 窄屏是整页滚动（body 没有 `overflow:hidden`），
  该断点下把 `#pluginView` 的 `overflow` 还原成 `visible`，否则会出现双滚动条。
- 新增 `body.modal-open { overflow:hidden }`：弹窗打开时锁背景滚动，
  免得在弹窗上滚滚轮把底下的插件列表带着跑。
  用重写 `HTMLDialogElement.prototype.showModal` + 监听 `close` 事件统一收口，
  覆盖 Esc / 关闭按钮 / `form method=dialog` 全部关闭路径，不用逐处改调用点。

## v2.6 — 2026-11-07 · 插件安装改为「仅用户显式触发」

**需求**：启动器不要自动安装插件。

**排查结论（基于 dsh 源码实证）**
- `dsh-app-boot` 全文无 `spawn` / `child_process`，启动链路不执行任何外部命令。
- `initProfile()` 只写 `package.json`（`dependencies: {}`）、`cordis.patch.yml` 模板、
  `pnpm-workspace.yaml` 三个空壳文件，注释写明 *Existing files are never touched*。
- `dsh-plugin-manager` 的 `installBundle()` 是 `@Remote` 方法，只有界面显式调用才执行；
  全文件无 `setInterval`，三个 `setTimeout` 均为进程树等待 / 管道超时 / 静默看门狗。
- `autoInstallPeers: false` 是 pnpm 的设置（禁止自动补 peer），与 dsh 行为无关。
- 结论：dsh 本身就没有自动装插件机制。

**启动器侧的保证**
- `GET /api/health` 新增 `pluginAutoInstall:false` 字段，可直接验证。
- 启动、重启、日志长轮询、环境检测等全部自动流程均不触碰 npm / pnpm。
- 后端文件头写明「不自动安装，只在用户显式请求时安装」的原则。

**新增三个写接口（全部只响应用户点击）**
- `POST /api/plugins/install` `{spec, profile, registry}`
  执行一次 `pnpm add <spec> --save-exact`；安装前备份 `package.json` 与
  `pnpm-lock.yaml`，**失败自动回滚**；pnpm 输出逐行转发到控制台日志。
- `POST /api/plugins/uninstall` `{name, profile}` —— `pnpm remove <name>`。
- `POST /api/plugins/toggle` `{name, enabled, profile}` —— 只改
  `cordis.patch.yml` 的 `disabled` 标记，不动 `node_modules`。

**patch 写入的正确性**（这部分踩了坑）
- 最初用「整行 contains 匹配」，`dsh-code-checker` 会误命中
  `dsh-code-checker-ext`，且顶层条目首行 `- name: xxx` 无法匹配；
  接口还会返回成功却没写任何内容。
- 改为**按条目块解析**：以行首 `- ` 切块，块间空行归属下一块，
  `bodyEnd()` 跳过尾部空行，匹配时先剥掉 `- ` 前缀再比 `name:` / `id:`。
- 已用 16 条断言 + 真实 `cordis.patch.yml` 三个条目验证：
  禁用、重复禁用幂等、启用后逐字还原、前缀不误伤、缺失条目自动新增。

**前端**
- 「解析安装」→ 确认弹窗 → 勾选「我已确认来源可信」→「确认安装」才发请求；
  安装中按钮显示「安装中…」，失败提示是否已回滚。
- 插件卡片开关不再置灰，改为真正调用 `/api/plugins/toggle`，
  失败自动拨回原状态。
- `writable` 字段改为真实检测 `Files.isWritable(profileDir)`。

**构建脚本**
- 修复本机 `/tmp` 只有 10M 导致 `javac` 报
  `insufficient memory ... Failed to reserve memory for metaspace` 的问题：
  把 `TMPDIR` 指到项目内 `.buildtmp/`，并限制 JVM 预留内存。
  这是**临时目录太小**，不是内存不足，普通桌面环境同样适用。
- `.buildtmp/` 加入 `.gitignore`。

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

