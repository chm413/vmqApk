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
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.zxing.activity.CaptureActivity;
import com.vone.qrcode.R;
import com.vone.vmq.util.Constant;

import java.io.IOException;
import java.util.Collections;
import java.util.List;

import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.Request;
import okhttp3.Response;
import rikka.shizuku.Shizuku;

// AGP 8 默认 nonTransitiveRClass=true，app 的 R 只含本模块资源。
// colorPrimary / colorOnSurface / colorError 由 material 库声明，必须取库的 R.attr。
import static com.google.android.material.R.attr.colorError;
import static com.google.android.material.R.attr.colorOnSurface;
import static com.google.android.material.R.attr.colorPrimary;

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
    /** 日志 TextView 的字符上限，超出后裁掉前半段，防止长时间运行卡顿 */
    private static final int MAX_LOG_CHARS = 60_000;

    private TextView logText;
    private ScrollView logScroll;
    private MaterialSwitch swAliveMonitor;
    private MaterialButton btnAliveInterval;
    private MaterialButton btnLogLevel;
    private TextView tvAliveStatus;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private ActivityResultLauncher<Intent> qrLauncher;
    private BroadcastReceiver logReceiver;
    private TextView tvShizukuStatus;
    private Shizuku.OnRequestPermissionResultListener shizukuPermissionListener;

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
            AppLog.clear();
            if (logText != null) {
                logText.setText("");
            }
            // 日志管理动作本身也要留痕，否则"谁清空的"永远查不到
            AppLog.event("Settings", "清空日志", "用户手动清除全部内存与磁盘日志");
        });
        findViewById(R.id.btn_export_logs).setOnClickListener(v -> exportLogs());
        btnLogLevel = findViewById(R.id.btn_log_level);
        btnLogLevel.setOnClickListener(v -> showLevelPicker());

        // 运行监控配置
        swAliveMonitor = findViewById(R.id.sw_alive_monitor);
        btnAliveInterval = findViewById(R.id.btn_alive_interval);
        tvAliveStatus = findViewById(R.id.tv_alive_status);
        swAliveMonitor.setChecked(AliveMonitor.isMonitorEnabled(this));
        swAliveMonitor.setOnCheckedChangeListener((buttonView, isChecked) -> {
            AliveMonitor.setMonitorEnabled(this, isChecked);
            AppLog.event("Monitor", "存活监控开关", isChecked ? "已启用" : "已停用");
        });
        btnAliveInterval.setOnClickListener(v -> showIntervalPicker());
        findViewById(R.id.btn_alive_check).setOnClickListener(v -> runAliveCheckNow());
        findViewById(R.id.btn_usage_stats).setOnClickListener(v -> openUsageAccessSettings());
        refreshAliveStatus();

        tvShizukuStatus = findViewById(R.id.tv_shizuku_status);
        findViewById(R.id.btn_shizuku_auth).setOnClickListener(v -> requestShizuku());
        findViewById(R.id.btn_shizuku_keepalive).setOnClickListener(v -> runShizukuKeepAlive());
        findViewById(R.id.btn_shizuku_notif).setOnClickListener(v -> runShizukuNotificationGrant());
        findViewById(R.id.btn_shizuku_adb).setOnClickListener(v -> showAdbCommands());

        shizukuPermissionListener = (requestCode, grantResult) -> {
            if (requestCode == ShizukuKeepAlive.REQUEST_CODE) {
                refreshShizukuStatus();
                Toast.makeText(this,
                        grantResult == PackageManager.PERMISSION_GRANTED ? "Shizuku 授权成功" : "Shizuku 授权被拒绝",
                        Toast.LENGTH_SHORT).show();
            }
        };
        try {
            Shizuku.addRequestPermissionResultListener(shizukuPermissionListener);
        } catch (Throwable ignored) {
            // Shizuku 未安装/未运行时注册失败，属预期情况
        }
        refreshShizukuStatus();

        IntentFilter filter = new IntentFilter(AppLog.ACTION_APPEND);
        logReceiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                appendLog(intent.getStringExtra(AppLog.EXTRA_LINE));
            }
        };
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            // 日志广播只在本应用内流转，不需要对外导出
            registerReceiver(logReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(logReceiver, filter);
        }

        // 进入页面时回填已有日志（含进程重启前写入磁盘的部分）
        if (logText != null) {
            logText.setText(AppLog.dumpText());
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        }
        updateLevelButton();
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshShizukuStatus();
        refreshAliveStatus();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (shizukuPermissionListener != null) {
            try {
                Shizuku.removeRequestPermissionResultListener(shizukuPermissionListener);
            } catch (Throwable ignored) {
            }
        }
        if (logReceiver != null) {
            try {
                unregisterReceiver(logReceiver);
            } catch (Exception ignored) {
            }
        }
    }

    // ---------------- Shizuku 增强保活 ----------------

    private void refreshShizukuStatus() {
        if (tvShizukuStatus == null) {
            return;
        }
        tvShizukuStatus.setText(ShizukuKeepAlive.statusText());
        // 按主题属性取色，避免深色模式下用浅色常量导致对比度不足
        int colorAttr;
        if (ShizukuKeepAlive.isGranted()) {
            colorAttr = colorPrimary;
        } else if (ShizukuKeepAlive.isAvailable()) {
            colorAttr = colorOnSurface;
        } else {
            colorAttr = colorError;
        }
        tvShizukuStatus.setTextColor(MaterialColors.getColor(tvShizukuStatus, colorAttr));
    }

    private void requestShizuku() {
        if (!ShizukuKeepAlive.isAvailable()) {
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Shizuku 未运行")
                    .setMessage("请先安装并启动 Shizuku（无线调试或 root 启动），再回到本页授权。\n\n"
                            + "不使用 Shizuku 也可以：点「查看 ADB 等效命令」，在电脑上执行同样的保活命令。")
                    .setPositiveButton("知道了", null)
                    .show();
            return;
        }
        ShizukuKeepAlive.requestPermission();
    }

    /** 一键保活：本应用 + 支付宝 + 微信（Doze 白名单 / active 待机桶 / 后台 appop）。 */
    private void runShizukuKeepAlive() {
        if (!ShizukuKeepAlive.isGranted()) {
            Toast.makeText(this, "请先授权 Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        List<String> commands = ShizukuKeepAlive.keepAliveCommands(getPackageName());
        executeBatchAsync("支付宝 / 微信 / 本应用 保活", commands);
    }

    /** Android 15 敏感通知权限：替代 README 里的 adb 命令。 */
    private void runShizukuNotificationGrant() {
        if (!ShizukuKeepAlive.isGranted()) {
            Toast.makeText(this, "请先授权 Shizuku", Toast.LENGTH_SHORT).show();
            return;
        }
        executeBatchAsync("敏感通知权限",
                Collections.singletonList(ShizukuKeepAlive.sensitiveNotificationCommand(getPackageName())));
    }

    private void executeBatchAsync(final String title, final List<String> commands) {
        AppLog.event("KeepAlive", "开始执行", title + "，共 " + commands.size() + " 条命令");
        final AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle(title)
                .setMessage("正在执行…")
                .setCancelable(false)
                .create();
        progress.show();

        new Thread(() -> {
            // 先单独确认执行通道（Shizuku user service 绑定是异步的），
            // 失败时给一条明确提示，而不是把 15 条命令全部标成 ✘。
            if (!ShizukuKeepAlive.ensureBound(this)) {
                AppLog.w("KeepAlive", title + "中止：Shizuku 执行通道未就绪（未授权或 Shizuku 未运行）");
                runOnUiThread(() -> {
                    progress.dismiss();
                    new MaterialAlertDialogBuilder(this)
                            .setTitle("Shizuku 执行通道未就绪")
                            .setMessage("请确认：\n"
                                    + "1. Shizuku 已安装并正在运行\n"
                                    + "2. 已在本页点「授权 Shizuku」并同意\n"
                                    + "3. 授权后 Shizuku 未被系统或省电策略杀掉\n\n"
                                    + "若反复失败，可点「查看 ADB 等效命令」在电脑上执行。")
                            .setPositiveButton("知道了", null)
                            .show();
                    refreshShizukuStatus();
                });
                return;
            }

            StringBuilder report = new StringBuilder();
            boolean allOk = true;
            int failed = 0;
            for (String command : commands) {
                String result = ShizukuKeepAlive.exec(SettingsActivity.this, command);
                boolean ok = result.contains("exit=0");
                allOk &= ok;
                if (!ok) {
                    failed++;
                }
                report.append(ok ? "✔ " : "✘ ").append(command).append('\n');
                // 逐条留痕：保活改动的是系统级配置，出问题必须能回溯是哪一条、返回了什么
                AppLog.event("KeepAlive", ok ? "命令成功" : "命令失败",
                        command + (ok ? "" : " → " + result.replace("\n", " ")));
                if (!ok) {
                    report.append("   ").append(result.replace("\n", " ")).append('\n');
                }
            }
            final String text = report.toString();
            final boolean success = allOk;
            AppLog.event("KeepAlive", success ? "全部成功" : "部分失败",
                    title + "，成功 " + (commands.size() - failed) + "/" + commands.size());
            runOnUiThread(() -> {
                progress.dismiss();
                new MaterialAlertDialogBuilder(this)
                        .setTitle(success ? title + "：全部成功" : title + "：部分命令失败")
                        .setMessage(text)
                        .setPositiveButton("知道了", null)
                        .show();
                refreshShizukuStatus();
            });
        }).start();
    }

    private void showAdbCommands() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("ADB 等效命令")
                .setMessage(ShizukuKeepAlive.adbEquivalents(getPackageName()))
                .setPositiveButton("知道了", null)
                .show();
    }

    // ---------------- 日志 ----------------

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

    /** 单条日志已自带时间戳与级别，这里只做追加与长度裁剪。 */
    private void appendLog(final String line) {
        if (TextUtils.isEmpty(line) || logText == null) {
            return;
        }
        handler.post(() -> {
            logText.append(line);
            logText.append("\n");
            // 长时间运行会累积大量行，超出上限时裁掉前半段，避免 TextView 无限膨胀导致卡顿
            CharSequence content = logText.getText();
            int len = content.length();
            if (len > MAX_LOG_CHARS) {
                logText.setText(content.subSequence(len - MAX_LOG_CHARS + 2000, len));
            }
            if (logScroll != null) {
                logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /**
     * 导出日志。
     *
     * <p>导出取的是**磁盘全量**（{@link AppLog#fileText()}，含前几天的滚动文件），
     * 而不是内存里那 500 条——用户导出的目的通常是发给别人排查，越全越好。
     * 读盘可能几十毫秒到几秒，放后台线程并给一个进度框。</p>
     */
    private void exportLogs() {
        final AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle("正在整理日志")
                .setMessage("读取磁盘日志…")
                .setCancelable(false)
                .create();
        progress.show();
        new Thread(() -> {
            String memory = AppLog.dumpText();
            String disk = AppLog.fileText();
            // 磁盘尾部与内存有重叠，优先用磁盘版本；两者都为空才提示
            final String dump = disk.isEmpty() ? memory : disk;
            runOnUiThread(() -> {
                progress.dismiss();
                if (TextUtils.isEmpty(dump)) {
                    Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show();
                    return;
                }
                AppLog.event("Settings", "导出日志", "共 " + dump.length() + " 字符");
                Intent intent = new Intent(Intent.ACTION_SEND);
                intent.setType("text/plain");
                intent.putExtra(Intent.EXTRA_SUBJECT, "V免签运行日志");
                intent.putExtra(Intent.EXTRA_TEXT, dump);
                try {
                    startActivity(Intent.createChooser(intent, "导出日志"));
                } catch (Exception e) {
                    Toast.makeText(this, "没有可用的分享应用", Toast.LENGTH_SHORT).show();
                }
            });
        }, "vmq-log-export").start();
    }

    private void showLevelPicker() {
        final AppLog.Level[] levels = AppLog.Level.values();
        String[] labels = {"调试（最详细，含每次检测）", "常规（默认）", "仅警告", "仅错误"};
        int checked = 0;
        for (int i = 0; i < levels.length; i++) {
            if (levels[i] == AppLog.getLevel()) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("日志级别")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AppLog.setLevel(this, levels[which]);
                    updateLevelButton();
                    AppLog.event("Settings", "切换日志级别", levels[which].name());
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void updateLevelButton() {
        if (btnLogLevel != null) {
            btnLogLevel.setText(AppLog.getLevel().name());
        }
    }

    // ---------------- 运行监控 ----------------

    private void refreshAliveStatus() {
        if (tvAliveStatus == null) {
            return;
        }
        String wechat = AliveMonitor.summaryOf(this, AliveMonitor.SLOT_WECHAT, "微信");
        String alipay = AliveMonitor.summaryOf(this, AliveMonitor.SLOT_ALIPAY, "支付宝");
        tvAliveStatus.setText(wechat + "\n" + alipay);
        if (btnAliveInterval != null) {
            btnAliveInterval.setText("间隔 " + AliveMonitor.getIntervalMinutes(this) + " 分钟");
        }
    }

    private void showIntervalPicker() {
        final int[] options = {1, 5, 15, 30};
        String[] labels = {"1 分钟", "5 分钟", "15 分钟", "30 分钟"};
        int current = AliveMonitor.getIntervalMinutes(this);
        int checked = 1;
        for (int i = 0; i < options.length; i++) {
            if (options[i] == current) {
                checked = i;
            }
        }
        new MaterialAlertDialogBuilder(this)
                .setTitle("存活检查间隔")
                .setSingleChoiceItems(labels, checked, (dialog, which) -> {
                    AliveMonitor.setIntervalMinutes(this, options[which]);
                    refreshAliveStatus();
                    AppLog.event("Monitor", "调整检查间隔", options[which] + " 分钟，下次循环生效");
                    dialog.dismiss();
                })
                .setNegativeButton("取消", null)
                .show();
    }

    private void runAliveCheckNow() {
        final AlertDialog progress = new MaterialAlertDialogBuilder(this)
                .setTitle("正在检测微信 / 支付宝")
                .setMessage("通过 Shizuku（精确）或使用情况统计（推断）判断存活…")
                .setCancelable(false)
                .create();
        progress.show();
        AppLog.event("Monitor", "手动检测存活", "用户触发");

        new Thread(() -> {
            AliveMonitor.Status alipay =
                    AliveMonitor.checkAndRecord(this, ShizukuKeepAlive.PKG_ALIPAY);
            AliveMonitor.Status wechat =
                    AliveMonitor.checkAndRecord(this, ShizukuKeepAlive.PKG_WECHAT);
            runOnUiThread(() -> {
                progress.dismiss();
                refreshAliveStatus();
                new MaterialAlertDialogBuilder(this)
                        .setTitle("存活检测结果")
                        .setMessage("微信：" + wechat.summary()
                                + "\n　检测方式：" + methodLabel(wechat.method)
                                + "\n\n支付宝：" + alipay.summary()
                                + "\n　检测方式：" + methodLabel(alipay.method))
                        .setPositiveButton("知道了", null)
                        .show();
            });
        }, "vmq-alive-check").start();
    }

    private String methodLabel(String method) {
        if ("pidof".equals(method)) {
            return "Shizuku pidof（精确）";
        }
        if ("usage".equals(method)) {
            return "使用情况统计（仅推断活跃，不代表进程存活）";
        }
        return "无可用手段";
    }

    private void openUsageAccessSettings() {
        AppLog.event("Monitor", "打开使用情况访问设置", "引导用户授予 PACKAGE_USAGE_STATS");
        try {
            Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
            intent.setData(Uri.parse("package:" + getPackageName()));
            startActivity(intent);
        } catch (Exception e) {
            try {
                startActivity(new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS));
            } catch (Exception e2) {
                Toast.makeText(this,
                        "无法打开系统设置，请手动前往：设置 → 应用 → 特殊权限 → 使用情况访问",
                        Toast.LENGTH_LONG).show();
            }
        }
    }
}
