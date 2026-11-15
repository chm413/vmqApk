package com.vone.vmq;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.concurrent.TimeUnit;

/**
 * 提权执行通道：Root 与 Shizuku 的统一入口。
 *
 * <h3>为什么还要 Root</h3>
 * Shizuku 好用，但它自己是个应用：Shizuku 进程被系统杀掉、用户手动停了 Shizuku、
 * 或手机重启后没重新启动它，保活能力就整体失效——而此时恰恰是最需要保活的时候。
 * Root（Magisk / KernelSU / APatch）没有这个中间环节，{@code su} 是常驻的，
 * 授权一次长期有效，所以作为**首选通道**。
 *
 * <h3>返回格式必须与 Shizuku 一致</h3>
 * 两条通道都返回 {@code "<stdout+stderr>\nexit=<code>"}。
 * 这样上层（{@link AliveMonitor} 的退出码解析、设置页的 "✔/✘" 判定）不需要区分通道，
 * 也不会因为新增通道而改坏既有逻辑。
 *
 * <h3>线程约束</h3>
 * {@code su} 首次调用会弹 Magisk 授权框，用户不点就一直挂着。所有会真正执行命令的方法
 * 都是<b>阻塞</b>的，<b>严禁在主线程调用</b>。探测结果缓存 {@value #ROOT_CACHE_MS} 毫秒，
 * 避免每轮存活检查都去 fork 一个 su。
 */
public final class PrivilegeShell {

    /** 执行方式偏好 */
    public static final String PREF_MODE = "priv_mode";

    /** 单条命令的最长等待时间。su 授权框没人点时会一直阻塞，必须兜底。 */
    private static final long ROOT_TIMEOUT_MS = 12_000L;

    /** Root 探测结论（明确可用 / 明确不可用）的缓存时长 */
    private static final long ROOT_CACHE_MS = 60_000L;

    /**
     * Root 探测<b>超时</b>结论的缓存时长，必须显著短于 {@link #ROOT_CACHE_MS}。
     *
     * <p>"超时"不等于"没有 Root"——它多半意味着授权框刚弹出、用户正在找那个按钮。
     * 若按 60 秒缓存，用户 30 秒后点了允许，接下来半分钟里所有保活命令仍然全部失败，
     * 表现就是"明明授权了却不生效"。这里只压 15 秒，既避免每轮巡检都去弹框，
     * 又保证用户点完允许后几乎立刻生效。</p>
     */
    private static final long ROOT_TIMEOUT_CACHE_MS = 15_000L;

    public enum Channel {
        /** 没有可用通道 */
        NONE("无"),
        /** Root（su） */
        ROOT("Root"),
        /** Shizuku */
        SHIZUKU("Shizuku");

        public final String label;

        Channel(String label) {
            this.label = label;
        }
    }

    public enum Mode {
        /** Root 优先，不可用时回落到 Shizuku */
        AUTO("自动（优先 Root）"),
        ROOT("仅 Root"),
        SHIZUKU("仅 Shizuku");

        public final String label;

        Mode(String label) {
            this.label = label;
        }

        public static Mode parse(String name) {
            if (name != null) {
                for (Mode m : values()) {
                    if (m.name().equals(name)) {
                        return m;
                    }
                }
            }
            return AUTO;
        }
    }

    private static volatile Boolean rootUsable;
    private static volatile long rootCheckedAt;
    /** 当前这份结论的有效期：正常结论 60 秒，超时结论 15 秒 */
    private static volatile long rootTtl = ROOT_CACHE_MS;
    /** 缓存代号：每次作废 +1，让正在进行的探测丢弃过时的结论（替代加锁，避免 UI 线程被拖住） */
    private static volatile long cacheGeneration;

    /**
     * 探测串行化锁。
     *
     * <p>不加锁时，存活巡检线程与设置页的「检测 Root」可能<b>同时</b>fork 两个 {@code su}，
     * Magisk 会连弹两个授权框（用户点掉一个还有一个），而且两次 12 秒探测互相叠加。
     * 串行化后后到者直接命中前者的缓存结论。</p>
     */
    private static final Object PROBE_LOCK = new Object();

    private PrivilegeShell() {
    }

    // ---------------- 偏好 ----------------

    public static Mode getMode(Context context) {
        if (context == null) {
            return Mode.AUTO;
        }
        SharedPreferences prefs = context.getSharedPreferences(AppLog.PREFS, Context.MODE_PRIVATE);
        return Mode.parse(prefs.getString(PREF_MODE, null));
    }

    public static void setMode(Context context, Mode mode) {
        if (context == null || mode == null) {
            return;
        }
        context.getSharedPreferences(AppLog.PREFS, Context.MODE_PRIVATE)
                .edit().putString(PREF_MODE, mode.name()).apply();
        // 换通道后旧的探测结论可能不再适用
        invalidateRootCache();
    }

