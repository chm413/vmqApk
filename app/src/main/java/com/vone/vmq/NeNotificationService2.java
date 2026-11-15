package com.vone.vmq;

import android.annotation.SuppressLint;
import android.app.Notification;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import androidx.core.app.NotificationCompat;
import android.util.Log;
import android.widget.Toast;
import android.content.pm.PackageManager;
import androidx.core.content.ContextCompat;

import com.vone.qrcode.R;
import com.vone.vmq.util.SecurePrefs;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

public class NeNotificationService2 extends NotificationListenerService {
    private static String TAG = "NeNotificationService2";
    /** 心跳基础间隔：30s；断联时按指数退避放大到上限 5 分钟 */
    private static final long HEART_INTERVAL_MS = 30_000L;
    private static final long HEART_MAX_BACKOFF_MS = 300_000L;
    /**
     * 收款推送期间持有电源锁的上限。
     *
     * <p>从 5 秒放宽到 15 秒：5 秒在弱网下不够一次 POST 走完，锁会提前自动释放，
     * 而此时推送回调又去 {@code release()} 一个已经不存在的锁 —— 会闪退（见 {@link #releaseWakeLock()}）。
     * 带超时的锁本身不会泄漏，放宽的代价只是极端情况下多亮 10 秒屏。</p>
     */
    private static final long WAKE_LOCK_TIMEOUT_MS = 15_000L;

    /** 收款类型：与后端 /api/monitor/push 的 type 参数一一对应 */
    private static final int PAY_TYPE_WECHAT = 1;
    private static final int PAY_TYPE_ALIPAY = 2;

    /** 收款类应用的包名，集中一处便于核对 */
    private static final String PKG_ALIPAY = "com.eg.android.AlipayGphone";
    private static final String PKG_WECHAT = "com.tencent.mm";
    private static final String PKG_WEWORK = "com.tencent.wework";

    /**
     * 到账通知去重窗口。
     *
     * <p>同一笔到账在很多情况下会触发<b>多次</b> {@code onNotificationPosted}：
     * 通知内容被更新（同一个 key 再回调一次）、收款助手先发「到账」再更新为「已存入零钱」、
     * 部分 ROM 直接重发。没有去重时每一笔钱都会被上报多次 ——
     * 对按金额核销订单的后端来说就是**重复入账**，比少报更难处理。</p>
     *
     * <p>取 5 秒：同一通知的重复回调通常发生在 1 秒内；而两位顾客在同一 5 秒内
     * 支付完全相同的金额、且通知文案一字不差的概率极低。窗口取太大会有丢单风险，
     * 取太小则压不住 ROM 重发。</p>
     */
    private static final long DEDUP_WINDOW_MS = 5_000L;

    /** 去重表容量上限，超过直接清空（收款场景用不到这么多条） */
    private static final int DEDUP_MAX_ENTRIES = 32;
    /** 连续心跳失败次数，成功即清零（volatile：心跳回调线程与循环线程共享） */
    private volatile int heartFailures = 0;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String host = "";
    private String key = "";
    private Thread newThread = null;
    /** 存活监控线程（微信 / 支付宝），与心跳线程并行、独立生命周期 */
    private Thread aliveThread = null;
    private PowerManager.WakeLock mWakeLock = null;
    /**
     * 监听服务是否处于已连接状态。
     *
     * <p>写在主线程（onListenerConnected / onListenerDisconnected），
     * 读在心跳线程与存活监控线程。<b>必须 volatile</b>：此前靠 interrupt / notifyAll
     * 的锁语义"顺带"保证可见性，一旦某条路径只置标志不唤醒，线程就看不到变化。</p>
     */
    public static volatile boolean isRunning;

    /**
     * 到账通知去重表：内容指纹 → 首次见到的时间戳。
     *
     * <p>只在通知回调里访问，加锁是为了防止厂商 ROM 并发投递回调时踩坏 HashMap。</p>
     */
    private final Map<String, Long> recentNotifications = new HashMap<>();

    /**
     * 「同包 + 同金额」去重窗口，<b>刻意比 {@link #DEDUP_WINDOW_MS} 更短</b>。
     *
     * <p>补的是文案指纹拦不住的那一类：支付宝到账通知更新后文案会变
     * （「成功收款12.34元。」→「成功收款12.34元。已存入余额」），指纹不同但仍是同一笔钱。
     * 只靠文案去重会漏掉它，于是同一笔被上报两次。</p>
     *
     * <p>窗口取 3 秒而不是 5 秒，是为了把「丢单」风险压到最小：通知回声几乎都在 1 秒内发生，
     * 而 V免签的订单金额通常按分位随机，两笔真实到账出现<b>完全相同金额</b>且间隔 3 秒内的
     * 概率极低。即便如此，每次命中都会写一条带金额的日志，万一真漏单能立刻查出来。</p>
     */
    private static final long AMOUNT_DEDUP_WINDOW_MS = 3_000L;

