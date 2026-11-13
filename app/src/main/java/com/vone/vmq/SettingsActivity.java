package com.vone.vmq;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.View;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.activity.CaptureActivity;
import com.vone.qrcode.R;
import com.vone.vmq.util.Constant;

import java.io.IOException;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;

/**
 * 设置页：服务器配置、心跳与重试说明、保活入口、运行日志。
 * 主页只做状态展示，所有可写配置集中在这里。
 */
public class SettingsActivity extends AppCompatActivity {
    private static final String TAG = "SettingsActivity";
    private static final String PREFS = "vone";

    private TextInputEditText edtHost;
    private TextInputEditText edtKey;
    private MaterialSwitch swVerifySsl;
    private TextView logText;
    private ScrollView logScroll;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> qrLauncher;
    private BroadcastReceiver logReceiver;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        ThemeUtils.applyThemeToActivity(this);
        setContentView(R.layout.activity_settings);

        Toolbar toolbar = findViewById(R.id.settings_toolbar);
        toolbar.setNavigationIcon(androidx.appcompat.R.drawable.abc_ic_ab_back_material);
        toolbar.setNavigationOnClickListener(v -> finish());

        edtHost = findViewById(R.id.settings_edt_host);
        edtKey = findViewById(R.id.settings_edt_key);
        swVerifySsl = findViewById(R.id.settings_sw_verify_ssl);
        logText = findViewById(R.id.settings_log_text);
        logScroll = findViewById(R.id.settings_log_scroll);

        loadConfig();

        MaterialButton btnSave = findViewById(R.id.btn_save);
        btnSave.setOnClickListener(v -> saveConfig());