    // ---------------- 探测 ----------------

    /** 清空探测缓存，下次询问会重新跑一次 {@code su -c id}。 */
    public static void invalidateRootCache() {
        // 刻意不去抢 PROBE_LOCK：isRootAvailable 探测期间会持锁最长 12 秒，
        // 而本方法会被设置页在主线程调用（切换执行通道），抢锁就等于让界面卡 12 秒 → ANR。
        // 用代号（generation）代替锁：正在跑的探测发现代号变了就丢弃自己的结论。
        cacheGeneration++;
        rootUsable = null;
        rootCheckedAt = 0L;
        rootTtl = ROOT_CACHE_MS;
    }

    /** 只读缓存，不触发探测。未探测过返回 {@code null}。给 UI 用（不阻塞）。 */
    public static Boolean peekRootState() {
        return rootUsable;
    }

    /**
     * Root 是否可用。<b>可能阻塞最长 {@value #ROOT_TIMEOUT_MS} 毫秒</b>（授权框等待）。
     *
     * <p>结论缓存：明确的可用 / 不可用缓存 {@value #ROOT_CACHE_MS} 毫秒；
     * 超时这种"还不知道"只缓存 {@value #ROOT_TIMEOUT_CACHE_MS} 毫秒，
     * 以免用户随后点了允许却迟迟不生效。</p>
     *
     * <p>加锁只为把并发探测串起来（否则会同时 fork 两个 {@code su} 弹两个授权框），
     * 不承担失效职责 —— 失效走 {@link #invalidateRootCache()} 的代号。</p>
     */
    public static boolean isRootAvailable(Context context) {
        synchronized (PROBE_LOCK) {
            long now = System.currentTimeMillis();
            Boolean cached = rootUsable;
            if (cached != null && now - rootCheckedAt < rootTtl) {
                return cached;
            }
            long generation = cacheGeneration;
            String raw = execRoot("id");
            boolean usable = raw.contains("uid=0") && raw.contains("exit=0");
            boolean timedOut = raw.startsWith("su 超时");
            // 探测期间缓存被作废过 → 这次的结论已经过时，不写回，交给下一次重新探
            if (generation == cacheGeneration) {
                rootUsable = usable;
                rootCheckedAt = System.currentTimeMillis();
                rootTtl = timedOut ? ROOT_TIMEOUT_CACHE_MS : ROOT_CACHE_MS;
            }
            if (timedOut) {
                AppLog.w("Privilege", "Root 探测超时：若刚才弹出了授权框，点允许后约 15 秒内生效");
            }
            return usable;
        }
    }