    /**
     * 通知落盘用的单线程执行器。
     *
     * <p>落盘原先直接在 {@code onNotificationPosted} 里做，每条通知都要
     * open/write/close 三次系统调用；通知密集时（群聊、推送风暴）会把回调线程拖住，
     * 进而丢通知。改成异步后回调只需投递一个任务。</p>
     */
    private final ExecutorService notifyDumpExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vmq-notify-dump");
        t.setDaemon(true);
        return t;
    });

    /**
     * 申请设备电源锁，最多持有 {@value #WAKE_LOCK_TIMEOUT_MS} 毫秒。
     *
     * <p>这里用带超时的 {@code acquire}，即使后续 {@link #releaseWakeLock()} 因为
     * 回调丢失没被调用，锁也会自己到期释放，不会变成"永久持锁吸干电池"。</p>
     */
    //申请设备电源锁
    @SuppressLint("InvalidWakeLockTag")
    public void acquireWakeLock(final Context context) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (null == mWakeLock) {
                    PowerManager pm = (PowerManager) context.getSystemService(Context.POWER_SERVICE);
                    if (pm != null) {
                        mWakeLock = pm.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK | PowerManager.ON_AFTER_RELEASE, "WakeLock");
                        // 不计数：重复 acquire 不累加引用，一次 release 就能放开。
                        // 否则同一个锁被 acquire 两次、只 release 一次，会永远留着不放开。
                        mWakeLock.setReferenceCounted(false);
                    }
                }
                if (null != mWakeLock) {
                    mWakeLock.acquire(WAKE_LOCK_TIMEOUT_MS);
                }
            }
        });
    }

    /**
     * 释放设备电源锁。
     *
     * <p><b>必须容忍"锁已经不在手上"</b>：{@code acquire(timeout)} 到点后系统会自动释放，
     * 若此时网络请求才失败回调进来，直接 {@code release()} 会抛
     * {@code RuntimeException: WakeLock under-locked}，而这段跑在主线程 —— 应用直接闪退。
     * 断网时请求耗时超过 5 秒是常态，所以这条路径在真实环境里很容易被踩到。</p>
     */
    //释放设备电源锁
    public void releaseWakeLock() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                final PowerManager.WakeLock lock = mWakeLock;
                mWakeLock = null;
                if (lock == null) {
                    return;
                }
                try {
                    if (lock.isHeld()) {
                        lock.release();
                    }
                } catch (Throwable t) {
                    // 极少数厂商 ROM 的 isHeld 与实际引用计数不一致，兜底吞掉
                    AppLog.w(TAG, "释放电源锁失败（已忽略）: " + t.getClass().getSimpleName());
                }
            }
        });
    }

    /**
     * 让一条旧的循环线程退场，但<b>不 join</b>（本方法可能跑在主线程上，join 会卡 UI）。
     *
     * <p>调用方必须<b>先</b>把字段置空再调本方法，顺序不能反：旧线程在 {@code wait()} 返回后
     * 会先查循环条件 {@code newThread == Thread.currentThread()}，字段若还是它自己就会
     * 若无其事地再跑一轮，等字段随后被换成新线程，它就又成了没人能叫醒的孤儿。
     * 置空在前，这条自我校验立刻失败。</p>
     *
     * <p>{@code interrupt} 负责把挂在 {@link RunSwitch} 的 {@code wait()} 上的旧线程拽出来，
     * 它会抛 {@code InterruptedException}、返回 {@code false}，循环随即 {@code break}。</p>
     */
    private static void retireThread(Thread old, String name) {
        if (old == null || !old.isAlive()) {
            return;
        }
        AppLog.w(TAG, name + " 已存在，先停旧线程再重启（疑似 onListenerConnected 重复回调）");
        old.interrupt();
    }

    //心跳进程
    public void initAppHeart() {
        AppLog.d(TAG, "开始启动心跳线程");
        // 系统重绑 / requestRebind 成功时 onListenerConnected 可能被连续回调两次。
        // 若直接覆盖字段，旧线程若正挂在上报关闭的 wait() 上，就再没人能唤醒它了 ——
        // 字段已被换掉，onListenerDisconnected 也只 interrupt 新线程，形成永久泄漏。
        Thread previousHeart = newThread;
        newThread = null;
        retireThread(previousHeart, "心跳线程");
        newThread = new Thread(new Runnable() {
            @Override
            public void run() {
                AppLog.d(TAG, "心跳线程启动！");
                long nextHeartAt = 0L;
                while (isRunning && newThread == Thread.currentThread()) {
                    // 上报开关关闭时线程挂起在 wait() 上：不发请求、不退避、不产生唤醒。
                    // 注意这里必须等"上报"这一个开关，不能等 isAnyActive，
                    // 否则在"监听开、上报关"的组合下会被立刻唤醒造成忙等。
                    if (!RunSwitch.isReportEnabled(NeNotificationService2.this)) {
                        AppLog.i(TAG, "心跳上报已关闭，线程进入休眠（进程与监听服务保留）");
                        nextHeartAt = 0L;
                        if (!RunSwitch.awaitReportEnabled(NeNotificationService2.this)) {
                            break;
                        }
                        AppLog.i(TAG, "心跳上报已开启，线程恢复");
                        continue;
                    }
                    // 还没到点就继续等。被开关变化提前唤醒时重新判断：
                    // 若仍开启则接着把剩余时间睡完，不会因为动了下别的开关就多发一次心跳。
                    if (nextHeartAt > 0L) {
                        long remain = nextHeartAt - System.currentTimeMillis();
                        if (remain > 0L) {
                            if (!RunSwitch.sleepOrWakeup(remain)) {
                                break;
                            }
                            continue;
                        }
                    }
                    SharedPreferences read = getSharedPreferences("vone", MODE_PRIVATE);
                    host = read.getString("host", "");
                    key = SecurePrefs.readKey(read);
                    final String scheme = read.getString("scheme", "http");

                    //这里写入子线程需要做的工作
                    String t = MonitorSign.timestamp();
                    String sign = MonitorSign.heartbeat(t, key);

                    final String url = scheme + "://" + host + "/api/monitor/heart?t=" + t + "&sign=" + sign;
                    final long requestStart = System.currentTimeMillis();
                    Request request = new Request.Builder().url(url).method("GET", null).build();
                    Call call = Utils.getOkHttpClient(NeNotificationService2.this).newCall(request);
                    call.enqueue(new Callback() {
                        @Override
                        public void onFailure(Call call, IOException e) {
                            heartFailures++;
                            // 具体原因由 recordHeartResult 统一落日志，这里不再重复print
                            recordHeartResult(false, 0, e.getMessage());
                            foregroundHeart(url, 0L);
                        }

                        // HTTP 200 仍可能包含业务错误；必须同步校验 PHP 兼容 envelope 的 code。
                        @Override
                        public void onResponse(Call call, Response response) throws IOException {
                            try {
                                MonitorResponse result = MonitorResponse.from(response);
                                AppLog.d(TAG, "心跳服务结果: " + Desensitizer.any(result.summary()));
                                if (!result.isSuccessful()) {
                                    AppLog.w(TAG, "心跳未被服务端接受，触发前台重试");
                                    heartFailures++;
                                    recordHeartResult(false, 0, result.summary());
                                    foregroundHeart(url, result.foregroundRetryDelayMillis(0L));
                                } else {
                                    heartFailures = 0;
                                    recordHeartResult(true, System.currentTimeMillis() - requestStart, null);
                                }
                            } catch (Exception e) {
                                AppLog.e(TAG, "心跳服务解析异常: " + e.getMessage(), e);
                                heartFailures++;
                                recordHeartResult(false, 0, e.getMessage());
                                foregroundHeart(url, 0L);
                            } finally {
                                response.close();
                            }
                        }
                    });
                    // 断联退避：连续失败时 30s→60s→120s→240s→300s(上限)，成功立即回落到 30s
                    long sleepMs = Utils.backoffDelayMs(heartFailures, HEART_INTERVAL_MS, HEART_MAX_BACKOFF_MS);
                    if (heartFailures > 0) {
                        AppLog.d(TAG, "心跳连续失败 " + heartFailures + " 次，下次重试间隔 " + sleepMs + "ms");
                    }
                    // 只记下截止时间，真正的等待放到循环顶部：这样"关开关立即停"和
                    // "不因无关开关变化而多发心跳"两件事可以同时成立。
                    nextHeartAt = System.currentTimeMillis() + sleepMs;
                }
                AppLog.i(TAG, "心跳线程退出");
            }
        });
        newThread.start(); //启动线程
    }

    /**
     * 存活监控线程：按设定间隔检查微信 / 支付宝是否还在运行。
     *
     * <p>与心跳线程同生命周期，但用独立 Thread 引用，避免互相干扰中断状态。</p>
     *
     * <p>检测可能阻塞（Shizuku user service 绑定最长 8 秒），必须放在后台线程。
     * 结果写入 SharedPreferences 供主页/设置页展示，同时每次检测都记一条日志。</p>
     */
    private void initAliveMonitor() {
        Thread previousAlive = aliveThread;
        aliveThread = null;
        retireThread(previousAlive, "存活监控线程");
        aliveThread = new Thread(() -> {
            AppLog.i(TAG, "存活监控线程启动，间隔 " + AliveMonitor.getIntervalMinutes(this) + " 分钟");
            long nextCheckAt = 0L;
            while (isRunning && aliveThread == Thread.currentThread()) {
                try {
                    // 监听与上报都关 → 保活一并停止：
                    // 线程挂起在 wait() 上（零 CPU、无定时唤醒），进程与监听服务仍然保留，
                    // 重新打开开关会立刻唤醒，不需要重启应用。
                    if (!RunSwitch.isAnyActive(this)) {
                        AppLog.i(TAG, "监听与上报均已关闭，保活循环进入休眠（不退出进程）");
                        nextCheckAt = 0L;
                        if (!RunSwitch.awaitActive(this)) {
                            break;
                        }
                        AppLog.i(TAG, "监听/上报已恢复，保活循环重新开始");
                        continue;
                    }
                    // 未到检查时刻就继续等；被无关开关提前唤醒时接着睡剩余时间，
                    // 不会因为动了下别的开关就额外跑一次 pidof
                    if (nextCheckAt > 0L) {
                        long remain = nextCheckAt - System.currentTimeMillis();
                        if (remain > 0L) {
                            if (!RunSwitch.sleepOrWakeup(remain)) {
                                break;
                            }
                            continue;
                        }
                    }
                    if (AliveMonitor.isMonitorEnabled(this)) {
                        AliveMonitor.Status alipay =
                                AliveMonitor.checkAndRecord(this, ShizukuKeepAlive.PKG_ALIPAY);
                        AliveMonitor.maybeRevive(this, alipay);
                        AliveMonitor.Status wechat =
                                AliveMonitor.checkAndRecord(this, ShizukuKeepAlive.PKG_WECHAT);
                        AliveMonitor.maybeRevive(this, wechat);
                    }
                    // 定时重启（实验性，默认关闭）：落在存活检查之后，
                    // 避免在 pidof / am start 执行中途把进程杀掉。
                    AutoRestart.maybeRestart(this);
                    long interval = Math.max(1, AliveMonitor.getIntervalMinutes(this)) * 60_000L;
                    nextCheckAt = System.currentTimeMillis() + interval;
                } catch (Throwable t) {
                    // 单次检测异常不能让监控线程整体退出
                    AppLog.e(TAG, "存活监控异常", t);
                    if (!RunSwitch.sleepOrWakeup(30_000L)) {
                        break;
                    }
                }
            }
            AppLog.i(TAG, "存活监控线程退出");
        }, "vmq-alive-monitor");
        aliveThread.start();
    }

    /**
     * 记录最近心跳结果供主页仪表盘展示（跨进程/页面共享，写入 SharedPreferences）。
     *
     * <p>这里同时是心跳日志的**唯一汇聚点**：不论成功、失败还是解析异常，
     * 最终都会走到本方法，因此在这里记一条结构化日志即可保证"每次心跳都有记录"，
     * 不必依赖各个分支各自补日志。错误信息可能是服务端回带的地址/签名，落库前脱敏。</p>
     */
    private void recordHeartResult(boolean ok, long latencyMs, String error) {
        if (ok) {
            AppLog.i(TAG, "心跳成功 · 耗时 " + latencyMs + "ms");
        } else {
            // 错误信息本身是有诊断价值的技术描述，整段遮掉就失去意义；
            // 用 any() 只遮里面的 URL / 签名参数，保留可读性。
            AppLog.w(TAG, "心跳失败 · 连续 " + heartFailures + " 次"
                    + " · " + Desensitizer.any(error == null ? "未知原因" : error));
        }
        try {
            SharedPreferences.Editor editor = getSharedPreferences("vone", MODE_PRIVATE).edit();
            if (ok) {
                editor.putLong("last_heart_ok_at", System.currentTimeMillis());
                editor.putLong("last_heart_latency_ms", latencyMs);
                editor.putInt("last_heart_fail_count", 0);
            } else {
                editor.putLong("last_heart_fail_at", System.currentTimeMillis());
                editor.putInt("last_heart_fail_count", heartFailures);
                if (error != null) {
                    // 主页仅展示最近错误，原文保留便于用户自查配置；展示层不额外脱敏
                    editor.putString("last_heart_error", error);
                }
            }
            editor.apply();
        } catch (Exception ignored) {
        }
    }

    /**
     * 增强的通知处理方法 - 适配Android 14+的通知变化
     */
    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // 最前置的闸门：关闭监听后连解析都不做。
        // 放在最前面（而不是解析完再判断）是为了真的省下这些开销，
        // 也避免"关着还在落库/写日志"把日志刷满。
        if (!RunSwitch.isListenEnabled(this)) {
            return;
        }
        AppLog.d(TAG, "接受到通知消息");
        
        // Android 14+ 额外的通知验证
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            if (!validateNotificationAccess(sbn)) {
                AppLog.w(TAG, "通知访问验证失败，跳过处理");
                return;
            }
        }
        
        // 落盘已移到「确认是支付相关通知」之后（见 processPaymentNotification）：
        // 此处对全部应用的通知都写盘，既把用户私信明文堆进一个文件，又占着回调线程做 IO。

        // 微信支付部分通知，会调用两次，导致统计不准确
        if ((sbn.getNotification().flags & Notification.FLAG_GROUP_SUMMARY) != 0) {
            AppLog.d(TAG, "群组摘要通知，忽略");
            return;
        }
        
        // 继续原有的通知处理逻辑
        processPaymentNotification(sbn);
    }

    //当移除一条消息的时候回调，sbn是被移除的消息
    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {

    }

    //当连接成功时调用，一般在开启监听后会回调一次该方法
    @Override
    public void onListenerConnected() {
        isRunning = true;
        
        // Android 14+ 兼容性检查
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            AppLog.d(TAG, "Android 14+ 检测到，执行兼容性检查");
            checkAndroid14Compatibility();
        }
        
        //开启心跳线程
        initAppHeart();
        // 开启微信 / 支付宝存活监控线程
        initAliveMonitor();

        handler.post(new Runnable() {
            public void run() {
                Toast.makeText(getApplicationContext(), "监听服务开启成功！", Toast.LENGTH_SHORT).show();
            }
        });
        
        // 发送服务状态广播
        sendBroadcastLog("通知监听服务已连接，Android版本: " + Build.VERSION.RELEASE);
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        isRunning = false;
        if (newThread != null) {
            newThread.interrupt();
        }
        newThread = null;
        if (aliveThread != null) {
            aliveThread.interrupt();
        }
        aliveThread = null;
        // 保活：监听被系统回收/断开时主动请求重新绑定，10 秒后仍未恢复则再兜底一次
        scheduleRebind();
    }

    /**
     * 保活兜底：请求系统重新绑定通知监听服务。
     * Android 7.0+ 提供 requestRebind；失败（权限被撤销等）只记录日志，不抛给系统。
     */
    private void scheduleRebind() {
        rebindListener("断开即重绑");
        handler.postDelayed(() -> {
            if (!isRunning) {
                rebindListener("10 秒兜底重绑");
            }
        }, 10_000L);
    }

    private void rebindListener(String reason) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                requestRebind(new ComponentName(this, NeNotificationService2.class));
                AppLog.i(TAG, "监听服务未就绪，已请求 rebind（" + reason + "）");
                sendBroadcastLog("监听服务断开，正在自动恢复（" + reason + "）");
            }
        } catch (Exception e) {
            // SecurityException：通知使用权被用户关闭，需引导重新授权
            AppLog.w(TAG, "rebind 失败（" + reason + "）: " + e.getMessage());
            sendBroadcastLog("监听服务自动恢复失败，请检查通知使用权: " + e.getMessage());
        }
    }

    /**
     * 把一条<b>支付类</b>通知追加到排查文件。
     *
     * <p>两处收窄：① 调用点已移到「确认是微信/支付宝/企业微信」之后，不再把用户所有应用
     * 的通知明文都写进去；② 真正的文件 IO 丢到单线程执行器，回调线程只投递任务。
     * 原先每条通知都在回调线程里做一次 open/write/close，推送风暴时会把回调拖慢到丢通知。</p>
     */
    private void writeNotifyToFile(final StatusBarNotification sbn, String title, String content) {
        Notification notification = sbn.getNotification();
        if (notification == null || !sbn.isClearable()) {
            return;
        }
        final String packageName = sbn.getPackageName();
        Bundle extras = notification.extras;
        final CharSequence subText =
                extras == null ? null : extras.getCharSequence(Notification.EXTRA_SUB_TEXT);
        final String time = Utils.formatTime(Calendar.getInstance().getTime());
        final String writText = "\n" + "[" + time + "]" + "[" + packageName + "]" + "\n" +
                "[" + title + "]" + "\n" + "[" + content + "]" + "\n" +
                "[" + subText + "]" + "\n";
        try {
            notifyDumpExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    Utils.putStr(getApplicationContext(), writText);
                }
            });
        } catch (Throwable t) {
            // 执行器已关闭（服务销毁）时 RejectedExecutionException 会抛出来，忽略即可
            AppLog.d(TAG, "通知落盘任务投递失败: " + t.getClass().getSimpleName());
        }
    }

    /**
     * 服务真正销毁时（不是断开重绑）收掉后台线程与落盘执行器。
     *
     * <p>只在 {@code onDestroy} 里关：{@code onListenerDisconnected} 之后系统可能立刻重绑、
     * 复用同一个服务实例，那时关掉执行器会让后续通知落盘永久失效。</p>
     *
     * <p>顺带把 {@link #isRunning} 置假并中断两条循环线程 —— 正常路径会先走
     * {@code onListenerDisconnected}，但系统也可能直接销毁服务；不中断的话线程会继续
     * 循环，持有一个已销毁的 Service 引用。</p>
     */
    @Override
    public void onDestroy() {
        isRunning = false;
        if (newThread != null) {
            newThread.interrupt();
        }
        if (aliveThread != null) {
            aliveThread.interrupt();
        }
        notifyDumpExecutor.shutdown();
        super.onDestroy();
    }

    /**
     * 通知服务器收款到账
     */
    public void appPush(int type, double price) {
        acquireWakeLock(getApplicationContext());
        // 记下推送时刻：供「定时重启进程」判断静默窗口——重启窗口内到达的
        // 通知监听事件不会被系统重投递，必须避开正在推送的时刻。
        getSharedPreferences("vone", MODE_PRIVATE).edit()
                .putLong("last_push_at", System.currentTimeMillis()).apply();
        SharedPreferences read = getSharedPreferences("vone", MODE_PRIVATE);
        host = read.getString("host", "");
        key = SecurePrefs.readKey(read);
        final String scheme = read.getString("scheme", "http");

        String t = MonitorSign.timestamp();
        String typeText = String.valueOf(type);
        // 金额文本必须与参与签名的文本完全一致，否则服务端按查询参数重算出的签名不会相等
        String priceText = MonitorSign.priceText(price);

        String sign = MonitorSign.push(typeText, priceText, t, key);
        final String url = scheme + "://" + host + "/api/monitor/push?t=" + t + "&type=" + typeText + "&price=" + priceText + "&sign=" + sign;

        // 签名属于短期 bearer 凭据，日志只保留脱敏后的请求路径。
        sendBroadcastLog("准备推送订单: " + Utils.redactMonitorUrl(url));
        Request request = new Request.Builder().url(url).get().build();
        Call call = Utils.getOkHttpClient(NeNotificationService2.this).newCall(request);
        call.enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                sendBroadcastLog("推送失败: " + e.getMessage());
                foregroundPost(url, 0L);
                releaseWakeLock();
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                try {
                    MonitorResponse result = MonitorResponse.from(response);
                    if (result.isSuccessful()) {
                        sendBroadcastLog("推送成功，服务器结果: " + result.summary());
                    } else {
                        sendBroadcastLog("推送未被服务端接受: " + result.summary());
                        foregroundPost(url, result.foregroundRetryDelayMillis(0L));
                    }
                } catch (Exception e) {
                    sendBroadcastLog("推送响应解析失败: " + e.getMessage());
                    foregroundPost(url, 0L);
                } finally {
                    response.close();
                    releaseWakeLock();
                }
            }
        });
    }

    private void foregroundHeart(String url, long retryAfterMillis) {
        final Context context = NeNotificationService2.this;
        if (isRunning) {
            final JSONObject extraJson = new JSONObject();
            try {
                extraJson.put("url", url);
                extraJson.put("show", false);
                extraJson.put("retry_after_millis", retryAfterMillis);
            } catch (JSONException jsonException) {
                AppLog.w(TAG, "编码心跳前台重试参数失败", jsonException);
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    enterForeground(context,
                            context.getString(R.string.app_name),
                            context.getString(R.string.app_is_heart), extraJson.toString());
                }
            });
        }
    }

    /**
     * 当通知失败的时候，进入前台服务按受控间隔重试。
     */
    private void foregroundPost(String url, long retryAfterMillis) {
        final Context context = NeNotificationService2.this;
        if (isRunning) {
            final JSONObject extraJson = new JSONObject();
            try {
                extraJson.put("url", url);
                extraJson.put("try_count", 5);
                extraJson.put("retry_after_millis", retryAfterMillis);
            } catch (JSONException jsonException) {
                AppLog.w(TAG, "编码推送前台重试参数失败", jsonException);
            }
            handler.post(new Runnable() {
                @Override
                public void run() {
                    enterForeground(context,
                            context.getString(R.string.app_name),
                            context.getString(R.string.app_is_post), extraJson.toString());
                }
            });
        }
    }

    /**
     * 如果出现无法通知的情况，进入前台，然后主动打开通知。
     *
     * <p><b>必须包住异常</b>：Android 12 起「后台启动前台服务」被系统限制，应用在后台
     * （没有可见界面、也不在电池优化白名单里）调用 {@code startForegroundService} 会抛
     * {@code ForegroundServiceStartNotAllowedException}（{@code IllegalStateException} 子类）。</p>
     *
     * <p>而这个方法的两个调用点恰好都在"后台出错"的时刻：心跳失败、收款推送失败。
     * 也就是说<b>越是需要它兜底的场景，越容易被系统拒绝</b>。不catch 的话，
     * 一次网络抖动就会把整个进程带走 —— 本来只是丢一次重试，变成丢掉整个监听服务。</p>
     */
    public static void enterForeground(Context context, String title, String text, String extra) {
        if (context == null) return;
        Intent intent = new Intent(context, ForegroundServer.class);
        intent.putExtra(ForegroundServer.GET_NOTIFY_TITLE, title == null ? "" : title);
        intent.putExtra(ForegroundServer.GET_NOTIFY_TEXT, text == null ? "" : text);
        intent.putExtra(ForegroundServer.GET_NOTIFY_EXTRA, extra == null ? "" : extra);
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent);
            } else {
                context.startService(intent);
            }
            AppLog.i(TAG, "已启动前台重试服务");
        } catch (IllegalStateException e) {
            // 后台启动前台服务被拒：应用在后台且不在电池优化白名单
            AppLog.w(TAG, "启动前台重试服务被系统拒绝（Android 12+ 后台启动限制）: " + e.getMessage()
                    + "；请把 App 加入电池优化白名单");
        } catch (Throwable t) {
            AppLog.w(TAG, "启动前台重试服务失败: " + t.getClass().getSimpleName());
        }
    }

    public static void exitForeground(Context context) {
        if (context == null) return;
        AppLog.i(TAG, "exitForeground");

        Intent intent1 = new Intent();
        intent1.setAction(Constant.FINISH_FOREGROUND_SERVICE);
        context.sendBroadcast(intent1);
    }

    /**
     * 「数字 + 元」形态的金额。预编译：这是通知回调的热路径，每条通知都会调到。
     *
     * <p>两个分支的顺序很关键：<b>带小数的必须排在前面</b>。若把 {@code \d+元} 放前面，
     * {@code "12.34元"} 会先被匹配成 {@code "34元"}。</p>
     */
    private static final Pattern MONEY_WITH_YUAN = Pattern.compile("(\\d+\\.\\d+)元|(\\d+)元");

    /**
     * 一个"像金额"的数字段：整数或最多一段小数。
     *
     * <p>用它过滤掉 {@code "1.2.3"}、{@code "."} 这类碎片 —— 否则后续
     * {@code Double.parseDouble} 会抛 {@code NumberFormatException}，那一单就静默丢了。</p>
     */
    private static final Pattern MONEY_SEGMENT = Pattern.compile("\\d+(?:\\.\\d+)?");

    /**
     * 匹配支付宝的收款金额：只要数字后面带「元」就算，命中<b>第一个</b>即可。
     *
     * <p>v3.3.6 修：先去掉千分位逗号。此前 {@code "1,234.56元"} 会被匹配成
     * {@code "234.56元"} —— 千元以上的大额收款金额被截掉一位，属于实打实的错单。</p>
     */
    public static String getMoney2(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        Matcher matcher = MONEY_WITH_YUAN.matcher(content.replace(",", ""));
        if (!matcher.find()) {
            return null;
        }
        String price = matcher.group();
        int yuan = price.lastIndexOf("元");
        return yuan > 0 ? price.substring(0, yuan) : null;
    }

    /**
     * 从一段没有「元」后缀的文本里猜金额：取<b>最后一个像金额的数字段</b>。
     *
     * <h3>为什么要"像金额"这个筛选</h3>
     * 这个方法是兜底路径，输入是整条通知的标题或正文。原实现是「把非数字全换成逗号，
     * 取最后一段」，在真实文案上会错得很难看：
     * <ul>
     *   <li>{@code "成功收款12.34元，获得1个商家积分"} → 取到 <b>1</b></li>
     *   <li>{@code "成功收款12.34元，当前余额5678.90元"} → 取到 <b>5678.90</b>（余额当金额）</li>
     * </ul>
     * 现在优先取"带小数点"的段 —— 人民币金额几乎总带两位小数，而积分数、序号、
     * 版本号、余额里的小数点位置往往不同。只有整段都找不到小数时才回落到最后一段。
     */
    public static String getMoney(String content) {
        if (content == null || content.isEmpty()) {
            return null;
        }
        List<String> segments = new ArrayList<>();
        for (String s : content.replace(",", "").split("[^0-9.]")) {
            if (s.isEmpty()) {
                continue;
            }
            // 丢掉 "1.2.3"、"." 这类碎片，避免下游 parseDouble 抛异常丢单
            if (MONEY_SEGMENT.matcher(s).matches()) {
                segments.add(s);
            }
        }
        if (segments.isEmpty()) {
            return null;
        }
        for (int i = segments.size() - 1; i >= 0; i--) {
            if (segments.get(i).indexOf('.') > 0) {
                return segments.get(i);
            }
        }
        return segments.get(segments.size() - 1);
    }

    /**
     * 保留旧方法名以兼容既有调用点，实际统一走 {@link AppLog}：
     * 这样服务侧日志能同时进内存缓冲、按天落盘，并在设置页实时刷新。
     * 早期那条 {@code ACTION_LOG_UPDATE} 自定义广播通道已整体移除。
     */
    private void sendBroadcastLog(String logMessage) {
        AppLog.i(TAG, logMessage);
    }

    /**
     * Android 14+ 兼容性检查
     * 检查通知监听权限和相关功能的兼容性
     */
    private void checkAndroid14Compatibility() {
        try {
            // 检查通知监听权限状态
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                // Android 13+ 通知权限检查
                int notificationPermission = ContextCompat.checkSelfPermission(
                    this, android.Manifest.permission.POST_NOTIFICATIONS);
                
                if (notificationPermission != PackageManager.PERMISSION_GRANTED) {
                    AppLog.w(TAG, "Android 13+ 通知权限未授予，可能影响功能");
                    sendBroadcastLog("警告：通知权限未授予，请在设置中开启");
                }
            }
            
            // 检查通知监听服务权限
            String enabledListeners = android.provider.Settings.Secure.getString(
                getContentResolver(), "enabled_notification_listeners");
            
            if (enabledListeners == null || !enabledListeners.contains(getPackageName())) {
                AppLog.w(TAG, "通知监听权限可能未正确配置");
                sendBroadcastLog("警告：通知监听权限配置异常");
            } else {
                AppLog.d(TAG, "通知监听权限配置正常");
                sendBroadcastLog("通知监听权限配置正常");
            }
            
            // 检查电池优化白名单
            if (!Utils.checkBatteryWhiteList(this)) {
                AppLog.w(TAG, "应用未加入电池优化白名单，可能影响后台运行");
                sendBroadcastLog("建议：将应用加入电池优化白名单");
            }
            
            // 验证支付宝和微信包名的可访问性
            validatePaymentAppsAccessibility();
            
        } catch (Exception e) {
            AppLog.e(TAG, "Android 14+ 兼容性检查异常: " + e.getMessage(), e);
            sendBroadcastLog("兼容性检查异常: " + e.getMessage());
        }
    }

    /**
     * 验证支付宝和微信应用的可访问性
     * 确保通知解析逻辑在新版本上仍然有效
     */
    private void validatePaymentAppsAccessibility() {
        PackageManager pm = getPackageManager();
        
        // 检查支付宝
        try {
            pm.getPackageInfo("com.eg.android.AlipayGphone", PackageManager.GET_ACTIVITIES);
            AppLog.d(TAG, "支付宝应用检测正常");
            sendBroadcastLog("支付宝应用检测正常");
        } catch (PackageManager.NameNotFoundException e) {
            AppLog.w(TAG, "未检测到支付宝应用");
            sendBroadcastLog("未检测到支付宝应用");
        }
        
        // 检查微信
        try {
            pm.getPackageInfo("com.tencent.mm", PackageManager.GET_ACTIVITIES);
            AppLog.d(TAG, "微信应用检测正常");
            sendBroadcastLog("微信应用检测正常");
        } catch (PackageManager.NameNotFoundException e) {
            AppLog.w(TAG, "未检测到微信应用");
            sendBroadcastLog("未检测到微信应用");
        }
        
        // 检查企业微信
        try {
            pm.getPackageInfo("com.tencent.wework", PackageManager.GET_ACTIVITIES);
            AppLog.d(TAG, "企业微信应用检测正常");
            sendBroadcastLog("企业微信应用检测正常");
        } catch (PackageManager.NameNotFoundException e) {
            AppLog.d(TAG, "未检测到企业微信应用（可选）");
        }
    }

    /**
     * Android 14+ 通知访问验证
     */
    private boolean validateNotificationAccess(StatusBarNotification sbn) {
        try {
            // 验证通知是否可读
            if (sbn.getNotification() == null) {
                return false;
            }
            
            // 验证包名是否为目标应用
            String pkg = sbn.getPackageName();
            if (pkg == null) {
                return false;
            }
            
            // 只处理支付相关应用的通知
            return "com.eg.android.AlipayGphone".equals(pkg) || 
                   "com.tencent.mm".equals(pkg) || 
                   "com.tencent.wework".equals(pkg) ||
                   "com.vone.qrcode".equals(pkg);
                   
        } catch (Exception e) {
            AppLog.e(TAG, "通知访问验证异常: " + e.getMessage(), e);
            return false;
        }
    }

    /**
     * 处理支付通知的核心逻辑（从原onNotificationPosted方法中提取）
     */
    private void processPaymentNotification(StatusBarNotification sbn) {
        Notification notification = sbn.getNotification();
        String pkg = sbn.getPackageName();
        boolean isAlipay = PKG_ALIPAY.equals(pkg);
        boolean isWechat = PKG_WECHAT.equals(pkg) || PKG_WEWORK.equals(pkg);
        boolean isSelf = "com.vone.qrcode".equals(pkg);
        // 包名过滤提到最前：此前每个应用的通知都要写一条日志、读一次配置，
        // 群聊和推送密集时日志会被无关应用刷满，真正要排查的收款记录反而被淹没。
        if (notification == null || !(isAlipay || isWechat || isSelf)) {
            return;
        }
        Bundle extras = notification.extras;
        if (extras == null) {
            return;
        }

        CharSequence _title = extras.getCharSequence(NotificationCompat.EXTRA_TITLE, "");
        CharSequence _content = extras.getCharSequence(NotificationCompat.EXTRA_TEXT, "");
        // to string (企业微信之类的 getString 会出错，换getCharSequence)
        String title = _title == null ? "" : _title.toString();
        String content = _content == null ? "" : _content.toString();

        // 通知原文含用户昵称、到账金额、转账备注，属敏感信息，绝不落原文。
        // 日志只保留"哪个包、首尾少量字符"，用于判断是不是到账消息这一类；
        // 真正要核对识别结果，看紧随其后的「到账 / 未匹配」记录即可。
        AppLog.d(TAG, "收到通知 pkg=" + pkg
                + " 标题=" + Desensitizer.text(title, 2, 2)
                + " 正文=" + Desensitizer.text(content, 2, 2));

        // 同一笔到账可能触发多次回调（内容更新 / ROM 重发），重复上报会被后端当成多笔入账。
        // 必须在真正上报之前拦截，且只对支付相关的三个包生效，
        // 否则无关通知会把去重表挤满、把真正需要拦的那条挤掉。
        if (!markOnceIfNew(pkg, title, content)) {
            AppLog.i(TAG, "疑似重复到账通知，已忽略（" + (DEDUP_WINDOW_MS / 1000)
                    + " 秒内文案完全相同） pkg=" + pkg);
            return;
        }

        // 只在确认是支付相关通知之后才落盘：此前对所有应用的通知都写，
        // 等于把用户全部私信明文堆进一个文件，还占了回调线程的 IO 时间。
        writeNotifyToFile(sbn, title, content);

        if (isAlipay) {
            processAlipayNotification(title, content);
        } else if (isWechat) {
            processWechatNotification(title, content);
        } else {
            processTestNotification(content);
        }
    }

    /**
     * 记录一次到账通知的指纹，用于抑制重复回调。
     *
     * @return {@code true} 表示窗口内首次见到，应当继续处理
     */
    private boolean markOnceIfNew(String pkg, String title, String content) {
        return registerFingerprint(pkg + '\u0001' + title + '\u0001' + content, DEDUP_WINDOW_MS);
    }

    /**
     * 记录一次「包名 + 金额」指纹，拦住文案变了但仍是同一笔的重复通知。
     *
     * @return {@code true} 表示窗口内首次见到该金额
     */
    private boolean markAmountOnce(String pkg, String money) {
        return registerFingerprint(pkg + "\u0001#amount\u0001" + money, AMOUNT_DEDUP_WINDOW_MS);
    }

    /**
     * 指纹登记：先清掉过期项，再看窗口内是否已存在。
     *
     * @return {@code true} 表示首次见到
     */
    private boolean registerFingerprint(String fingerprint, long windowMs) {
        long now = System.currentTimeMillis();
        synchronized (recentNotifications) {
            long worstWindow = Math.max(DEDUP_WINDOW_MS, AMOUNT_DEDUP_WINDOW_MS);
            Iterator<Map.Entry<String, Long>> it = recentNotifications.entrySet().iterator();
            while (it.hasNext()) {
                if (now - it.next().getValue() > worstWindow) {
                    it.remove();
                }
            }
            Long last = recentNotifications.get(fingerprint);
            if (last != null && now - last <= windowMs) {
                return false;
            }
            if (recentNotifications.size() >= DEDUP_MAX_ENTRIES) {
                recentNotifications.clear();
            }
            recentNotifications.put(fingerprint, now);
            return true;
        }
    }

    /**
     * 处理支付宝通知。
     *
     * <p>v3.3.6 起不再要求「正文必须非空」：部分 ROM 精简通知后金额只留在标题里，
     * 原实现 {@code if (!content.equals(""))} 会把这种通知整单吞掉。关键词与金额
     * 现在都在标题、正文两个字段上分别匹配。</p>
     */
    private void processAlipayNotification(String title, String content) {
        boolean hit = containsAny(title, content,
                "通过扫码向你付款", "成功收款", "店员通");
        if (!hit) {
            return;
        }
        // 统一走 resolveMoney：带「元」的优先。此前「商家积分」分支直接调 getMoney，
        // 而 getMoney 取的是最后一个数字段 —— "成功收款12.34元，获得1个商家积分"
        // 会被解析成 1 元，把整笔金额记错。
        reportPayment(PKG_ALIPAY, PAY_TYPE_ALIPAY, "支付宝", resolveMoney(title, content));
    }

    /**
     * 处理微信通知。
     *
     * <p>微信 8.0.50 起调整过收款通知格式，标题与正文的关键词形态都保留兼容。</p>
     */
    private void processWechatNotification(String title, String content) {
        boolean titleHit = "微信".equals(title) || "微信支付".equals(title)
                || "微信收款助手".equals(title) || "微信收款商业版".equals(title);
        boolean bodyHit = content.contains("微信支付") || content.contains("微信收款助手")
                || content.contains("微信收款商业版");
        // 「对外收款 / 企业微信」标题太泛，必须配合正文关键词，否则聊天消息也会命中
        boolean corpHit = ("对外收款".equals(title) || "企业微信".equals(title))
                && (content.contains("成功收款") || content.contains("收款通知"));
        if (!titleHit && !bodyHit && !corpHit) {
            return;
        }
        reportPayment(PKG_WECHAT, PAY_TYPE_WECHAT, "微信", resolveMoney(title, content));
    }

    /**
     * 金额解析优先级：标题带「元」→ 正文带「元」→ 正文裸数字 → 标题裸数字。
     *
     * <p>标题优先是因为新版支付宝/微信把「成功收款X元」放在标题里，
     * 正文则可能混入积分、余额、活动文案。</p>
     */
    private static String resolveMoney(String title, String content) {
        String money = getMoney2(title);
        if (money == null) {
            money = getMoney2(content);
        }
        if (money == null) {
            money = getMoney(content);
        }
        if (money == null) {
            money = getMoney(title);
        }
        return money;
    }

    /** 标题或正文任一包含任一关键词。两个字段都可能为 ""，不会为 null。 */
    private static boolean containsAny(String title, String content, String... keywords) {
        for (String keyword : keywords) {
            if (title.contains(keyword) || content.contains(keyword)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 把一笔到账上报给服务端。
     *
     * <p>三条失败路径全部要留下**可定位**的记录，不能像原来那样只写一句
     * {@code "app push 错误！！！"} —— 那句话既没有金额也没有异常类型，
     * 等于把钱丢了还不留线索。金额解析失败尤其必须弹提示：用户只有看到
     * "本单未上报"才会去补单，否则这单就无声无息地消失了。</p>
     */
    private void reportPayment(String pkg, int type, String label, String money) {
        if (money == null) {
            AppLog.w(TAG, label + "通知命中收款关键词，但标题与正文都没解析出金额");
            toast("监听到" + label + "消息但未匹配到金额！");
            return;
        }
        final double amount;
        try {
            amount = Double.parseDouble(money);
        } catch (NumberFormatException e) {
            AppLog.w(TAG, label + " 金额非法，本单未上报：" + money);
            toast("金额解析失败，本单未上报：" + money);
            return;
        }
        // 文案变了但金额没变 → 高度怀疑是同一笔的第二次回声。
        // 这一行必须留日志：万一真是两笔同额到账被合并，用户能从日志里立刻看到并补单。
        if (!markAmountOnce(pkg, money)) {
            AppLog.w(TAG, label + " 疑似同一笔的重复通知，已忽略：" + money
                    + " 元（" + (AMOUNT_DEDUP_WINDOW_MS / 1000) + " 秒内同金额只上报一次）");
            return;
        }
        AppLog.i(TAG, label + " 到账 " + money);
        toast("匹配成功：" + label + "到账" + money + "元");
        try {
            appPush(type, amount);
        } catch (Throwable t) {
            AppLog.e(TAG, label + " 发起推送失败，本单未上报：" + money, t);
            toast("推送发起失败，本单未上报：" + money);
        }
    }

    private void toast(final String text) {
        handler.post(new Runnable() {
            @Override
            public void run() {
                Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show();
            }
        });
    }

    /**
     * 处理测试通知
     */
    private void processTestNotification(String content) {
        if (content.equals("这是一条测试推送信息，如果程序正常，则会提示监听权限正常")) {
            handler.post(new Runnable() {
                public void run() {
                    Toast.makeText(getApplicationContext(), "监听正常，如无法正常回调请联系作者反馈！", Toast.LENGTH_SHORT).show();
                }
            });
            // 发送日志广播，将监听状态记录到日志框
            sendBroadcastLog("监听权限检测结果：监听权限正常");
        }
    }
}