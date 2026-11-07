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
    if (st.since === 0) box.innerHTML = "";
    const frag = document.createDocumentFragment();
    for (const raw of lines) {
      const d = document.createElement("div");
      d.className = "log-line " + logClass(raw);
      d.textContent = maskToken(raw);
      frag.appendChild(d);
    }
    box.appendChild(frag);
    while (box.childElementCount > 1500) box.removeChild(box.firstElementChild);
    if (st.autoScroll) box.scrollTop = box.scrollHeight;
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
  }

  // ---------------------------------------------------------------- 轮询
  async function poll() {
    if (!st.instance) return;
    try {
      const r = await api(`/api/instances/logs?id=${encodeURIComponent(st.instance.id)}&since=${st.since}`);
      appendLogs(r.lines);
      st.since = r.total || 0;

      const changed = r.state !== st.instance.state
        || r.port !== st.instance.actualPort
        || r.url !== st.instance.url;
      if (changed) {
        st.instance.state = r.state;
        st.instance.actualPort = r.port;
        st.instance.url = r.url;
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
      if (!st.instance || st.instance.id !== it.id) st.since = 0;
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
      toast("已发送停止请求");
      await poll();
    });
  });

  $("restartButton").addEventListener("click", () => {
    const target = currentId(); if (!target) return;
    act(async () => {
      st.instance.state = "starting";
      st.since = 0;
      render();
      await api("/api/instances/restart", { id: target });
      toast("正在重启…");
    });
  });

  $("safeStartButton").addEventListener("click", () => {
    const target = currentId(); if (!target) return;
    act(async () => {
      st.instance.state = "starting";
      st.since = 0;
      render();
      await api("/api/start-safe", { id: target });
      toast("安全启动：已跳过全部补丁");
    });
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
  $("activityButton").addEventListener("click", () => {
    const box = $("logWindow");
    box.scrollTop = box.scrollHeight;
    toast("已定位到最新日志");
  });

  $("logsTabButton").addEventListener("click", () => {
    $("logWindow").scrollTop = 0;
    toast("已回到日志开头");
  });

  $("themeButton").addEventListener("click", () => {
    document.body.classList.toggle("light");
    const light = document.body.classList.contains("light");
    localStorage.setItem("dsha.theme", light ? "light" : "dark");
    toast(light ? "已切换浅色主题" : "已切换深色主题");
  });

  document.querySelectorAll(".modal-close").forEach((b) =>
    b.addEventListener("click", () => b.closest("dialog").close()));

  document.querySelectorAll(".nav-item").forEach((btn) => btn.addEventListener("click", () => {
    document.querySelectorAll(".nav-item").forEach((n) => n.classList.toggle("active", n === btn));
    const page = btn.dataset.page;
    if (page === "dashboard") { $("pageTitle").textContent = "仪表盘"; $("environmentButton").click(); }
    else if (page === "projects") { $("pageTitle").textContent = "项目"; toast("项目视图待接入"); }
    else { $("pageTitle").textContent = "DSHA 控制中心"; }
  }));

  const addBtn = $("addInstanceButton");
  if (addBtn) addBtn.addEventListener("click", async () => {
    try {
      const list = await api("/api/instances/add", {});
      toast("已新增实例，共 " + list.length + " 个");
      await refresh();
    } catch (e) { toast("添加失败：" + e.message); }
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