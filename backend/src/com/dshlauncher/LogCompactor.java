package com.dshlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 只压缩<b>显示副本</b>；数字、路径和记录顺序具有诊断意义，不作为重复噪声抹掉。
 *
 * <p>移植自 DSHA 的 {@code LogCompactor}。和「把所有数字换成 #」那种粗暴归一化不同，
 * 这里要求正文<b>完全一致</b>才折叠——{@code 端口 7080} 和 {@code 端口 7081} 是两件事，
 * 折叠掉就等于丢证据。折叠后保留首末时间戳与次数，让人知道被省略了多少。
 */
public final class LogCompactor {
    private LogCompactor() {}

    /** 行首的时间前缀：绝对时间 / 单调毫秒 / 秒级耗时，三种都不会参与重复比较。 */
    private static final Pattern PREFIX =
            Pattern.compile("^(?:\\d{2}:\\d{2}:\\d{2}|\\d{2}-\\d{2} \\d{2}:\\d{2}:\\d{2}|\\[\\d{10,}\\]|\\[\\d+(?:\\.\\d+)?s\\]) ");

    /** 明确的失败线索，压缩时优先保留。 */
    private static final Pattern IMPORTANT = Pattern.compile(
            "(?i)(exception|error|failed|failure|fatal|crash|denied|read-only|killed|signal|errno"
                    + "|超时|失败|崩溃|异常|拒绝)");

    /**
     * 折叠连续且正文完全相同的行；时间前缀以首末时间和次数保留。
     */
    public static String foldRepeats(String text) {
        if (text == null || text.isEmpty()) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; ) {
            String body = body(lines[i]);
            int end = i + 1;
            // 太短的行（"ok"、"="）不折叠，否则正常内容也会被当成重复
            if (body.length() >= 12) {
                while (end < lines.length && body.equals(body(lines[end]))) end++;
            }
            if (i > 0) out.append('\n');
            out.append(lines[i]);
            if (end > i + 1) {
                out.append("  (×").append(end - i);
                String first = stamp(lines[i]), last = stamp(lines[end - 1]);
                if (!first.isEmpty() && !last.isEmpty() && !first.equals(last))
                    out.append(", ").append(first).append(" ~ ").append(last);
                out.append(')');
            }
            i = end;
        }
        return out.toString();
    }

    /** 只留头尾，中间省略；失败线索无论在哪都保留。 */
    public static String headTail(String text, int head, int tail) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        if (lines.length <= head + tail + 2) return text;
        List<String> kept = new ArrayList<>();
        boolean omitted = false;
        for (int i = 0; i < lines.length; i++) {
            if (i < head || i >= lines.length - tail || IMPORTANT.matcher(lines[i]).find()) {
                if (omitted) {
                    kept.add(marker());
                    omitted = false;
                }
                kept.add(lines[i]);
            } else {
                omitted = true;
            }
        }
        if (omitted) kept.add(marker());
        return String.join("\n", kept);
    }

    /** 真正包含标记的字符上限；超长单行也保留行尾，避免丢掉错误路径。 */
    public static String cap(String text, int maxChars) {
        if (maxChars < 0) throw new IllegalArgumentException("NEGATIVE_LOG_LIMIT");
        if (text == null) return "";
        return text;
    }

    private static String body(String line) {
        return PREFIX.matcher(line).replaceFirst("");
    }

    private static String stamp(String line) {
        Matcher match = PREFIX.matcher(line);
        return match.find() ? match.group().trim() : "";
    }

    private static String marker() {
        return "… [已省略部分显示内容，原始记录仍保留] …";
    }
}
