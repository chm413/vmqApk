package com.vone.vmq;

import android.annotation.SuppressLint;
import android.app.Application;
import android.content.Context;
import android.os.Process;
import android.util.Log;

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
