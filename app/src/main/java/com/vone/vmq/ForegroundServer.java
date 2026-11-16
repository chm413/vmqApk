package com.vone.vmq;

import android.annotation.TargetApi;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import androidx.annotation.RequiresApi;
import android.text.TextUtils;
import android.util.Log;

import com.vone.qrcode.R;

import org.json.JSONObject;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

@SuppressWarnings("FieldCanBeLocal")
public class ForegroundServer extends Service {
    public static final String GET_NOTIFY_TITLE = "get_notify_title";
    public static final String GET_NOTIFY_TEXT = "get_notify_text";
    //这里传 json 过来
    public static final String GET_NOTIFY_EXTRA = "get_notify_extra";

    private final int FOREGROUND_ID = 1;

    private final String channel_name = "ForegoundServer";
    private final String CHANNEL_ID = "service";

    private final Handler handler = new Handler(Looper.getMainLooper());

    private final long MIN_SHOW_TIME = 2000;
    /**
     * 最后一次性尝试结束之后，前台通知最多再挂多久（<b>空闲超时</b>，不是总时长）。
     *
     * <p>见 {@link #armStopTimer()}：每次发起重试都会重新计时。</p>
     */
    private final long MAX_SHOW_TIME = 20000;
    /** 单次前台重试的绝对寿命上限，防止服务被无限续命长期挂着常驻通知 */
    private static final long MAX_FOREGROUND_LIFETIME_MS = 10 * 60_000L;
    /** 本轮的绝对退出时刻（elapsedRealtime） */
    private long foregroundDeadline;
    /** 前台推送连续失败的最大退避间隔：5 分钟 */
    private static final long MAX_PUSH_BACKOFF_MS = 300_000L;
    /** 当前推送任务的连续失败次数（成功清零） */
    private int pushAttempt = 0;

    private long nextPushDelay(IOException e) {
        long delay = Utils.backoffDelayMs(pushAttempt, MIN_SHOW_TIME, MAX_PUSH_BACKOFF_MS);
        AppLog.d("ForegroundServer", "推送重试退避 " + delay + "ms（连续失败 " + pushAttempt + " 次）: "
                + (e != null ? e.getMessage() : "服务端拒绝"));
        return delay;
    }

    private long enterTime;

    @Override
    public void onCreate() {
        super.onCreate();
        enterTime = SystemClock.elapsedRealtime();
        
        // Android 8.0+ 需要前台服务
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            setForegroundService();
        }
        
