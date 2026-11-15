package com.vone.vmq;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Process;
import android.provider.Settings;
import android.text.TextUtils;

/**
 * 定时重启进程（实验性）。
 *
 * <h3>要解决什么</h3>
 * Android 15（API 35）起，{@code dataSync} 类型的前台服务有<b>累计 6 小时/天</b>的
 * 运行时长上限，超限后系统拒绝再次启动该类型前台服务（{@code ForegroundServiceStartNotAllowedException}）。
 * 本应用的「前台重试窗口」正是 dataSync 类型，一旦当天额度用尽，
 * 心跳失败后的前台重试链路就整体失效，只能等次日重置。
 *
 * <p>重启进程能让系统重新计算配额吗？<b>不一定。</b>配额记在系统侧的 uid 维度，
 * 跨进程通常保留。所以本选项<b>不是可靠解法</b>，只是留给用户在特定 ROM 上自行验证的
 * 实验开关 —— 部分机型（重启后按新进程计时）确实有效，部分机型完全无效。
 * 默认关闭，界面明确标注"实验性"。</p>
 *
 * <h3>风险与前置校验</h3>
 * 杀进程最直接的代价是 <b>可能丢一条收款通知</b>：通知监听事件不会被重投递，
 * 重启窗口内到达的推送就永久丢了。因此这里做了三重避让：
 * <ol>
 *   <li><b>必须有通知监听授权</b>：否则进程死了没人重绑，应用彻底停摆到下次手动打开。</li>
 *   <li><b>必须有运行中的链路</b>：监听与上报两个开关都关时无意义，直接跳过。</li>
 *   <li><b>静默窗口</b>：最近 {@value #QUIET_MS} 毫秒内有过收款推送或心跳收发，本轮跳过，
 *       等下一轮（约 5 分钟后）再试，避免打断在途请求。</li>
 * </ol>
 *
 * <h3>为什么用 commit() 而不是 apply()</h3>
 * 重启时间戳必须在进程被杀死<b>之前</b>真正落盘。{@code apply()} 是异步写，
 * 进程一死就丢了 —— 重启后读到的仍是旧时间戳，立刻又满足"该重启了"，形成重启风暴。
 */
public final class AutoRestart {

    private static final String TAG = "AutoRestart";

    private static final String PREFS = AppLog.PREFS;

    /** 开关 */
    public static final String PREF_ENABLED = "auto_restart_enabled";
    /** 间隔（分钟） */
    public static final String PREF_INTERVAL_MIN = "auto_restart_interval_min";
    /** 上次重启时间戳 */
    private static final String PREF_LAST_AT = "auto_restart_last_at";

    public static final int DEFAULT_INTERVAL_MIN = 180;
    /** 下限，防止用户把间隔调到几分钟导致反复重启 */
    public static final int MIN_INTERVAL_MIN = 30;

    /** 可选间隔，给设置页的选项列表用。 */
    public static final int[] INTERVAL_OPTIONS = {30, 60, 120, 180, 360, 720};

    /** 静默窗口：这段时间内有过推送或心跳就不重启。 */
    private static final long QUIET_MS = 90_000L;

    private AutoRestart() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 配置 ----------------

    public static boolean isEnabled(Context context) {
        return context != null && prefs(context).getBoolean(PREF_ENABLED, false);
    }

    public static int getIntervalMinutes(Context context) {
        if (context == null) {
            return DEFAULT_INTERVAL_MIN;
        }
        int min = prefs(context).getInt(PREF_INTERVAL_MIN, DEFAULT_INTERVAL_MIN);
        return min < MIN_INTERVAL_MIN ? DEFAULT_INTERVAL_MIN : min;
    }

