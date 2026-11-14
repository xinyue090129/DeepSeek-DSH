package com.dshlauncher;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 日志脱敏：把不该出现在屏幕上的东西换成占位符。
 *
 * <p>移植自 DSHA 的 {@code SensitiveData}，但只保留启动器真正会碰到的那几类：
 *
 * <ul>
 *   <li>常见凭据形状（Bearer / api key / password= / 私钥块）；
 *   <li>家目录绝对路径——界面上 {@code ~/...} 更短，也少暴露一层结构。
 * </ul>
 *
 * <p>注意：工作台 launch token <b>不打码</b>。它是本机 127.0.0.1 上的一次性地址，
 * 用户需要能直接复制粘贴到浏览器；把它涂掉反而挡住了唯一的入口。
 *
 * <p>只用于<b>展示</b>，原始 stdout 不做替换，启动判定仍看原文。
 */
public final class SensitiveData {
    private SensitiveData() {}

    /** 已知敏感值（私钥等），整体替换。 */
    private static final List<String> KNOWN = new ArrayList<>();

    private static final Pattern[] RULES = {
        // Authorization: Bearer xxx
        Pattern.compile("(?i)(bearer\\s+)[A-Za-z0-9._\\-]{8,}"),
        // api_key= / apikey: / password= / secret=
        Pattern.compile("(?i)((?:api[_-]?key|apikey|password|passwd|secret|access[_-]?token)\\s*[=:]\\s*)\\S{6,}"),
        // sk-xxxxxx 形态的模型 key
        Pattern.compile("\\b(sk-[A-Za-z0-9]{12,})\\b"),
        // PEM 私钥块
        Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?-----END [A-Z ]*PRIVATE KEY-----"),
    };

    private static final String[] REPLACEMENTS = {
        "Bearer ***", "$1***", "***", "[私钥已隐藏]",
    };

    /** 登记一个本次真实出现的敏感值（例如私钥），以后凡是它出现都被替换。 */
    public static synchronized void remember(String secret) {
        if (secret == null || secret.length() < 8) return;
        if (!KNOWN.contains(secret)) KNOWN.add(secret);
        while (KNOWN.size() > 20) KNOWN.remove(0);
    }

    public static String redact(String s) {
        if (s == null || s.isEmpty()) return s;
        String safe = s;
        List<String> known;
        synchronized (SensitiveData.class) {
            known = new ArrayList<>(KNOWN);
        }
        // 长的先换，避免短值把长值切碎
        known.sort((a, b) -> Integer.compare(b.length(), a.length()));
        for (String secret : known) safe = safe.replace(secret, "***");
        for (int i = 0; i < RULES.length; i++) {
            safe = RULES[i].matcher(safe).replaceAll(REPLACEMENTS[i]);
        }
        String home = Backend.HOME;
        if (home != null && !home.isEmpty() && !"/".equals(home)) safe = safe.replace(home, "~");
        return safe;
    }
}
