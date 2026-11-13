package com.vone.vmq;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;

import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 保活通道。
 *
 * <p>原理：Shizuku 以 shell(uid 2000) 或 root 身份提供 Binder，本类借此把
 * 本应用、支付宝、微信的后台存活等级拉高（Doze 白名单 + active 待机桶 + 后台 appop），
 * 并自动授予 Android 15 的 RECEIVE_SENSITIVE_NOTIFICATIONS 权限。</p>
 *
 * <p>命令的实际执行者是 {@link RemoteShellService}——一个由 Shizuku 以 shell/root 身份
 * 托管的 user service 进程。这里采用的是 Shizuku 官方推荐做法
 * （{@code bindUserService}），而非 {@code Shizuku.newProcess}：
 * 后者自 13.1.1 起已私有化，并计划在 API 14 移除。</p>
 *
 * <p>未安装 Shizuku 时所有方法安全降级（isAvailable=false），不影响应用其他功能。</p>
 */
public final class ShizukuKeepAlive {

    public static final int REQUEST_CODE = 4001;

    /**
     * user service 版本号。{@link RemoteShellService} 的逻辑一旦变更就必须递增，
     * 否则 Shizuku 会继续复用旧进程里加载的旧代码。
     */
    private static final int USER_SERVICE_VERSION = 1;

    /** 绑定 user service 的最长等待时间。 */
    private static final long BIND_TIMEOUT_MS = 8000L;

    /** 支付宝 */
    public static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    /** 微信 */
    public static final String PKG_WECHAT = "com.tencent.mm";

    private static final Object LOCK = new Object();
    private static IRemoteShell remoteShell;
    private static Shizuku.UserServiceArgs userServiceArgs;
    private static boolean binding;

    private static final ServiceConnection CONNECTION = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            synchronized (LOCK) {
                remoteShell = IRemoteShell.Stub.asInterface(service);
                binding = false;
                LOCK.notifyAll();
            }
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            synchronized (LOCK) {
                remoteShell = null;
                binding = false;
                LOCK.notifyAll();
            }
        }
    };

    private ShizukuKeepAlive() {
    }

    public static boolean isAvailable() {
        try {
            return Shizuku.pingBinder();
        } catch (Throwable t) {
            return false;
        }
    }

    public static boolean isGranted() {
        try {
            return isAvailable() && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED;
        } catch (Throwable t) {
            return false;
        }
    }

    public static void requestPermission() {
        try {
            Shizuku.requestPermission(REQUEST_CODE);
        } catch (Throwable ignored) {
        }
    }

    /** 状态文案，直接用于设置页展示。 */
    public static String statusText() {
        if (!isAvailable()) {
            return "Shizuku：未运行（安装并启动 Shizuku 后可用）";
        }
        if (!isGranted()) {
            return "Shizuku：已运行，等待授权";
        }
        String version = "";
        try {
            version = " v" + Shizuku.getVersion();
        } catch (Throwable ignored) {
        }
        boolean bound;
        synchronized (LOCK) {
            bound = remoteShell != null;
        }
        return "Shizuku：已授权" + version + (bound ? "（执行通道已就绪）" : "（可下发保活命令）");
    }

    /**
     * 保活命令清单：本应用 + 支付宝 + 微信。
     * <ul>
     *   <li>set-standby-bucket active：脱离 rare/restricted 待机桶，后台窗口更宽松</li>
     *   <li>appops RUN_IN_BACKGROUND / RUN_ANY_IN_BACKGROUND：允许后台运行</li>
     *   <li>deviceidle whitelist：加入 Doze 白名单</li>
     *   <li>set-inactive false：从闲置状态唤醒</li>
     * </ul>
     */
    public static List<String> keepAliveCommands(String selfPkg) {
        List<String> cmds = new ArrayList<>();
        String[] pkgs = {selfPkg, PKG_ALIPAY, PKG_WECHAT};
        for (String pkg : pkgs) {
            cmds.add("am set-standby-bucket " + pkg + " active");
            cmds.add("cmd appops set " + pkg + " RUN_IN_BACKGROUND allow");
            cmds.add("cmd appops set " + pkg + " RUN_ANY_IN_BACKGROUND allow");
            cmds.add("cmd deviceidle whitelist +" + pkg);
            cmds.add("am set-inactive " + pkg + " false");
        }
        return cmds;
    }

    /** Android 15 敏感通知 appop：替代 README 中的 adb 手动命令。 */
    public static String sensitiveNotificationCommand(String selfPkg) {
        return "cmd appops set " + selfPkg + " RECEIVE_SENSITIVE_NOTIFICATIONS allow";
    }

    /** ADB 等效命令（未安装 Shizuku 时给用户在电脑上执行）。 */
    public static String adbEquivalents(String selfPkg) {
        StringBuilder sb = new StringBuilder();
        sb.append("adb shell am set-standby-bucket ").append(PKG_ALIPAY).append(" active\n");
        sb.append("adb shell am set-standby-bucket ").append(PKG_WECHAT).append(" active\n");
        sb.append("adb shell cmd deviceidle whitelist +").append(PKG_ALIPAY).append("\n");
        sb.append("adb shell cmd deviceidle whitelist +").append(PKG_WECHAT).append("\n");
        sb.append("adb shell cmd deviceidle whitelist +").append(selfPkg).append("\n");
        sb.append("adb shell ").append(sensitiveNotificationCommand(selfPkg));
        return sb.toString();
    }

    /**
     * 确保 user service 已绑定，阻塞至就绪或超时。
     *
     * <p><b>切勿在主线程调用</b>：绑定回调在主线程派发，主线程等待会死锁。</p>
     */
    public static boolean ensureBound(Context context) {
        if (context == null || !isGranted()) {
            return false;
        }
        synchronized (LOCK) {
            if (remoteShell != null) {
                return true;
            }
            if (userServiceArgs == null) {
                userServiceArgs = new Shizuku.UserServiceArgs(
                        new ComponentName(context.getPackageName(), RemoteShellService.class.getName()))
                        // daemon(false)：本应用进程结束时一并回收，不留常驻进程
                        .daemon(false)
                        .version(USER_SERVICE_VERSION)
                        .processNameSuffix("vmqshell")
                        .debuggable(false);
            }
            if (!binding) {
                try {
                    Shizuku.bindUserService(userServiceArgs, CONNECTION);
                    binding = true;
                } catch (Throwable t) {
                    binding = false;
                    return false;
                }
            }
            long deadline = System.currentTimeMillis() + BIND_TIMEOUT_MS;
            while (remoteShell == null) {
                long remain = deadline - System.currentTimeMillis();
                if (remain <= 0) {
                    return false;
                }
                try {
                    LOCK.wait(remain);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            return true;
        }
    }

    /**
     * 在 Shizuku user service 中执行一条 shell 命令（阻塞，需在后台线程调用）。
     */
    public static String exec(Context context, String command) {
        if (!ensureBound(context)) {
            return "Shizuku 执行通道未就绪（请确认已授权、Shizuku 正在运行后重试）";
        }
        IRemoteShell shell;
        synchronized (LOCK) {
            shell = remoteShell;
        }
        if (shell == null) {
            return "Shizuku 执行通道已断开，请重试";
        }
        try {
            return shell.exec(command);
        } catch (Throwable t) {
            synchronized (LOCK) {
                remoteShell = null;
                binding = false;
            }
            return "执行失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        }
    }
}