    /** 探测放到后台线程，回调在主线程（给设置页刷新状态用）。 */
    public static void probeRootAsync(Context context, Runnable onDone) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();
        final Handler main = new Handler(Looper.getMainLooper());
        new Thread(() -> {
            invalidateRootCache();
            final boolean ok = isRootAvailable(app);
            AppLog.i("Privilege", "Root 探测结果: " + (ok ? "可用（uid=0）" : "不可用"));
            if (onDone != null) {
                main.post(onDone);
            }
        }, "vmq-root-probe").start();
    }

    // ---------------- 通道解析 ----------------

    /**
     * 按用户偏好与实际可用性决定本次走哪条通道。
     *
     * <p>AUTO 下<b>Root 优先</b>：不依赖 Shizuku 这个中间应用是否存活。</p>
     */
    public static Channel resolve(Context context) {
        Mode mode = getMode(context);
        if (mode == Mode.ROOT) {
            return isRootAvailable(context) ? Channel.ROOT : Channel.NONE;
        }
        if (mode == Mode.SHIZUKU) {
            return ShizukuKeepAlive.isGranted() ? Channel.SHIZUKU : Channel.NONE;
        }
        if (isRootAvailable(context)) {
            return Channel.ROOT;
        }
        if (ShizukuKeepAlive.isGranted()) {
            return Channel.SHIZUKU;
        }
        return Channel.NONE;
    }

    /** 是否存在可用通道（会触发一次 Root 探测，勿在主线程调用）。 */
    public static boolean isChannelAvailable(Context context) {
        return resolve(context) != Channel.NONE;
    }

    /**
     * 确认执行通道已就绪，阻塞。
     *
     * <p>批量执行前先调一次，避免 15 条命令全部标红。</p>
     */
    public static boolean ensureReady(Context context) {
        Channel channel = resolve(context);
        if (channel == Channel.ROOT) {
            return true;
        }
        if (channel == Channel.SHIZUKU) {
            return ShizukuKeepAlive.ensureBound(context);
        }
        return false;
    }

    // ---------------- 执行 ----------------

    /**
     * 执行一条 shell 命令（阻塞）。返回 {@code "<输出>\nexit=<code>"}，与 Shizuku 通道一致。
     */
    public static String exec(Context context, String command) {
        Channel channel = resolve(context);
        switch (channel) {
            case ROOT:
                return execRoot(command);
            case SHIZUKU:
                return ShizukuKeepAlive.exec(context, command);
            case NONE:
            default:
                return "没有可用的提权通道（Root 未授权 / Shizuku 未运行）\nexit=-1";
        }
    }

    /**
     * 用 {@code su -c} 执行命令。
     *
     * <p><b>必须先起读线程，再 {@code waitFor}</b>。管道缓冲区只有约 64KB，
     * 一旦子进程的输出超过这个量，它会阻塞在 {@code write} 上等我们读走；
     * 而我们又在等它退出 —— 双方互等，直到 {@value #ROOT_TIMEOUT_MS} 毫秒超时才解开，
     * 命令被误判成"未授权"。{@code pm list packages}、{@code dumpsys} 这类命令轻松超限，
     * 所以不能依赖"本应用下发的命令输出都很短"这个前提。</p>
     */
    private static String execRoot(String command) {
        Process process = null;
        Thread reader = null;
        final StringBuilder sink = new StringBuilder();
        try {
            ProcessBuilder builder = new ProcessBuilder("su", "-c", command);
            builder.redirectErrorStream(true);
            process = builder.start();

            final InputStream stdout = process.getInputStream();
            reader = new Thread(() -> {
                try {
                    ByteArrayOutputStream out = new ByteArrayOutputStream();
                    byte[] buf = new byte[4096];
                    int n;
                    while ((n = stdout.read(buf)) > 0) {
                        out.write(buf, 0, n);
                    }
                    synchronized (sink) {
                        sink.append(new String(out.toByteArray(), "UTF-8"));
                    }
                } catch (Throwable ignored) {
                    // 进程被 destroy 时 read 会抛异常，属正常收尾路径，不记录
                }
            }, "vmq-su-reader");
            reader.setDaemon(true);
            reader.start();

            if (!process.waitFor(ROOT_TIMEOUT_MS, TimeUnit.MILLISECONDS)) {
                return "su 超时（未授权或授权框未响应）\nexit=-1";
            }
            // 进程已退出，输出通常已读完；最多再等 1 秒兜住管道里残留的尾巴。
            // 单独 catch 中断：不能让它掉进下面的 Throwable 分支被误报成"su 不可用"。
            try {
                reader.join(1000L);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
            }
            int code = process.exitValue();
            String output;
            synchronized (sink) {
                output = sink.toString();
            }
            StringBuilder sb = new StringBuilder(output);
            if (sb.length() > 0 && sb.charAt(sb.length() - 1) != '\n') {
                sb.append('\n');
            }
            sb.append("exit=").append(code);
            return sb.toString();
        } catch (Throwable t) {
            // su 不存在 / 被策略拦截 / 权限拒绝，都归到"不可用"，不抛出
            return "su 不可用: " + t.getClass().getSimpleName()
                    + (t.getMessage() == null ? "" : (": " + t.getMessage()))
                    + "\nexit=-1";
        } finally {
            if (process != null) {
                process.destroy();
            }
            if (reader != null) {
                reader.interrupt();
            }
        }
    }

    // ---------------- 状态文案 ----------------

    /** 设置页展示用的多行状态。只读缓存，不触发 su 探测，不会卡 UI。 */
    public static String statusText(Context context) {
        Mode mode = getMode(context);
        StringBuilder sb = new StringBuilder();
        sb.append("执行方式：").append(mode.label).append('\n');

        Boolean root = rootUsable;
        if (root == null) {
            sb.append("Root：未检测（点「检测 Root」）");
        } else if (root) {
            sb.append("Root：已授权（uid=0）");
        } else {
            sb.append("Root：不可用（未 Root / 未授权 / su 被拦截）");
        }

        sb.append('\n').append(ShizukuKeepAlive.statusText());

        Channel effective = effectiveFromCache(context, mode, root);
        sb.append('\n').append("当前生效：").append(effective.label);
        return sb.toString();
    }

    /** 不触发探测的通道推断，仅用于文案。 */
    private static Channel effectiveFromCache(Context context, Mode mode, Boolean rootCache) {
        boolean rootOk = Boolean.TRUE.equals(rootCache);
        boolean shizukuOk = ShizukuKeepAlive.isGranted();
        if (mode == Mode.ROOT) {
            return rootOk ? Channel.ROOT : Channel.NONE;
        }
        if (mode == Mode.SHIZUKU) {
            return shizukuOk ? Channel.SHIZUKU : Channel.NONE;
        }
        if (rootOk) {
            return Channel.ROOT;
        }
        return shizukuOk ? Channel.SHIZUKU : Channel.NONE;
    }
}
