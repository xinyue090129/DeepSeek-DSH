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
    // user.home 在正常桌面会话里就是登录用户的家目录；
    // 支持 DSH_HOME 覆盖，便于在特殊环境（容器 / 沙箱）里调试。
    static final String HOME = System.getenv("DSH_HOME") != null
            && !System.getenv("DSH_HOME").isBlank()
            ? System.getenv("DSH_HOME")
            : System.getProperty("user.home");
    static final Path CFG_DIR = Paths.get(HOME, ".config", "dsh-launcher");
    static final Path CFG_FILE = CFG_DIR.resolve("instances.json");
    static final String DSH_BIN = resolveDsh();
    static final String EDGE_BIN = "/opt/microsoft/msedge/msedge";
    static final DateTimeFormatter TS = DateTimeFormatter.ofPattern("HH:mm:ss");
    static final int DEFAULT_PORT = 7080;
    static final int MAX_LOG_LINES = 4000;

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

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/api", new Router());
        server.setExecutor(Executors.newFixedThreadPool(8));
        server.start();

        System.out.println("[dsh-backend] listening on http://127.0.0.1:" + port);
        System.out.println("[dsh-backend] instances loaded: " + inst.size());
        System.out.flush();

        // 优雅退出：关闭启动器时必须把本后端派生的 dsh 进程树一起带走，
        // 不留孤儿 node 进程。
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            List<Instance> snapshot;
            synchronized (inst) { snapshot = new ArrayList<>(inst); }
            for (Instance i : snapshot) {
                Process p = i.proc;
                if (p != null) killTree(p);
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

                switch (path) {
                    case "/api/health": {
                        List<String[]> foreign = findForeignDsh(null);
                        StringBuilder fb = new StringBuilder("[");
                        for (int k = 0; k < foreign.size(); k++) {
                            if (k > 0) fb.append(',');
                            fb.append("{\"pid\":").append(foreign.get(k)[0])
                              .append(",\"profile\":\"").append(esc(profileOf(foreign.get(k)[1]))).append("\"")
                              .append(",\"cmd\":\"").append(esc(foreign.get(k)[1])).append("\"}");
                        }
                        fb.append(']');
                        ok(ex, "{\"ok\":true,\"name\":\"dsh-backend\",\"version\":\"1.0.0\","
                                + "\"instances\":" + inst.size()
                                + ",\"instanceProfile\":\"" + esc(inst.isEmpty() ? "" : inst.get(0).profile) + "\""
                                + ",\"foreignDsh\":" + fb + "}");
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
                            addLog(t, "安全启动：已跳过全部补丁");
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
                        int since = 0;
                        try { since = Integer.parseInt(q.getOrDefault("since", "0")); } catch (Exception ignored) {}
                        if (t == null) { ok(ex, "{\"lines\":[],\"total\":0}"); return; }
                        StringBuilder sb = new StringBuilder("{\"lines\":[");
                        int total;
                        synchronized (t.logs) {
                            total = t.logs.size();
                            for (int k = Math.max(0, since); k < total; k++) {
                                if (k > Math.max(0, since)) sb.append(',');
                                sb.append('"').append(esc(t.logs.get(k))).append('"');
                            }
                        }
                        sb.append("],\"total\":").append(total)
                          .append(",\"state\":\"").append(t.state).append("\"")
                          .append(",\"port\":").append(t.effectivePort())
                          .append(",\"url\":\"").append(t.url()).append("\"}");
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

                    default:
                        notFound(ex, path);
                }
            } catch (Exception e) {
                err(ex, e.toString());
            }
        }
    }

    // =====================================================================
    //                           实例启停
    // =====================================================================
    static void startInstance(Instance i) {
        if (i.state.equals("running") || i.state.equals("starting")) return;

        // 同一个 profile 只允许一个 dsh：dsh 的排他锁按 profile 加，
        // 起了第二个会报「当前会话已被占用」。不同 profile 可以并存，不拦。
        List<String[]> foreign = findForeignDsh(i.profile);
        if (!foreign.isEmpty()) {
            StringBuilder sb = new StringBuilder(
                    "profile " + i.profile + " 已有一个 dsh 在运行，为避免会话锁冲突，本次启动已取消。");
            for (String[] f : foreign) {
                sb.append("\n  PID ").append(f[0]).append("  ").append(f[1]);
            }
            sb.append("\n请先在终端结束它：kill ").append(foreign.get(0)[0])
              .append("（或 pkill -f \"bin.js --profile ").append(i.profile).append("\"）");
            i.state = "error";
            addLog(i, sb.toString());
            return;
        }

        // 固定端口被占用：若允许回退，就改用系统随机端口（等价于 --port 0），
        // 否则只提示、不抢占任何进程。
        boolean useAuto = i.autoPort;
        if (!useAuto && isPortOpen(i.port)) {
            if (i.portFallback) {
                addLog(i, "端口 " + i.port + " 已被占用，按设置自动回退到随机端口（不会结束任何进程）");
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
        addLog(i, "正在启动 " + i.name + " …");

        List<String> cmd = new ArrayList<>();
        cmd.add(i.mode.equals("local") ? "npx" : DSH_BIN);
        cmd.add("--profile");
        cmd.add(i.profile);
        for (String seg : i.extraArgs.trim().split("\\s+"))
            if (!seg.isEmpty()) cmd.add(seg);
        cmd.add("--port");
        cmd.add(String.valueOf(autoPortFinal ? 0 : i.port));
        for (String p : patchList(i)) { cmd.add("--patch"); cmd.add(expand(p)); }

        addLog(i, "$ " + String.join(" ", cmd));

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
            Map<String, String> env = pb.environment();
            env.put("PATH", mergedPath());
            env.put("NO_COLOR", "1");
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

            Thread reader = new Thread(() -> {
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(running.getInputStream(), StandardCharsets.UTF_8))) {
                    Pattern pPort = Pattern.compile("(?:listening[^0-9]*|127\\.0\\.0\\.1:|port[:= ]+)\\s*(\\d{2,5})");
                    // dsh web: http://127.0.0.1:42531/?token=XXXX —— 带鉴权 token 的完整工作台地址
                    Pattern pUrl = Pattern.compile("(https?://(?:127\\.0\\.0\\.1|localhost):(\\d{2,5})/\\S*?(?:\\?token=[^\\s\"']+)?)");
                    String ln;
                    while ((ln = r.readLine()) != null) {
                        addLog(i, ln);

                        // 先抓完整工作台地址（含 token），它同时能确定端口
                        Matcher mu = pUrl.matcher(ln);
                        if (mu.find()) {
                            String full = mu.group(1);
                            int cand = Integer.parseInt(mu.group(2));
                            if (cand > 1024) {
                                i.actualPort = cand;
                                i.workbenchUrl = full;
                            }
                        }

                        if (i.actualPort < 0) {
                            Matcher m = pPort.matcher(ln);
                            if (m.find()) {
                                int cand = Integer.parseInt(m.group(1));
                                if (cand > 1024) i.actualPort = cand;
                            }
                        }
                        if (i.actualPort > 0 && i.state.equals("starting") && !i.workbenchUrl.isEmpty()) {
                            i.state = "running";
                            addLog(i, "服务已就绪 \u2192 " + i.url());
                        }
                    }
                } catch (Exception ignored) {}

                try {
                    int code = running.waitFor();
                    if (!i.state.equals("stopped")) {
                        i.state = code == 0 ? "stopped" : "error";
                        addLog(i, "进程退出，exit code = " + code);
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
                            addLog(i, "服务已就绪（端口探活）\u2192 " + i.url());
                            return;
                        }
                    }
                    if (i.state.equals("starting")) {
                        i.state = "error";
                        addLog(i, "启动超时：15 秒内未检测到服务端口");
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

    static void stopInstance(Instance i) {
        Process p = i.proc;
        if (p == null || !p.isAlive()) {
            i.state = "stopped";
            i.workbenchUrl = "";
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
            if (p == null) continue;
            mine.add(p.pid());
            try { p.descendants().forEach(h -> mine.add(h.pid())); } catch (Exception ignored) {}
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
            // 取命令行第一个词，判断它是不是 dsh（可能是绝对路径，也可能是裸名字）
            String first = cmd.trim().split("\\s+", 2)[0];
            String base = first.substring(first.lastIndexOf('/') + 1);
            boolean isDsh = base.equals("dsh") || base.equals("bin.js")
                    || cmd.contains("@deepseek-ai/dsh") || cmd.contains("dsh/lib/bin.js");
            if (!isDsh) continue;
            // 别把启动器自己算进去
            if (cmd.contains("dsh-launcher") || cmd.contains("dsh-backend")) continue;
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
        String line = "[" + LocalTime.now().format(TS) + "] " + msg;
        synchronized (i.logs) {
            i.logs.add(line);
            while (i.logs.size() > MAX_LOG_LINES) i.logs.remove(0);
        }
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
                .append("\"url\":\"").append(esc(i.url())).append('"')
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

    /** 统一 CORS：WebView 的 origin 是 tauri://localhost，跨域访问本地 HTTP 必须显式放行。 */
    static void cors(HttpExchange ex) {
        ex.getResponseHeaders().add("Content-Type", "application/json; charset=utf-8");
        ex.getResponseHeaders().add("Access-Control-Allow-Origin", "*");
        ex.getResponseHeaders().add("Access-Control-Allow-Methods", "GET, POST, OPTIONS");
        ex.getResponseHeaders().add("Access-Control-Allow-Headers", "Content-Type");
        ex.getResponseHeaders().add("Access-Control-Max-Age", "86400");
    }

    static void err(HttpExchange ex, String msg) throws IOException {
        byte[] b = ("{\"error\":\"" + esc(msg) + "\"}").getBytes(StandardCharsets.UTF_8);
        cors(ex);
        ex.sendResponseHeaders(500, b.length);
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

    static void loadConfig() {
        inst.clear();
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
