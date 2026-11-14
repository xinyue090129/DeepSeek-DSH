package com.dshlauncher;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * 有界、脱敏的启动时间线。
 *
 * <p>移植自 DSHA（app/src/main/java/com/deepseekharness/app/util/StartupTrace.java）。
 * 关键点与上游一致：
 *
 * <ul>
 *   <li>日志是<b>结构化</b>的：每行带 {@link Entry#kind}，阶段用 START/DONE/LEFT 表达，
 *       GUI 按 kind 分组即可，<b>不用去匹配中文文案</b>——文案改了不会把分组弄坏；
 *   <li>{@link #append} 时如果新行渲染结果与上一行完全相同就直接丢弃，天然去重；
 *   <li>容量双上限（字符数 + 行数）环形裁剪，长时间运行不会把内存吃光；
 *   <li>{@link #issue} 单独记录问题，供概览上浮，不靠匹配日志关键字。
 * </ul>
 *
 * <p>与上游的差异：这里每个实例一份，不需要 generation 校验；时间用绝对时间戳
 * （启动器窗口长时间挂着，「20:48:52」比「运行 +3.2s」更好对照系统日志）。
 */
public final class StartupTrace {
    private static final int MAX_CHARS = 48 * 1024;
    private static final int MAX_LINES = 300;
    private static final int MAX_ISSUES = 20;
    private static final Pattern ANSI = Pattern.compile("\u001B\\[[0-?]*[ -/]*[@-~]");
    private static final Pattern CTRL = Pattern.compile("[\\p{Cntrl}&&[^\\n\\t]]");

    /** 普通输出 / 启动器自有输出 / 阶段开始 / 阶段完成 / 阶段切离。 */
    public static final int OUTPUT = 0, OWNED = 1, STAGE_START = 2, STAGE_DONE = 3, STAGE_LEFT = 4;

    private final ArrayDeque<Line> lines = new ArrayDeque<>();
    private final LinkedHashMap<String, String> issues = new LinkedHashMap<>();
    /** 首次 append 时补上起点，避免没调用 begin() 就用时算出天文数字的耗时。 */
    private long began = -1, stageBegan, revision, readyAt = -1, endedAt = -1;
    private String stage = "尚未启动";
    private boolean ready;
    private int length;
    private boolean appendingProblem;
    /** 被环形裁剪掉的行数，供前端校正增量游标。 */
    private long dropped;

    // 同一 revision 下 snapshot() 会被前端反复拉，缓存渲染结果避免重复遍历
    private long renderedRevision = -1;
    private String renderedLog = "";
    private List<Entry> renderedEntries = Collections.emptyList();

    private static final class Line {
        /** 自启动开始的毫秒数。 */
        final long at;
        /** 网页就绪后的运行毫秒数；就绪前为 -1。 */
        final long runtimeAt;
        final String text, previous;
        final int kind;
        final boolean problem;

        Line(long at, long runtimeAt, String text, String previous, int kind, boolean problem) {
            this.at = at;
            this.runtimeAt = runtimeAt;
            this.text = text;
            this.previous = previous;
            this.kind = kind;
            this.problem = problem;
        }

        /** 阶段行只返回阶段名，供概览按 kind 分组。 */
        String body() {
            return text;
        }

        String render() {
            String value = body();
            if (kind == STAGE_START) value = "正在" + value + "…";
            if (kind == STAGE_DONE) value = "完成「" + value + "」，耗时 " + previous;
            if (kind == STAGE_LEFT) value = "切换离开「" + value + "」，停留 " + previous;
            // 与 DSHA 完全一致：就绪前用「自启动开始」，就绪后用「运行 +N / 自启动记录 M」。
            // 相对耗时能直接看出哪一步拖了，墙钟时刻反而没人看。
            if (runtimeAt < 0) return "[" + formatDuration(at) + "] " + value;
            return "[运行 +" + formatDuration(runtimeAt)
                    + " / 自启动记录 " + formatDuration(at) + "] " + value;
        }
    }

    /** 结构化日志行；与 {@link Snapshot#log} 逐行对应。 */
    public static final class Entry {
        /** 自本次启动开始的毫秒数。 */
        public final long atMs;

        /** 网页就绪后的运行毫秒数；就绪前为 -1。 */
        public final long runtimeMs;

        public final int kind;

        /** 阶段行是阶段名，其余是去掉时间前缀的正文。 */
        public final String body;

        /** 与 {@link Snapshot#log} 中同一行相同的原文（含时间前缀）。 */
        public final String line;

        /** 由 {@link #issue} 记录的问题行。 */
        public final boolean problem;

        Entry(long atMs, long runtimeMs, int kind, String body, String line, boolean problem) {
            this.atMs = atMs;
            this.runtimeMs = runtimeMs;
            this.kind = kind;
            this.body = body == null ? "" : body;
            this.line = line == null ? "" : line;
            this.problem = problem;
        }
    }

    public static final class Snapshot {
        public final long revision, elapsedMs, stageElapsedMs, runElapsedMs, readyMs;
        public final String stage, log;
        public final boolean ready, ended;
        public final Map<String, String> issues;
        public final List<Entry> entries;
        /** 被环形裁剪掉的行数。 */
        public final long dropped;

        Snapshot(
                long revision,
                long elapsed,
                long stageElapsed,
                long runElapsed,
                long readyElapsed,
                String stage,
                String log,
                boolean ready,
                boolean ended,
                Map<String, String> issues,
                List<Entry> entries,
                long dropped) {
            this.revision = revision;
            this.elapsedMs = elapsed;
            this.stageElapsedMs = stageElapsed;
            this.runElapsedMs = runElapsed;
            this.readyMs = readyElapsed;
            this.stage = stage;
            this.log = log;
            this.ready = ready;
            this.ended = ended;
            this.issues = Collections.unmodifiableMap(issues);
            this.entries = entries;
            this.dropped = dropped;
        }
    }

    /** 开一次新启动：清空上一轮的全部状态。 */
    public synchronized void begin(long now, boolean safeMode) {
        began = stageBegan = now;
        stage = "准备启动";
        ready = false;
        readyAt = endedAt = -1;
        lines.clear();
        issues.clear();
        length = 0;
        dropped = 0;
        revision++;
        renderedRevision = -1;
        renderedLog = "";
        renderedEntries = Collections.emptyList();
        // 第一个阶段就是「准备启动」：先把起点行写出来，避免概览里出现一个没有名字的空阶段。
        append(now, stage, "", STAGE_START);
        owned(now, safeMode
                ? "安全启动：使用独立基础配置，原插件和配置保留"
                : "开始启动 DSH");
    }

    /** 切换阶段：先把上一阶段记成完成（带停留耗时），再开新阶段。 */
    public synchronized void stage(long now, String next) {
        if (next == null || next.isBlank() || next.equals(stage)) return;
        if (began < 0) {
            began = stageBegan = now;
            stage = "尚未启动";
        }
        // 从未真正开始启动过（stage 还是占位的「尚未启动」）就直接切到新阶段：
        // 不补一条「完成『尚未启动』」，那是没有意义的噪声。
        if (!"尚未启动".equals(stage) && !"排队启动".equals(stage))
            append(now, stage, formatDuration(now - stageBegan), ready ? STAGE_LEFT : STAGE_DONE);
        stage = clean(next);
        stageBegan = now;
        append(now, stage, "", STAGE_START);
    }

    /** 追加一行外部输出（dsh / npx 的 stdout+stderr）。 */
    public synchronized void add(long now, String text) {
        appendLines(now, text, OUTPUT);
    }

    /** 追加一行启动器自己产生的输出。 */
    public synchronized void owned(long now, String text) {
        appendLines(now, text, OWNED);
    }

    private void appendLines(long now, String text, int kind) {
        if (text == null) return;
        for (String raw : text.replace('\r', '\n').split("\n")) {
            String value = clean(raw);
            if (value.isEmpty()) continue;
            if (value.length() > 3000) value = value.substring(0, 3000) + "…";
            append(now, value, "", kind);
        }
    }

    private void append(long now, String text, String previous, int kind) {
        // 没有显式 begin()（例如只是外部实例探测产生的一行）时，以首行作为起点，
        // 否则 began=0 会让耗时变成「距 1970 年」的天文数字。
        if (began < 0) {
            began = stageBegan = now;
            stage = "尚未启动";
        }
        Line line = new Line(
                now - began,
                ready ? Math.max(0, now - readyAt) : -1,
                text,
                previous,
                kind,
                appendingProblem);
        // 与上一行渲染结果完全相同 → 丢弃（天然去重，和上游一致）
        if (!lines.isEmpty() && lines.peekLast().render().equals(line.render())) return;
        lines.addLast(line);
        length += line.text.length() + line.previous.length() + 80;
        while (length > MAX_CHARS || lines.size() > MAX_LINES) {
            Line old = lines.removeFirst();
            length -= old.text.length() + old.previous.length() + 80;
            dropped++;
        }
        revision++;
    }

    /**
     * 记录一个启动问题（插件加载失败、端口冲突、启动异常）。
     *
     * <p>同名问题的相同原因只记一次；概览据此把失败上浮，不需要解析日志正文。
     */
    public synchronized void issue(long now, String plugin, String detail) {
        String name = plugin == null ? "" : clean(plugin);
        String reason = clean(detail);
        if (name.length() > 180 || reason.isEmpty()) return;
        if (reason.length() > 1200) reason = reason.substring(0, 1200) + "…";
        if (reason.equals(issues.get(name))) return;
        issues.put(name, reason);
        while (issues.size() > MAX_ISSUES) issues.remove(issues.keySet().iterator().next());
        appendingProblem = true;
        try {
            owned(now, name.isEmpty() ? "DSH 启动异常" : "插件加载异常：" + name);
            add(now, reason);
        } finally {
            appendingProblem = false;
        }
    }

    /** 工作台地址（含 token）就绪的那一刻；只认第一次。 */
    public synchronized void ready(long now) {
        if (ready) return;
        readyAt = now;
        ready = true;
        // 就绪即关掉当前阶段：否则「服务已就绪」的时长会随着窗口挂着一直涨。
        append(now, stage, formatDuration(now - stageBegan), STAGE_DONE);
        stage = "运行中";
        stageBegan = now;
        revision++;
        notifyAll();
    }

    /** 结束启动计时，但不丢后续进程输出。 */
    public synchronized void completed(long now) {
        if (endedAt >= 0) return;
        endedAt = now;
        revision++;
        notifyAll();
    }

    /**
     * 长轮询等待：当 revision 超过 {@code seen} 时立刻返回，否则最多等到 deadline。
     *
     * <p>前端保持一条挂起的长请求，有输出立刻渲染，不必高频空转。
     */
    public synchronized void awaitRevision(long seen, long deadline) {
        while (revision <= seen) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) return;
            try {
                wait(Math.max(1L, left));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    /** 当前 revision，供长轮询判断「有没有新东西」。 */
    public synchronized long revision() {
        return revision;
    }

    public synchronized Snapshot snapshot(long now) {
        if (renderedRevision != revision) {
            renderedLog = renderLog();
            renderedEntries = renderEntries();
            renderedRevision = revision;
        }
        long base = began < 0 ? now : began;
        long end = ready ? readyAt : endedAt >= 0 ? endedAt : now;
        return new Snapshot(
                revision,
                Math.max(0, end - base),
                Math.max(0, end - stageBegan),
                Math.max(0, (endedAt >= 0 ? endedAt : now) - base),
                readyAt < 0 ? -1 : Math.max(0, readyAt - base),
                stage,
                renderedLog,
                ready,
                endedAt >= 0,
                new LinkedHashMap<>(issues),
                renderedEntries,
                dropped);
    }

    private List<Entry> renderEntries() {
        List<Entry> result = new ArrayList<>(lines.size());
        for (Line line : lines)
            result.add(new Entry(
                    line.at, line.runtimeAt, line.kind, line.body(), line.render(), line.problem));
        return Collections.unmodifiableList(result);
    }

    private String renderLog() {
        StringBuilder result = new StringBuilder();
        for (Line line : lines) {
            if (result.length() > 0) result.append('\n');
            result.append(line.render());
        }
        return result.toString();
    }

    private static String format(long ms) {
        return formatDuration(ms);
    }

    static String formatDuration(long ms) {        if (ms < 1000) return Math.max(0, ms) + "ms";
        return String.format(Locale.ROOT, "%.1fs", ms / 1000.0);
    }

    /** 去 ANSI 转义、去控制字符、脱敏。 */
    static String clean(String text) {
        String value = text == null ? "" : text;
        value = ANSI.matcher(value).replaceAll("");
        value = CTRL.matcher(value).replaceAll("");
        return SensitiveData.redact(value).trim();
    }
}
