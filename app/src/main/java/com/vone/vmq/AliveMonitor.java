package com.vone.vmq;

import android.app.AppOpsManager;
import android.app.usage.UsageEvents;
import android.app.usage.UsageStats;
import android.app.usage.UsageStatsManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Process;

import java.util.List;

/**
 * 微信 / 支付宝 存活监控。
 *
 * <h3>为什么需要分层</h3>
 * Android 上非 root 应用<b>无法</b>直接判断其他应用的进程是否存活：
 * <ul>
 *   <li>{@code ActivityManager.getRunningAppProcesses()} 自 API 22 起只返回调用者自己的进程</li>
 *   <li>{@code getRunningServices()} 自 API 26 起同样受限</li>
 * </ul>
 * 因此这里按能力分层，<b>绝不把"不知道"伪装成"没运行"</b>：
 * <ol>
 *   <li><b>Shizuku</b>：{@code pidof <pkg>}，退出码 0 且输出非空即确实在跑。这是唯一精确的来源。</li>
 *   <li><b>UsageStatsManager</b>：需用户在系统设置授予"使用情况访问权限"。
 *       只能推断"最近 {@value #USAGE_WINDOW_MIN} 分钟内有过前台活动"，不等于进程存活。</li>
 *   <li>两者都不可用 → {@link State#UNKNOWN}，界面明确显示"无法检测"。</li>
 * </ol>
 */
public final class AliveMonitor {

    /** SharedPreferences 沿用全局的 "vone" */
    private static final String PREFS = AppLog.PREFS;

    public static final String SLOT_ALIPAY = "alipay";
    public static final String SLOT_WECHAT = "wechat";

    public static final String PREF_ENABLED = "alive_monitor_enabled";
    public static final String PREF_INTERVAL_MIN = "alive_interval_min";
    public static final int DEFAULT_INTERVAL_MIN = 5;

    private static final int USAGE_WINDOW_MIN = 10;

    private static final String TAG = "Alive";

    public enum State {
        RUNNING("运行中"),
        NOT_RUNNING("未运行"),
        ACTIVE_RECENTLY("最近活跃"),
        NO_ACTIVITY("无前台活动"),
        UNKNOWN("无法检测");

        public final String label;

        State(String label) {
            this.label = label;
        }
    }

    /** 一次检测的结果。 */
    public static final class Status {
        public final String pkg;
        public final State state;
        /** 检测手段：pidof / usage / none */
        public final String method;
        public final String detail;
        public final long at;

        Status(String pkg, State state, String method, String detail, long at) {
            this.pkg = pkg;
            this.state = state;
            this.method = method;
            this.detail = detail;
            this.at = at;
        }

        public String summary() {
            return state.label + "（" + detail + "）";
        }
    }

    private AliveMonitor() {
    }

    public static String slotOf(String pkg) {
        if (ShizukuKeepAlive.PKG_ALIPAY.equals(pkg)) {
            return SLOT_ALIPAY;
        }
        if (ShizukuKeepAlive.PKG_WECHAT.equals(pkg)) {
            return SLOT_WECHAT;
        }
        return "other";
    }

    public static String displayName(String pkg) {
        if (ShizukuKeepAlive.PKG_ALIPAY.equals(pkg)) {
            return "支付宝";
        }
        if (ShizukuKeepAlive.PKG_WECHAT.equals(pkg)) {
            return "微信";
        }
        return pkg;
    }

    public static boolean isMonitorEnabled(Context context) {
        return prefs(context).getBoolean(PREF_ENABLED, true);
    }

    public static int getIntervalMinutes(Context context) {
        int min = prefs(context).getInt(PREF_INTERVAL_MIN, DEFAULT_INTERVAL_MIN);
        return min < 1 ? DEFAULT_INTERVAL_MIN : min;
    }

