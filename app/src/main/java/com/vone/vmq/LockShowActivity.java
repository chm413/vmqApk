package com.vone.vmq;

import android.app.Activity;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.TextView;

import com.vone.qrcode.R;

public class LockShowActivity extends Activity {
    private TextView showTv;
    /** 广播是否已成功注册：避免注册失败后 onDestroy 反注册再抛一次 */
    private boolean receiverRegistered;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON);
        getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
        setContentView(R.layout.activity_lock_show);
        registerBroadCast();
        InitView();
        InitData();
    }

    /**
     * 注册「结束锁屏页」广播。
     *
     * <p><b>Android 14（API 34）起必须显式指定导出标志</b>：targetSdk >= 34 时，
     * 用 {@code registerReceiver} 注册<b>非系统广播</b>而不带
     * {@code RECEIVER_EXPORTED / RECEIVER_NOT_EXPORTED}，会直接抛
     * {@code SecurityException} 让进程崩溃。</p>
     *
     * <p>{@code Constant.FINISH_LOCK_SHOW_ACTIVITY} 是本应用自定义的 action，
     * 不属于系统广播豁免范围 —— 所以这台设备上一旦收款推送失败、这个锁屏页被拉起，
     * 就会闪退。{@code SettingsActivity} / {@code ForegroundServer} 早就按新规注册了，
     * 唯独这里漏了。</p>
     */
    private void registerBroadCast() {
        IntentFilter filter = new IntentFilter(Constant.FINISH_LOCK_SHOW_ACTIVITY);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 只有本应用自己会发这个广播，用 NOT_EXPORTED 最小化暴露面
            registerReceiver(finishActivityBroadcast, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(finishActivityBroadcast, filter);
        }
        receiverRegistered = true;
    }

    private void unregisterBroadCast() {
        if (!receiverRegistered) {
            return;
        }
        receiverRegistered = false;
        try {
            unregisterReceiver(finishActivityBroadcast);
        } catch (IllegalArgumentException ignored) {
            // 极端情况下注册尚未生效就被反注册，忽略
        }
    }

    private void InitView() {
        showTv = findViewById(R.id.showTv);
    }

    private void InitData() {
        resetData(getIntent());
    }

    /**
     * 显示通知类型：
     * -1 ：检测到对应的配置通知
     * 0 ： 远程的重要通知
     * 1 ： 通知栏通知
     */
    private void resetData(Intent intent) {
        String showTvText = intent.getStringExtra(Constant.GET_MESSAGE_KEY);
        showTv.setText(showTvText);
    }


    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        resetData(intent);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unregisterBroadCast();
    }

    private final BroadcastReceiver finishActivityBroadcast = new BroadcastReceiver() {

        @Override
        public void onReceive(Context context, Intent intent) {
            if (Constant.FINISH_LOCK_SHOW_ACTIVITY.equals(intent.getAction())) {
                if (!isFinishing()) {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        finishAndRemoveTask();
                    }
                }
            } else if (Constant.UPDATA_MESSAGE_DATA_ACTION.equals(intent.getAction())) {
                resetData(intent);
            }
        }
    };
}
