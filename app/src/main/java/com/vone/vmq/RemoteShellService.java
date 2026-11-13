package com.vone.vmq;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * Shizuku user service 实现。
 *
 * <p>本类<b>不是</b> Android Service，不需要在 AndroidManifest 中声明。它由 Shizuku
 * 通过 {@code bindUserService} 反射实例化，运行在 Shizuku 以 shell(uid 2000) 或 root
 * 身份创建的独立进程中，因此这里的 {@link Runtime} 调用具备系统级权限。</p>
 *
 * <p>注意：该进程不是合法的 Android 应用进程，Context 相关 API（registerReceiver、
 * getContentResolver 等）不可用，故本类只做最纯粹的进程执行，不触碰任何 Android 组件。</p>
 *
 * <p>改动本类的逻辑后必须提升 {@link ShizukuKeepAlive#USER_SERVICE_VERSION}，
 * 否则 Shizuku 会复用旧版本的进程。</p>
 */
public class RemoteShellService extends IRemoteShell.Stub {

    @Override
    public String exec(String command) {
        if (command == null || command.trim().isEmpty()) {
            return "空命令";
        }
        Process process = null;
        try {
            // redirectErrorStream(true)：stderr 合并进 stdout。
            // 单流读取可彻底避免「只读 stdout 时 stderr 缓冲区写满导致子进程阻塞」的死锁。
            ProcessBuilder builder = new ProcessBuilder("sh", "-c", command);
            builder.redirectErrorStream(true);
            process = builder.start();

            StringBuilder sb = new StringBuilder();
            try (BufferedReader reader = new BufferedReader(
                    new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    sb.append(line).append('\n');
                }
            }
            int code = process.waitFor();
            sb.append("exit=").append(code);
            return sb.toString().trim();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return "执行被中断";
        } catch (Throwable t) {
            return "执行失败: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()));
        } finally {
            if (process != null) {
                process.destroy();
            }
        }
    }
}
