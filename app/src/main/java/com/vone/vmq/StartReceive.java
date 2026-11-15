package com.vone.vmq;

import static android.content.Context.POWER_SERVICE;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.os.PowerManager;
import android.util.Log;

import com.vone.qrcode.R;

/**
 * Created by user68 on 2018/7/30.
 * <p>
 * 接收另一个app的广播启动本地服务
 */

public class StartReceive extends BroadcastReceiver {
    public static final String START_SETTING_ACTIVITY_ACTION = "android.provider.Telephony.SECRET_CODE";
    /** 前台重试通知被点击时发出的动作，用于让用户手动结束那根常驻通知 */
    public static final String TRY_CLOSE_ACTIVITY_ACTION = "try_close_activity_action";

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent == null ? "" : String.valueOf(intent.getAction());
        // 开机自启是整条链路的起点：日志里必须能看出"系统到底有没有把我们拉起来"，
        // 否则用户报"重启后就不工作了"时无从判断。
        AppLog.i("StartReceive", "收到广播 action=" + action);

        if (Intent.ACTION_BOOT_COMPLETED.equals(action)) {
            boolean whitelisted = checkBatteryWhiteList(context);
            if (!whitelisted) {
                Utils.sendBatteryNotify(context);
            }
            AppLog.event("StartReceive", "开机自启",
                    "电池优化白名单=" + (whitelisted ? "已加入" : "未加入"));
            NeNotificationService2.enterForeground(context,
                    context.getString(R.string.app_name),
                    context.getString(R.string.app_is_start), "");
        }

        if (START_SETTING_ACTIVITY_ACTION.equals(action)) {
            AppLog.i("StartReceive", "通过 SECRET_CODE 打开设置页");
            Intent startActivityIntent = new Intent(context, MainActivity.class);
            startActivityIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            context.startActivity(startActivityIntent);
        }

        if (TRY_CLOSE_ACTIVITY_ACTION.equals(action)) {
            // 前台重试通知的点击动作。此前这个 action 只被声明、没有任何地方处理，
            // 用户点通知"没反应"，常驻通知只能等它自己超时消失。
            AppLog.i("StartReceive", "用户点击前台重试通知，结束前台服务");
            NeNotificationService2.exitForeground(context);
        }
    }

    static boolean checkBatteryWhiteList(Context context) {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.M) {
            PowerManager powerManager = (PowerManager) context.getSystemService(POWER_SERVICE);
            if (powerManager == null) return true;
            return powerManager.isIgnoringBatteryOptimizations(context.getPackageName());
        }
        return true;
    }
}
