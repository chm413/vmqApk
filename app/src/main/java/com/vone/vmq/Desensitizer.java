package com.vone.vmq;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 日志脱敏。
 *
 * <p>原则：<b>只遮标识信息，保留结构信息</b>。主机地址、通讯密钥、签名、通知正文里的
 * 昵称与备注属于标识信息，必须遮；协议、端口、路径、状态码、金额、耗时属于排查必需的结构信息，
 * 一律保留。过度脱敏会让日志失去价值，等于没记。</p>
 *
 * <p>注意：<b>配置页面不经过本类</b>，用户在设置页看到的必须是真实填写值，否则没法核对。</p>
 */
public final class Desensitizer {

    private static final String MASK = "****";

    private static final Pattern IPV4 =
            Pattern.compile("^(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})\\.(\\d{1,3})$");

    /** 查询串里的敏感参数：sign / key / token / secret / password */
    private static final Pattern SENSITIVE_QUERY =
            Pattern.compile("(?i)\\b(sign|key|token|secret|password|pwd)=([^&\\s\"']*)");

    /** URL 形态：scheme://authority[/path] */
    private static final Pattern URL_LIKE =
            Pattern.compile("\\b(https?)://([^/\\s\"']+)([^\\s\"']*)?");

    private Desensitizer() {
    }

    /**
     * 主机脱敏。
     * <ul>
     *   <li>IPv4 {@code 192.168.1.10:8080} → {@code 192.168.*.*:8080}（保留前两段，端口保留）</li>
     *   <li>域名 {@code pay.example.com} → {@code *.example.com}（保留后两段）</li>
     *   <li>双段域名 {@code example.com} → {@code e****.com}</li>
     *   <li>IPv6 / 其他 → 整段遮</li>
     * </ul>
     */
    public static String host(String host) {
        if (host == null || host.isEmpty()) {
            return "";
        }
        String h = host.trim();
        String port = "";
        // 只在"恰好一个冒号"时拆端口，避免把 IPv6 拆坏
        int firstColon = h.indexOf(':');
        int lastColon = h.lastIndexOf(':');
        if (firstColon > 0 && firstColon == lastColon) {
            port = h.substring(lastColon);
            h = h.substring(0, lastColon);
        }
        if (h.isEmpty()) {
            return MASK + port;
        }
        if (h.startsWith("[")) {
            return "[****]" + port;
        }
        Matcher ip = IPV4.matcher(h);
        if (ip.matches()) {
            return ip.group(1) + "." + ip.group(2) + ".*.*" + port;
        }
        String[] parts = h.split("\\.");
        if (parts.length >= 3) {
            return "*." + parts[parts.length - 2] + "." + parts[parts.length - 1] + port;
        }
        if (parts.length == 2) {
            return maskMiddle(parts[0], 1, 0) + "." + parts[1] + port;
        }
        return maskMiddle(h, 1, 0) + port;
    }

    /** 通讯密钥：保留首尾各 4 位便于比对，中间全遮。6 位及以下整体遮。 */
    public static String key(String key) {
        if (key == null || key.isEmpty()) {
            return "";
        }
        int n = key.length();
        if (n <= 6) {
            return repeat('*', 6);
        }
        int keep = Math.min(4, n / 3);
        return key.substring(0, keep) + MASK + key.substring(n - keep);
    }

    /** 任意密钥类字符串整体遮蔽，不保留任何原文。 */
    public static String secret(String value) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        return MASK + "(" + value.length() + ")";
    }

    /**
     * URL 脱敏：authority 按 {@link #host} 处理，敏感查询参数值整体替换为 ****。
     * 路径与普通参数保留。
     */
    public static String url(String url) {
        if (url == null || url.isEmpty()) {
            return "";
        }
        String result = url;
        Matcher m = URL_LIKE.matcher(url);
        StringBuffer sb = new StringBuffer();
        while (m.find()) {
            String scheme = m.group(1);
            String authority = host(m.group(2));
            String rest = m.group(3) == null ? "" : m.group(3);
            m.appendReplacement(sb, Matcher.quoteReplacement(scheme + "://" + authority + rest));
        }
        m.appendTail(sb);
        result = sb.toString();
        // 敏感参数值替换，保留参数名，便于看出请求带了哪些参数
        return SENSITIVE_QUERY.matcher(result).replaceAll("$1=****");
    }

    /**
     * 自由文本脱敏（用于通知正文、服务端回包等）。
     * 保留首尾各 {@code keepHead} / {@code keepTail} 个字符，并标注原长度。
     *
     * <p>这样既能看出"是不是一条到账消息"，又不会把金额、昵称、备注原样落到日志里。</p>
     */
    public static String text(String value, int keepHead, int keepTail) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        String t = value.replaceAll("\\s+", " ").trim();
        int n = t.length();
        int head = Math.max(0, keepHead);
        int tail = Math.max(0, keepTail);
        if (n <= head + tail + 4) {
            // 太短时保留长度信息即可，避免遮了跟没遮一样
            return MASK + "(" + n + "字)";
        }
        return t.substring(0, head) + "…(" + n + "字)…" + t.substring(n - tail);
    }

    /** 文本脱敏的常用预设：通知标题/正文这种，只保留首尾各 2 字。 */
    public static String text(String value) {
        return text(value, 2, 2);
    }

    /**
     * 兜底脱敏：对一段已经拼好的日志做扫描，把 URL、敏感查询参数、疑似密钥遮掉。
     * 用于那些无处不在地拼进日志的外部字符串，避免漏网。
     */
    public static String any(String raw) {
        if (raw == null || raw.isEmpty()) {
            return "";
        }
        return url(raw);
    }

    private static String maskMiddle(String value, int keepHead, int keepTail) {
        if (value == null || value.isEmpty()) {
            return "";
        }
        int n = value.length();
        if (n <= keepHead + keepTail + 1) {
            return repeat('*', Math.max(4, n));
        }
        return value.substring(0, keepHead) + MASK + value.substring(n - keepTail);
    }

    private static String repeat(char c, int count) {
        if (count <= 0) {
            return "";
        }
        StringBuilder sb = new StringBuilder(count);
        for (int i = 0; i < count; i++) {
            sb.append(c);
        }
        return sb.toString();
    }
}
