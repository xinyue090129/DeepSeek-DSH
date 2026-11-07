/* =====================================================================
   DSHA 控制中心 —— 前端
   界面：DSHA-Linux-UI 深色控制中心
   逻辑：全部接到 Java 后端 (127.0.0.1:7080 /api/*)
   ===================================================================== */
(() => {
  // 后端端口由 Tauri 在启动时选定（7080 起，被占用则顺延），
  // 通过 IPC 取真实值，不能写死。
  let API = "http://127.0.0.1:7080";
  const $ = (id) => document.getElementById(id);

  // 启动器偏好：默认手动启动 —— 打开启动器不会自作主张拉起 dsh
  const pref = (key, dflt) => {
    const v = localStorage.getItem(key);
    return v === null ? dflt : v === "1";
  };
  // v2.6 之前 autoStart 默认是开的，老用户本地可能已存 "1"。
  // 迁移一次：清掉旧值，让新默认（手动）生效；用户之后手动开启会重新写入。
  if (localStorage.getItem("dsha.prefMigrated") !== "v26") {
    localStorage.removeItem("dsha.autoStart");
    localStorage.setItem("dsha.prefMigrated", "v26");
  }
  let AUTO_START = pref("dsha.autoStart", false);
  let AUTO_OPEN = pref("dsha.autoOpen", true);

  async function resolveApi() {
    try {
      const invoke = window.__TAURI__?.core?.invoke
        || window.__TAURI_INTERNALS__?.invoke;
      if (invoke) {
        const port = await invoke("backend_port");
        if (port) API = "http://127.0.0.1:" + port;
      }
    } catch (_) { /* 回退默认端口 */ }
  }

  const tauriInvoke = () =>
    window.__TAURI__?.core?.invoke || window.__TAURI_INTERNALS__?.invoke || null;

  /**
   * 在应用内窗口打开工作台（不弹外部浏览器）。
   * 拿不到 Tauri IPC 时才退回后端 /api/open，保证 Web 调试也能用。
   */
  async function openWorkbench(url) {
    const invoke = tauriInvoke();
    if (invoke) {
      await invoke("open_workbench", { url });
      return { ok: true, inApp: true };
    }
    return api("/api/open", { url });
  }

  const st = {
    instance: null,
    instances: [],
    foreignDsh: [],
    since: 0,
    backendUp: false,
    busy: false,
    autoScroll: true,
    paused: false,
    localOffset: 0,   // 已渲染的绝对行号（含被后端裁掉的部分）
    ready: false
  };

  // ---------------------------------------------------------------- 工具
  let toastTimer;
  function toast(msg) {
    const el = $("toast");
    el.textContent = msg;
    el.classList.add("show");
    clearTimeout(toastTimer);
    toastTimer = setTimeout(() => el.classList.remove("show"), 2800);
  }

  async function api(path, body) {
    const opt = body === undefined
      ? { method: "GET" }
      : { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify(body) };
    const res = await fetch(API + path, opt);
    if (!res.ok) throw new Error("HTTP " + res.status);
    return res.json();
  }

  const esc = (s) => String(s ?? "").replace(/[&<>"]/g, (c) =>
    ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

  // ---------------------------------------------------------------- 日志
  function logClass(line) {
    if (/error|失败|异常|超时|exit code = [1-9]/i.test(line)) return "error";
    if (/占用|警告|停止|重启|ExperimentalWarning/i.test(line)) return "warn";
    if (/就绪|已启动|listening|running|token=/i.test(line)) return "success";
    if (/^\s*\$/.test(line.replace(/^\[[^\]]*\]\s*/, ""))) return "muted";
    return "info";
  }

  // 日志里出现的 token 打码，避免明文暴露在屏幕上
  const maskToken = (s) => String(s).replace(/(token=)[A-Za-z0-9_\-]{6,}/g, "$1••••••••");

  function appendLogs(lines) {
    if (!lines || !lines.length) return;
    const box = $("logWindow");
    const frag = document.createDocumentFragment();
    for (const raw of lines) {
      const d = document.createElement("div");
      d.className = "log-line " + logClass(raw);
      d.textContent = maskToken(raw);
      frag.appendChild(d);
    }
    box.appendChild(frag);
    while (box.childElementCount > 3000) box.removeChild(box.firstElementChild);
    if (st.autoScroll) box.scrollTop = box.scrollHeight;
  }

  function clearLogs() {
    $("logWindow").innerHTML = "";
    st.localOffset = 0;
  }

  function setLive(mode) {
    const el = $("logLive");
    if (!el) return;
    el.classList.toggle("paused", mode === "paused");
    el.classList.toggle("offline", mode === "offline");
    el.lastChild.nodeValue = mode === "paused" ? "已暂停"
      : mode === "offline" ? "重连中" : "实时";
  }

  // ---------------------------------------------------------------- 渲染
  const LABEL = { stopped: "未运行", starting: "启动中", running: "运行中", error: "异常" };
  const HEAD = {
    stopped: ["DSH 未运行", "随时开始你的下一项任务。"],
    starting: ["DSH 正在启动", "正在准备运行环境，请稍候。"],
    running: ["DSH 正在运行", "运行环境已就绪，可以开始使用。"],
    error: ["DSH 运行异常", "启动过程中遇到问题，请检查日志。"]
  };

  function setDots(s) {
    document.querySelectorAll(".status-dot").forEach((d) => {
      d.classList.remove("running", "starting", "error", "stopped");
      d.classList.add(s);
    });
  }

  function render() {
    const it = st.instance;
    const s = st.backendUp ? (it ? it.state : "stopped") : "error";
    const [title, sub] = HEAD[s] || HEAD.stopped;

    setDots(s);
    const foreign = st.foreignDsh || [];
    $("headerStatus").textContent = !st.backendUp ? "后端未连接"
      : foreign.length ? `已有外部 dsh (PID ${foreign[0].pid})`
      : LABEL[s];
    $("instanceTitle").textContent = title;
    $("instanceSubtitle").textContent = foreign.length
      ? `系统里已有 dsh 在运行（PID ${foreign[0].pid}），需先结束它才能启动。全局只允许一个 dsh。`
      : it ? `${it.name} · profile ${it.profile}` : "等待本地后端连接…";

    const running = s === "running";
    const port = it && it.actualPort > 0 ? it.actualPort : null;

    // ONLINE 标题旁显示真实端口
    const webEl = $("webState");
    const online = running && port;
    webEl.classList.toggle("online", !!online);
    webEl.textContent = online
      ? `ONLINE :${port}`
      : s === "starting" ? "STARTING" : s === "error" ? "ERROR" : "READY";

    $("portValue").textContent = port && (running || s === "starting") ? port : "—";
    $("runtimeValue").textContent = running ? "ACTIVE" : s === "starting" ? "BOOTING" : s === "error" ? "ERROR" : "READY";

    const hint = $("addressHint");
    if (hint) {
      hint.textContent = running && port
        ? `http://127.0.0.1:${port} · 点击进入工作台`
        : (it ? `端口 ${it.autoPort ? "自动分配" : it.port} · 启动后可访问` : "启动后可直接进入工作台");
    }

    const btn = $("startButton");
    if (running) {
      // 运行中 → 主按钮变成「进入工作台」
      btn.disabled = st.busy;
      btn.innerHTML = '<span class="play-icon">↗</span><span>进入工作台</span>';
    } else if (s === "starting") {
      btn.disabled = true;
      btn.innerHTML = '<span class="spinner"></span><span>正在启动…</span>';
    } else {
      btn.disabled = st.busy || !st.backendUp;
      btn.innerHTML = '<span class="play-icon">▶</span><span>启动 DSHA</span>';
    }

    $("stopButton").disabled = st.busy || s === "stopped";
    $("restartButton").disabled = st.busy || s === "stopped";
    $("safeStartButton").disabled = st.busy || running;

    // 日志行数放在标题右侧，长跑时也能看出流有没有在动
    const counter = $("logCount");
    if (counter) counter.textContent = st.localOffset ? `${st.localOffset} 行` : "";
  }

  // ---------------------------------------------------------------- 轮询
  const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

  /** 用一帧后端响应驱动界面状态；失败时兜底 undefined。 */
  function applyState(r) {
    const it = st.instance;
    if (!it || !r) return;
    const port = typeof r.port === "number" ? r.port : 0;
    const changed = r.state !== it.state || port !== it.actualPort || r.url !== it.url;
    if (changed) {
      it.state = r.state;
      it.actualPort = port;
      it.url = r.url;
      render();
    }
  }

  /**
   * 拉一次日志并渲染。
   * wait=true 时后端长轮询（最多挂 2 秒），有日志立即返回 —— 这就是「实时」。
   */
  async function pullLogs(wait) {
    const it = st.instance;
    if (!it || st.paused) return;
    const q = `/api/instances/logs?id=${encodeURIComponent(it.id)}&since=${st.since}` + (wait ? "&wait=1" : "");
    const r = await api(q);
    const lines = r.lines || [];
    if (lines.length) {
      appendLogs(lines);
    }
    // since 是对「后端当前缓冲」的游标，total 可能因环形裁剪而回落
    st.since = typeof r.total === "number" ? r.total : st.since;
    st.localOffset = (typeof r.dropped === "number" ? r.dropped : 0) + st.since;
    applyState(r);
    const counter = $("logCount");
    if (counter) counter.textContent = st.localOffset ? `${st.localOffset} 行` : "";
  }

  /** 常驻的日志循环：空闲时长轮询保状态新鲜，有日志时立刻刷。 */
  async function logLoop() {
    let backoff = 400;
    while (true) {
      if (st.paused) {
        await sleep(400);
        backoff = 400;
        continue;
      }
      if (!st.instance) {
        await sleep(600);
        continue;
      }
      try {
        await pullLogs(true);
        setLive("live");
        backoff = 400;
      } catch (_) {
        setLive("offline");
        await sleep(backoff);
        backoff = Math.min(backoff * 2, 5000);
      }
    }
  }

  // 状态轮询：日志循环只管日志，这里只管实例状态，两者互不拖累。
  async function poll() {
    if (!st.instance) return;
    try {
      const list = await api("/api/instances");
      const cur = (list || []).find((i) => i.id === st.instance.id);
      if (cur) {
        st.instance.state = cur.state;
        st.instance.actualPort = cur.actualPort;
        st.instance.url = cur.url;
        render();
      }
    } catch (_) { /* 下一轮重试 */ }
  }

  async function refresh() {
    const list = await api("/api/instances");
    st.instances = list;
    st.backendUp = true;
    const it = list.find((i) => st.instance && i.id === st.instance.id) || list[0];
    if (it) {
      if (!st.instance || st.instance.id !== it.id) {
        st.since = 0;
        st.localOffset = 0;
      }
      st.instance = it;
    }
    // 只把「跟我同一个 profile」的外部 dsh 当成冲突；不同 profile 允许并存
    try {
      const h = await api("/api/health");
      const myProf = (st.instance && st.instance.profile) || h.instanceProfile || "";
      st.foreignDsh = (Array.isArray(h.foreignDsh) ? h.foreignDsh : [])
        .filter((f) => !myProf || !f.profile || f.profile === myProf);
    } catch (_) { st.foreignDsh = []; }
    render();
  }

  async function boot() {
    try {
      await refresh();
      if (!st.ready) {
        st.ready = true;
        setInterval(poll, 700);
        poll();
        logLoop();          // 实时日志：长轮询，有输出立刻刷
        // 手动模式：打开启动器不自动拉起 dsh，由用户点「启动 DSHA」。
        // 只有用户显式在设置里勾了「打开启动器时自动启动」才会自动执行。
        if (AUTO_START) autoStart();
      }
    } catch (_) {
      st.backendUp = false;
      render();
      setTimeout(boot, 1500);
    }
  }

  // ------------------------------------------------- 可选：打开时自动启动
  // 默认关闭。只有用户在设置里勾选「打开启动器时自动启动」才会走到这里。
  // 且只在「本轮会话首次打开 + 实例处于停止状态」时触发一次。
  let autoStartTried = false;
  async function autoStart() {
    if (autoStartTried) return;
    autoStartTried = true;
    if (!AUTO_START) return;
    const it = st.instance;
    if (!it) return;
    if (it.state === "running" || it.state === "starting") return;
    st.autoStarted = true;
    await act(async () => {
      it.state = "starting";
      st.since = 0;
      st.localOffset = 0;
      render();
      try {
        await api("/api/instances/start", { id: it.id });
        toast("已启动 " + it.name + "，就绪后将进入工作台");
      } catch (e) {
        st.autoStarted = false;
        toast("启动失败：" + e.message);
        throw e;
      }
      await poll();
    });
    if (AUTO_OPEN) watchReadyToOpen();
  }

  // 跟踪自动启动的实例，一旦 running 且拿到带 token 的地址就自动开工作台
  let openWatcher = null;
  function watchReadyToOpen() {
    if (openWatcher) return;
    let attempts = 0;
    openWatcher = setInterval(async () => {
      attempts++;
      const it = st.instance;
      if (!it) return stopWatch();
      if (it.state === "error" || it.state === "stopped") return stopWatch();
      if (it.state === "running" && it.url) {
        stopWatch();
        try {
          const r = await openWorkbench(it.url);
          if (r.ok) toast("工作台已就绪，已在应用内打开");
        } catch (_) { /* 用户可以自己点「进入工作台」 */ }
      }
      if (attempts > 120) stopWatch();   // 约 2 分钟后放弃，避免空转
    }, 1000);
  }
  function stopWatch() {
    if (openWatcher) { clearInterval(openWatcher); openWatcher = null; }
  }

  // ---------------------------------------------------------------- 动作
  async function act(fn) {
    st.busy = true; render();
    try { await fn(); } catch (e) { toast("操作失败：" + e.message); }
    st.busy = false; render();
  }

  function currentId() {
    if (!st.instance) { toast("没有可用实例"); return null; }
    return st.instance.id;
  }

  // 主按钮：未运行→启动；运行中→进入工作台
  $("startButton").addEventListener("click", () => {
    const it = st.instance;
    if (!it) return toast("没有可用实例");

    if (it.state === "running") {
      const url = it.url;
      if (!url) return toast("尚未取得工作台地址");
      act(async () => {
        try {
          await openWorkbench(url);
          toast("已在应用内打开工作台");
        } catch (e) {
          toast("打开失败：" + (e.message || e));
        }
      });
      return;
    }

    act(async () => {
      const target = currentId(); if (!target) return;
      it.state = "starting";
      st.since = 0;
      st.localOffset = 0;
      render();
      await api("/api/instances/start", { id: target });
      toast("正在启动 " + it.name + "…");
      await poll();
    });
    if (AUTO_OPEN) watchReadyToOpen();
  });

  $("stopButton").addEventListener("click", () => {
    const target = currentId(); if (!target) return;
    act(async () => {
      stopWatch();
      await api("/api/instances/stop", { id: target });
      toast("正在停止…");
      // 等真的停下来再给结论，别只甩一句「已发送请求」
      const done = await waitForState(target, ["stopped", "error"], 12000);
      if (done) {
        await poll();
        toast(done === "stopped" ? "已停止" : "已停止（异常退出）");
      } else {
        toast("停止指令已发出，但进程仍未退出，请查看日志");
      }
    });
  });

  /** 轮询等待实例进入目标状态之一，超时返回 null。 */
  async function waitForState(id, targets, timeoutMs) {
    const deadline = Date.now() + timeoutMs;
    while (Date.now() < deadline) {
      try {
        const list = await api("/api/instances");
        const cur = (list || []).find((i) => i.id === id);
        if (cur && targets.includes(cur.state)) return cur.state;
      } catch (_) { /* 后端暂时不可用，继续等 */ }
      await new Promise((r) => setTimeout(r, 400));
    }
    return null;
  }

  $("restartButton").addEventListener("click", () => {
    const target = currentId(); if (!target) return;
    act(async () => {
      stopWatch();
      st.instance.state = "starting";
      st.since = 0;
      st.localOffset = 0;
      render();
      await api("/api/instances/restart", { id: target });
      toast("正在重启…");
      await poll();
      // 等它重新跑起来（或失败），再给结论
      const done = await waitForState(target, ["running", "error", "stopped"], 60000);
      await poll();
      if (done === "running") toast("已重启，可以进入工作台");
      else if (done) toast("重启未成功：" + LABEL[done]);
      else toast("重启耗时较长，仍在启动中，请查看日志");
    });
    if (AUTO_OPEN) watchReadyToOpen();
  });

  $("safeStartButton").addEventListener("click", () => {
    const target = currentId(); if (!target) return;
    act(async () => {
      stopWatch();
      st.instance.state = "starting";
      st.since = 0;
      st.localOffset = 0;
      render();
      await api("/api/start-safe", { id: target });
      toast("安全启动：已跳过全部补丁");
      await poll();
      const done = await waitForState(target, ["running", "error", "stopped"], 60000);
      await poll();
      if (done === "running") toast("安全启动成功");
      else if (done) toast("安全启动未成功：" + LABEL[done]);
      else toast("仍在启动中，请查看日志");
    });
    if (AUTO_OPEN) watchReadyToOpen();
  });

  $("webPanel").addEventListener("click", () => {
    const it = st.instance;
    if (!it || it.state !== "running") return toast("Web 服务尚未启动");
    toast("工作台端口 " + it.actualPort);
  });

  $("addressButton").addEventListener("click", async () => {
    const it = st.instance;
    if (!it || it.state !== "running") return toast("请先启动 DSHA");
    try {
      await openWorkbench(it.url);
      toast("已在应用内打开工作台");
    } catch (e) { toast("打开失败：" + (e.message || e)); }
  });

  $("environmentButton").addEventListener("click", async () => {
    toast("环境检测中…");
    try {
      const r = await api("/api/env");
      const bad = (r.items || []).filter((i) => !i.ok).map((i) => i.name);
      toast(bad.length ? "缺失：" + bad.join("、") : "环境完整，全部就绪");
    } catch (e) { toast("检测失败：" + e.message); }
  });

  $("recoveryButton").addEventListener("click", async () => {
    const it = st.instance;
    if (!it) return toast("没有可用实例");
    try {
      const r = await api("/api/profile?name=" + encodeURIComponent(it.profile));
      toast("补丁文件：" + r.path);
    } catch (e) { toast("读取失败：" + e.message); }
  });

  // ---------------------------------------------------------------- 其它
  $("aboutButton")?.addEventListener("click", async () => {
    const el = $("aboutVersion");
    let version = "Tauri + Java 后端";
    try {
      const h = await api("/api/health");
      if (h && h.version) version = `后端 v${h.version} · Tauri 2 + Java`;
    } catch (_) { /* 后端没起就显示默认文案 */ }
    if (el) el.textContent = version;
    $("aboutModal")?.showModal();
  });

  $("activityButton").addEventListener("click", () => {
    const box = $("logWindow");
    st.autoScroll = true;
    box.scrollTop = box.scrollHeight;
    toast("已定位到最新日志");
  });

  $("logsTabButton").addEventListener("click", () => {
    $("logWindow").scrollTop = 0;
    toast("已回到日志开头");
  });

  $("logPauseButton").addEventListener("click", () => {
    st.paused = !st.paused;
    $("logPauseButton").textContent = st.paused ? "继续" : "暂停";
    setLive(st.paused ? "paused" : "live");
    if (!st.paused) pullLogs(true).catch(() => {});
    toast(st.paused ? "日志已暂停" : "日志已继续");
  });

  $("logClearButton").addEventListener("click", () => {
    clearLogs();
    toast("已清屏（后端日志保留）");
  });

  // 用户手动往上翻时暂停自动滚动，滑回底部再恢复
  let scrollGuard = 0;
  $("logWindow").addEventListener("scroll", () => {
    clearTimeout(scrollGuard);
    // 程序性滚动也会触发 scroll，稍等一帧再判定
    scrollGuard = setTimeout(() => {
      const box = $("logWindow");
      st.autoScroll = box.scrollHeight - box.scrollTop - box.clientHeight < 24;
    }, 60);
  });

  $("themeButton").addEventListener("click", () => {
    document.body.classList.toggle("light");
    const light = document.body.classList.contains("light");
    localStorage.setItem("dsha.theme", light ? "light" : "dark");
    toast(light ? "已切换浅色主题" : "已切换深色主题");
  });

  // 「日志」标签页：把控制台日志区滚进来，不重复开窗口
  document.querySelectorAll('.tab[data-tab="logs"]').forEach((b) => b.addEventListener("click", () => {
    $("logWindow").scrollIntoView({ behavior: "smooth", block: "center" });
  }));

  document.querySelectorAll(".modal-close").forEach((b) =>
    b.addEventListener("click", () => b.closest("dialog").close()));

  // 弹窗打开时锁住背景滚动：否则在插件页滚轮会带着底下的列表一起跑。
  // 用 close 事件统一收尾，覆盖 Esc、关闭按钮、form method=dialog 所有关闭路径。
  document.querySelectorAll("dialog").forEach((d) => {
    d.addEventListener("close", () => {
      if (!document.querySelector("dialog[open]")) document.body.classList.remove("modal-open");
    });
  });
  const _showModal = HTMLDialogElement.prototype.showModal;
  HTMLDialogElement.prototype.showModal = function () {
    document.body.classList.add("modal-open");
    return _showModal.call(this);
  };

  document.querySelectorAll(".nav-item").forEach((btn) => btn.addEventListener("click", () => {
    const page = btn.dataset.page;
    if (page === "plugins") {
      showView("plugins");
      loadPlugins();
    } else if (page === "settings") {
      showView("console");
      $("settingsButton").click();
    } else {
      showView("console");
    }
  }));

  const addBtn = $("addInstanceButton");
  if (addBtn) addBtn.addEventListener("click", async () => {
    try {
      const list = await api("/api/instances/add", {});
      toast("已新增实例，共 " + list.length + " 个");
      await refresh();
    } catch (e) { toast("添加失败：" + e.message); }
  });


  // =====================================================================
  //  插件页
  //  数据来自后端 /api/plugins：真实读取 profile 的 package.json、
  //  node_modules 里的包清单和补丁里的 disabled 标记。
  //  红线：启动器只读插件清单，永不安装 / 升级 / 卸载 / 启停插件。
  //  所有写入口在后端都被显式拒绝（见 Backend 的 READ_GUARD），
  //  界面上也不提供任何能触发写入的按钮 —— 见 docs/CHANGELOG.md。
  // =====================================================================
  const plug = {
    items: [],
    available: [],
    loaded: false,
    loading: false,
    marketScope: "all",
    filter: "all",
    query: "",
    profile: "",
    writable: false
  };

  const SPEC_LABEL = {
    npm: "npm", github: "GitHub", archive: "压缩包",
    local: "本地路径", workspace: "workspace", profile: "profile"
  };

  function showView(which) {
    $("consoleView").hidden = which !== "console";
    $("pluginView").hidden = which !== "plugins";
    document.querySelectorAll(".nav-item").forEach((n) => {
      const p = n.dataset.page;
      n.classList.toggle("active",
        (which === "plugins" && p === "plugins") || (which === "console" && p === "overview"));
    });
  }

  function pluginLogo(name) {
    const base = name.split("/").pop() || name;
    return base.replace(/^dsh-/, "").slice(0, 1).toUpperCase() || "?";
  }

  function pluginCard(p) {
    const card = document.createElement("article");
    card.className = "plugin-card";

    const logo = document.createElement("div");
    logo.className = "plugin-logo" + (p.official ? " official" : "");
    logo.textContent = pluginLogo(p.name);

    const body = document.createElement("div");
    body.className = "plugin-body";

    const title = document.createElement("div");
    title.className = "plugin-title";
    const nm = document.createElement("span");
    nm.className = "plugin-name";
    nm.textContent = p.name;
    title.appendChild(nm);
    if (p.version) {
      const v = document.createElement("span");
      v.className = "plugin-version";
      v.textContent = "v" + p.version;
      title.appendChild(v);
    }

    const desc = document.createElement("div");
    desc.className = "plugin-desc";
    desc.textContent = p.description || "（该包未提供 description）";

    const tags = document.createElement("div");
    tags.className = "plugin-tags";
    const t1 = document.createElement("span");
    t1.className = "tag" + (p.official ? " official" : "");
    t1.textContent = p.official ? "官方内置" : "第三方";
    tags.appendChild(t1);

    if (p.bundle) {
      const t2 = document.createElement("span");
      t2.className = "tag";
      t2.textContent = "已装载";
      tags.appendChild(t2);
    }
    const t3 = document.createElement("span");
    t3.className = "tag " + (p.enabled ? "ok" : "off");
    t3.textContent = p.enabled ? "已启用" : "补丁禁用";
    tags.appendChild(t3);

    if (p.spec) {
      const t4 = document.createElement("span");
      t4.className = "tag";
      t4.textContent = (SPEC_LABEL[p.source] || p.source) + " · " + p.spec;
      tags.appendChild(t4);
    }

    body.append(title, desc, tags);

    const ctrl = document.createElement("div");
    ctrl.className = "plugin-ctrl";
    const toggle = document.createElement("input");
    toggle.type = "checkbox";
    toggle.className = "toggle";
    toggle.checked = !!p.enabled;
    toggle.title = p.enabled
      ? "已启用，点击可禁用（仅改 cordis.patch.yml，不卸载）"
      : "已禁用，点击可启用";
    // 只在用户拖动时写一次 cordis.patch.yml；任何自动流程都不会触发
    toggle.addEventListener("change", async () => {
      const want = toggle.checked;
      toggle.disabled = true;
      try {
        const r = await api("/api/plugins/toggle", {
          name: p.name, enabled: want,
          profile: plug.profile || st.instance?.profile || "web",
          id: st.instance?.id,
        });
        if (r.ok) {
          p.enabled = want;
          toast((want ? "已启用 " : "已禁用 ") + p.name + "，重启实例生效");
        } else {
          toggle.checked = !want;
          toast("切换失败" + (r.error ? "：" + r.error : ""));
        }
      } catch (err) {
        toggle.checked = !want;
        toast("切换失败：" + err.message);
      } finally {
        toggle.disabled = false;
      }
    });
    ctrl.appendChild(toggle);

    card.append(logo, body, ctrl);
    return card;
  }

  function renderPlugins() {
    const all = plug.items || [];
    const q = plug.query.trim().toLowerCase();

    // 市场页：按来源范围筛
    const marketList = $("marketList");
    const marketItems = all.filter((p) => {
      if (plug.marketScope === "external" && p.official) return false;
      if (plug.marketScope === "official" && !p.official) return false;
      return true;
    });
    marketList.replaceChildren();
    if (!marketItems.length) {
      const d = document.createElement("div");
      d.className = "plugin-empty";
      d.textContent = plug.loading ? "读取中…" : "当前 profile 没有装载插件";
      marketList.appendChild(d);
    } else {
      marketItems.forEach((p) => marketList.appendChild(pluginCard(p)));
    }
    $("installedCount").textContent = marketItems.length;

    // 官方可选扩展：随 dsh 分发但本 profile 没装载的能力包
    const avail = plug.available || [];
    const availList = $("availableList");
    availList.replaceChildren();
    if (!avail.length) {
      const d = document.createElement("div");
      d.className = "plugin-empty";
      d.textContent = plug.loading ? "读取中…" : "没有发现未装载的官方扩展";
      availList.appendChild(d);
    } else {
      avail.forEach((a) => {
        const card = document.createElement("article");
        card.className = "plugin-card";

        const logo = document.createElement("div");
        logo.className = "plugin-logo official";
        logo.textContent = (a.name.split("/").pop() || "?")
          .replace(/^dsh-/, "").replace(/-bundle$/, "").slice(0, 1).toUpperCase();

        const body = document.createElement("div");
        body.className = "plugin-body";
        const title = document.createElement("div");
        title.className = "plugin-title";
        const nm = document.createElement("span");
        nm.className = "plugin-name";
        nm.textContent = a.name;
        title.appendChild(nm);
        if (a.version) {
          const v = document.createElement("span");
          v.className = "plugin-version";
          v.textContent = "v" + a.version;
          title.appendChild(v);
        }
        const desc = document.createElement("div");
        desc.className = "plugin-desc";
        desc.textContent = a.description || "（该包未提供 description）";
        body.append(title, desc);

        const ctrl = document.createElement("div");
        ctrl.className = "plugin-ctrl";
        const badge = document.createElement("span");
        badge.className = "tag off";
        badge.textContent = "未装载";
        ctrl.appendChild(badge);

        card.append(logo, body, ctrl);
        availList.appendChild(card);
      });
    }
    $("availableCount").textContent = avail.length;

    // 管理页：搜索 + 状态筛选
    const listItems = all.filter((p) => {
      if (q && !(p.name + " " + (p.description || "")).toLowerCase().includes(q)) return false;
      if (plug.filter === "enabled") return p.enabled;
      if (plug.filter === "disabled") return !p.enabled;
      if (plug.filter === "official") return p.official;
      return true;
    });
    const list = $("pluginList");
    list.replaceChildren();
    listItems.forEach((p) => list.appendChild(pluginCard(p)));
    $("pluginEmpty").hidden = listItems.length !== 0;

    $("pluginTotal").textContent = all.length;
    $("pluginEnabledCount").textContent = all.filter((p) => p.enabled).length;
    $("allCount").textContent = all.length;

    $("manageFootnote").textContent = plug.profile
      ? `数据来源：profile「${plug.profile}」的 package.json 与 node_modules 实际清单，共 ${all.length} 项。启停写操作待后端接口开放。`
      : "插件清单来自当前 profile 的 package.json 与已安装依赖。";
  }

  async function loadPlugins(force) {
    if (plug.loading) return;
    if (plug.loaded && !force) return;
    plug.loading = true;
    $("manageFootnote").textContent = "正在读取插件清单…";
    try {
      const it = st.instance;
      const q = it ? `?id=${encodeURIComponent(it.id)}` : "";
      const r = await api("/api/plugins" + q);
      plug.items = Array.isArray(r.items) ? r.items : [];
      plug.profile = r.profile || "";
      plug.writable = !!r.writable;
      plug.available = Array.isArray(r.availableItems) ? r.availableItems : [];
      plug.loaded = true;
    } catch (e) {
      plug.items = [];
      $("manageFootnote").textContent = "读取失败：" + (e.message || e);
    } finally {
      plug.loading = false;
      renderPlugins();
    }
  }

  // ---- 市场 / 管理 切换 ----
  document.querySelectorAll(".segment").forEach((b) => b.addEventListener("click", () => {
    const v = b.dataset.view;
    document.querySelectorAll(".segment").forEach((x) => x.classList.toggle("active", x === b));
    $("marketPane").classList.toggle("active", v === "market");
    $("managePane").classList.toggle("active", v === "manage");
  }));

  // ---- 市场来源筛选 ----
  $("marketChips").addEventListener("click", (e) => {
    const b = e.target.closest(".chip");
    if (!b) return;
    plug.marketScope = b.dataset.scope;
    $("marketChips").querySelectorAll(".chip").forEach((x) => x.classList.toggle("active", x === b));
    renderPlugins();
  });

  // ---- 管理页筛选与搜索 ----
  document.querySelectorAll('#managePane .chip').forEach((b) => b.addEventListener("click", () => {
    plug.filter = b.dataset.filter;
    document.querySelectorAll('#managePane .chip').forEach((x) => x.classList.toggle("active", x === b));
    renderPlugins();
  }));
  $("pluginSearch").addEventListener("input", (e) => {
    plug.query = e.target.value;
    renderPlugins();
  });
  $("reloadPlugins").addEventListener("click", () => loadPlugins(true));

  // ---- 从链接安装：只在用户点「确认安装」后调用一次后端接口 ----
  // 启动器任何自动流程（启动 / 重启 / 轮询 / 环境检测）都不会调用该接口。
  const pluginSource = $("pluginSource");
  let pendingSpec = "";

  pluginSource.addEventListener("input", () => {
    const has = !!pluginSource.value.trim();
    $("parseButton").disabled = !has;
    $("clearSource").classList.toggle("show", has);
  });
  $("clearSource").addEventListener("click", () => {
    pluginSource.value = "";
    pluginSource.dispatchEvent(new Event("input"));
    pluginSource.focus();
  });

  $("parseButton").addEventListener("click", () => {
    const v = pluginSource.value.trim();
    if (!v) return;
    pendingSpec = v;
    $("candidateName").textContent = specBasename(v) || v;
    $("candidateSource").textContent = v;
    $("trustCheck").checked = false;
    $("confirmInstall").disabled = true;
    $("confirmInstall").textContent = "确认安装";
    $("installNote").textContent = "点「确认安装」后才会执行一次 pnpm add，失败会自动回滚 package.json。";
    $("installModal").showModal();
  });

  $("trustCheck").addEventListener("change", (e) => {
    $("confirmInstall").disabled = !e.target.checked;
  });

  $("installForm").addEventListener("submit", async (e) => {
    if (!e.submitter || e.submitter.id !== "confirmInstall") return;
    e.preventDefault();
    if (!pendingSpec) return;

    const btn = $("confirmInstall");
    btn.disabled = true;
    btn.textContent = "安装中…";
    $("installNote").textContent = "正在执行 pnpm add，详见控制台日志…";

    try {
      const r = await api("/api/plugins/install", {
        spec: pendingSpec,
        profile: plug.profile || st.instance?.profile || "web",
        registry: $("sourceSelect").value,
        id: st.instance?.id,
      });
      if (r.ok) {
        $("installNote").textContent = "安装成功，重启实例后生效。";
        toast("已安装 " + pendingSpec + "，重启实例生效");
        setTimeout(() => $("installModal").close(), 900);
        loadPlugins(true);
      } else {
        $("installNote").textContent = "安装失败" + (r.rolledBack ? "，已回滚 package.json" : "")
          + (r.error ? "：" + r.error : "") + "。详见控制台日志。";
        toast("安装失败，见控制台日志");
      }
    } catch (err) {
      $("installNote").textContent = "安装请求失败：" + err.message;
      toast("安装请求失败");
    } finally {
      btn.textContent = "确认安装";
      btn.disabled = !$("trustCheck").checked;
    }
  });

  $("sourceSelect").addEventListener("change", (e) => {
    const label = e.target.options[e.target.selectedIndex].textContent;
    toast("下载源：" + label);
  });  $("pasteButton").addEventListener("click", async () => {
    try {
      pluginSource.value = await navigator.clipboard.readText();
      pluginSource.dispatchEvent(new Event("input"));
      toast("已粘贴");
    } catch (_) {
      pluginSource.focus();
      toast("请用 Ctrl+V 粘贴");
    }
  });

  function specBasename(s) {
    return s.trim().replace(/\/+$/, "").split("/").pop().replace(/\.git$/, "");
  }

  $("parseButton").addEventListener("click", () => {
    const v = pluginSource.value.trim();
    if (!v) return;
    $("candidateName").textContent = specBasename(v) || v;
    $("candidateSource").textContent = v;
    $("installNote").textContent = "启动器只读：不会安装、不会写入 profile、不会调用 npm / pnpm。";
    $("installModal").showModal();
  });
  // 弹窗里唯一的动作是把来源复制出来，方便去 dsh 自带的插件管理界面粘贴
  $("installForm").addEventListener("submit", async (e) => {
    if (!e.submitter || e.submitter.id !== "copySpec") return;
    e.preventDefault();
    const spec = ($("candidateSource").textContent || "").trim();
    $("installModal").close();
    if (!spec) return;
    try {
      await navigator.clipboard.writeText(spec);
      toast("已复制来源，可粘贴到 dsh 插件管理界面");
    } catch (_) {
      toast("复制失败，请手动选中来源复制");
    }
  });

  $("sourceSelect").addEventListener("change", (e) => {
    const label = e.target.options[e.target.selectedIndex].textContent;
    toast("已记录下载源：" + label + "（启动器不下载任何内容）");
  });

  // ---- 导入 / 导出 / 社区：启动器只读，不参与插件增删 ----
  // 隐藏文件选择器本身仍保留在 DOM，但选择后不做任何写入。
  $("importButton").addEventListener("click", () => $("pluginFile").click());
  $("pluginFile").addEventListener("change", (e) => {
    const f = e.target.files[0];
    if (f) toast("已选择 " + f.name + "；启动器不导入插件，请用 dsh 自带的插件管理界面");
    e.target.value = "";
  });
  $("exportButton").addEventListener("click", () => {
    toast("启动器只读插件清单，不导出、不打包插件");
  });
  $("communityButton").addEventListener("click", () => {
    toast("社区插件源需在 dsh 自带插件管理界面浏览");
  });

  // ---------------------------------------------------------------- 设置
  // 设置里改的是「dsh 工作台端口」，保存后写进实例配置，下次启动生效。
  const portNote = $("portCheckNote");

  async function checkPort(port) {
    if (!portNote) return;
    const p = parseInt(port, 10);
    if (!p || p < 1024 || p > 65535) {
      portNote.textContent = p ? "端口范围应为 1024–65535" : "";
      return;
    }
    try {
      const r = await api("/api/port-check?port=" + p);
      portNote.textContent = r.inUse
        ? `端口 ${p} 已被占用，开启「自动回退」或换一个`
        : `端口 ${p} 可用`;
    } catch (_) { portNote.textContent = ""; }
  }

  $("settingsButton")?.addEventListener("click", () => {
    const it = st.instance;
    $("setPort").value = it ? it.port : 3080;
    $("setAutoPort").checked = it ? !!it.autoPort : true;
    $("setFallback").checked = it ? it.portFallback !== false : true;
    $("setAutoStart").checked = AUTO_START;
    $("setAutoOpen").checked = AUTO_OPEN;
    portNote.textContent = "";
    $("settingsModal").showModal();
    const p = it && !it.autoPort ? it.port : 3080;
    checkPort(p);
  });

  let portTimer;
  $("setPort")?.addEventListener("input", (e) => {
    clearTimeout(portTimer);
    portTimer = setTimeout(() => checkPort(e.target.value), 350);
  });

  $("settingsSaveBtn")?.addEventListener("click", async (e) => {
    e.preventDefault();
    const it = st.instance;
    if (!it) return;
    const autoPort = $("setAutoPort").checked;
    const port = parseInt($("setPort").value, 10);
    if (!autoPort && (!port || port < 1024 || port > 65535)) {
      return toast("端口需在 1024–65535 之间");
    }
    try {
      await api("/api/instances/update", {
        id: it.id,
        port: String(autoPort ? (port || 3080) : port),
        autoPort: String(autoPort),
        portFallback: String($("setFallback").checked)
      });
      // 启动器偏好存本地，不写进实例配置
      AUTO_START = $("setAutoStart").checked;
      AUTO_OPEN = $("setAutoOpen").checked;
      localStorage.setItem("dsha.autoStart", AUTO_START ? "1" : "0");
      localStorage.setItem("dsha.autoOpen", AUTO_OPEN ? "1" : "0");
      await refresh();
      $("settingsModal").close();
      toast(autoPort ? "已保存：端口由系统自动分配" : `已保存：工作台端口 ${port}（重启后生效）`);
    } catch (err) { toast("保存失败：" + err.message); }
  });

  const settingsForm = $("settingsModal")?.querySelector("form");
  if (settingsForm) settingsForm.addEventListener("submit", (e) => e.preventDefault());

  // 主按钮内 spinner
  const style = document.createElement("style");
  style.textContent =
    ".spinner{width:17px;height:17px;border:2px solid rgba(5,20,40,.25);border-top-color:#071629;border-radius:50%;animation:spin .8s linear infinite;display:inline-block}@keyframes spin{to{transform:rotate(360deg)}}";
  document.head.appendChild(style);

  if (localStorage.getItem("dsha.theme") === "light") document.body.classList.add("light");

  render();
  // 先问清后端端口，再开始连接
  resolveApi().then(boot);
})();