    /**
     * 开关。打开时同时把"上次重启时间"重置为现在，
     * 否则关掉很久再打开会立刻满足间隔条件，一开就重启。
     */
    public static void setEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        SharedPreferences.Editor editor = prefs(context).edit()
                .putBoolean(PREF_ENABLED, enabled);
        if (enabled) {
            editor.putLong(PREF_LAST_AT, System.currentTimeMillis());
        }
        editor.apply();
    }

    public static void setIntervalMinutes(Context context, int minutes) {
        if (context == null) {
            return;
        }
        int value = Math.max(MIN_INTERVAL_MIN, minutes);
        prefs(context).edit()
                .putInt(PREF_INTERVAL_MIN, value)
                // 改间隔视为重新计时，避免刚改成 30 分钟就立刻触发
                .putLong(PREF_LAST_AT, System.currentTimeMillis())
                .apply();
    }

    /** 距下次重启还有多少毫秒；{@code <=0} 表示已到期。给界面展示用。 */
    public static long millisUntilNext(Context context) {
        if (context == null || !isEnabled(context)) {
            return -1L;
        }
        long last = prefs(context).getLong(PREF_LAST_AT, 0L);
        if (last <= 0L) {
            return 0L;
        }
        long due = last + getIntervalMinutes(context) * 60_000L;
        return due - System.currentTimeMillis();
    }

    /** 给 UI 用的一句话状态。 */
    public static String summary(Context context) {
        if (!isEnabled(context)) {
            return "未开启";
        }
        long remain = millisUntilNext(context);
        if (remain <= 0L) {
            return "已到期，等待静默窗口";
        }
        long min = remain / 60_000L;
        if (min >= 60) {
            return "约 " + (min / 60) + " 小时 " + (min % 60) + " 分钟后";
        }
        return "约 " + Math.max(1, min) + " 分钟后";
    }

    // ---------------- 执行 ----------------

    /**
     * 检查是否该重启，该重启就重启（正常情况下本方法执行不到返回）。
     *
     * <p>必须在后台线程调用。</p>
     *
     * @return {@code true} 表示已发起重启（进程即将退出）；{@code false} 表示本轮跳过
     */
    public static boolean maybeRestart(Context context) {
        if (context == null || !isEnabled(context)) {
            return false;
        }

        // ① 通知监听授权是这个开关的生命线：没有它，进程死了就没人把服务绑回来，
        //    应用会彻底停摆到用户下次手动打开，比不重启糟糕得多。
        if (!isListenerGranted(context)) {
            AppLog.w(TAG, "已开启定时重启，但通知监听授权已失效，跳过重启（否则进程无法自动恢复）");
            return false;
        }

        // ② 监听与上报都关时，进程重启没有任何意义
        if (!RunSwitch.isAnyActive(context)) {
            return false;
        }

        // ③ 间隔未到（首次使用 last 为 0 时视为刚启用，从此刻开始计时）
        SharedPreferences p = prefs(context);
        long now = System.currentTimeMillis();
        long last = p.getLong(PREF_LAST_AT, 0L);
        if (last <= 0L) {
            p.edit().putLong(PREF_LAST_AT, now).commit();
            AppLog.i(TAG, "定时重启开始计时，间隔 " + getIntervalMinutes(context) + " 分钟");
            return false;
        }
        long due = last + getIntervalMinutes(context) * 60_000L;
        if (now < due) {
            return false;
        }

        // ④ 静默窗口：最近有推送或心跳就避开，防止打断在途请求、丢掉刚到的收款通知
        long recent = Math.max(p.getLong("last_push_at", 0L),
                Math.max(p.getLong("last_heart_ok_at", 0L), p.getLong("last_heart_fail_at", 0L)));
        if (recent > 0L && now - recent < QUIET_MS) {
            AppLog.d(TAG, "定时重启已到期，但 " + (now - recent) + "ms 前仍有推送/心跳活动，本轮跳过");
            return false;
        }

        return killSelf(context, p, now);
    }

    private static boolean killSelf(Context context, SharedPreferences p, long now) {
        // 先把时间戳真正落盘（commit 是同步写），再杀进程。
        // 用 apply() 会丢：进程一死异步写就没机会执行，重启后又判到期 → 无限重启。
        if (!p.edit().putLong(PREF_LAST_AT, now).commit()) {
            AppLog.w(TAG, "重启时间戳落盘失败，本次放弃重启");
            return false;
        }
        int interval = getIntervalMinutes(context);
        AppLog.event("Run", "定时重启进程（实验性）",
                "已到期，间隔 " + interval + " 分钟；进程即将退出，监听服务由系统重新绑定");
        Process.killProcess(Process.myPid());
        // 正常情况下走不到这里；万一 kill 被拦下，下一轮仍会重新判定
        return true;
    }

    /** 通知监听授权是否仍然有效（系统侧的 ground truth，与本地 isRunning 无关）。 */
    private static boolean isListenerGranted(Context context) {
        try {
            String flat = Settings.Secure.getString(context.getContentResolver(),
                    "enabled_notification_listeners");
            if (TextUtils.isEmpty(flat)) {
                return false;
            }
            String pkg = context.getPackageName();
            for (String name : flat.split(":")) {
                if (name != null && name.startsWith(pkg + "/")) {
                    return true;
                }
            }
            return false;
        } catch (Throwable t) {
            // 读不到就当作未授权：宁可少重启一次，也不能把应用弄死
            return false;
        }
    }
}
