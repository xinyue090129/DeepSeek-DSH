package com.dshlauncher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.*;

/**
 * DSH Backend —— 独立业务后端进程。
 *
 * 设计：由 Tauri 前端拉起，或手动 `java -jar dsh-backend.jar`。
 * 只暴露 127.0.0.1 上的本地 HTTP 接口，供 GUI 调用；也可直接用 curl 调试。
 *
 * 安全红线：只启动、只检测、只聚焦；从不 kill 任何非本进程派生的子进程。
 *
 * 插件原则：**不自动安装，只在用户显式请求时安装**。
 *   - 启动、重启、轮询、环境检测等一切自动流程都不碰 npm / pnpm，
 *     不写 profile 的 package.json，不发起任何插件下载；
 *   - /api/plugins 只读磁盘上的既有清单，供界面展示；
 *   - 只有 POST /api/plugins/install 才会真正执行安装，且必须由用户在界面上
 *     点击触发（前端不做任何自动调用），失败会回滚 package.json。
 *
 * 接口：
 *   GET  /api/health                健康检查 + 版本
 *   GET  /api/instances             实例列表
 *   POST /api/instances/add         新增
 *   POST /api/instances/update      更新
 *   POST /api/instances/delete      删除
 *   POST /api/instances/start       启动
 *   POST /api/instances/stop        停止
 *   POST /api/instances/restart     重启
 *   POST /api/start-safe            安全启动（跳过全部补丁）
 *   GET  /api/instances/logs?id=    取日志（?since=序号）
 *   GET  /api/profile?name=         读 profile 补丁
 *   POST /api/profile               写 profile 补丁
 *   GET  /api/env                   环境检测
 *   GET  /api/update                版本检查
 *   POST /api/open                  用 Edge 打开
 */
public class Backend {

    // ---------------- 常量 ----------------
    // 启动器自己的路径一律跟真实家目录走。
    // DSH_HOME 是 dsh 自己的数据目录覆盖项（本机设为 ~/.dsh），
    // 若拿它当 HOME，实例配置会跑到 ~/.dsh/.config 下，从终端里启动就读到另一份空配置。
    static final String HOME = System.getProperty("user.home");
    static final String CFG_HOME = HOME;
    static final Path CFG_DIR = Paths.get(CFG_HOME, ".config", "dsh-launcher");
    /** 旧版把配置写在 $DSH_HOME/.config 下；首次启动时迁回标准位置。 */
    static final Path LEGACY_CFG_FILE =
            Paths.get(System.getenv().getOrDefault("DSH_HOME", Paths.get(HOME, ".dsh").toString()),
                    ".config", "dsh-launcher", "instances.json");
    static final Path CFG_FILE = CFG_DIR.resolve("instances.json");
    /** 会话记录目录：每个实例一份，含 launch token，权限 0600。 */
    static final Path SESS_DIR = CFG_DIR.resolve("sessions");
    static final String DSH_BIN = resolveDsh();
    static final String EDGE_BIN = "/opt/microsoft/msedge/msedge";
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final int DEFAULT_PORT = 7080;
    static final int MAX_LOG_LINES = 4000;
    /** 启动时生成的一次性访问令牌，前端首次请求 /api/health 时获取，随后每次请求携带 X-DSH-Token 头。 */
    static final String STARTUP_TOKEN = java.util.UUID.randomUUID().toString().replace("-", "");
    /** 已验证过 token 的前端来源（CORS 白名单，避免 * 的过宽松）。 */
    static final Set<String> ALLOWED_ORIGINS = new java.util.HashSet<>(java.util.Arrays.asList(
            "tauri://localhost",
            "http://tauri.localhost",
            "https://tauri.localhost",
            "http://127.0.0.1",
            "http://127.0.0.1:7080",
            "http://127.0.0.1:7081",
            "http://127.0.0.1:7082",
            "http://127.0.0.1:7083",
            "http://127.0.0.1:7084",
            "http://127.0.0.1:7085",
            "http://127.0.0.1:7086",
            "http://127.0.0.1:7087",
            "http://127.0.0.1:7088",
            "http://127.0.0.1:7089"
    ));

    /** Tauri WebView 的 origin 随平台不同（tauri://、http(s)://tauri.localhost），
     *  另外 file:// 预览的 origin 是 null —— 一并放行，避免前端连不上本地后端。 */
    static boolean isAllowedOrigin(String origin) {
        if (origin == null) return false;
        if (ALLOWED_ORIGINS.contains(origin)) return true;
        if (origin.startsWith("tauri://")) return true;
        if (origin.equals("http://tauri.localhost") || origin.equals("https://tauri.localhost")) return true;
        return origin.equals("null");
    }
    /** 前端异常上报速率限制：同一来源最多 50 条，防日志被刷爆。 */
    static final int MAX_FRONTEND_ERRORS = 50;
    static int frontendErrorCount = 0;

    // ---------------- 模型 ----------------
    static class Instance {
        String id = UUID.randomUUID().toString().substring(0, 8);
        String name = "local-deepseek";
        String mode = "npm";          // npm | local
        String cwd = HOME;
        int port = 3080;
        boolean autoPort = true;
        boolean portFallback = true;   // 固定端口被占用时，自动改用随机端口
        String profile = "web";
        String extraArgs = "--no-open";
        String envText = "";
        String patches = "";

        // 运行时
        transient String state = "stopped";   // stopped | starting | running | error
        transient int actualPort = -1;
        transient String workbenchUrl = "";    // 带 token 的完整工作台地址
        transient Process proc;
        transient final List<String> logs = Collections.synchronizedList(new ArrayList<>());
        transient int logDropped;              // 被裁掉的行数，用于校正前端下标
        transient StartupTrace trace;          // 结构化启动时间线（阶段 / 计时 / 问题）
        // 会话记录与接管状态（不持久化，持久化的是 SESS_DIR 下的 Session 文件）
        transient Session session;             // 本次接回/记录的原实例
        transient String foreignPid = "";      // conflict 时被拦下的外部 PID
        transient String foreignCmd = "";
        transient int foreignPort = -1;
        transient long foreignStart;           // 外部实例出生时间，接管前二次核验
        transient String unknownReason = "";   // unknown 状态的稳定原因码

        int effectivePort() { return actualPort > 0 ? actualPort : port; }

        /** 工作台地址：优先用带 token 的完整 URL，否则退回纯端口地址 */
        String url() {
            if (workbenchUrl != null && !workbenchUrl.isEmpty()) return workbenchUrl;
            return "http://127.0.0.1:" + effectivePort();
        }
    }

    static final List<Instance> inst = Collections.synchronizedList(new ArrayList<>());