        registerFinishBroad();
        AppLog.d("ForegroundServer", "前台服务已创建");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        foregroundDeadline = SystemClock.elapsedRealtime() + MAX_FOREGROUND_LIFETIME_MS;
        armStopTimer();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            updateNotify(intent);
        }
        startForegroundActivity(intent);
        return super.onStartCommand(intent, flags, startId);
    }

    /**
     * 重新给「自动退出前台」计时。
     *
     * <p><b>语义必须是空闲超时，不能是总时长。</b>原实现只在 {@code onStartCommand} 里
     * {@code postDelayed(MAX_SHOW_TIME)} 一次，20 秒后无论如何都 {@code stopService}；
     * 而推送重试的退避上限是 5 分钟（{@code MAX_PUSH_BACKOFF_MS}），按 2s→4s→8s→16s 退避，
     * 第 4 次重试就要等到第 30 秒 —— <b>永远等不到，服务已经被停了</b>。
     * 表现就是「断网时剩下的几次重试静默消失」，钱那笔单就丢了。</p>
     *
     * <p>现在每次真正发起一次重试都重新计时，只有「最后一次尝试结束后 20 秒」才退出；
     * 同时用 {@link #MAX_FOREGROUND_LIFETIME_MS} 兜住绝对寿命，避免被无限续命。</p>
     */
    private void armStopTimer() {
        handler.removeCallbacks(stopServerRunnable);
        long remain = foregroundDeadline > 0
                ? foregroundDeadline - SystemClock.elapsedRealtime()
                : MAX_SHOW_TIME;
        long delay = Math.min(MAX_SHOW_TIME, Math.max(1000L, remain));
        handler.postDelayed(stopServerRunnable, delay);
    }

    @Override
    public void onDestroy() {
        unregisterFinishBroad();
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        AppLog.i("ForegoundServer", "stop notification");
        if (notificationManager != null) {
            notificationManager.cancel(FOREGROUND_ID);
        }
        handler.removeCallbacks(stopServerRunnable);
        super.onDestroy();
    }

    private void startForegroundActivity(Intent intent) {
        if (intent == null) {
            return;
        }
        String extraStr = intent.getStringExtra(GET_NOTIFY_EXTRA);
        if (TextUtils.isEmpty(extraStr)) {
            return;
        }
        try {
            JSONObject jsonObject = new JSONObject(extraStr);
            final String url = jsonObject.optString("url");
            if (url == null) {
                return;
            }
            // 缺省改为静默（false）：本服务的职责是"保网络 + 受控重试"，
            // 不是向用户展示。v3.3.8 前缺省 true 导致推送失败时强制亮屏弹锁屏页，
            // 深夜挂机时尤其扰民。心跳链路早已传 false，这里把默认值对齐，
            // 以后新增调用点即使漏传也不会突然亮屏。
            if (jsonObject.optBoolean("show", false)) {
                startLockActivity(this.getString(R.string.app_is_post));
            }
            tryPushByUrl(
                    url,
                    jsonObject.optInt("try_count", 1),
                    MonitorResponse.normalizeForegroundRetryDelayMillis(
                            jsonObject.optLong("retry_after_millis", 0L),
                            MIN_SHOW_TIME
                    )
            );
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void tryPushByUrl(final String url, final int count, final long retryDelayMillis) {
        if (count <= 0) {
            handler.post(new Runnable() {
                @Override
                public void run() {
                    NeNotificationService2.exitForeground(App.getContext());
                }
            });
            return;
        }
        // 失败后按服务端允许的最短间隔重试，避免 429 期间持续撞击限流器；
        // 连续失败叠加指数退避（5s→10s→20s...上限 5 分钟），成功后计数在下一笔推送重置。
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                // 每发起一次重试就续一次空闲超时，否则 20 秒后服务被停、后面的重试全部落空
                armStopTimer();
                Request request = new Request.Builder().url(url).method("GET", null).build();
                Call call = Utils.getOkHttpClient(ForegroundServer.this).newCall(request);
                call.enqueue(new Callback() {
                    @Override
                    public void onFailure(Call call, IOException e) {
                        AppLog.d("ForegroundServer", "前台推送请求失败: " + e.getMessage());
                        pushAttempt++;
                        tryPushByUrl(url, count - 1, nextPushDelay(e));
                    }

                    @Override
                    public void onResponse(Call call, Response response) throws IOException {
                        try {
                            MonitorResponse result = MonitorResponse.from(response);
                            AppLog.d("ForegroundServer", "前台推送结果: " + result.summary());
                            if (!result.isSuccessful()) {
                                AppLog.d("ForegroundServer", "前台推送未被服务端接受，重试");
                                pushAttempt++;
                                tryPushByUrl(
                                        url,
                                        count - 1,
                                        Math.max(
                                                result.foregroundRetryDelayMillis(MIN_SHOW_TIME),
                                                Utils.backoffDelayMs(pushAttempt, MIN_SHOW_TIME, MAX_PUSH_BACKOFF_MS)
                                        )
                                );
                                return;
                            }
                            pushAttempt = 0;

                            AppLog.d("ForegroundServer", "前台推送成功，退出前台");
                            handler.post(new Runnable() {
                                @Override
                                public void run() {
                                    NeNotificationService2.exitForeground(App.getContext());
                                }
                            });
                        } catch (Exception e) {
                            AppLog.e("ForegroundServer", "前台推送响应解析异常: " + e.getMessage(), e);
                            tryPushByUrl(url, count - 1, MIN_SHOW_TIME);
                        } finally {
                            response.close();
                        }
                    }
                });
            }
        }, MonitorResponse.normalizeForegroundRetryDelayMillis(retryDelayMillis, MIN_SHOW_TIME));
    }

    /**
     * 通过通知启动服务
     */
    @TargetApi(Build.VERSION_CODES.O)
    private void setForegroundService() {
        // IMPORTANCE_LOW：前台通知仍然可见（startForeground 的硬性要求，不能去掉），
        // 但不响铃、不横幅 —— 推送重试期间保持"静默"。
        // 注意：渠道 importance 创建后只能降不能升，从 DEFAULT 降到 LOW 会立即生效。
        Utils.createNotificationChannel(this, CHANNEL_ID, channel_name
                , NotificationManager.IMPORTANCE_LOW);
        Notification notification = getNotifycation(null);

        startForeground(FOREGROUND_ID, notification);
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private void updateNotify(Intent intent) {
        NotificationManager notificationManager = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        if (notificationManager != null) {
            Notification notification = getNotifycation(intent);

            notificationManager.notify(FOREGROUND_ID, notification);
        }
    }

    @RequiresApi(api = Build.VERSION_CODES.LOLLIPOP)
    private Notification getNotifycation(Intent intent) {
        String title = intent == null ? getString(R.string.app_name) :
                TextUtils.isEmpty(intent.getStringExtra(GET_NOTIFY_TITLE)) ? getString(R.string.app_name)
                        : intent.getStringExtra(GET_NOTIFY_TITLE);
        String text = intent == null ? getString(R.string.click_close_notify) :
                TextUtils.isEmpty(intent.getStringExtra(GET_NOTIFY_TEXT)) ? getString(R.string.click_close_notify)
                        : intent.getStringExtra(GET_NOTIFY_TEXT);
        Notification.Builder notificationBuilder = new Notification.Builder(this)
                .setContentTitle(title)//设置通知标题
                .setContentText(text)//设置通知内容
                .setPriority(Notification.PRIORITY_MIN)
                .setCategory(Notification.CATEGORY_SERVICE)
                .setOngoing(true)
                .setWhen(System.currentTimeMillis())
                .setShowWhen(true)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setAutoCancel(true);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            notificationBuilder.setChannelId(CHANNEL_ID);
        }

        Notification notification = notificationBuilder.build();

        Intent notificationIntent = new Intent(getApplicationContext(), StartReceive.class);
        notificationIntent.setAction(StartReceive.TRY_CLOSE_ACTIVITY_ACTION);
        
        // 适配Android 12+的PendingIntent要求
        int flags = 0;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            flags = PendingIntent.FLAG_IMMUTABLE;
        }
        notification.contentIntent = PendingIntent.getBroadcast(getApplicationContext(),
                0, notificationIntent, flags);
        return notification;
    }

    private void registerFinishBroad() {
        IntentFilter intentFilter = new IntentFilter(Constant.FINISH_FOREGROUND_SERVICE);
        
        // 适配Android 13+的BroadcastReceiver注册要求
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(finishServiceBroadcast, intentFilter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishServiceBroadcast, intentFilter);
        }
    }

    private void unregisterFinishBroad() {
        try {
            this.unregisterReceiver(finishServiceBroadcast);
        } catch (Exception ignore) {
        }
    }

    private final Runnable stopServerRunnable = new Runnable() {
        @Override
        public void run() {
            try {
                finishLockActivity();
                Intent intent1 = new Intent(App.getContext(), ForegroundServer.class);
                stopService(intent1);
            } catch (Exception ignore) {
            }
        }
    };

    private final BroadcastReceiver finishServiceBroadcast = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constant.FINISH_FOREGROUND_SERVICE.equals(intent.getAction())) {
                long temp = SystemClock.elapsedRealtime() - enterTime;
                handler.removeCallbacks(stopServerRunnable);
                if (temp > MIN_SHOW_TIME) {
                    handler.post(stopServerRunnable);
                } else {
                    handler.postDelayed(stopServerRunnable, MIN_SHOW_TIME - temp);
                }
            }
        }
    };

    private void startLockActivity(String msg) {
        Intent intent = new Intent(this, LockShowActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        intent.putExtra(Constant.GET_MESSAGE_KEY, msg);
        startActivity(intent);
    }

    private void finishLockActivity() {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Constant.FINISH_LOCK_SHOW_ACTIVITY);
        sendBroadcast(sendIntent);
    }

    private void updataMessageData(String msg) {
        Intent sendIntent = new Intent();
        sendIntent.setAction(Constant.UPDATA_MESSAGE_DATA_ACTION);
        sendIntent.putExtra(Constant.GET_MESSAGE_KEY, msg);
        sendBroadcast(sendIntent);
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