        qrLauncher = registerForActivityResult(
                new ActivityResultContracts.StartActivityForResult(),
                result -> {
                    if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                        Bundle extras = result.getData().getExtras();
                        if (extras != null) {
                            handleQrConfig(extras.getString(Constant.INTENT_EXTRA_KEY_QR_SCAN));
                        }
                    }
                });

        findViewById(R.id.btn_qr).setOnClickListener(v -> startQrScan());
        findViewById(R.id.btn_manual).setOnClickListener(v -> showManualInputDialog());
        findViewById(R.id.btn_battery).setOnClickListener(v -> requestIgnoreBatteryOptimization());
        findViewById(R.id.btn_app_detail).setOnClickListener(v -> openAppDetail());
        findViewById(R.id.btn_keepalive_guide).setOnClickListener(v -> showKeepAliveGuide());
        findViewById(R.id.btn_clear_logs).setOnClickListener(v -> {
            if (logText != null) {
                logText.setText("");
            }
        });

        IntentFilter filter = new IntentFilter(NeNotificationService2.ACTION_LOG_UPDATE);
        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                String msg = intent.getStringExtra("log");
                appendLog(msg);
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(logReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (logReceiver != null) {
            try {
                unregisterReceiver(logReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    private void loadConfig() {
        SharedPreferences read = getSharedPreferences(PREFS, MODE_PRIVATE);
        String host = read.getString("host", "");
        String scheme = read.getString("scheme", "http");
        edtHost.setText(TextUtils.isEmpty(host) ? "" : scheme + "://" + host);
        edtKey.setText(read.getString("key", ""));
        swVerifySsl.setChecked(read.getBoolean("verify_ssl", false));
    }

    /** 保存配置：规范化地址（剥离协议前缀、去掉末尾斜杠），协议与密钥校验开关一并持久化。 */
    private void saveConfig() {
        String inputHost = edtHost.getText() != null ? edtHost.getText().toString().trim() : "";
        String inputKey = edtKey.getText() != null ? edtKey.getText().toString().trim() : "";
        if (TextUtils.isEmpty(inputHost)) {
            Toast.makeText(this, "请填写通知地址", Toast.LENGTH_SHORT).show();
            return;
        }
        if (TextUtils.isEmpty(inputKey)) {
            Toast.makeText(this, "请填写通讯密钥", Toast.LENGTH_SHORT).show();
            return;
        }

        String scheme = "http";
        String host = inputHost;
        if (inputHost.startsWith("https://")) {
            scheme = "https";
            host = inputHost.substring(8);
        } else if (inputHost.startsWith("http://")) {
            host = inputHost.substring(7);
        }
        if (host.endsWith("/")) {
            host = host.substring(0, host.length() - 1);
        }

        persistConfig(scheme, host, inputKey, swVerifySsl.isChecked());
        Toast.makeText(this, "配置已保存", Toast.LENGTH_SHORT).show();
        setResult(RESULT_OK);
    }

    private void persistConfig(String scheme, String host, String key, boolean verifySsl) {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                .putString("host", host)
                .putString("key", key)
                .putString("scheme", scheme)
                .putBoolean("verify_ssl", verifySsl)
                .apply();
        // 配置变更后心跳线程下一轮自动生效（线程每轮重新读取 SharedPreferences）
    }

    // ---------------- 扫码 / 手动配置 ----------------

    private void startQrScan() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.CAMERA}, Constant.REQ_PERM_CAMERA);
            return;
        }
        launchScanner();
    }

    private void launchScanner() {
        try {
            Intent intent = new Intent(this, CaptureActivity.class);
            intent.addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP);
            qrLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "启动扫码界面失败: " + e.getMessage(), e);
            Toast.makeText(this, "启动扫码界面失败，请重试", Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == Constant.REQ_PERM_CAMERA) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                launchScanner();
            } else {
                Toast.makeText(this, "相机权限被拒绝，无法扫码", Toast.LENGTH_SHORT).show();
            }
        }
    }

    private void showManualInputDialog() {
        TextInputLayout inputLayout = new TextInputLayout(this);
        inputLayout.setBoxBackgroundMode(TextInputLayout.BOX_BACKGROUND_OUTLINE);
        inputLayout.setHint("粘贴配置数据");
        inputLayout.setHelperText("格式：服务器地址/通讯密钥（可带 https:// 前缀）");

        final TextInputEditText input = new TextInputEditText(this);
        input.setSingleLine(false);
        inputLayout.addView(input);

        new MaterialAlertDialogBuilder(this)
                .setTitle("手动配置")
                .setView(inputLayout)
                .setNegativeButton("取消", null)
                .setPositiveButton("确认", (dialog, which) -> handleQrConfig(input.getText().toString()))
                .show();
    }

    /**
     * 解析并验证配置（兼容 host/key 与 https://host/key），验证通过后写入。
     */
    private void handleQrConfig(String scanResult) {
        if (TextUtils.isEmpty(scanResult)) {
            Toast.makeText(this, "配置数据为空", Toast.LENGTH_SHORT).show();
            return;
        }
        String rest = scanResult.trim();
        String scheme = "http";
        if (rest.startsWith("https://")) {
            scheme = "https";
            rest = rest.substring(8);
        } else if (rest.startsWith("http://")) {
            rest = rest.substring(7);
        }
        String[] parts = rest.split("/");
        if (parts.length != 2) {
            Toast.makeText(this, "配置格式错误，应为 服务器地址/通讯密钥", Toast.LENGTH_SHORT).show();
            return;
        }
        String host = parts[0].trim();
        String key = parts[1].trim();
        if (TextUtils.isEmpty(host) || TextUtils.isEmpty(key)) {
            Toast.makeText(this, "配置内容不完整", Toast.LENGTH_SHORT).show();
            return;
        }
        if (host.contains("localhost")) {
            Toast.makeText(this, "本机调试请使用局域网 IP:端口 获取配置", Toast.LENGTH_LONG).show();
            return;
        }

        final String finalScheme = scheme;
        String t = MonitorSign.timestamp();
        String sign = MonitorSign.heartbeat(t, key);
        Request request = new Request.Builder()
                .url(scheme + "://" + host + "/api/monitor/heart?t=" + t + "&sign=" + sign)
                .method("GET", null)
                .build();

        Toast.makeText(this, "正在验证配置…", Toast.LENGTH_SHORT).show();
        Utils.getOkHttpClient(this).newCall(request).enqueue(new Callback() {
            @Override
            public void onFailure(Call call, IOException e) {
                runOnUiThread(() -> Toast.makeText(SettingsActivity.this,
                        "验证失败：" + e.getMessage(), Toast.LENGTH_LONG).show());
            }

            @Override
            public void onResponse(Call call, Response response) throws IOException {
                boolean ok;
                String summary;
                try {
                    MonitorResponse result = MonitorResponse.from(response);
                    ok = result.isSuccessful();
                    summary = result.summary();
                } catch (Exception e) {
                    ok = false;
                    summary = e.getMessage();
                } finally {
                    response.close();
                }
                final boolean success = ok;
                final String message = summary;
                runOnUiThread(() -> {
                    if (!success) {
                        Toast.makeText(SettingsActivity.this, "配置验证失败：" + message, Toast.LENGTH_LONG).show();
                        return;
                    }
                    persistConfig(finalScheme, host, key, swVerifySsl.isChecked());
                    edtHost.setText(host);
                    edtKey.setText(key);
                    Toast.makeText(SettingsActivity.this, "配置成功", Toast.LENGTH_SHORT).show();
                    setResult(RESULT_OK);
                });
            }
        });
    }

    // ---------------- 保活 ----------------

    private void requestIgnoreBatteryOptimization() {
        PowerManager pm = (PowerManager) getSystemService(Context.POWER_SERVICE);
        if (pm != null && pm.isIgnoringBatteryOptimizations(getPackageName())) {
            Toast.makeText(this, "已在电池优化白名单中", Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            Intent intent = new Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this, "无法打开电池优化设置，请手动在系统设置中允许后台运行", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void openAppDetail() {
        try {
            Intent intent = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "无法打开应用详情页", Toast.LENGTH_SHORT).show();
        }
    }

    private void showKeepAliveGuide() {
        String guide = "1. 电池优化白名单：点上方按钮，允许应用后台无限制运行。\n\n"
                + "2. 自启动 / 后台运行：进入「应用详情」→ 自启动、关联启动、后台运行、锁屏清理，全部设为允许。\n\n"
                + "3. 支付宝：我的 → 设置 → 通用 → 后台运行 / 自启动，关闭省电策略，避免收款推送被系统冻结。\n\n"
                + "4. 微信：我 → 设置 → 通用 → 后台保留；在系统电池设置里取消对微信的限制。\n\n"
                + "5. 通知使用权必须保持开启；若被系统回收，本应用会自动重绑，失败时请到系统设置重新授权。\n\n"
                + "6. 验证方式：主页点「检测监听权限」，能收到测试通知说明链路正常；长时间无通知也可先在主页看心跳是否在线。";
        new MaterialAlertDialogBuilder(this)
                .setTitle("支付宝 / 微信 保活指引")
                .setMessage(guide)
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------- 日志 ----------------

    private void appendLog(final String message) {
        if (TextUtils.isEmpty(message) || logText == null) {
            return;
        }
        handler.post(() -> {
            logText.append("\n" + message);
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }
}
