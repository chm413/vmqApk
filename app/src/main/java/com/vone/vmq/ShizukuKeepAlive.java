package com.vone.vmq;

import android.content.pm.PackageManager;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import rikka.shizuku.Shizuku;

/**
 * Shizuku 保活通道。
 *
 * <p>原理：Shizuku 以 shell(uid 2000) 或 root 身份提供 Binder，本类借此下发系统命令，
 * 把本应用、支付宝、微信的后台存活等级拉高（Doze 白名单 + active 待机桶 + 后台 appop），
 * 并自动授予 Android 15 的 RECEIVE_SENSITIVE_NOTIFICATIONS 权限。</p>
 *
 * <p>未安装 Shizuku 时所有方法安全降级（isAvailable=false），不影响应用其他功能。</p>
 */
public final class ShizukuKeepAlive {

    public static final int REQUEST_CODE = 4001;

    /** 支付宝 */
    public static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    /** 微信 */
    public static final String PKG_WECHAT = "com.tencent.mm";

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
        return "Shizuku：已授权" + version + "（可下发保活命令）";
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

    /** 同步执行一条 shell 命令，返回合并后的 stdout/stderr 与退出码。 */
    public static String exec(String command) {
        Process process = null;
        try {
            process = Shizuku.newProcess(new String[]{"sh", "-c", command}, null, null);
            StringBuilder sb = new StringBuilder();
            try (BufferedReader out = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
                 BufferedReader err = new BufferedReader(
                         new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = out.readLine()) != null) {
                    sb.append(line).append('\n');
                }
                while ((line = err.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            sb.append("exit=").append(code);
            return sb.toString().trim();
        } catch (Throwable t) {
            return "执行失败: " + t.getMessage();
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