    public static void setMonitorEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(PREF_ENABLED, enabled).apply();
    }

    public static void setIntervalMinutes(Context context, int minutes) {
        prefs(context).edit().putInt(PREF_INTERVAL_MIN, Math.max(1, minutes)).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 检测 ----------------

    /** 执行一次检测（可能阻塞，必须在后台线程调用）。 */
    public static Status check(Context context, String pkg) {
        Status byShizuku = checkByShizuku(context, pkg);
        if (byShizuku != null) {
            return byShizuku;
        }
        Status byUsage = checkByUsageStats(context, pkg);
        if (byUsage != null) {
            return byUsage;
        }
        return new Status(pkg, State.UNKNOWN, "none",
                "未安装/未授权 Shizuku，且未授予使用情况访问权限", System.currentTimeMillis());
    }

    private static Status checkByShizuku(Context context, String pkg) {
        if (!ShizukuKeepAlive.isGranted()) {
            return null;
        }
        String raw;
        try {
            raw = ShizukuKeepAlive.exec(context, "pidof " + pkg);
        } catch (Throwable t) {
            AppLog.w(TAG, "pidof " + pkg + " 调用异常: " + t.getClass().getSimpleName());
            return null;
        }
        int exit = parseExit(raw);
        String output = stripExit(raw);
        long now = System.currentTimeMillis();
        if (exit == 0) {
            if (output.isEmpty()) {
                return new Status(pkg, State.NOT_RUNNING, "pidof", "pidof 无输出", now);
            }
            return new Status(pkg, State.RUNNING, "pidof", "pid=" + firstToken(output), now);
        }
        if (exit == 1) {
            // pidof 在进程不存在时返回 1
            return new Status(pkg, State.NOT_RUNNING, "pidof", "pidof 未找到进程", now);
        }
        // exit 126/127 等说明命令本身没跑起来，不能据此判定应用状态
        AppLog.w(TAG, "pidof " + pkg + " 返回 exit=" + exit + "，本次检测作废");
        return null;
    }

    private static Status checkByUsageStats(Context context, String pkg) {
        if (!hasUsageStatsPermission(context)) {
            return null;
        }
        try {
            UsageStatsManager usm =
                    (UsageStatsManager) context.getSystemService(Context.USAGE_STATS_SERVICE);
            if (usm == null) {
                return null;
            }
            long now = System.currentTimeMillis();
            long from = now - USAGE_WINDOW_MIN * 60_000L;

            // ① 先看 lastTimeUsed。比扫事件更可靠：应用一直停在前台时，
            //    MOVE_TO_FOREGROUND 只会在切入那一刻产生一次，扫窗口内的"新事件"
            //    会把「一直在用」误判成「无前台活动」。
            long lastUsed = queryLastTimeUsed(usm, pkg, from, now);
            if (lastUsed >= from) {
                long ago = Math.max(0L, (now - lastUsed) / 60_000L);
                return new Status(pkg, State.ACTIVE_RECENTLY, "usage",
                        ago + " 分钟前有前台活动", now);
            }

            // ② 事件流兜底：个别 ROM 的 queryUsageStats 返回空，但事件仍在
            if (hasForegroundEvent(usm, pkg, from, now)) {
                return new Status(pkg, State.ACTIVE_RECENTLY, "usage",
                        "近 " + USAGE_WINDOW_MIN + " 分钟有前台事件", now);
            }

            return new Status(pkg, State.NO_ACTIVITY, "usage",
                    "近 " + USAGE_WINDOW_MIN + " 分钟无前台活动（不代表进程已退出）", now);
        } catch (Throwable t) {
            AppLog.w(TAG, "UsageStats 查询失败: " + t.getClass().getSimpleName());
            return null;
        }
    }

    /** 该包在窗口内最后一次被使用的时间戳，取不到返回 0。 */
    private static long queryLastTimeUsed(UsageStatsManager usm, String pkg, long from, long to) {
        List<UsageStats> stats = usm.queryUsageStats(UsageStatsManager.INTERVAL_DAILY, from, to);
        if (stats == null) {
            return 0L;
        }
        long best = 0L;
        for (UsageStats s : stats) {
            if (s != null && pkg.equals(s.getPackageName())) {
                best = Math.max(best, s.getLastTimeUsed());
            }
        }
        return best;
    }

    private static boolean hasForegroundEvent(
            UsageStatsManager usm, String pkg, long from, long to) {
        UsageEvents events = usm.queryEvents(from, to);
        if (events == null) {
            return false;
        }
        UsageEvents.Event event = new UsageEvents.Event();
        while (events.hasNextEvent()) {
            events.getNextEvent(event);
            if (!pkg.equals(event.getPackageName())) {
                continue;
            }
            if (event.getEventType() == UsageEvents.Event.MOVE_TO_FOREGROUND) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasUsageStatsPermission(Context context) {
        try {
            AppOpsManager ops = (AppOpsManager) context.getSystemService(Context.APP_OPS_SERVICE);
            if (ops == null) {
                return false;
            }
            int mode = ops.checkOpNoThrow(AppOpsManager.OPSTR_GET_USAGE_STATS,
                    Process.myUid(), context.getPackageName());
            if (mode == AppOpsManager.MODE_DEFAULT) {
                return context.checkCallingOrSelfPermission(
                        android.Manifest.permission.PACKAGE_USAGE_STATS)
                        == PackageManager.PERMISSION_GRANTED;
            }
            return mode == AppOpsManager.MODE_ALLOWED;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * 检测并把结果落库 + 记日志。状态发生变化时额外记一条 WARN，便于事后回溯。
     */
    public static Status checkAndRecord(Context context, String pkg) {
        Status status = check(context, pkg);
        String slot = slotOf(pkg);
        String name = displayName(pkg);
        State previous = readState(context, slot);

        prefs(context).edit()
                .putString("alive_" + slot + "_state", status.state.name())
                .putLong("alive_" + slot + "_at", status.at)
                .putString("alive_" + slot + "_detail", status.detail)
                .putString("alive_" + slot + "_method", status.method)
                .apply();

        AppLog.i(TAG, name + " " + status.state.label + " | " + status.method
                + " | " + status.detail);
        if (previous != null && previous != status.state) {
            AppLog.w(TAG, name + " 状态变化: " + previous.label + " → " + status.state.label);
        } else if (previous == null) {
            AppLog.i(TAG, name + " 首次记录状态: " + status.state.label);
        }
        return status;
    }

    /** 读取上次记录的状态，从未检测过返回 null。 */
    public static State readState(Context context, String slot) {
        String raw = prefs(context).getString("alive_" + slot + "_state", null);
        if (raw == null) {
            return null;
        }
        try {
            return State.valueOf(raw);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    public static String readDetail(Context context, String slot) {
        return prefs(context).getString("alive_" + slot + "_detail", "");
    }

    public static String readMethod(Context context, String slot) {
        return prefs(context).getString("alive_" + slot + "_method", "");
    }

    public static long readAt(Context context, String slot) {
        return prefs(context).getLong("alive_" + slot + "_at", 0L);
    }

    /** 给 UI 用的一行摘要。 */
    public static String summaryOf(Context context, String slot, String name) {
        State state = readState(context, slot);
        if (state == null) {
            return name + "：尚未检测";
        }
        long at = readAt(context, slot);
        String time = "";
        if (at > 0) {
            time = " · " + new java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                    .format(new java.util.Date(at));
        }
        return name + "：" + state.label + time;
    }

    // ---------------- Shizuku 输出解析 ----------------

    private static int parseExit(String raw) {
        if (raw == null) {
            return -1;
        }
        int idx = raw.lastIndexOf("exit=");
        if (idx < 0) {
            return -1;
        }
        try {
            return Integer.parseInt(raw.substring(idx + 5).trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static String stripExit(String raw) {
        if (raw == null) {
            return "";
        }
        int idx = raw.lastIndexOf("exit=");
        String body = idx < 0 ? raw : raw.substring(0, idx);
        return body.trim();
    }

    private static String firstToken(String text) {
        String[] parts = text.split("\\s+");
        for (String p : parts) {
            if (!p.isEmpty()) {
                return p;
            }
        }
        return "";
    }
}
