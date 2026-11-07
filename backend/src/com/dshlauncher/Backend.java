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
        transient int logDropped;              // 被裁掉的行数，用于校正前端下标

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
                            fb.append("{\"pid\":").append(foreign.get(k)[0])
                              .append(",\"profile\":\"").append(esc(profileOf(foreign.get(k)[1]))).append("\"")
                              .append(",\"cmd\":\"").append(esc(foreign.get(k)[1])).append("\"}");
                        }
                        fb.append(']');
                        ok(ex, "{\"ok\":true,\"name\":\"dsh-backend\",\"version\":\"1.0.0\","
                                + "\"instances\":" + inst.size()
                                + ",\"pluginAutoInstall\":false"
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
                        // wait=1：长轮询。有新日志立刻返回，没有就最多挂 2 秒再返回。
                        // 前端据此做「实时」流，不必高频空转。
                        boolean wait = "1".equals(q.get("wait"));
                        if (t == null) { ok(ex, "{\"lines\":[],\"total\":0,\"offset\":0}"); return; }

                        long deadline = System.currentTimeMillis() + 2000L;
                        synchronized (t.logs) {
                            while (wait && t.logs.size() <= since
                                    && System.currentTimeMillis() < deadline) {
                                try { t.logs.wait(Math.max(1L, deadline - System.currentTimeMillis())); }
                                catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
                            }
                        }

                        StringBuilder sb = new StringBuilder("{\"lines\":[");
                        int total;
                        // offset = 本次首行在全量日志里的下标，前端用它做累计行数
                        int offset;
                        synchronized (t.logs) {
                            total = t.logs.size();
                            offset = Math.min(Math.max(0, since), total);
                            for (int k = offset; k < total; k++) {
                                if (k > offset) sb.append(',');
                                sb.append('"').append(esc(t.logs.get(k))).append('"');
                            }
                        }
                        sb.append("],\"total\":").append(total)
                          .append(",\"offset\":").append(offset)
                          .append(",\"dropped\":").append(t.logDropped)
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
            while (i.logs.size() > MAX_LOG_LINES) {
                i.logs.remove(0);
                i.logDropped++;
            }
            // 唤醒正在长轮询的请求，做到「有日志立刻推」
            i.logs.notifyAll();
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
