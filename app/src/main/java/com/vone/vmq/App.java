package com.vone.vmq;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.util.Log;

import com.vone.vmq.util.SecurePrefs;

public class App extends Application {

    @SuppressLint("StaticFieldLeak")
    private static Context mContext;

    public static Context getContext() {
        return mContext;
    }

    @Override
    public void onCreate() {
        super.onCreate();

        // 统一日志必须最先初始化：后续主题初始化、崩溃处理的日志都要能落盘
        AppLog.init(this);
        AppLog.i("App", "进程启动 pid=" + Process.myPid());

        // 初始化Material You主题系统
        ThemeUtils.initializeTheme(this);

        // 预热通讯密钥加密层：在后台线程完成 Keystore 密钥生成、
        // 以及存量明文密钥 → 密文的一次性迁移。
        // 不预热的话，升级后第一笔收款会在通知回调（主线程）上现场生成密钥，
        // 个别机型 Keystore 生成密钥需上百毫秒，白白拖慢第一次收款处理。
        new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    SecurePrefs.readKey(getSharedPreferences("vone", MODE_PRIVATE));
                } catch (Throwable t) {
                    // 预热失败不影响主流程，真正读配置时会再试一次
                    AppLog.w("App", "密钥加密层预热失败: " + t.getClass().getSimpleName());
                }
            }
        }, "vmq-secureprefs-warmup").start();

        Thread.setDefaultUncaughtExceptionHandler(new Thread.UncaughtExceptionHandler() {
            @Override
            public void uncaughtException(Thread thread, Throwable throwable) {
                AppLog.e("App", "未捕获异常 on " + thread.getName(), throwable);
                Utils.putStr(App.this, "exception:" + Log.getStackTraceString(throwable));
                Process.killProcess(Process.myPid());
            }
        });
    }

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        mContext = base;
    }
}
