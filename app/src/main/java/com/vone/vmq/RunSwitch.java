package com.vone.vmq;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * 运行开关（总闸）与自动休眠。
 *
 * <h3>为什么需要它</h3>
 * 挂机场景下用户并不是 7×24 都真的在收款。但此前只要通知监听连着，
 * 心跳每 30 秒一次、存活检查每 5 分钟一轮，还会周期性把进程从 Doze 里唤醒——
 * 关掉收款时这些唤醒纯属浪费电。
 *
 * <h3>三个开关的语义</h3>
 * <ul>
 *   <li>{@link #PREF_LISTEN}：通知监听。关闭后 {@code onNotificationPosted} 直接丢弃，
 *       不解析、不推送、不落库。</li>
 *   <li>{@link #PREF_REPORT}：心跳上报。关闭后心跳线程不发请求、不退避重试、不唤前台。</li>
 *   <li>二者<b>都为关</b>时，保活（存活检查 / 自动拉起）也一并停止 —— 见 {@link #isAnyActive}。</li>
 * </ul>
 *
 * <h3>"停止但不是杀死"</h3>
 * 关闭开关时线程不会被 {@code interrupt}、进程不会被杀、通知监听服务仍然连着：
 * 线程会 {@link Object#wait()} 在 {@link #LOCK} 上，<b>零 CPU 占用</b>，不产生任何唤醒。
 * 重新打开开关时 {@link #notifyChanged()} 会立刻唤醒它们，最多损失一次循环间隔。
 *
 * <p>与 {@code Thread.sleep} 的区别：sleep 只能等够时间才醒，
 * 这里一旦开关变化立即返回，所以"关了之后马上安静"是即时的，不用等 5 分钟。</p>
 */
public final class RunSwitch {

    /** 通知监听开关 */
    public static final String PREF_LISTEN = "sw_listen";
    /** 心跳上报开关 */
    public static final String PREF_REPORT = "sw_report";
    /** 检测到微信/支付宝未运行时是否自动拉起（需提权通道） */
    public static final String PREF_AUTO_REVIVE = "sw_auto_revive";

    private static final String PREFS = AppLog.PREFS;

    private static final Object LOCK = new Object();

    private RunSwitch() {
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    // ---------------- 读取 ----------------

    public static boolean isListenEnabled(Context context) {
        return context != null && prefs(context).getBoolean(PREF_LISTEN, true);
    }

    public static boolean isReportEnabled(Context context) {
        return context != null && prefs(context).getBoolean(PREF_REPORT, true);
    }

    public static boolean isAutoReviveEnabled(Context context) {
        return context != null && prefs(context).getBoolean(PREF_AUTO_REVIVE, true);
    }

    /** 有没有任何一条链路需要干活。两者皆假 → 保活循环休眠。 */
    public static boolean isAnyActive(Context context) {
        return isListenEnabled(context) || isReportEnabled(context);
    }

    /** 给 UI 用的一句话状态。 */
    public static String summary(Context context) {
        boolean listen = isListenEnabled(context);
        boolean report = isReportEnabled(context);
        if (listen && report) {
            return "运行中";
        }
        if (!listen && !report) {
            return "已暂停";
        }
        return listen ? "仅监听（心跳已停）" : "仅心跳（监听已停）";
    }

    // ---------------- 写入 ----------------

    public static void setListenEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(PREF_LISTEN, enabled).apply();
        notifyChanged();
    }

    public static void setReportEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(PREF_REPORT, enabled).apply();
        notifyChanged();
    }

    public static void setAutoReviveEnabled(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        prefs(context).edit().putBoolean(PREF_AUTO_REVIVE, enabled).apply();
        notifyChanged();
    }

    /** 一键全开 / 全关（主页总开关用）。 */
    public static void setAll(Context context, boolean enabled) {
        if (context == null) {
            return;
        }
        prefs(context).edit()
                .putBoolean(PREF_LISTEN, enabled)
                .putBoolean(PREF_REPORT, enabled)
                .apply();
        notifyChanged();
    }

    // ---------------- 休眠原语 ----------------

    /**
     * 阻塞直到任一链路被打开。
     *
     * <p>线程在此处被 {@link Object#wait()} 挂起，不占 CPU、不产生定时唤醒，
     * 相当于把这条后台线程"冻结"住，但进程与监听服务仍然存活。</p>
     *
     * @return {@code true} 已恢复运行；{@code false} 被 interrupt（应退出线程）
     */
    public static boolean awaitActive(Context context) {
        if (context == null) {
            return false;
        }
        synchronized (LOCK) {
            while (!isAnyActive(context)) {
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 阻塞直到「心跳上报」被打开。
     *
     * <p>与 {@link #awaitActive} 的区别：这里只认 {@link #PREF_REPORT}。
     * 如果错用 awaitActive，在"监听开、上报关"的组合下会被立刻唤醒，
     * 循环回到顶部又发现上报是关的、又立刻唤醒 —— 变成忙等空转把 CPU 烧掉。</p>
     *
     * @return {@code false} 表示被 interrupt（应退出线程）
     */
    public static boolean awaitReportEnabled(Context context) {
        if (context == null) {
            return false;
        }
        synchronized (LOCK) {
            while (!isReportEnabled(context)) {
                try {
                    LOCK.wait();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * 可被打断的等待，用来替代 {@code Thread.sleep}。
     *
     * <p>开关一变就立刻返回，调用方应重新判断状态再决定下一步。</p>
     *
     * @return {@code false} 表示被 interrupt（应退出线程）
     */
    public static boolean sleepOrWakeup(long millis) {
        if (millis <= 0) {
            return true;
        }
        synchronized (LOCK) {
            try {
                LOCK.wait(millis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return true;
    }

    /** 任一开关发生变化时唤醒所有等待线程。 */
    public static void notifyChanged() {
        synchronized (LOCK) {
            LOCK.notifyAll();
        }
    }
}