    // =====================================================================
    public static void main(String[] args) throws IOException {
        int port = DEFAULT_PORT;
        for (int i = 0; i < args.length; i++) {
            if (args[i].equals("--port") && i + 1 < args.length) {
                try { port = Integer.parseInt(args[++i]); } catch (Exception ignored) {}
            }
        }
        loadConfig();
        if (inst.isEmpty()) inst.add(defaultInstance());
        // 宿主（后端）重建时先接回上次仍在运行的实例：只核验身份与鉴权，不新建进程、不重放业务。
        for (Instance i : inst) restoreSessionOnBoot(i);

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api", new Router());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("[dsh-backend] listening on http://127.0.0.1:" + port);
        System.out.println("[dsh-backend] config: " + CFG_FILE);
        System.out.println("[dsh-backend] instances loaded: " + inst.size());
        for (Instance i : inst) {
            System.out.println("[dsh-backend]   " + i.id + " profile=" + i.profile + " state=" + i.state);
        }
        System.out.flush();

        // 周期复核接回的实例：只读核验 + 鉴权，不重放业务。
        Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "dsha-session-recheck");
            t.setDaemon(true);
            return t;
        }).scheduleWithFixedDelay(() -> {
            try { recheckSessions(); } catch (Throwable ignored) {}
        }, 5, 5, TimeUnit.SECONDS);

        // 优雅退出：关闭启动器时必须把本后端派生的 dsh 进程树一起带走，
        // 不留孤儿 node 进程。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<Instance> snapshot;
            synchronized (inst) { snapshot = new ArrayList<>(inst); }
            for (Instance i : snapshot) {
                Process p = i.proc;
                if (p != null) killTree(p);
            }
            // 接回的实例没有 Process 句柄，但同样是“本启动器在管”的 dsh：
            // 关闭启动器仍应带走它，否则会留下孤儿进程。按记录身份停，不按端口/名字。
            for (Instance i : snapshot) {
                Session s = i.session;
                if (s == null || i.proc != null) continue;
                if (observe(s) != Observation.LIVE) continue;
                try {
                    signalProcess(s.pid, "TERM");
                    long deadline = System.currentTimeMillis() + 4000;
                    while (System.currentTimeMillis() < deadline && observe(s) != Observation.EXITED) {
                        Thread.sleep(100);
                    }
                    if (observe(s) != Observation.EXITED) signalProcess(s.pid, "KILL");
                } catch (Exception ignored) {}
            }
        }, "dsh-shutdown"));
    }

    /**
     * 结束一个进程及其全部子孙进程。
     *
     * 顺序很重要：先把子孙快照下来（父进程一死，descendants() 就查不到了），
     * 再发 SIGTERM 让 dsh 有机会清理，等不到就 SIGKILL，
     * 最后逐个补刀并反复复查，防止 dsh 在退出过程中又派生了新进程。
     */
    static void killTree(Process root) {
        List<ProcessHandle> kids = new ArrayList<>();
        try { root.descendants().forEach(kids::add); } catch (Exception ignored) {}

        long pid = root.pid();

        // 先整组发 TERM（dsh 由 setsid 启动，自成一组，组号 = 其 PID）
        killGroup(pid, "TERM");

        try { root.destroy(); } catch (Exception ignored) {}
        for (ProcessHandle h : kids) { try { h.destroy(); } catch (Exception ignored) {} }

        try { root.waitFor(2, TimeUnit.SECONDS); } catch (InterruptedException ignored) {}

        // 复查：等待期间可能又有新派生
        try { root.descendants().forEach(h -> { if (!kids.contains(h)) kids.add(h); }); } catch (Exception ignored) {}

        // 整组 KILL 兜底，再逐个补刀
        killGroup(pid, "KILL");

        if (root.isAlive()) { try { root.destroyForcibly(); } catch (Exception ignored) {} }
        for (ProcessHandle h : kids) {
            if (h.isAlive()) { try { h.destroyForcibly(); } catch (Exception ignored) {} }
        }

        try { root.waitFor(1500, TimeUnit.MILLISECONDS); } catch (InterruptedException ignored) {}

        for (ProcessHandle h : kids) {
            if (h.isAlive()) { try { h.destroyForcibly(); } catch (Exception ignored) {} }
        }
    }

    /** 向整个进程组发信号；组号等于 setsid 后的首进程 PID。 */
    static void killGroup(long pgid, String signal) {
        try {
            new ProcessBuilder("kill", "-" + signal, "--", "-" + pgid)
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    static Instance defaultInstance() {
        Instance i = new Instance();
        i.name = "local-deepseek";
        i.port = 3080;
        i.extraArgs = "--no-open";
        return i;
    }

    // =====================================================================
    //                              路由
    // =====================================================================
    static class Router implements HttpHandler {
        @Override public void handle(HttpExchange ex) throws IOException {
            String path = ex.getRequestURI().getPath();
            String method = ex.getRequestMethod();
            Map<String, String> q = query(ex.getRequestURI().getRawQuery());
            String body = readBody(ex);

            try {
                // CORS 预检：直接放行
                if ("OPTIONS".equals(method)) {
                    cors(ex);
                    ex.sendResponseHeaders(204, -1);
                    ex.close();
                    return;
                }

                // 所有路由都需要 X-DSH-Token 头，/api/health 除外（前端先拿 token）
                if (!checkToken(ex)) return;

                switch (path) {
                    // 插件安装 / 卸载：只响应用户的显式请求，绝不由启动流程自动调用。
                    // 参数：{"spec":"包名或仓库","profile":"web","registry":"npm|mirror","enabled":true}
                    case "/api/plugins/install": {
                        Map<String, String> d = flatJson(body);
                        String spec = d.getOrDefault("spec", "").trim();
                        if (spec.isEmpty()) {
                            ok(ex, "{\"ok\":false,\"error\":\"spec 不能为空\"}");
                            return;
                        }
                        Instance t = d.containsKey("id") ? find(d.get("id"))
                                : (inst.isEmpty() ? null : inst.get(0));
                        String prof = d.containsKey("profile") && !d.get("profile").isBlank()
                                ? d.get("profile")
                                : (t != null ? t.profile : "web");
                        String registry = d.getOrDefault("registry", "auto");
                        ok(ex, pluginInstall(spec, prof, registry, t));
                        return;
                    }

                    case "/api/plugins/uninstall": {
                        Map<String, String> d = flatJson(body);
                        String name = d.getOrDefault("name", "").trim();
                        if (name.isEmpty()) {
                            ok(ex, "{\"ok\":false,\"error\":\"name 不能为空\"}");
                            return;
                        }
                        Instance t = d.containsKey("id") ? find(d.get("id"))
                                : (inst.isEmpty() ? null : inst.get(0));
                        String prof = d.containsKey("profile") && !d.get("profile").isBlank()
                                ? d.get("profile")
                                : (t != null ? t.profile : "web");
                        ok(ex, pluginUninstall(name, prof, t));
                        return;
                    }

                    // 启停：只改 cordis.patch.yml 的 disabled 标记，不动 node_modules
                    case "/api/plugins/toggle": {
                        Map<String, String> d = flatJson(body);
                        String name = d.getOrDefault("name", "").trim();
                        boolean enabled = "true".equals(d.getOrDefault("enabled", "false"));
                        if (name.isEmpty()) {
                            ok(ex, "{\"ok\":false,\"error\":\"name 不能为空\"}");
                            return;
                        }
                        Instance t = d.containsKey("id") ? find(d.get("id"))
                                : (inst.isEmpty() ? null : inst.get(0));
                        String prof = d.containsKey("profile") && !d.get("profile").isBlank()
                                ? d.get("profile")
                                : (t != null ? t.profile : "web");
                        ok(ex, pluginToggle(name, prof, enabled, t));
                        return;
                    }

                    case "/api/health": {
                        List<String[]> foreign = findForeignDsh(null);
                        StringBuilder fb = new StringBuilder("[");
                        for (int k = 0; k < foreign.size(); k++) {
                            if (k > 0) fb.append(',');
                            int fpid = 0;
                            try { fpid = Integer.parseInt(foreign.get(k)[0]); } catch (Exception ignored) {}
                            int fport = fpid > 0 ? probeForeignPort(fpid) : -1;
                            fb.append("{\"pid\":").append(foreign.get(k)[0])
                              .append(",\"profile\":\"").append(esc(profileOf(foreign.get(k)[1]))).append("\"")
                              .append(",\"port\":").append(fport)
                              .append(",\"cmd\":\"").append(esc(foreign.get(k)[1])).append("\"}");
                        }
                        fb.append(']');
                        String healthBody = "{\"ok\":true,\"name\":\"dsh-backend\",\"version\":\"1.0.0\","
                                + "\"instances\":" + inst.size()
                                + ",\"pluginAutoInstall\":false"
                                + ",\"instanceProfile\":\"" + esc(inst.isEmpty() ? "" : inst.get(0).profile) + "\""
                                + ",\"foreignDsh\":" + fb + "}";
                        // 健康检查响应头带上一次性启动令牌，前端首次访问后缓存并用于后续请求
                        ex.getResponseHeaders().add("X-DSH-Token", STARTUP_TOKEN);
                        ok(ex, healthBody);
                        return;
                    }

                    case "/api/instances":
                        ok(ex, instancesJson());
                        return;

                    case "/api/instances/add": {
                        int p = 3081;
                        while (containsPort(p)) p++;
                        Instance n = defaultInstance();
                        n.name = "dsh-" + (inst.size() + 1);
                        n.port = p;
                        n.autoPort = true;
                        inst.add(n);
                        saveConfig();
                        ok(ex, instancesJson());
                        return;
                    }

                    case "/api/instances/update": {
                        Map<String, String> d = flatJson(body);
                        synchronized (inst) {
                            for (Instance i : inst) {
                                if (i.id.equals(d.get("id"))) {
                                    if (d.containsKey("name")) i.name = d.get("name");
                                    if (d.containsKey("mode")) i.mode = d.get("mode");
                                    if (d.containsKey("cwd")) i.cwd = d.get("cwd");
                                    if (d.containsKey("port")) {
                                        try { i.port = Integer.parseInt(d.get("port")); } catch (Exception ignored) {}
                                    }
                                    if (d.containsKey("autoPort")) i.autoPort = Boolean.parseBoolean(d.get("autoPort"));
                                    if (d.containsKey("portFallback")) i.portFallback = Boolean.parseBoolean(d.get("portFallback"));
                                    if (d.containsKey("profile")) i.profile = d.get("profile");
                                    if (d.containsKey("extraArgs")) i.extraArgs = d.get("extraArgs");
                                    if (d.containsKey("envText")) i.envText = d.get("envText");
                                    if (d.containsKey("patches")) i.patches = d.get("patches");
                                    break;
                                }
                            }
                        }
                        saveConfig();
                        ok(ex, instancesJson());
                        return;
                    }

                    case "/api/instances/delete": {
                        String id = jsonVal(body, "id");
                        Instance target = find(id);
                        if (target != null) {
                            if (target.proc != null && target.proc.isAlive()) {
                                target.state = "stopped";
                                target.proc.destroy();
                                try { target.proc.waitFor(3, TimeUnit.SECONDS); } catch (Exception ignored) {}
                                if (target.proc.isAlive()) target.proc.destroyForcibly();
                            }
                            inst.remove(target);
                            saveConfig();
                        }
                        ok(ex, instancesJson());
                        return;
                    }

                    case "/api/instances/start": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t != null) startInstance(t);
                        ok(ex, instanceJson(t));
                        return;
                    }

                    // 安全启动：忽略实例上配置的全部补丁，仅用基础 profile 拉起
                    case "/api/start-safe": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t != null) {
                            String keep = t.patches;
                            t.patches = "";
                            addLog(t, "安全启动：跳过实例配置里的全部补丁");
                            startInstance(t);
                            t.patches = keep;
                        }
                        ok(ex, instanceJson(t));
                        return;
                    }

                    case "/api/instances/stop": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t != null) stopInstance(t);
                        ok(ex, instanceJson(t));
                        return;
                    }

                    case "/api/instances/restart": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t != null) {
                            stopInstance(t);
                            final Instance tt = t;
                            new Thread(() -> {
                                try { Thread.sleep(1200); } catch (InterruptedException ignored) {}
                                startInstance(tt);
                            }).start();
                        }
                        ok(ex, instanceJson(t));
                        return;
                    }

                    case "/api/instances/logs": {
                        Instance t = find(q.get("id"));
                        long since = 0;
                        try { since = Long.parseLong(q.getOrDefault("since", "0")); } catch (Exception ignored) {}
                        boolean wait = "1".equals(q.get("wait"));
                        if (t == null) {
                            ok(ex, "{\"lines\":[],\"total\":0,\"offset\":0,\"entries\":[]}");
                            return;
                        }

                        StartupTrace trace = traceOf(t);
                        if (wait) trace.awaitRevision(since, System.currentTimeMillis() + 2000L);
                        StartupTrace.Snapshot s = trace.snapshot(System.currentTimeMillis());
                        StartupLogDigest.Outcome outcome = outcomeOf(t);
                        StartupLogDigest.Result digest =
                                StartupLogDigest.digest(s.entries, outcome,
                                        StartupLogDigest.endMs(s, outcome));

                        StringBuilder sb = new StringBuilder("{\"lines\":[");
                        int total = s.entries.size();
                        // since 是 revision 游标，不是行下标：拿不到增量就退回全量，
                        // 保证前端永远有新内容可渲染（环形裁剪后下标会错位）。
                        int from = 0;
                        if (s.revision > since && since > 0) {
                            long delta = s.revision - since;
                            from = (int) Math.max(0, total - delta);
                        }
                        for (int k = from; k < total; k++) {
                            if (k > from) sb.append(',');
                            sb.append('"').append(esc(s.entries.get(k).line)).append('"');
                        }
                        sb.append("],\"total\":").append(total)
                          .append(",\"offset\":").append(from)
                          .append(",\"dropped\":").append(s.dropped)
                          .append(",\"revision\":").append(s.revision)
                          .append(",\"entryLines\":[");
                        for (int k = from; k < total; k++) {
                            StartupTrace.Entry e = s.entries.get(k);
                            if (k > from) sb.append(',');
                            sb.append('"').append(esc(e.line)).append('"');
                        }
                        sb.append("],\"stages\":").append(stagesJson(digest))
                          .append(",\"outcome\":\"").append(outcome.name()).append("\"")
                          .append(",\"issueCount\":").append(digest.issueCount)
                          .append(",\"stage\":\"").append(esc(s.stage)).append("\"")
                          .append(",\"elapsedMs\":").append(s.elapsedMs)
                          .append(",\"state\":\"").append(t.state).append("\"")
                          .append(",\"port\":").append(t.effectivePort())
                          .append(",\"url\":\"").append(esc(t.url())).append("\"}");
                        ok(ex, sb.toString());
                        return;
                    }

                    case "/api/profile": {
                        if ("GET".equals(method)) {
                            String prof = q.getOrDefault("name", "web");
                            Path f = Paths.get(HOME, ".dsh", "profiles", prof, "cordis.patch.yml");
                            String content = Files.exists(f) ? Files.readString(f, StandardCharsets.UTF_8)
                                    : "# 文件不存在：" + f + "\n[]\n";
                            ok(ex, "{\"path\":\"" + esc(f.toString()) + "\",\"content\":\"" + esc(content) + "\"}");
                        } else {
                            Map<String, String> d = flatJson(body);
                            String prof = d.getOrDefault("profile", "web");
                            String content = d.getOrDefault("content", "");
                            Path f = Paths.get(HOME, ".dsh", "profiles", prof, "cordis.patch.yml");
                            Files.createDirectories(f.getParent());
                            if (Files.exists(f)) {
                                Path bak = f.resolveSibling("cordis.patch.yml.bak-" + System.currentTimeMillis() / 1000);
                                Files.copy(f, bak, StandardCopyOption.REPLACE_EXISTING);
                            }
                            Files.writeString(f, content, StandardCharsets.UTF_8);
                            ok(ex, "{\"ok\":true,\"path\":\"" + esc(f.toString()) + "\"}");
                        }
                        return;
                    }

                    case "/api/env":
                        ok(ex, envJson());
                        return;

                    // 插件清单：只读。数据来自当前 profile 的 package.json
                    // （dsh.profile.bundles + dependencies）与 node_modules 里的真实
                    // package.json，不猜测、不伪造。安装/启停等写操作不在本接口范围。
                    case "/api/plugins": {
                        String id = q.get("id");
                        Instance t = id != null && !id.isBlank() ? find(id)
                                : (inst.isEmpty() ? null : inst.get(0));
                        String prof = t != null ? t.profile
                                : q.getOrDefault("profile", "web");
                        ok(ex, pluginsJson(prof));
                        return;
                    }

                    case "/api/update":
                        ok(ex, updateJson());
                        return;

                    // 打开工作台：优先系统默认浏览器，失败回退 Edge 应用模式。
                    // url 缺省时取当前实例的运行地址（含 token）。
                    case "/api/open": {
                        String url = jsonVal(body, "url");
                        if (url == null || url.isBlank()) {
                            String id = jsonVal(body, "id");
                            Instance t = id != null ? find(id) : (inst.isEmpty() ? null : inst.get(0));
                            url = t != null ? t.url() : "http://127.0.0.1:3080";
                        }
                        boolean launched = false;
                        for (String[] cmd : new String[][]{
                                {"xdg-open", url},
                                {EDGE_BIN, "--app=" + url},
                                {"gio", "open", url}}) {
                            try {
                                new ProcessBuilder(cmd).redirectErrorStream(true).start();
                                launched = true;
                                break;
                            } catch (Exception ignored) {}
                        }
                        ok(ex, "{\"ok\":" + launched + ",\"url\":\"" + esc(url) + "\"}");
                        return;
                    }

                    // 端口可用性预检：?port=3080
                    case "/api/port-check": {
                        int p = 0;
                        try { p = Integer.parseInt(q.getOrDefault("port", "")); } catch (Exception ignored) {}
                        boolean inUse = p > 0 && isPortOpen(p);
                        ok(ex, "{\"port\":" + p + ",\"inUse\":" + inUse
                                + ",\"ok\":" + (p > 1023 && p < 65536 && !inUse) + "}");
                        return;
                    }

                    // 会话记录与接管：只做核验、接回、退休，不启动/停止业务。
                    case "/api/instances/session": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t == null) { ok(ex, "{\"ok\":false,\"error\":\"找不到实例\"}"); return; }
                        Session s = t.session != null ? t.session : readSession(t);
                        if (s == null) { ok(ex, "{\"ok\":true,\"session\":null}"); return; }
                        Observation o = observe(s);
                        ok(ex, "{\"ok\":true,\"session\":{\"pid\":" + s.pid
                                + ",\"startTime\":" + s.startTime
                                + ",\"port\":" + s.port
                                + ",\"profile\":\"" + esc(s.profile) + "\""
                                + ",\"hasToken\":" + (s.authUrl != null && s.authUrl.contains("token="))
                                + ",\"generation\":" + s.generation
                                + ",\"observation\":\"" + o.name() + "\""
                                + ",\"cmdline\":\"" + esc(s.cmdline) + "\"}}");
                        return;
                    }

                    // 重新核验：只读观察 + 真实鉴权，成功才恢复 adopted。
                    case "/api/instances/recheck": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t == null) { ok(ex, "{\"ok\":false,\"error\":\"找不到实例\"}"); return; }
                        Session s = t.session != null ? t.session : readSession(t);
                        if (s == null) {
                            ok(ex, "{\"ok\":true,\"result\":\"NONE\",\"state\":\"" + t.state + "\"}");
                            return;
                        }
                        Observation o = observe(s);
                        String result;
                        if (o == Observation.EXITED) {
                            clearSession(t, s);
                            t.session = null;
                            t.state = "stopped";
                            t.actualPort = -1;
                            t.workbenchUrl = "";
                            t.unknownReason = "";
                            result = "EXITED";
                            addLog(t, "重新核验：原 dsh（PID " + s.pid + "）已确认退出。");
                        } else if (o == Observation.UNKNOWN) {
                            t.session = s;
                            t.state = "unknown";
                            t.unknownReason = "IDENTITY_UNKNOWN";
                            result = "UNKNOWN";
                        } else if (authenticate(s.authUrl)) {
                            adopt(t, s);
                            result = "RESTORED";
                            addLog(t, "重新核验通过，已接回 dsh（PID " + s.pid + "）。");
                        } else {
                            t.session = s;
                            t.state = "unknown";
                            t.unknownReason = "AUTH_UNAVAILABLE";
                            result = "AUTH_FAILED";
                        }
                        ok(ex, "{\"ok\":true,\"result\":\"" + result + "\",\"state\":\"" + t.state + "\"}");
                        return;
                    }

                    // 确认已退出：用户明确声明原实例已不在，才允许退休记录。
                    case "/api/instances/forget": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t == null) { ok(ex, "{\"ok\":false,\"error\":\"找不到实例\"}"); return; }
                        Session s = t.session != null ? t.session : readSession(t);
                        if (s != null && observe(s) == Observation.LIVE) {
                            ok(ex, "{\"ok\":false,\"error\":\"进程仍存活，不能退休记录；请先停止它\"}");
                            return;
                        }
                        clearSession(t, s);
                        t.session = null;
                        t.state = "stopped";
                        t.actualPort = -1;
                        t.workbenchUrl = "";
                        t.unknownReason = "";
                        addLog(t, "已确认退出，会话记录已退休。");
                        ok(ex, "{\"ok\":true,\"state\":\"" + t.state + "\"}");
                        return;
                    }

                    // 只结束外部实例（不启动）：二次核验 PID 出生身份后才发信号。
                    case "/api/instances/kill-foreign": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t == null) { ok(ex, "{\"ok\":false,\"error\":\"找不到实例\"}"); return; }
                        String pidStr = t.foreignPid;
                        if (pidStr == null || pidStr.isBlank()) {
                            List<String[]> foreign = findForeignDsh(t.profile);
                            if (!foreign.isEmpty()) pidStr = foreign.get(0)[0];
                        }
                        int fpid;
                        try { fpid = Integer.parseInt(pidStr); } catch (Exception e) { fpid = -1; }
                        if (fpid <= 1) { ok(ex, "{\"ok\":false,\"error\":\"没有可结束的外部 dsh\"}"); return; }
                        String cmd = procCmdline(fpid);
                        long started = procStartTime(fpid);
                        if (cmd.isEmpty() || started <= 0) {
                            ok(ex, "{\"ok\":false,\"error\":\"该进程已不存在\"}"); return;
                        }
                        if (!t.foreignCmd.isBlank() && !cmd.equals(t.foreignCmd)) {
                            ok(ex, "{\"ok\":false,\"error\":\"PID 已被其它进程复用，已取消\"}"); return;
                        }
                        addLog(t, "结束外部 dsh（PID " + fpid + "）…");
                        signalProcess(fpid, "TERM");
                        long deadline = System.currentTimeMillis() + 8000;
                        while (System.currentTimeMillis() < deadline && procStartTime(fpid) == started) {
                            try { Thread.sleep(120); } catch (InterruptedException ignored) { break; }
                        }
                        if (procStartTime(fpid) == started) signalProcess(fpid, "KILL");
                        long hard = System.currentTimeMillis() + 4000;
                        while (System.currentTimeMillis() < hard && procStartTime(fpid) == started) {
                            try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
                        }
                        boolean gone = procStartTime(fpid) != started;
                        if (gone) {
                            t.foreignPid = ""; t.foreignCmd = ""; t.foreignPort = -1;
                            t.state = "stopped"; t.actualPort = -1; t.workbenchUrl = "";
                            addLog(t, "外部 dsh 已确认退出。");
                        }
                        ok(ex, "{\"ok\":" + gone + ",\"state\":\"" + t.state + "\""
                                + (gone ? "" : ",\"error\":\"外部 dsh 未确认退出\"") + "}");
                        return;
                    }

                    // 接管重启：先按记录身份停掉外部 dsh，再走正常启动。
                    case "/api/instances/reclaim": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t == null) { ok(ex, "{\"ok\":false,\"error\":\"找不到实例\"}"); return; }
                        String pidStr = t.foreignPid;
                        if (pidStr == null || pidStr.isBlank()) {
                            List<String[]> foreign = findForeignDsh(t.profile);
                            if (!foreign.isEmpty()) pidStr = foreign.get(0)[0];
                        }
                        if (pidStr == null || pidStr.isBlank()) {
                            ok(ex, "{\"ok\":false,\"error\":\"没有可接管的外部 dsh\"}");
                            return;
                        }
                        int fpid;
                        try { fpid = Integer.parseInt(pidStr); } catch (Exception e) { fpid = -1; }
                        if (fpid <= 1) { ok(ex, "{\"ok\":false,\"error\":\"PID 无效\"}"); return; }
                        // 二次核验：PID 仍在、命令行仍是 dsh、profile 仍一致
                        String cmd = procCmdline(fpid);
                        long started = procStartTime(fpid);
                        if (cmd.isEmpty() || started <= 0) {
                            ok(ex, "{\"ok\":false,\"error\":\"该进程已不存在\"}");
                            return;
                        }
                        if (!t.foreignCmd.isBlank() && !cmd.equals(t.foreignCmd)) {
                            ok(ex, "{\"ok\":false,\"error\":\"PID 已被其它进程复用，已取消接管\"}");
                            return;
                        }
                        addLog(t, "接管：正在结束外部 dsh（PID " + fpid + "）…");
                        signalProcess(fpid, "TERM");
                        long deadline = System.currentTimeMillis() + 8000;
                        while (System.currentTimeMillis() < deadline) {
                            if (procStartTime(fpid) != started) break;
                            try { Thread.sleep(120); } catch (InterruptedException ignored) { break; }
                        }
                        if (procStartTime(fpid) == started) {
                            signalProcess(fpid, "KILL");
                            long hard = System.currentTimeMillis() + 4000;
                            while (System.currentTimeMillis() < hard && procStartTime(fpid) == started) {
                                try { Thread.sleep(100); } catch (InterruptedException ignored) { break; }
                            }
                        }
                        if (procStartTime(fpid) == started) {
                            ok(ex, "{\"ok\":false,\"error\":\"外部 dsh 未确认退出，接管已中止\"}");
                            return;
                        }
                        addLog(t, "外部 dsh 已退出，开始启动新实例。");
                        t.foreignPid = "";
                        t.foreignCmd = "";
                        t.foreignPort = -1;
                        t.state = "stopped";
                        startInstance(t);
                        ok(ex, "{\"ok\":true,\"state\":\"" + t.state + "\",\"instance\":" + instanceJson(t) + "}");
                        return;
                    }

                    // 附着打开：外部 dsh 的 token 不可得，只返回端口，由用户自行粘贴地址。
                    case "/api/instances/attach": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t == null) { ok(ex, "{\"ok\":false,\"error\":\"找不到实例\"}"); return; }
                        int p = t.foreignPort > 0 ? t.foreignPort : -1;
                        if (p <= 0 && !t.foreignPid.isBlank()) {
                            try { p = probeForeignPort(Integer.parseInt(t.foreignPid)); } catch (Exception ignored) {}
                        }
                        if (p <= 0) {
                            ok(ex, "{\"ok\":false,\"error\":\"未能确定外部 dsh 的监听端口\"}");
                            return;
                        }
                        String url = "http://127.0.0.1:" + p + "/";
                        boolean launched = false;
                        try {
                            new ProcessBuilder("xdg-open", url).redirectErrorStream(true).start();
                            launched = true;
                        } catch (Exception ignored) {}
                        ok(ex, "{\"ok\":true,\"port\":" + p + ",\"url\":\"" + esc(url)
                                + "\",\"launched\":" + launched
                                + ",\"note\":\"该实例的 token 只在启动时打印过，需自行粘贴带 token 的完整地址\"}");
                        return;
                    }

                    // 实例健康检查：检查 dsh 进程是否还在运行，如果不在了自动尝试重启
                    case "/api/instances/health": {
                        Instance t = find(jsonVal(body, "id"));
                        if (t == null) {
                            ok(ex, "{\"ok\":false,\"error\":\"找不到实例\"}");
                            return;
                        }
                        // 接回的实例没有 Process 句柄，健康判据是内核身份 + 鉴权。
                        if ("adopted".equals(t.state) && t.session != null) {
                            Observation o = observe(t.session);
                            if (o == Observation.EXITED) {
                                clearSession(t, t.session);
                                t.session = null;
                                t.state = "stopped";
                                t.actualPort = -1;
                                t.workbenchUrl = "";
                                ok(ex, "{\"ok\":true,\"healthy\":false,\"autoRestarted\":false,\"state\":\"stopped\"}");
                            } else if (o == Observation.UNKNOWN) {
                                t.state = "unknown";
                                t.unknownReason = "IDENTITY_UNKNOWN";
                                ok(ex, "{\"ok\":true,\"healthy\":false,\"autoRestarted\":false,\"state\":\"unknown\"}");
                            } else {
                                ok(ex, "{\"ok\":true,\"healthy\":true,\"autoRestarted\":false,\"state\":\"adopted\"}");
                            }
                            return;
                        }
                        boolean processAlive = t.proc != null && t.proc.isAlive();
                        boolean portAlive = t.actualPort > 0 && isPortOpen(t.actualPort);
                        boolean healthy = processAlive && portAlive;
                        if (!healthy && t.state.equals("running")) {
                            // dsh 意外崩溃了，尝试自动重启
                            addLog(t, "检测到 dsh 进程已停止，尝试自动重启...");
                            stopInstance(t); // 清理残留
                            t.state = "starting";
                            t.actualPort = -1;
                            t.workbenchUrl = "";
                            startInstance(t);
                            ok(ex, "{\"ok\":true,\"healthy\":false,\"autoRestarted\":true,\"message\":\"dsh 已崩溃，正在自动重启\"}");
                        } else {
                            ok(ex, "{\"ok\":true,\"healthy\":" + healthy
                                    + ",\"autoRestarted\":false,\"state\":\"" + t.state + "\"}");
                        }
                        return;
                    }

                    // 前端异常上报：DSHA 风格的启动观察器会把 JS 异常、未处理拒绝、
                    // 插件加载失败等发到这里。只记录，不做业务处理，且有速率限制。
                    case "/api/frontend-error": {
                        if (frontendErrorCount >= MAX_FRONTEND_ERRORS) {
                            err(ex, "前端错误上报已达上限，暂停接收");
                            return;
                        }
                        Map<String, String> d = flatJson(body);
                        String type = d.getOrDefault("type", "issue");
                        String id = d.getOrDefault("id", "");
                        String msg = d.getOrDefault("message", "");
                        boolean fatal = "true".equals(d.getOrDefault("fatal", "false"));
                        String nonce = d.getOrDefault("nonce", "");
                        String docId = d.getOrDefault("documentId", "");
                        frontendErrorCount++;
                        System.out.println("[DSHA_FRONTEND] " + type + " | " + id + " | " + msg
                                + (fatal ? " [FATAL]" : "") + " | doc=" + docId + " nonce=" + nonce);
                        ok(ex, "{\"ok\":true,\"received\":" + frontendErrorCount + "}");
                        return;
                    }

                    default:
                        notFound(ex, path);
                }
            } catch (Exception e) {
                err(ex, e.toString());
            }
        }
    }

    // =====================================================================
    //                    会话记录 / 接管（adopt）机制
    //  参考 DSHA(Android) 的 WebSessionRecovery：先核验内核身份，再真实鉴权，
    //  二次核验后才接回；任何不确定都保持屏障，绝不当作“已退出”。
    // =====================================================================

    /** 一次已确认（或待确认）的 dsh 实例身份。含 launch token，文件权限 0600。 */
    static class Session {
        int pid;
        long startTime;      // /proc/PID/stat 第 22 字段（时钟滴答），PID 复用判据
        String bootId = "";
        int uid;
        String cmdline = "";
        long generation;     // 每次启动递增，防止旧回调删除新记录
        int port = -1;
        String authUrl = "";
        String profile = "";
        String instanceId = "";
        long savedAt;

        String key() { return pid + ":" + startTime + ":" + bootId; }
    }

    enum Observation { LIVE, EXITED, UNKNOWN }

    static final java.util.concurrent.atomic.AtomicLong GENERATION =
            new java.util.concurrent.atomic.AtomicLong();

    static String bootId() {
        try {
            return Files.readString(Paths.get("/proc/sys/kernel/random/boot_id"), StandardCharsets.UTF_8).trim();
        } catch (Exception e) { return ""; }
    }

    /** /proc/PID/stat 的 starttime（字段 22）。进程不存在返回 -1。 */
    static long procStartTime(int pid) {
        try {
            String s = Files.readString(Paths.get("/proc", String.valueOf(pid), "stat"), StandardCharsets.UTF_8);
            int rp = s.lastIndexOf(')');
            if (rp < 0) return -1;
            String[] f = s.substring(rp + 1).trim().split("\\s+");
            // 字段 3 是 state，索引 0 对应字段 3；starttime 是字段 22 → 索引 19
            if (f.length < 20) return -1;
            return Long.parseLong(f[19]);
        } catch (Exception e) { return -1; }
    }

    static String procCmdline(int pid) {
        try {
            byte[] raw = Files.readAllBytes(Paths.get("/proc", String.valueOf(pid), "cmdline"));
            return new String(raw, StandardCharsets.UTF_8).replace('\0', ' ').trim();
        } catch (Exception e) { return ""; }
    }

    static int procUid(int pid) {
        try {
            for (String line : Files.readAllLines(Paths.get("/proc", String.valueOf(pid), "status"))) {
                if (line.startsWith("Uid:")) return Integer.parseInt(line.substring(4).trim().split("\\s+")[0]);
            }
        } catch (Exception ignored) {}
        return -1;
    }

    static Path sessionFile(Instance i) { return SESS_DIR.resolve(i.id + ".json"); }

    static void saveSession(Instance i, Session s) {
        if (s == null) return;
        try {
            Files.createDirectories(SESS_DIR);
            s.savedAt = System.currentTimeMillis();
            String js = "{"
                    + "\"pid\":" + s.pid + ","
                    + "\"startTime\":" + s.startTime + ","
                    + "\"bootId\":\"" + esc(s.bootId) + "\","
                    + "\"uid\":" + s.uid + ","
                    + "\"cmdline\":\"" + esc(s.cmdline) + "\","
                    + "\"generation\":" + s.generation + ","
                    + "\"port\":" + s.port + ","
                    + "\"authUrl\":\"" + esc(s.authUrl) + "\","
                    + "\"profile\":\"" + esc(s.profile) + "\","
                    + "\"instanceId\":\"" + esc(s.instanceId) + "\","
                    + "\"savedAt\":" + s.savedAt
                    + "}";
            Path f = sessionFile(i);
            Files.createDirectories(f.getParent());
            Files.writeString(f, js, StandardCharsets.UTF_8);
            try {
                Files.setPosixFilePermissions(f, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            } catch (Exception ignored) {}
        } catch (Exception ignored) {}
    }

    static Session readSession(Instance i) {
        Path f = sessionFile(i);
        if (!Files.exists(f)) return null;
        try {
            Map<String, String> d = flatJson(Files.readString(f, StandardCharsets.UTF_8));
            Session s = new Session();
            s.pid = Integer.parseInt(d.getOrDefault("pid", "0"));
            s.startTime = Long.parseLong(d.getOrDefault("startTime", "-1"));
            s.bootId = d.getOrDefault("bootId", "");
            s.uid = Integer.parseInt(d.getOrDefault("uid", "-1"));
            s.cmdline = d.getOrDefault("cmdline", "");
            s.generation = Long.parseLong(d.getOrDefault("generation", "0"));
            s.port = Integer.parseInt(d.getOrDefault("port", "-1"));
            s.authUrl = d.getOrDefault("authUrl", "");
            s.profile = d.getOrDefault("profile", "");
            s.instanceId = d.getOrDefault("instanceId", "");
            s.savedAt = Long.parseLong(d.getOrDefault("savedAt", "0"));
            if (s.pid <= 1 || s.startTime <= 0) return null;
            return s;
        } catch (Exception e) { return null; }
    }

    /** 只有记录仍与当前文件一致才删除；旧回调不能删掉新记录。 */
    static void clearSession(Instance i, Session expected) {
        try {
            Path f = sessionFile(i);
            if (!Files.exists(f)) return;
            if (expected != null) {
                Session cur = readSession(i);
                if (cur == null || cur.generation != expected.generation) return;
            }
            Files.delete(f);
        } catch (Exception ignored) {}
    }

    /** 内核身份核验：只有 boot 结束、PID 退出或出生变化才能判 EXITED。 */
    static Observation observe(Session s) {
        if (s == null) return Observation.EXITED;
        if (!s.bootId.isEmpty() && !s.bootId.equals(bootId())) return Observation.EXITED;
        long started = procStartTime(s.pid);
        if (started < 0) return Observation.EXITED;          // PID 已不存在
        if (started != s.startTime) return Observation.EXITED; // PID 被复用
        int uid = procUid(s.pid);
        if (uid >= 0 && s.uid >= 0 && uid != s.uid) return Observation.EXITED;
        String cmd = procCmdline(s.pid);
        if (cmd.isEmpty()) return Observation.UNKNOWN;        // 读不到，不等于退出
        if (!cmd.equals(s.cmdline)) return Observation.UNKNOWN;
        return Observation.LIVE;
    }

    /** 真实 HTTP 鉴权：带 token 的根路径应签发 cookie 并 302；200 也算已授权。 */
    static boolean authenticate(String authUrl) {
        if (authUrl == null || authUrl.isBlank()) return false;
        java.net.HttpURLConnection c = null;
        try {
            java.net.URL u = new java.net.URL(authUrl);
            if (!"http".equals(u.getProtocol()) && !"https".equals(u.getProtocol())) return false;
            c = (java.net.HttpURLConnection) u.openConnection();
            c.setConnectTimeout(4000);
            c.setReadTimeout(4000);
            c.setInstanceFollowRedirects(false);
            c.setRequestMethod("GET");
            c.setRequestProperty("User-Agent", "dsha-launcher/adopt");
            int code = c.getResponseCode();
            return code == 200 || code == 204 || code == 302 || code == 303;
        } catch (Exception e) {
            return false;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception ignored) {}
        }
    }

    /** 接回已核验的实例：只改本地状态，不启动、不停止、不重放业务。 */
    static void adopt(Instance i, Session s) {
        i.session = s;
        i.proc = null;
        i.state = "adopted";
        i.actualPort = s.port;
        i.workbenchUrl = s.authUrl == null ? "" : s.authUrl;
        i.unknownReason = "";
        i.foreignPid = "";
        i.foreignCmd = "";
        i.foreignPort = -1;
    }

    /** 后端启动时接回上次记录：LIVE+鉴权成功 → adopted；不确定 → unknown；确认退出 → 退休。 */
    static void restoreSessionOnBoot(Instance i) {
        Session s = readSession(i);
        if (s == null) {
            // 没有记录，但系统里可能已有同 profile 的 dsh（例如上次由旧版本拉起）：
            // 直接标记冲突，让界面第一时间显示真实情况，而不是假装“未运行”。
            List<String[]> foreign = findForeignDsh(i.profile);
            if (!foreign.isEmpty()) {
                int fpid = 0;
                try { fpid = Integer.parseInt(foreign.get(0)[0]); } catch (Exception ignored) {}
                i.foreignPid = foreign.get(0)[0];
                i.foreignCmd = foreign.get(0)[1];
                i.foreignPort = fpid > 0 ? probeForeignPort(fpid) : -1;
                i.foreignStart = fpid > 0 ? procStartTime(fpid) : -1;
                i.state = "conflict";
                i.actualPort = i.foreignPort;
                addLog(i, "检测到非本启动器拉起的 dsh（PID " + i.foreignPid
                        + (i.foreignPort > 0 ? "，端口 " + i.foreignPort : "")
                        + "），已标记为外部实例。");
            }
            return;
        }
        i.session = s;
        Observation o = observe(s);
        if (o == Observation.EXITED) {
            clearSession(i, s);
            i.session = null;
            addLog(i, "上次运行的 dsh（PID " + s.pid + "）已确认退出，记录已退休。");
            return;
        }
        if (o == Observation.UNKNOWN) {
            i.state = "unknown";
            i.unknownReason = "IDENTITY_UNKNOWN";
            addLog(i, "上次运行的 dsh（PID " + s.pid + "）身份未确认，已保留屏障；请点「重新核验」或「确认已退出」。");
            return;
        }
        if (authenticate(s.authUrl)) {
            adopt(i, s);
            addLog(i, "已接回上次运行的 dsh（PID " + s.pid + "，端口 " + s.port + "），未重启。");
        } else {
            i.state = "unknown";
            i.unknownReason = "AUTH_UNAVAILABLE";
            addLog(i, "上次运行的 dsh（PID " + s.pid + "）仍在，但鉴权未通过；保留屏障，请点「重新核验」。");
        }
    }

    /** 周期复核：只读观察 + 鉴权，不启动/停止/重放。 */
    static void recheckSessions() {
        List<Instance> snapshot;
        synchronized (inst) { snapshot = new ArrayList<>(inst); }
        for (Instance i : snapshot) {
            Session s = i.session;
            if (s == null) continue;
            if (!"adopted".equals(i.state) && !"unknown".equals(i.state)) continue;
            Observation o = observe(s);
            if (o == Observation.EXITED) {
                clearSession(i, s);
                synchronized (i) {
                    i.session = null;
                    i.state = "stopped";
                    i.actualPort = -1;
                    i.workbenchUrl = "";
                    i.unknownReason = "";
                }
                addLog(i, "接回的 dsh 已确认退出（PID " + s.pid + "）。");
                continue;
            }
            if (o == Observation.UNKNOWN) {
                synchronized (i) {
                    i.state = "unknown";
                    i.unknownReason = "IDENTITY_UNKNOWN";
                }
                continue;
            }
            if ("adopted".equals(i.state)) continue;
            // unknown 但身份已 LIVE：再试一次鉴权，成功才恢复
            if (authenticate(s.authUrl)) {
                synchronized (i) { adopt(i, s); }
                addLog(i, "复核通过，已重新接回 dsh（PID " + s.pid + "）。");
            }
        }
    }

    // =====================================================================
    //                           实例启停
    // =====================================================================
    /**
     * 探测某个外部 dsh 进程实际监听的端口。
     *
     * 只做同 uid 的只读匹配：先收集该进程持有的 socket inode（/proc/PID/fd → socket:[n]），
     * 再在 /proc/net/tcp{,6} 里找状态为 LISTEN(0A) 且 inode 相符的本地端口。
     * 读不到就返回 -1 —— 不猜、不按端口反查进程。
     */
    static int probeForeignPort(int pid) {
        Set<String> inodes = new HashSet<>();
        try {
            File[] fds = new File("/proc/" + pid + "/fd").listFiles();
            if (fds != null) {
                for (File fd : fds) {
                    try {
                        String target = Files.readSymbolicLink(fd.toPath()).toString();
                        if (target.startsWith("socket:[")) {
                            inodes.add(target.substring(8, target.length() - 1));
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        if (inodes.isEmpty()) return -1;

        for (String table : new String[]{"/proc/net/tcp", "/proc/net/tcp6"}) {
            try {
                List<String> lines = Files.readAllLines(Paths.get(table));
                for (String line : lines) {
                    String[] f = line.trim().split("\\s+");
                    // sl local_address rem_address st ... uid timeout inode
                    if (f.length < 10) continue;
                    if (!"0A".equals(f[3])) continue;           // LISTEN
                    if (!inodes.contains(f[9])) continue;
                    String local = f[1];
                    int colon = local.lastIndexOf(':');
                    if (colon < 0) continue;
                    int p = Integer.parseInt(local.substring(colon + 1), 16);
                    if (p > 1024 && p < 65536) return p;
                }
            } catch (Exception ignored) {}
        }
        return -1;
    }

    /** 读取 /proc/PID/stat 的 pgrp（字段 5）。读不到返回 -1。 */
    static int procPgid(int pid) {
        try {
            String s = Files.readString(Paths.get("/proc", String.valueOf(pid), "stat"), StandardCharsets.UTF_8);
            int rp = s.lastIndexOf(')');
            if (rp < 0) return -1;
            String[] f = s.substring(rp + 1).trim().split("\\s+");
            if (f.length < 3) return -1;
            return Integer.parseInt(f[2]);
        } catch (Exception e) { return -1; }
    }

    /**
     * 向一个进程发信号。
     *
     * 只有它自己是进程组首领（pgrp == pid，通常由 setsid 产生）时才整组发送；
     * 否则只发给这个 PID —— 外部 dsh 可能与用户的 shell 同组，整组会误伤。
     */
    static void signalProcess(int pid, String signal) {
        if (pid <= 1) return;
        int pgid = procPgid(pid);
        if (pgid == pid) {
            killGroup(pid, signal);
            return;
        }
        try {
            new ProcessBuilder("kill", "-" + signal, "--", String.valueOf(pid))
                    .redirectErrorStream(true)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start()
                    .waitFor(1, TimeUnit.SECONDS);
        } catch (Exception ignored) {}
    }

    static void startInstance(Instance i) {
        if (i.state.equals("running") || i.state.equals("starting")) return;
        // 已接回的实例不重启：用户点“启动”时应先走停止/接管流程。
        if ("adopted".equals(i.state)) {
            addLog(i, "该 dsh 已接回并在运行（PID " + (i.session == null ? "?" : i.session.pid)
                    + "），未重启。需要重启请点「重启」或先停止。");
            return;
        }

        // 1) 先看自己的会话记录：LIVE 就接回，UNKNOWN 保持屏障，确认退出才继续。
        Session saved = readSession(i);
        if (saved != null) {
            Observation o = observe(saved);
            if (o == Observation.UNKNOWN) {
                i.session = saved;
                i.state = "unknown";
                i.unknownReason = "IDENTITY_UNKNOWN";
                addLog(i, "上次的 dsh（PID " + saved.pid + "）身份未确认，启动被屏障拦住。"
                        + "请点「重新核验」，或确认它已退出后点「确认已退出」。");
                return;
            }
            if (o == Observation.LIVE) {
                if (authenticate(saved.authUrl)) {
                    adopt(i, saved);
                    addLog(i, "检测到 dsh 仍在运行（PID " + saved.pid + "），已接回，未重启。");
                    return;
                }
                i.session = saved;
                i.state = "unknown";
                i.unknownReason = "AUTH_UNAVAILABLE";
                addLog(i, "dsh 进程（PID " + saved.pid + "）仍在，但鉴权失败；已保留屏障，未重启。"
                        + "请点「重新核验」，或确认后点「确认已退出」。");
                return;
            }
            // EXITED：记录已失效，退休后继续正常启动
            clearSession(i, saved);
            i.session = null;
            addLog(i, "上次的 dsh（PID " + saved.pid + "）已确认退出，继续启动。");
        }

        // 2) 再看系统里同 profile 的外部 dsh：不硬拒，也不抢杀，标记 conflict 交给用户决定。
        List<String[]> foreign = findForeignDsh(i.profile);
        if (!foreign.isEmpty()) {
            String pid = foreign.get(0)[0];
            String cmd = foreign.get(0)[1];
            int fport = probeForeignPort(Integer.parseInt(pid));
            i.foreignPid = pid;
            i.foreignCmd = cmd;
            i.foreignPort = fport;
            i.foreignStart = procStartTime(Integer.parseInt(pid));
            i.state = "conflict";
            i.actualPort = fport > 0 ? fport : -1;
            i.workbenchUrl = "";
            // 「拦下外部实例」是一次事件，不是阶段：用普通行记，别让它变成一个永不结束的阶段。
            addLog(i, "已拦下外部 dsh（PID " + pid + (fport > 0 ? "，端口 " + fport : "") + "）");
            addLog(i, "它的 launch token 只在启动时打印过一次，启动器取不到，不能自动接回。");
            addLog(i, "可选：①「附着打开」用端口访问（需自己粘贴带 token 的地址）"
                    + "；②「接管重启」结束它并由启动器重新拉起；③ 手动结束后再启动。");
            return;
        }

        // 固定端口被占用：若允许回退，就改用系统随机端口（等价于 --port 0），
        // 否则只提示、不抢占任何进程。
        boolean useAuto = i.autoPort;
        if (!useAuto && isPortOpen(i.port)) {
            if (i.portFallback) {
                addLog(i, "端口 " + i.port + " 已被占用 → 自动改用随机端口（不会结束任何进程）");
                useAuto = true;
            } else {
                i.state = "error";
                addLog(i, "端口 " + i.port + " 已被其他进程占用。为安全起见不会结束任何进程，"
                        + "请换端口，或开启「端口冲突时自动回退」。");
                return;
            }
        }
        final boolean autoPortFinal = useAuto;

        // 启动前先确认可执行文件存在，报错比 exec 的 errno 更容易理解
        String exe = i.mode.equals("local") ? "npx" : DSH_BIN;
        if (i.mode.equals("local")) {
            if (which("npx").isBlank()) {
                i.state = "error";
                addLog(i, "未找到 npx，请安装 Node.js 后重试。");
                return;
            }
        } else if (!Files.isExecutable(Paths.get(DSH_BIN))) {
            i.state = "error";
            addLog(i, "找不到可执行的 dsh：" + DSH_BIN
                    + "\n已尝试：~/.npm-global/bin/dsh、~/.local/bin/dsh 以及 PATH 中的所有目录"
                    + "\n请先安装：npm i -g @deepseek-ai/dsh");
            return;
        }

        i.state = "starting";
        i.actualPort = -1;
        i.workbenchUrl = "";
        i.unknownReason = "";
        i.foreignPid = "";
        i.foreignCmd = "";
        i.foreignPort = -1;
        // 新一轮启动：重置结构化时间线（阶段、计时、问题一起清）
        traceOf(i).begin(System.currentTimeMillis(), false);
        logStage(i, "准备启动");
        addLog(i, i.mode.equals("local")
                ? "启动方式：npx（本地源码），profile " + i.profile
                : "启动方式：dsh 全局命令，profile " + i.profile);

        List<String> cmd = new ArrayList<>();
        cmd.add(i.mode.equals("local") ? "npx" : DSH_BIN);
        cmd.add("--profile");
        cmd.add(i.profile);
        for (String seg : i.extraArgs.trim().split("\\s+"))
            if (!seg.isEmpty()) cmd.add(seg);
        cmd.add("--port");
        cmd.add(String.valueOf(autoPortFinal ? 0 : i.port));
        for (String p : patchList(i)) { cmd.add("--patch"); cmd.add(expand(p)); }

        addLog(i, "$ " + prettyCmd(cmd));

        try {
            // 尝试用 setsid 让 dsh 独立成进程组，便于整组清理；
            // 某些受限环境不允许 exec setsid，此时自动回退为直接启动。
            List<String> real = new ArrayList<>();
            String setsid = null;
            for (String cand : new String[]{"/usr/bin/setsid", "/bin/setsid"}) {
                if (Files.isExecutable(Paths.get(cand))) { setsid = cand; break; }
            }
            if (setsid != null) real.add(setsid);
            real.addAll(cmd);

            // 工作目录：local 模式用实例配置的 cwd，否则用 HOME；
            // 目录不存在就退回家目录，避免 exec 直接失败。
            String workDir = i.mode.equals("local") && !i.cwd.isBlank() ? i.cwd : HOME;
            if (!Files.isDirectory(Paths.get(workDir))) workDir = HOME;

            ProcessBuilder pb = new ProcessBuilder(real);
            pb.directory(new File(workDir));
            pb.redirectErrorStream(true);
            // 关掉 Java 侧缓冲：dsh 不停输出时也能立刻看到日志
            pb.redirectInput(ProcessBuilder.Redirect.PIPE);
            Map<String, String> env = pb.environment();
            env.put("PATH", mergedPath());
            env.put("NO_COLOR", "1");
            env.put("FORCE_COLOR", "0");
            for (String line : i.envText.split("\\R")) {
                int eq = line.indexOf('=');
                if (eq > 0) env.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }

            Process proc;
            try {
                proc = pb.start();
            } catch (IOException e) {
                if (setsid == null) throw e;
                // setsid 不可用（权限受限）：退回直接启动
                addLog(i, "setsid 不可用，改为直接启动：" + e.getMessage());
                ProcessBuilder pb2 = new ProcessBuilder(cmd);
                pb2.directory(new File(workDir));
                pb2.redirectErrorStream(true);
                pb2.environment().putAll(env);
                proc = pb2.start();
            }
            i.proc = proc;
            final Process running = proc;
            logStage(i, "等待工作台地址");
            addLog(i, "已派生进程 PID " + proc.pid());

            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    Pattern pPort = Pattern.compile("(?:listening[^0-9]*|127\\.0\\.0\\.1:|port[:= ]+)\\s*(\\d{2,5})");
                    // dsh web: http://127.0.0.1:42531/?token=XXXX —— 带鉴权 token 的完整工作台地址
                    Pattern pUrl = Pattern.compile("(https?://(?:127\\.0\\.0\\.1|localhost):(\\d{2,5})/\\S*?(?:\\?token=[^\\s\"']+)?)");
                    String ln;
                    while ((ln = r.readLine()) != null) {
                        // 先抓地址再决定要不要展示：就绪那一行会被下面的
                        // 「▸ 服务已就绪」顶掉，免得同一条 URL 印两遍。
                        Matcher mu = pUrl.matcher(ln);
                        boolean readyLine = false;
                        if (mu.find()) {
                            String full = mu.group(1);
                            int cand = Integer.parseInt(mu.group(2));
                            if (cand > 1024) {
                                i.actualPort = cand;
                                i.workbenchUrl = full;
                                readyLine = true;
                            }
                        }

                        if (i.actualPort < 0) {
                            Matcher m = pPort.matcher(ln);
                            if (m.find()) {
                                int cand = Integer.parseInt(m.group(1));
                                if (cand > 1024) i.actualPort = cand;
                            }
                        }

                        if (!readyLine) {
                            addLog(i, ln, true);
                        } else if (!i.state.equals("starting")) {
                            // 已经就绪后重复打印的地址（例如 token 变化）仍然保留，
                            // 但只留一行，不打码以外的修饰。
                            addLog(i, "工作台地址已更新 → " + maskSecrets(i.url()));
                        }

                        if (i.actualPort > 0 && i.state.equals("starting") && !i.workbenchUrl.isEmpty()) {
                            i.state = "running";
                            // 记下 token，之后凡是它出现在日志里都会被替换成 ***
                            logStage(i, "服务已就绪");
                            traceOf(i).ready(System.currentTimeMillis());
                            // token 不遮蔽：这是本机地址，用户要能直接复制到浏览器
                            addLog(i, "工作台地址 → " + i.url());
                            recordRunningSession(i, running.pid());
                        }
                    }
                } catch (Exception ignored) {}

                try {
                    int code = running.waitFor();
                    if (!i.state.equals("stopped") && !i.state.equals("error")) {
                        i.state = code == 0 ? "stopped" : "error";
                        if (code == 0) addLog(i, "进程已退出（exit code = 0）");
                        else traceOf(i).issue(System.currentTimeMillis(), "", "进程退出，exit code = " + code);
                    }
                } catch (Exception ignored) {}
            }, "dsh-log-" + i.id);
            reader.setDaemon(true);
            reader.start();

            // 固定端口兜底探活
            if (!autoPortFinal) {
                Thread probe = new Thread(() -> {
                    for (int k = 0; k < 30; k++) {
                        try { Thread.sleep(500); } catch (InterruptedException e) { return; }
                        if (!i.state.equals("starting")) return;
                        if (isPortOpen(i.port)) {
                            i.actualPort = i.port;
                            i.state = "running";
                            logStage(i, "服务已就绪（端口探活）");
                            traceOf(i).ready(System.currentTimeMillis());
                            addLog(i, "工作台地址 → " + i.url());
                            recordRunningSession(i, running.pid());
                            return;
                        }
                    }
                    if (i.state.equals("starting")) {
                        i.state = "error";
                        traceOf(i).issue(System.currentTimeMillis(), "", "启动超时：15 秒内未检测到服务端口");
                    }
                }, "dsh-probe-" + i.id);
                probe.setDaemon(true);
                probe.start();
            }
        } catch (Exception ex) {
            i.state = "error";
            addLog(i, "启动失败：" + ex.getMessage());
        }
    }

    /**
     * 启动成功、拿到鉴权地址后写入会话记录。
     *
     * 记录的是 dsh 自身的出生身份（PID + starttime + boot_id + uid + cmdline），
     * 宿主重建后据此判断“还是不是同一个实例”，不靠端口或名字猜。
     */
    static void recordRunningSession(Instance i, long pid) {
        try {
            int p = (int) pid;
            String cmd = procCmdline(p);
            long started = procStartTime(p);
            // setsid 在少数环境会 fork：句柄 PID 可能是包装进程，真正的 node 在子进程里。
            if (!looksLikeDshCmd(cmd)) {
                int child = findDshDescendant(p);
                if (child > 0) {
                    p = child;
                    cmd = procCmdline(p);
                    started = procStartTime(p);
                }
            }
            if (cmd.isEmpty() || started <= 0) {
                addLog(i, "会话记录未写入：进程身份不可读（PID " + p + "）");
                return;
            }
            Session s = new Session();
            s.pid = p;
            s.startTime = started;
            s.bootId = bootId();
            s.uid = procUid(p);
            s.cmdline = cmd;
            s.generation = GENERATION.incrementAndGet();
            s.port = i.actualPort;
            s.authUrl = i.workbenchUrl;
            s.profile = i.profile;
            s.instanceId = i.id;
            i.session = s;
            saveSession(i, s);
        } catch (Exception e) {
            addLog(i, "会话记录写入失败：" + e.getMessage());
        }
    }

    /**
     * 判断一条 cmdline 是不是 dsh 本体（不含启动器/后端/包装脚本）。
     *
     * 真实命令行有几种形态，必须都认出来：
     *   - /home/x/.npm-global/bin/dsh --profile web
     *   - node /home/x/.npm-global/bin/dsh --profile web
     *   - node --expose-internals .../@deepseek-ai/dsh/lib/bin.js --profile web
     *   - npx @deepseek-ai/dsh --profile web
     * 只有“dsh 一定带 --profile”不够：node 形态的第一个词是 node，旧判据会漏。
     */
    static boolean looksLikeDshCmd(String cmd) {
        if (cmd == null || cmd.isEmpty()) return false;
        String c = cmd.trim();
        if (c.contains("dsh-backend") || c.contains("dsh-launcher")) return false;
        String[] argv = c.split("\\s+");
        if (argv.length == 0) return false;
        String firstBase = argv[0].substring(argv[0].lastIndexOf('/') + 1);
        // 裸可执行文件形态
        if (firstBase.equals("dsh")) return true;
        if (firstBase.equals("bin.js")) return c.contains("@deepseek-ai/dsh") || c.contains("dsh/lib/bin.js");
        // node / nodejs / npx 形态：看后续参数里的入口脚本
        if (firstBase.equals("node") || firstBase.equals("nodejs") || firstBase.equals("npx")) {
            for (int k = 1; k < argv.length; k++) {
                String a = argv[k];
                if (a.startsWith("-")) continue;
                String b = a.substring(a.lastIndexOf('/') + 1);
                if (b.equals("dsh")) return true;
                if (b.equals("bin.js") && (a.contains("@deepseek-ai/dsh") || a.contains("dsh/lib/bin.js"))) return true;
                if (a.equals("@deepseek-ai/dsh") || a.startsWith("@deepseek-ai/dsh@")) return true;
                break; // 只看第一个非选项参数
            }
            return false;
        }
        // 绝对路径里直接出现 dsh 入口
        return c.contains("@deepseek-ai/dsh/lib/bin.js") || c.contains("/bin/dsh ");
    }

    /** 在进程的子孙里找 dsh（setsid fork 后的真实 node）。找不到返回 -1。 */
    static int findDshDescendant(int pid) {
        try {
            List<ProcessHandle> kids = ProcessHandle.of(pid)
                    .map(h -> h.descendants().toList())
                    .orElse(List.of());
            for (ProcessHandle h : kids) {
                String cmd = procCmdline((int) h.pid());
                if (looksLikeDshCmd(cmd)) return (int) h.pid();
            }
        } catch (Exception ignored) {}
        return -1;
    }

    /** 按记录身份停止接回的实例：不按端口/名字，只有身份核验通过才发信号。 */
    static boolean stopAdopted(Instance i) {
        Session s = i.session;
        if (s == null) return false;
        Observation o = observe(s);
        if (o == Observation.EXITED) {
            clearSession(i, s);
            i.session = null;
            i.state = "stopped";
            i.actualPort = -1;
            i.workbenchUrl = "";
            addLog(i, "接回的 dsh 已确认退出。");
            return true;
        }
        if (o == Observation.UNKNOWN) {
            i.state = "unknown";
            i.unknownReason = "IDENTITY_UNKNOWN";
            addLog(i, "身份未确认，未发送任何信号。请点「重新核验」或「确认已退出」。");
            return false;
        }
        addLog(i, "按记录身份停止接回的 dsh（PID " + s.pid + "）…");
        try {
            // 自己拉起的实例通常是 setsid 首领；外部接回的则只发给该 PID。
            signalProcess(s.pid, "TERM");
            long deadline = System.currentTimeMillis() + 8000;
            while (System.currentTimeMillis() < deadline) {
                if (observe(s) == Observation.EXITED) break;
                Thread.sleep(120);
            }
            if (observe(s) != Observation.EXITED) {
                signalProcess(s.pid, "KILL");
                long hard = System.currentTimeMillis() + 4000;
                while (System.currentTimeMillis() < hard && observe(s) != Observation.EXITED) {
                    Thread.sleep(100);
                }
            }
            Observation after = observe(s);
            if (after == Observation.EXITED) {
                clearSession(i, s);
                i.session = null;
                i.state = "stopped";
                i.actualPort = -1;
                i.workbenchUrl = "";
                addLog(i, "已停止（PID " + s.pid + " 已确认退出）。");
                return true;
            }
            i.state = "unknown";
            i.unknownReason = after == Observation.UNKNOWN ? "IDENTITY_UNKNOWN" : "EXIT_UNCONFIRMED";
            addLog(i, "停止未确认：进程状态为 " + after + "，记录与屏障保留。");
            return false;
        } catch (Exception e) {
            i.state = "unknown";
            i.unknownReason = "STOP_FAILED";
            addLog(i, "停止失败：" + e.getMessage());
            return false;
        }
    }

    static void stopInstance(Instance i) {
        // 外部实例不归本启动器所有：停止按钮不能假装成功，也不替用户杀进程。
        if ("conflict".equals(i.state)) {
            addLog(i, "该 dsh 不是本启动器拉起的，停止按钮不会结束它；请用「仅结束外部实例」或「接管重启」。");
            return;
        }
        // 接回的实例没有 Process 句柄，只能按记录身份停止。
        if ("adopted".equals(i.state) || (i.session != null && i.proc == null)) {
            stopAdopted(i);
            return;
        }
        Process p = i.proc;
        if (p == null || !p.isAlive()) {
            Session s = i.session;
            if (s != null) {
                Observation o = observe(s);
                if (o == Observation.EXITED) clearSession(i, s);
            }
            i.state = "stopped";
            i.workbenchUrl = "";
            i.session = null;
            return;
        }
        addLog(i, "正在停止（仅结束本后端派生的子进程树）…");
        i.state = "stopped";
        i.workbenchUrl = "";
        p.destroy();
        new Thread(() -> {
            try {
                if (!p.waitFor(5, TimeUnit.SECONDS)) {
                    for (ProcessHandle h : p.descendants().toList()) h.destroy();
                    p.destroyForcibly();
                }
            } catch (Exception ignored) {}
            // 只有内核身份确认退出才退休记录；否则保留，下次启动会被屏障拦住。
            Session s = i.session;
            if (s != null) {
                if (observe(s) == Observation.EXITED) {
                    clearSession(i, s);
                    i.session = null;
                } else {
                    i.state = "unknown";
                    i.unknownReason = "EXIT_UNCONFIRMED";
                    addLog(i, "进程句柄已结束，但身份核验未确认退出；记录与屏障保留。");
                    return;
                }
            }
            addLog(i, "已停止");
        }).start();
    }

    /**
     * 查找系统里占用同一个 profile 的其他 dsh 实例（不属于本后端派生的）。
     *
     * dsh 的排他锁是**按 profile** 加的，同一个 profile 起第二个才会报
     * 「当前会话已被占用」；不同 profile 可以并存，所以不能一刀切全拦。
     *
     * @param profile 只找用这个 profile 的进程；传 null/空 则不限
     * 排除：本后端自己（及其子孙）、本进程自身。
     */
    static List<String[]> findForeignDsh(String profile) {
        List<String[]> found = new ArrayList<>();
        long self = ProcessHandle.current().pid();
        // 本后端派生的进程树，全部视为「自己的」
        List<Long> mine = new ArrayList<>();
        mine.add(self);
        for (Instance i : inst) {
            Process p = i.proc;
            if (p != null) {
                mine.add(p.pid());
                try { p.descendants().forEach(h -> mine.add(h.pid())); } catch (Exception ignored) {}
            }
            // 已接回/已记录的实例也是「自己的」，不能被当成外部冲突。
            Session s = i.session;
            if (s != null && s.pid > 1) mine.add((long) s.pid);
        }

        // 直接扫 /proc 比 ProcessHandle.allProcesses() 可靠：
        // 后者依赖 JVM 的进程快照，在受限命名空间 / 权限不足时会漏。
        File[] procs = new File("/proc").listFiles();
        if (procs == null) return found;
        for (File d : procs) {
            String name = d.getName();
            if (!name.chars().allMatch(Character::isDigit)) continue;
            long pid;
            try { pid = Long.parseLong(name); } catch (Exception e) { continue; }
            if (mine.contains(pid)) continue;
            String cmd;
            try {
                byte[] raw = Files.readAllBytes(Paths.get("/proc", name, "cmdline"));
                cmd = new String(raw, StandardCharsets.UTF_8).replace('\0', ' ').trim();
            } catch (Exception e) { continue; }
            if (cmd.isBlank()) continue;
            if (!cmd.contains("--profile")) continue;   // dsh 一定带 --profile
            if (!looksLikeDshCmd(cmd)) continue;        // 统一判据，node/bin.js/npx 都认
            // 只认同一个 profile 的，不同 profile 允许并存
            if (profile != null && !profile.isBlank()) {
                String p = profileOf(cmd);
                if (!profile.equals(p)) continue;
            }
            found.add(new String[]{String.valueOf(pid), cmd});
        }
        return found;
    }

    /** 从 dsh 命令行里取出 --profile 后面的值。 */
    static String profileOf(String cmd) {
        String[] parts = cmd.trim().split("\\s+");
        for (int k = 0; k < parts.length; k++) {
            if (parts[k].equals("--profile") && k + 1 < parts.length) return parts[k + 1];
            if (parts[k].startsWith("--profile=")) return parts[k].substring("--profile=".length());
        }
        return "";
    }

    static void addLog(Instance i, String msg) {
        addLog(i, msg, false);
    }

    /**
     * 写一行日志。
     *
     * <p>移植 DSHA 的做法后，这里只负责「分派到结构化时间线」，不再靠关键词丢行：
     *
     * <ul>
     *   <li>启动器自己的输出走 {@link StartupTrace#owned}，dsh 的原始输出走
     *       {@link StartupTrace#add}，两者在 {@code Entry.kind} 上分开，
     *       界面按 kind 分组就不会把插件输出误当阶段；
     *   <li>脱敏、去 ANSI、去重、环形裁剪都由 {@link StartupTrace} 统一处理；
     *   <li>「哪些行值得注意」由 {@link StartupLogDigest} 按规则分类，
     *       而不是在这里静默吞掉——吞掉就等于丢证据。
     * </ul>
     */
    static void addLog(Instance i, String msg, boolean fromDsh) {
        if (msg == null || msg.isEmpty()) return;
        StartupTrace t = traceOf(i);
        long now = System.currentTimeMillis();
        if (fromDsh) t.add(now, msg);
        else t.owned(now, msg);
    }

    /** 从工作台地址里取出 launch token，登记后整条都会被打码。 */
    static String tokenOf(String url) {
        if (url == null) return "";
        Matcher m = Pattern.compile("[?&]token=([A-Za-z0-9_\\-]{6,})").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /** 实例状态 → 启动概览的整体结果（不从日志文本猜）。 */
    static StartupLogDigest.Outcome outcomeOf(Instance i) {
        if (i == null) return StartupLogDigest.Outcome.IDLE;
        switch (i.state) {
            case "running":
            case "adopted":
                return StartupLogDigest.Outcome.READY;
            case "starting":
                return StartupLogDigest.Outcome.RUNNING;
            case "error":
                return StartupLogDigest.Outcome.FAILED;
            case "stopped":
                return StartupLogDigest.Outcome.STOPPED;
            default:
                return StartupLogDigest.Outcome.IDLE;
        }
    }

    /** 阶段概览序列化：名称 / 起止 / 状态 / 占比所需的耗时 / 明细条数。 */
    static String stagesJson(StartupLogDigest.Result d) {
        StringBuilder sb = new StringBuilder("[");
        for (int k = 0; k < d.stages.size(); k++) {
            StartupLogDigest.Stage st = d.stages.get(k);
            if (k > 0) sb.append(',');
            sb.append("{\"name\":").append(st.name == null ? "null" : "\"" + esc(st.name) + "\"")
              .append(",\"startMs\":").append(st.startMs)
              .append(",\"durationMs\":").append(st.durationMs)
              .append(",\"state\":\"").append(st.state.name()).append("\"")
              .append(",\"itemCount\":").append(st.items.size())
              .append(",\"firstEntryIndex\":").append(st.firstEntryIndex)
              .append('}');
        }
        return sb.append(']').toString();
    }

    /** 拿到实例的启动时间线；第一次用时按需创建。 */
    static StartupTrace traceOf(Instance i) {
        StartupTrace t = i.trace;
        if (t == null) {
            synchronized (i) {
                if (i.trace == null) i.trace = new StartupTrace();
                t = i.trace;
            }
        }
        return t;
    }

    /** 兼容旧调用：把展示文本打码（token / 家目录 / 凭据形状）。 */
    static String maskSecrets(String s) {
        return SensitiveData.redact(s);
    }

    /** 阶段锚点：界面上一眼能看出走到哪一步。 */
    static void logStage(Instance i, String stage) {
        traceOf(i).stage(System.currentTimeMillis(), stage);
    }

    /** 命令行展示：家目录缩成 ~，dsh 可执行文件只留 basename，别让一长串路径把行撑爆。 */
    static String prettyCmd(List<String> cmd) {
        List<String> out = new ArrayList<>(cmd.size());
        for (int k = 0; k < cmd.size(); k++) {
            String c = cmd.get(k);
            if (k == 0) {
                int slash = c.lastIndexOf('/');
                if (slash >= 0 && slash + 1 < c.length()) c = c.substring(slash + 1);
            }
            out.add(SensitiveData.redact(c));
        }
        return String.join(" ", out);
    }

    static String expand(String p) {
        if (p.startsWith("~")) return HOME + p.substring(1);
        if (!p.startsWith("/")) return HOME + "/" + p;
        return p;
    }

    static List<String> patchList(Instance i) {
        List<String> r = new ArrayList<>();
        for (String s : i.patches.split("[,\\n]")) {
            String t = s.trim();
            if (!t.isEmpty()) r.add(t);
        }
        return r;
    }

    static boolean isPortOpen(int port) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress("127.0.0.1", port), 400);
            return true;
        } catch (IOException e) { return false; }
    }

    static boolean containsPort(int p) {
        synchronized (inst) {
            for (Instance i : inst) if (i.port == p) return true;
        }
        return false;
    }

    static Instance find(String id) {
        if (id == null) return null;
        synchronized (inst) {
            for (Instance i : inst) if (i.id.equals(id)) return i;
        }
        return null;
    }

    // =====================================================================
    //                              JSON 输出
    // =====================================================================
    static String instancesJson() {
        StringBuilder sb = new StringBuilder("[");
        synchronized (inst) {
            for (int k = 0; k < inst.size(); k++) {
                if (k > 0) sb.append(',');
                sb.append(instanceJson(inst.get(k)));
            }
        }
        return sb.append(']').toString();
    }

    static String instanceJson(Instance i) {
        if (i == null) return "null";
        return new StringBuilder()
                .append('{')
                .append("\"id\":\"").append(esc(i.id)).append("\",")
                .append("\"name\":\"").append(esc(i.name)).append("\",")
                .append("\"mode\":\"").append(esc(i.mode)).append("\",")
                .append("\"cwd\":\"").append(esc(i.cwd)).append("\",")
                .append("\"port\":").append(i.port).append(',')
                .append("\"autoPort\":").append(i.autoPort).append(',')
                .append("\"portFallback\":").append(i.portFallback).append(',')
                .append("\"profile\":\"").append(esc(i.profile)).append("\",")
                .append("\"extraArgs\":\"").append(esc(i.extraArgs)).append("\",")
                .append("\"envText\":\"").append(esc(i.envText)).append("\",")
                .append("\"patches\":\"").append(esc(i.patches)).append("\",")
                .append("\"state\":\"").append(i.state).append("\",")
                .append("\"actualPort\":").append(i.effectivePort()).append(',')
                .append("\"url\":\"").append(esc(i.url())).append("\",")
                .append("\"sessionPid\":").append(i.session == null ? 0 : i.session.pid).append(',')
                .append("\"sessionPort\":").append(i.session == null ? -1 : i.session.port).append(',')
                .append("\"unknownReason\":\"").append(esc(i.unknownReason)).append("\",")
                .append("\"foreignPid\":\"").append(esc(i.foreignPid)).append("\",")
                .append("\"foreignCmd\":\"").append(esc(i.foreignCmd)).append("\",")
                .append("\"foreignPort\":").append(i.foreignPort).append(',')
                .append("\"hasToken\":").append(i.workbenchUrl != null && i.workbenchUrl.contains("token="))
                .append('}')
                .toString();
    }

    static String envJson() {
        List<String[]> checks = new ArrayList<>();
        // dsh 直接用解析结果：PATH 里不一定有 npm 全局目录
        String dshPath = Files.isExecutable(Paths.get(DSH_BIN)) ? DSH_BIN : which("dsh");
        checks.add(new String[]{"dsh", dshPath});
        checks.add(new String[]{"node", which("node")});
        checks.add(new String[]{"npm", which("npm")});
        checks.add(new String[]{"edge", Files.exists(Paths.get(EDGE_BIN)) ? EDGE_BIN : ""});
        checks.add(new String[]{"niri", which("niri")});
        String fz = shell("fc-list :lang=zh | head -1");
        checks.add(new String[]{"中文字体", fz});

        StringBuilder sb = new StringBuilder("{\"items\":[");
        for (int k = 0; k < checks.size(); k++) {
            String[] c = checks.get(k);
            boolean ok = c[1] != null && !c[1].isBlank();
            if (k > 0) sb.append(',');
            sb.append("{\"name\":\"").append(esc(c[0])).append("\",")
              .append("\"ok\":").append(ok).append(',')
              .append("\"detail\":\"").append(esc(ok ? c[1].trim() : "未在 PATH 中找到")).append("\"}");
        }
        sb.append("],\"ports\":[");
        boolean first = true;
        synchronized (inst) {
            for (Instance i : inst) {
                if (!first) sb.append(',');
                first = false;
                sb.append("{\"name\":\"").append(esc(i.name)).append("\",")
                  .append("\"port\":").append(i.port).append(',')
                  .append("\"open\":").append(isPortOpen(i.port)).append(',')
                  .append("\"auto\":").append(i.autoPort).append('}');
            }
        }
        sb.append("],\"java\":\"").append(esc(System.getProperty("java.version"))).append("\",")
          .append("\"session\":\"").append(esc(System.getenv().getOrDefault("XDG_SESSION_TYPE", "?"))).append("\",")
          .append("\"desktop\":\"").append(esc(System.getenv().getOrDefault("XDG_CURRENT_DESKTOP", "?"))).append("\"}");
        return sb.toString();
    }

    static String updateJson() {
        String cur = shell(DSH_BIN + " --version");
        String latest = shell("npm view @deepseek-ai/dsh version 2>/dev/null");
        return "{\"current\":\"" + esc(cur) + "\",\"latest\":\"" + esc(latest) + "\"}";
    }

    static String which(String cmd) {
        String s = shell("command -v " + cmd);
        return s.isBlank() ? "" : s;
    }

    // =====================================================================
    //  插件清单
    //  数据源（全部真实读取，不做任何推测）：
    //    1. profiles/<profile>/package.json 的 dsh.profile.bundles —— 装载顺序
    //    2. profiles/<profile>/package.json 的 dependencies    —— 依赖与来源
    //    3. profiles/<profile>/node_modules/<pkg>/package.json —— 版本与描述
    //    4. cordis.patch.yml 里的 disabled 标记                —— 启用状态
    //  官方内置（@deepseek-ai/*）与第三方据此区分。
    // =====================================================================
    /**
     * dsh 自身的运行骨架 bundle：不当作插件展示。
     * 只是隐藏，不会卸载，dsh 启动完全照旧。
     */
    static final Set<String> SKELETON_BUNDLES = Set.of(
            "@deepseek-ai/dsh-base",
            "@deepseek-ai/dsh-web-app",
            "@deepseek-ai/dsh-experimental-agent-team-profile");

    /** 插件仅在用户显式请求时安装；此处只保留提示文案常量。 */
    static final String PLUGIN_AUTO_DENY_MSG =
            "启动器不会自动安装插件；插件安装只在你主动点击时执行";

    /** POST /api/plugins/install —— 显式安装。带 package.json 备份与失败回滚。 */
    static String pluginInstall(String spec, String profile, String registry, Instance t) {
        Path profDir = profileDir(profile);
        Path pkgJson = profDir.resolve("package.json");
        if (!Files.isDirectory(profDir) || !Files.exists(pkgJson)) {
            return "{\"ok\":false,\"error\":\"profile 目录不存在：" + esc(profDir.toString()) + "\"}";
        }
        String pnpm = which("pnpm");
        if (pnpm.isBlank()) {
            return "{\"ok\":false,\"error\":\"未找到 pnpm，请先安装 pnpm 后重试\"}";
        }

        // 安装前备份 package.json 与 lock，失败原样恢复
        String pkgBackup;
        String lockPath = profDir.resolve("pnpm-lock.yaml").toString();
        String lockBackup = null;
        try {
            pkgBackup = new String(Files.readAllBytes(pkgJson), StandardCharsets.UTF_8);
            if (Files.exists(Paths.get(lockPath))) {
                lockBackup = new String(Files.readAllBytes(Paths.get(lockPath)), StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"读取 profile 失败：" + esc(e.getMessage()) + "\"}";
        }

        if (t != null) addLog(t, "用户请求安装插件：" + spec + " → profile " + profile);

        List<String> cmd = new ArrayList<>();
        cmd.add(pnpm);
        cmd.add("add");
        cmd.add(spec);
        cmd.add("--save-exact");
        if ("mirror".equals(registry)) {
            cmd.add("--registry=https://registry.npmmirror.com");
        } else if ("npm".equals(registry)) {
            cmd.add("--registry=https://registry.npmjs.org");
        }

        StringBuilder out = new StringBuilder();
        int code = 1;
        try {
            ProcessBuilder pb = new ProcessBuilder(cmd);
            pb.directory(profDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PATH", mergedPath());
            pb.environment().put("NO_COLOR", "1");
            pb.environment().put("FORCE_COLOR", "0");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = r.readLine()) != null) {
                    out.append(ln).append('\n');
                    if (t != null) addLog(t, "[pnpm] " + ln);
                }
            }
            code = p.waitFor();
        } catch (Exception e) {
            out.append(e.getMessage());
        }

        if (code != 0) {
            // 回滚：pnpm add 失败时 package.json / lock 可能已被改写
            try {
                Files.write(pkgJson, pkgBackup.getBytes(StandardCharsets.UTF_8));
                if (lockBackup != null) {
                    Files.write(Paths.get(lockPath), lockBackup.getBytes(StandardCharsets.UTF_8));
                }
                if (t != null) addLog(t, "安装失败，已回滚 package.json" + (lockBackup != null ? " 与 pnpm-lock.yaml" : ""));
            } catch (Exception ignored) {}
            return "{\"ok\":false,\"code\":" + code
                    + ",\"rolledBack\":true,\"log\":\"" + esc(tail(out.toString(), 4000)) + "\"}";
        }

        if (t != null) addLog(t, "安装完成：" + spec + "（重启实例后生效）");
        return "{\"ok\":true,\"code\":0,\"spec\":\"" + esc(spec) + "\",\"profile\":\"" + esc(profile)
                + "\",\"needRestart\":true,\"log\":\"" + esc(tail(out.toString(), 4000)) + "\"}";
    }

    /** POST /api/plugins/uninstall —— 显式卸载。 */
    static String pluginUninstall(String name, String profile, Instance t) {
        Path profDir = profileDir(profile);
        if (!Files.exists(profDir.resolve("package.json"))) {
            return "{\"ok\":false,\"error\":\"profile 目录不存在：" + esc(profDir.toString()) + "\"}";
        }
        String pnpm = which("pnpm");
        if (pnpm.isBlank()) return "{\"ok\":false,\"error\":\"未找到 pnpm\"}";

        if (t != null) addLog(t, "用户请求卸载插件：" + name + " ← profile " + profile);

        StringBuilder out = new StringBuilder();
        int code = 1;
        try {
            ProcessBuilder pb = new ProcessBuilder(pnpm, "remove", name);
            pb.directory(profDir.toFile());
            pb.redirectErrorStream(true);
            pb.environment().put("PATH", mergedPath());
            pb.environment().put("NO_COLOR", "1");
            pb.environment().put("FORCE_COLOR", "0");
            Process p = pb.start();
            try (BufferedReader r = new BufferedReader(
                    new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String ln;
                while ((ln = r.readLine()) != null) {
                    out.append(ln).append('\n');
                    if (t != null) addLog(t, "[pnpm] " + ln);
                }
            }
            code = p.waitFor();
        } catch (Exception e) {
            out.append(e.getMessage());
        }

        if (code != 0) {
            return "{\"ok\":false,\"code\":" + code + ",\"log\":\"" + esc(tail(out.toString(), 4000)) + "\"}";
        }
        if (t != null) addLog(t, "已卸载 " + name + "（重启实例后生效）");
        return "{\"ok\":true,\"code\":0,\"name\":\"" + esc(name) + "\",\"needRestart\":true}";
    }

    /** POST /api/plugins/toggle —— 只改 cordis.patch.yml 里的 disabled 标记。 */
    static String pluginToggle(String name, String profile, boolean enabled, Instance t) {
        Path patchFile = profileDir(profile).resolve("cordis.patch.yml");
        try {
            String cur = Files.exists(patchFile)
                    ? new String(Files.readAllBytes(patchFile), StandardCharsets.UTF_8)
                    : "";
            String next = setDisabledInPatch(cur, name, enabled);
            Files.createDirectories(patchFile.getParent());
            Files.write(patchFile, next.getBytes(StandardCharsets.UTF_8));
            if (t != null) {
                addLog(t, (enabled ? "已启用插件：" : "已禁用插件：") + name + "（重启实例后生效）");
            }
            return "{\"ok\":true,\"name\":\"" + esc(name) + "\",\"enabled\":" + enabled + ",\"needRestart\":true}";
        } catch (Exception e) {
            return "{\"ok\":false,\"error\":\"" + esc(e.getMessage()) + "\"}";
        }
    }

    /**
     * 在 patch YAML 里给某个包加/去 disabled 标记，其余内容逐字保留。
     *
     * patch 文件是一个顶层数组，每个条目形如：
     *   - id: xxx
     *     name: "@scope/pkg"
     *     config: ...
     * 所以匹配要按「条目块」判断 name/id，不能整行 contains ——
     * 否则 dsh-code-checker 会误命中 dsh-code-checker-ext。
     */
    static String setDisabledInPatch(String yaml, String name, boolean enabled) {
        String[] lines = yaml.split("\n", -1);

        // 切分成条目块：以行首 "- " 为界。
        // 规则：遇到 "- " 就开新块，但把「上一块尾部的连续空行」移到新块前面，
        // 这样空行不会卡在上一块内部（否则 disabled 会被插到空行之后，
        // 第二遍解析时两个块都匹配、都插一次）。
        List<List<String>> blocks = new ArrayList<>();
        List<String> cur = new ArrayList<>();
        for (String ln : lines) {
            if (ln.startsWith("- ") && hasBody(cur)) {
                List<String> tail = new ArrayList<>();
                while (!cur.isEmpty() && cur.get(cur.size() - 1).trim().isEmpty()) {
                    tail.add(0, cur.remove(cur.size() - 1));
                }
                blocks.add(cur);
                cur = new ArrayList<>(tail);
            }
            cur.add(ln);
        }
        if (!cur.isEmpty()) blocks.add(cur);

        boolean hit = false;
        for (List<String> blk : blocks) {
            if (!blockMatches(blk, name)) continue;
            hit = true;
            int end = bodyEnd(blk);
            // 去掉块内（body 范围）已有的 disabled 行
            for (int k = end - 1; k >= 0; k--) {
                if (blk.get(k).trim().startsWith("disabled:")) blk.remove(k);
            }
            if (!enabled) {
                // 插入点：body 最后一行的后面，即尾部空行之前
                blk.add(bodyEnd(blk), "  disabled: true");
            }
        }

        // 文件里没有该条目时，禁用需要新增一个块；启用则无需动作
        if (!hit && !enabled) {
            List<String> nb = new ArrayList<>();
            nb.add("- name: \"" + name + "\"");
            nb.add("  disabled: true");
            blocks.add(nb);
        }

        List<String> out = new ArrayList<>();
        for (List<String> blk : blocks) out.addAll(blk);
        return String.join("\n", out);
    }

    /** 列表里是否已经出现过条目首行（"- "），用于防止连续两个条目被合并。 */
    static boolean hasBody(List<String> blk) {
        for (String l : blk) if (l.startsWith("- ")) return true;
        return false;
    }

    /** 块内第一行 "- " 开头的条目之后，属于该条目的有效内容行数（跳过尾部空行与注释）。 */
    static int bodyEnd(List<String> blk) {
        int end = blk.size();
        while (end > 0) {
            String t = blk.get(end - 1).trim();
            if (t.isEmpty() || t.startsWith("#")) end--;
            else break;
        }
        return end;
    }

    /** 条目块的 name/id 是否精确等于目标（支持带引号、@scope 与 "- name:" 首行写法）。 */
    static boolean blockMatches(List<String> blk, String name) {
        int end = bodyEnd(blk);
        for (int k = 0; k < end; k++) {
            String t = blk.get(k).trim();
            // 顶层条目的首行形如 "- name: xxx"，要先剥掉列表符号
            if (t.startsWith("- ")) t = t.substring(2).trim();
            String v = null;
            if (t.startsWith("name:")) v = t.substring(5).trim();
            else if (t.startsWith("id:")) v = t.substring(3).trim();
            if (v == null) continue;
            v = v.replace("\"", "").replace("'", "").trim();
            if (v.equals(name)) return true;
        }
        return false;
    }

    /** 取字符串尾部若干字符，避免日志过长撑爆响应。 */
    static String tail(String s, int max) {
        return s.length() <= max ? s : s.substring(s.length() - max);
    }

    static String pluginsJson(String profile) {
        Path profDir = profileDir(profile);
        Path pkgJson = profDir.resolve("package.json");
        Path modDir = profDir.resolve("node_modules");
        Path patchFile = profDir.resolve("cordis.patch.yml");

        List<String> bundles = new ArrayList<>();
        Map<String, String> deps = new LinkedHashMap<>();
        String profileName = profile;

        // ---- 1/2. 读 profile 的 package.json ----
        if (Files.isReadable(pkgJson)) {
            try {
                String raw = Files.readString(pkgJson, StandardCharsets.UTF_8);
                // dsh.profile.bundles 是字符串数组，按出现顺序抓取
                int bi = raw.indexOf("\"bundles\"");
                if (bi >= 0) {
                    int lb = raw.indexOf('[', bi);
                    int rb = lb < 0 ? -1 : raw.indexOf(']', lb);
                    if (lb > 0 && rb > lb) {
                        for (String seg : raw.substring(lb + 1, rb).split(",")) {
                            String v = seg.trim();
                            if (v.startsWith("\"") && v.endsWith("\"") && v.length() > 2) {
                                bundles.add(v.substring(1, v.length() - 1));
                            }
                        }
                    }
                }
                // dependencies：只需要顶层字符串键值
                int di = raw.indexOf("\"dependencies\"");
                if (di >= 0) {
                    int lb = raw.indexOf('{', di);
                    if (lb > 0) {
                        int depth = 0, end = -1;
                        for (int k = lb; k < raw.length(); k++) {
                            char c = raw.charAt(k);
                            if (c == '{') depth++;
                            else if (c == '}') { depth--; if (depth == 0) { end = k; break; } }
                            else if (c == '"' && depth == 1) {
                                int close = raw.indexOf('"', k + 1);
                                if (close < 0) break;
                                String key = raw.substring(k + 1, close);
                                int colon = raw.indexOf(':', close);
                                int vq = raw.indexOf('"', colon);
                                int vqEnd = vq < 0 ? -1 : raw.indexOf('"', vq + 1);
                                if (colon > 0 && vq > 0 && vqEnd > vq) {
                                    deps.put(key, raw.substring(vq + 1, vqEnd));
                                    k = vqEnd;
                                } else {
                                    k = close;
                                }
                            }
                        }
                    }
                }
            } catch (Exception ignored) { }
        }

        // ---- 4. 补丁里的禁用标记 ----
        Set<String> disabled = new HashSet<>();
        if (Files.isReadable(patchFile)) {
            try {
                String prev = null;
                for (String line : Files.readAllLines(patchFile, StandardCharsets.UTF_8)) {
                    String s = line.trim();
                    if (s.startsWith("name:")) {
                        String v = s.substring(5).trim().replace("\"", "").replace("'", "");
                        prev = v;
                    } else if (s.startsWith("disabled:") && prev != null) {
                        String v = s.substring(9).trim();
                        if (v.equals("true")) disabled.add(prev);
                        prev = null;
                    }
                }
            } catch (Exception ignored) { }
        }

        // 已装载的 bundle 优先，其余依赖补在后面（同一个包只出现一次）
        Set<String> seen = new LinkedHashSet<>(bundles);
        seen.addAll(deps.keySet());

        StringBuilder items = new StringBuilder();
        int n = 0, enabledCount = 0;
        // 全局 dsh 自带的包目录：官方 bundle 装在这里，profile 里不会有
        List<Path> globalRoots = new ArrayList<>();
        for (String h : new String[]{loginHome(), HOME}) {
            if (h == null || h.isBlank()) continue;
            globalRoots.add(Paths.get(h, ".npm-global", "lib", "node_modules",
                    "@deepseek-ai", "dsh", "node_modules"));
            globalRoots.add(Paths.get(h, "node_modules", "@deepseek-ai", "dsh", "node_modules"));
        }
        // 解析到的 dsh 二进制同级的全局安装目录也纳入候选
        if (DSH_BIN.contains("/")) {
            String dir = DSH_BIN.substring(0, DSH_BIN.lastIndexOf('/'));
            int libIdx = dir.lastIndexOf("/bin");
            String prefix = libIdx > 0 ? dir.substring(0, libIdx) : dir;
            globalRoots.add(Paths.get(prefix, "lib", "node_modules",
                    "@deepseek-ai", "dsh", "node_modules"));
            globalRoots.add(Paths.get(prefix, "node_modules",
                    "@deepseek-ai", "dsh", "node_modules"));
        }
        // 从 DSH_PROFILE_ROOT（<home>/.dsh/profiles）反推 <home>，
        // 覆盖沙箱里 loginHome()/HOME 都指错的场景
        String proot = System.getenv("DSH_PROFILE_ROOT");
        if (proot != null && proot.contains("/.dsh/")) {
            String h = proot.substring(0, proot.indexOf("/.dsh/"));
            globalRoots.add(Paths.get(h, ".npm-global", "lib", "node_modules",
                    "@deepseek-ai", "dsh", "node_modules"));
        }
        for (String name : seen) {
            boolean official = name.startsWith("@deepseek-ai/");
            String version = "";
            String desc = "";
            // 包的真实清单：先看 profile 自己的 node_modules，再看全局 dsh
            List<Path> candidates = new ArrayList<>();
            candidates.add(modDir.resolve(name).resolve("package.json"));
            for (Path g : globalRoots) candidates.add(g.resolve(name).resolve("package.json"));
            for (Path root : candidates) {
                if (!Files.isReadable(root)) continue;
                try {
                    String raw = Files.readString(root, StandardCharsets.UTF_8);
                    version = jsonFlatValue(raw, "version");
                    desc = jsonFlatValue(raw, "description");
                    break;
                } catch (Exception ignored) { }
            }
            String spec = deps.getOrDefault(name, "");
            boolean isBundle = bundles.contains(name);
            boolean enabled = !disabled.contains(name);

            // 官方 bundle（dsh-base / dsh-web-app / agent-team-profile 这类）
            // 是 dsh 自身的运行骨架，没有独立插件语义，也不该出现在插件列表里。
            // 这里只是不展示，不会卸载它们，dsh 启动照旧。
            if (SKELETON_BUNDLES.contains(name)) continue;

            if (n > 0) items.append(',');
            n++;
            if (enabled) enabledCount++;
            items.append("{\"name\":\"").append(esc(name)).append("\",")
                 .append("\"version\":\"").append(esc(version)).append("\",")
                 .append("\"description\":\"").append(esc(desc)).append("\",")
                 .append("\"spec\":\"").append(esc(spec)).append("\",")
                 .append("\"official\":").append(official).append(',')
                 .append("\"bundle\":").append(isBundle).append(',')
                 .append("\"enabled\":").append(enabled).append(',')
                 .append("\"source\":\"").append(esc(specSource(spec))).append("\"}");
        }

        // ---- 官方可选扩展：全局 dsh 里带 *-bundle 的包 ----
        // 这些是随 dsh 一起分发、但当前 profile 没有装载的官方能力包。
        // 只做只读展示，告诉用户"还有什么可加"，不涉及任何安装动作。
        StringBuilder avail = new StringBuilder();
        int an = 0;
        Set<String> installedNames = new HashSet<>();
        for (String nm : seen) installedNames.add(nm);
        Set<String> availSeen = new LinkedHashSet<>();
        for (Path g : globalRoots) {
            if (!Files.isDirectory(g)) continue;
            // 全局目录里包名带 scope，实际在 @deepseek-ai/ 子目录下；
            // 也兼容直接平铺在根目录的情况。
            List<Path> scanRoots = new ArrayList<>();
            Path scoped = g.resolve("@deepseek-ai");
            if (Files.isDirectory(scoped)) scanRoots.add(scoped);
            scanRoots.add(g);
            for (Path sr : scanRoots) {
                File[] dirs = sr.toFile().listFiles();
                if (dirs == null) continue;
                for (File d : dirs) {
                    if (!d.isDirectory()) continue;
                    String nm = d.getName();
                    if (!nm.startsWith("dsh-") || !nm.endsWith("-bundle")) continue;
                    // 扫描根是 @deepseek-ai 时，包全名要补回 scope
                    String full = sr.equals(scoped) ? "@deepseek-ai/" + nm : nm;
                    if (installedNames.contains(full)) continue;
                    Path pj = d.toPath().resolve("package.json");
                    if (!Files.isReadable(pj)) continue;
                    if (!availSeen.add(full)) continue;
                    String ver = "", dsc = "";
                    try {
                        String raw = Files.readString(pj, StandardCharsets.UTF_8);
                        ver = jsonFlatValue(raw, "version");
                        dsc = jsonFlatValue(raw, "description");
                    } catch (Exception ignored) { }
                    if (an > 0) avail.append(',');
                    an++;
                    avail.append("{\"name\":\"").append(esc(full)).append("\",")
                         .append("\"version\":\"").append(esc(ver)).append("\",")
                         .append("\"description\":\"").append(esc(dsc)).append("\"}");
                }
            }
        }

        return "{\"profile\":\"" + esc(profileName) + "\","
             + "\"profileDir\":\"" + esc(profDir.toString()) + "\","
             + "\"exists\":" + Files.isDirectory(profDir) + ","
             + "\"total\":" + n + ","
             + "\"enabled\":" + enabledCount + ","
             + "\"writable\":" + Files.isWritable(profDir) + ","
             + "\"autoInstall\":false,"
             + "\"available\":" + an + ","
             + "\"availableItems\":[" + avail + "],"
             + "\"items\":[" + items + "]}";
    }

    /**
     * 定位某个 profile 的目录。
     * 优先级：DSH_PROFILE_ROOT/<profile> → 登录用户 home/.dsh/profiles/<profile>
     *        → HOME/.dsh/profiles/<profile>。
     * 沙箱或容器里 user.name 可能是 root，loginHome() 拿到的是 /root，
     * 此时靠 HOME 兜底；两者都不可用时再放开 DSH_PROFILE_ROOT。
     */
    static Path profileDir(String profile) {
        String root = System.getenv("DSH_PROFILE_ROOT");
        if (root != null && !root.isBlank()) {
            Path p = Paths.get(root, profile);
            if (Files.isDirectory(p)) return p;
        }
        for (String h : new String[]{loginHome(), HOME}) {
            if (h == null || h.isBlank()) continue;
            Path p = Paths.get(h, ".dsh", "profiles", profile);
            if (Files.isDirectory(p)) return p;
        }
        String h = loginHome();
        if (h == null || h.isBlank()) h = HOME;
        return Paths.get(h, ".dsh", "profiles", profile);
    }

    /** 从 JSON 文本里取一个顶层字符串字段的值，够用即可。 */
    static String jsonFlatValue(String raw, String key) {
        int i = raw.indexOf("\"" + key + "\"");
        if (i < 0) return "";
        int colon = raw.indexOf(':', i + key.length() + 2);
        if (colon < 0) return "";
        int q1 = raw.indexOf('"', colon);
        if (q1 < 0) return "";
        StringBuilder sb = new StringBuilder();
        for (int k = q1 + 1; k < raw.length(); k++) {
            char c = raw.charAt(k);
            if (c == '\\' && k + 1 < raw.length()) {
                char nx = raw.charAt(++k);
                sb.append(nx == 'n' ? '\n' : nx == 't' ? '\t' : nx);
            } else if (c == '"') {
                break;
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    /** 把依赖 spec 归类成人类可读的来源。 */
    static String specSource(String spec) {
        if (spec == null || spec.isBlank()) return "profile";
        if (spec.startsWith("github:") || spec.contains("github.com")) return "github";
        if (spec.startsWith("http") || spec.endsWith(".tar.gz") || spec.endsWith(".tgz")) return "archive";
        if (spec.startsWith("file:") || spec.startsWith("./") || spec.startsWith("/")) return "local";
        if (spec.startsWith("workspace:")) return "workspace";
        return "npm";
    }

    /**
     * 定位 dsh 可执行文件。
     *
     * 不能简单用 HOME + "/.npm-global/bin/dsh" 拼：某些环境（如从 dsh 内部
     * 派生的子进程）里 HOME 会被改写指向数据目录，拼出来就是错路径。
     * 顺序：DSH_BIN 覆盖 -> 常见 npm 全局前缀 -> 合并后的 PATH 查找。
     */
    static String resolveDsh() {
        String override = System.getenv("DSH_BIN");
        if (override != null && !override.isBlank() && Files.isExecutable(Paths.get(override))) {
            return override;
        }
        // 真实家目录候选：优先取 /etc/passwd 里的登录家目录，其次 user.home
        List<String> roots = new ArrayList<>();
        String realHome = loginHome();
        if (!realHome.isBlank() && !realHome.equals(HOME)) roots.add(realHome);
        roots.add(HOME);
        for (String r : roots) {
            for (String rel : new String[]{
                    ".npm-global/bin/dsh", ".local/bin/dsh", "bin/dsh"}) {
                Path p = Paths.get(r, rel);
                if (Files.isExecutable(p)) return p.toString();
            }
        }
        // 最后交给 PATH（用户可能装在 /usr/local/bin、nvm 目录等）
        for (String dir : mergedPath().split(":")) {
            if (dir.isBlank()) continue;
            Path p = Paths.get(dir, "dsh");
            if (Files.isExecutable(p)) return p.toString();
        }
        // 全都找不到时给一个最有提示性的默认值
        return (realHome.isBlank() ? HOME : realHome) + "/.npm-global/bin/dsh";
    }

    /** 从 /etc/passwd 里读当前用户的真实家目录，避开被改写的 HOME。 */
    static String loginHome() {
        String user = System.getProperty("user.name");
        try {
            for (String line : Files.readAllLines(Paths.get("/etc/passwd"))) {
                String[] f = line.split(":");
                if (f.length >= 6 && f[0].equals(user)) return f[5];
            }
        } catch (Exception ignored) {}
        return "";
    }

    /** dsh 启动时使用的 PATH：把常见 npm 全局目录补进去，但不破坏原有 PATH。 */
    static String mergedPath() {
        String base = System.getenv().getOrDefault("PATH", "/usr/local/bin:/usr/bin:/bin");
        StringBuilder sb = new StringBuilder();
        List<String> seen = new ArrayList<>();
        String realHome = loginHome();
        for (String h : new String[]{HOME, realHome}) {
            if (h == null || h.isBlank()) continue;
            for (String rel : new String[]{"/.npm-global/bin", "/.local/bin"}) {
                String d = h + rel;
                if (!seen.contains(d) && Files.isDirectory(Paths.get(d))) {
                    seen.add(d); sb.append(d).append(':');
                }
            }
        }
        return sb.append(base).toString();
    }

    static String shell(String cmd) {
        try {
            ProcessBuilder pb = new ProcessBuilder("sh", "-c", cmd);
            pb.redirectErrorStream(true);
            Process p = pb.start();
            String s = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            p.waitFor(3, TimeUnit.SECONDS);
            return s.isBlank() ? "" : s.trim();
        } catch (Exception e) { return ""; }
    }

    // =====================================================================
    //                           HTTP 小工具
    // =====================================================================
    static void ok(HttpExchange ex, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        cors(ex);
        ex.sendResponseHeaders(200, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    /** 统一 CORS：仅放行本地来源与 Tauri 壳，避免 * 的过宽松。 */
    static void cors(HttpExchange ex) {
        String origin = ex.getRequestHeaders().getFirst("Origin");
        if (origin != null && isAllowedOrigin(origin)) {
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", origin);
        } else {
            // 非浏览器直接调用（如 curl）无 Origin 头；不设置具体来源即可
            ex.getResponseHeaders().add("Access-Control-Allow-Origin", "null");
        }
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type, X-DSH-Token");
        // 关键：把 X-DSH-Token 响应头暴露给前端，否则 WebView（origin 非 http）里
        // fetch 读不到这个非 CORS 安全头，API_TOKEN 永远拿不到，后续请求全 401。
        ex.getResponseHeaders().add("Access-Control-Expose-Headers", "X-DSH-Token");
        ex.getResponseHeaders().add("Access-Control-Max-Age", "86400");
    }

    /** 校验 X-DSH-Token 头。首次访问 /api/health 时不带 token 也放行，让前端先拿到 token。 */
    static boolean checkToken(HttpExchange ex) throws IOException {
        String path = ex.getRequestURI().getPath();
        if ("/api/health".equals(path)) return true; // 健康检查免鉴权，前端先拿 token
        String token = ex.getRequestHeaders().getFirst("X-DSH-Token");
        if (token == null || !token.equals(STARTUP_TOKEN)) {
            err(ex, 401, "401 未授权：缺少或错误的 X-DSH-Token 头，请先访问 /api/health 获取令牌");
            return false;
        }
        return true;
    }

    static void err(HttpExchange ex, String msg) throws IOException {
        err(ex, 500, msg);
    }

    /** 指定状态码的错误响应（401 鉴权失败、404 等）。 */
    static void err(HttpExchange ex, int status, String msg) throws IOException {
        byte[] b = ("{\"error\":\"" + esc(msg) + "\"}").getBytes(StandardCharsets.UTF_8);
        cors(ex);
        ex.sendResponseHeaders(status, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static void notFound(HttpExchange ex, String path) throws IOException {
        byte[] b = ("{\"error\":\"no route: " + esc(path) + "\"}").getBytes(StandardCharsets.UTF_8);
        cors(ex);
        ex.sendResponseHeaders(404, b.length);
        try (OutputStream os = ex.getResponseBody()) { os.write(b); }
    }

    static String readBody(HttpExchange ex) throws IOException {
        try (InputStream is = ex.getRequestBody()) {
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String kv : raw.split("&")) {
            int eq = kv.indexOf('=');
            if (eq > 0) {
                try {
                    m.put(URLDecoder.decode(kv.substring(0, eq), "UTF-8"),
                          URLDecoder.decode(kv.substring(eq + 1), "UTF-8"));
                } catch (Exception ignored) {}
            }
        }
        return m;
    }

    /** 极简扁平 JSON 解析：只处理 {"k":"v","k2":123} 这种一层结构。 */
    static Map<String, String> flatJson(String s) {
        Map<String, String> m = new HashMap<>();
        if (s == null) return m;
        Matcher matcher = Pattern.compile("\"([^\"]+)\"\\s*:\\s*(\"((?:[^\"\\\\]|\\\\.)*)\"|[^,}\\s]+)").matcher(s);
        while (matcher.find()) {
            String k = matcher.group(1);
            String v = matcher.group(3) != null ? unescape(matcher.group(3)) : matcher.group(2);
            m.put(k, v);
        }
        return m;
    }

    static String jsonVal(String body, String key) {
        return flatJson(body).get(key);
    }

    static String unescape(String s) {
        return s.replace("\\n", "\n").replace("\\t", "\t")
                .replace("\\\"", "\"").replace("\\\\", "\\");
    }

    static String esc(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "").replace("\t", "\\t");
    }

    // =====================================================================
    //                             持久化
    // =====================================================================
    static void saveConfig() {
        try {
            Files.createDirectories(CFG_DIR);
            StringBuilder sb = new StringBuilder("[\n");
            synchronized (inst) {
                for (int k = 0; k < inst.size(); k++) {
                    Instance i = inst.get(k);
                    sb.append("  {")
                      .append("\"id\":\"").append(esc(i.id)).append("\",")
                      .append("\"name\":\"").append(esc(i.name)).append("\",")
                      .append("\"mode\":\"").append(esc(i.mode)).append("\",")
                      .append("\"cwd\":\"").append(esc(i.cwd)).append("\",")
                      .append("\"port\":").append(i.port).append(',')
                      .append("\"autoPort\":").append(i.autoPort).append(',')
                      .append("\"portFallback\":").append(i.portFallback).append(',')
                      .append("\"profile\":\"").append(esc(i.profile)).append("\",")
                      .append("\"extraArgs\":\"").append(esc(i.extraArgs)).append("\",")
                      .append("\"envText\":\"").append(esc(i.envText)).append("\",")
                      .append("\"patches\":\"").append(esc(i.patches)).append("\"")
                      .append("}").append(k < inst.size() - 1 ? "," : "").append("\n");
                }
            }
            sb.append("]\n");
            Files.writeString(CFG_FILE, sb.toString(), StandardCharsets.UTF_8);
        } catch (Exception ignored) {}
    }

    /**
     * 旧版在设置了 DSH_HOME 的进程里会把实例配置写到 $DSH_HOME/.config，
     * 导致“从终端启动”和“从桌面启动”看到两份不同的实例列表。
     * 这里只在标准位置缺失或更旧时迁移一次，并保留备份。
     */
    static void migrateLegacyConfig() {
        try {
            if (!Files.exists(LEGACY_CFG_FILE)) return;
            if (LEGACY_CFG_FILE.toAbsolutePath().normalize()
                    .equals(CFG_FILE.toAbsolutePath().normalize())) return;
            Files.createDirectories(CFG_DIR);
            if (!Files.exists(CFG_FILE)) {
                Files.copy(LEGACY_CFG_FILE, CFG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[dsh-backend] 已迁移实例配置：" + LEGACY_CFG_FILE + " → " + CFG_FILE);
                return;
            }
            // 旧位置更新：说明用户最近是从终端启动的，以它为准（先备份标准位置）。
            if (Files.getLastModifiedTime(LEGACY_CFG_FILE)
                    .compareTo(Files.getLastModifiedTime(CFG_FILE)) > 0) {
                Files.copy(CFG_FILE, CFG_DIR.resolve("instances.json.bak-before-migrate"),
                        StandardCopyOption.REPLACE_EXISTING);
                Files.copy(LEGACY_CFG_FILE, CFG_FILE, StandardCopyOption.REPLACE_EXISTING);
                System.out.println("[dsh-backend] 旧位置配置较新，已采用：" + LEGACY_CFG_FILE);
                return;
            }
            // 两边都有：不覆盖，按 id 合并，缺的补进来（旧位置另存备份）。
            String legacy = Files.readString(LEGACY_CFG_FILE, StandardCharsets.UTF_8);
            String current = Files.readString(CFG_FILE, StandardCharsets.UTF_8);
            Set<String> ids = new HashSet<>();
            Set<String> profiles = new HashSet<>();
            Matcher cm = Pattern.compile("\\{([^{}]*)\\}").matcher(current);
            while (cm.find()) {
                Map<String, String> d = flatJson("{" + cm.group(1) + "}");
                if (d.get("id") != null) ids.add(d.get("id"));
                if (d.get("profile") != null) profiles.add(d.get("profile"));
            }
            StringBuilder merged = new StringBuilder();
            Matcher lm = Pattern.compile("\\{([^{}]*)\\}").matcher(legacy);
            int added = 0;
            while (lm.find()) {
                Map<String, String> d = flatJson("{" + lm.group(1) + "}");
                String id = d.get("id");
                if (id == null || ids.contains(id)) continue;
                // 同 profile 已有实例就不再补：dsh 按 profile 加排他锁，重复条目只会制造假冲突。
                String prof = d.getOrDefault("profile", "");
                if (!prof.isBlank() && profiles.contains(prof)) continue;
                ids.add(id);
                if (!prof.isBlank()) profiles.add(prof);
                merged.append(added > 0 || !current.trim().equals("[]") ? ",\n  " : "\n  ");
                merged.append("{");
                boolean firstField = true;
                for (Map.Entry<String, String> e : d.entrySet()) {
                    if (!firstField) merged.append(',');
                    firstField = false;
                    String v = e.getValue();
                    boolean numeric = v.matches("-?[0-9]+") || v.equals("true") || v.equals("false");
                    merged.append('"').append(esc(e.getKey())).append("\":");
                    if (numeric) merged.append(v);
                    else merged.append('"').append(esc(v)).append('"');
                }
                merged.append("}");
                added++;
            }
            if (added == 0) return;
            Files.copy(CFG_FILE, CFG_DIR.resolve("instances.json.bak-before-merge"),
                    StandardCopyOption.REPLACE_EXISTING);
            String body = current.trim();
            if (body.endsWith("]")) body = body.substring(0, body.lastIndexOf(']')).trim();
            if (body.equals("[")) body = "[";
            String next = body + merged + "\n]\n";
            Files.writeString(CFG_FILE, next, StandardCharsets.UTF_8);
            System.out.println("[dsh-backend] 已合并旧位置实例配置 " + added + " 条：" + LEGACY_CFG_FILE);
        } catch (Exception e) {
            System.out.println("[dsh-backend] 配置迁移跳过：" + e.getMessage());
        }
    }

    static void loadConfig() {
        inst.clear();
        migrateLegacyConfig();
        if (!Files.exists(CFG_FILE)) return;
        try {
            String js = Files.readString(CFG_FILE, StandardCharsets.UTF_8);
            Matcher m = Pattern.compile("\\{([^{}]*)\\}").matcher(js);
            while (m.find()) {
                Map<String, String> d = flatJson("{" + m.group(1) + "}");
                Instance i = new Instance();
                if (d.containsKey("id")) i.id = d.get("id");
                if (d.containsKey("name")) i.name = d.get("name");
                if (d.containsKey("mode")) i.mode = d.get("mode");
                if (d.containsKey("cwd")) i.cwd = d.get("cwd");
                if (d.containsKey("port")) {
                    try { i.port = Integer.parseInt(d.get("port")); } catch (Exception ignored) {}
                }
                if (d.containsKey("autoPort")) i.autoPort = Boolean.parseBoolean(d.get("autoPort"));
                if (d.containsKey("portFallback")) i.portFallback = Boolean.parseBoolean(d.get("portFallback"));
                if (d.containsKey("profile")) i.profile = d.get("profile");
                if (d.containsKey("extraArgs")) i.extraArgs = d.get("extraArgs");
                if (d.containsKey("envText")) i.envText = d.get("envText");
                if (d.containsKey("patches")) i.patches = d.get("patches");
                inst.add(i);
            }
        } catch (Exception ignored) {}
    }
}
