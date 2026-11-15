# V免签 v3.3.8 发布说明

> 发布日期：2026-11-16
> 发布 tag：V3.3.8
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.3.8
> 上一版审计报告：[FUNCTION_AUDIT.md](FUNCTION_AUDIT.md)

上一版（v3.3.7）的全功能审计留了 6 项风险项 R1~R6，本版按用户决策逐项处理：
**R1 跳过、R2 加密、R3 收权、R4 加实验开关、R5 不动依赖、R6 不动代码**。

另外顺手修掉三个缺陷：两个**必崩**的空指针，一个**静默丢配置**。这三处都不在审计清单里，
是这轮改权限时逐行读代码发现的。

---

## 🔴 修复 1：授权相机后点「授权」必崩（`startQrCode(null)`）

```java
// onRequestPermissionsResult 里
case Constant.REQ_PERM_CAMERA:
    if (granted) { startQrCode(null); }      // ← 传的是 null

// 方法第一行
public void startQrCode(View v) {
    v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);   // ← NPE
```

权限回调里用 `startQrCode(null)` 表单式重入，而方法第一行无条件解引用 `v`。
**结果：用户在主页点「扫码」→ 弹权限框 → 点允许 → 应用直接闪退**，扫码功能等于不可用。

修复：`if (v != null)` 保护。触觉反馈本来就是「用户点了按钮」才需要的效果，程序化重入不需要它。

---

## 🔴 修复 2：相册选图解码失败必崩（`RGBLuminanceSource(null)`）

```java
scanBitmap = BitmapUtil.decodeUri(this, uri, 500, 500);
RGBLuminanceSource source = new RGBLuminanceSource(scanBitmap);   // scanBitmap 可能是 null
```

`BitmapUtil.decodeUri` 在三种情况下返回 `null`，全部会被内部的 `catch (Throwable)` 吞掉后返回：
URI 已被回收（相册进程被系统杀掉，单次读权限失效）、选中的不是图片、图片过大 OOM。

而 `new RGBLuminanceSource(null)` 第一行就是 `bitmap.getWidth()` —— **NPE**。
表现为「选完图就闪退」，而不是提示识别失败。

修复：解码结果为 `null` 时直接返回 `null`，让上层弹「识别失败」的 Toast。

---

## 🟠 修复 3：主页扫码配置根本没存下来，重启就丢

主页扫码验证成功后的回调里：

```java
// 将扫描出的信息更新到输入框（自动触发实时保存）   ← 这句注释是假的
if (edtHost != null) edtHost.setText(tmp[0]);
if (edtKey != null) edtKey.setText(tmp[1]);
host = tmp[0];
key = tmp[1];
scheme = qrScheme;
getSharedPreferences("vone", MODE_PRIVATE)
        .edit().putString("scheme", scheme).apply();   // ← 只写了 scheme
```

它依赖「往 EditText 里 `setText` 会自动保存」，但：

- `MainActivity` 里**没有任何 `TextWatcher`**；
- 名字很像那么回事的 `saveConfigRealtime()` **全类无调用点**，是彻底的死代码；
- 也没有 `onPause` / `onStop` 兜底保存。

**结果：主页面扫码 → 弹「配置成功」→ `host` 与 `key` 只活在内存和输入框里。**
重启应用后主页回到「未配置」，后台心跳线程读到的 host 也是空串，一路发不出去。
用户看到的「成功」和实际落盘完全是两件事。

修复：抽出 `persistHostKey()`，把 `host` / `key`（加密后）/ `scheme` 一次写全，
两个扫码结果分支（新版 `qrCodeLauncher` 回调 + 旧版 `onActivityResult`）都调用它。

---

## 变更 1（R2）：通讯密钥改为加密保存

### 之前的问题

密钥以**明文**写在 `/data/data/com.vone.qrcode/shared_prefs/vone.xml`，而清单里 `allowBackup="true"`。
拿到备份文件、或做一次能读应用私有目录的提权，密钥就整串泄露 ——
**拿到密钥就能伪造收款推送，等于替别人记账。**

### 现在的做法

新增 `util/SecurePrefs.java`：

- 密钥用 **AndroidKeyStore 里的 AES-256 密钥**加密，密钥材料不出 TEE/StrongBox，应用自己都读不到原始字节；
- 每条密文用**随机 IV**（GCM 的 `setRandomizedEncryptionRequired(true)`），存成 `v1:Base64(iv||ciphertext)`；
- Keystore 密钥**不会**被 `allowBackup` 打包带走，所以密文备份到别的设备上解不开 —— 这是特性不是缺陷。

### 兼容性：不需要清数据

读到的值**没有 `v1:` 前缀就当作明文直接用**，同时顺手用密文覆盖回写。
用户升级后第一次打开应用即完成迁移，全程无感，不需要重新扫码。

### 覆盖全部 6 个读写点

| 位置 | 用途 |
|---|---|
| `MainActivity:226` | 主页仪表盘读配置 |
| `MainActivity:325` | 主页扫码后写配置 |
| `SettingsActivity:480` | 设置页回填 |
| `SettingsActivity:527` | 设置页保存 |
| `NeNotificationService2:251` | 心跳线程每轮读密钥签名 |
| `NeNotificationService2:581` | 收款推送读密钥签名 |

> 审计清单里写的是「5 个读写点」，实际是 **4 读 2 写 = 6 处**。漏改任何一处，
> 那一处就会把密文当密钥去签名，服务端只会一直返回签名错误。

### 失败时的取舍

- **加密失败** → 退回明文保存。即「少一层保护」，但配置仍可用，用户不会突然收不到推送。
- **解密失败** → 返回空串。Keystore 密钥被系统失效（OTA / 恢复出厂）后密文不可恢复，
  界面显示「未配置」引导重新扫码，**绝不把密文当密钥去签名** —— 那样只会一直在服务端撞签名错误，更难排查。

---

## 变更 2（R3）：关闭「所有文件访问」权限

### 删掉的四个权限

```diff
- <uses-permission android:name="android.permission.READ_EXTERNAL_STORAGE"  android:maxSdkVersion="32" />
- <uses-permission android:name="android.permission.WRITE_EXTERNAL_STORAGE" android:maxSdkVersion="32" />
- <uses-permission android:name="android.permission.READ_MEDIA_IMAGES" />
- <uses-permission android:name="android.permission.MANAGE_EXTERNAL_STORAGE" tools:ignore="ScopedStorage" />
```

`MANAGE_EXTERNAL_STORAGE`（"所有文件访问"）此前是**声明了却完全用不上**：
它只是为了让「从相册选二维码图片」能跑通，而那条路径根本不需要它。

### 为什么不需要权限也能扫码

这条链路本来就是权限无关的，只是代码里一直没人确认：

```java
// CaptureActivity:122 —— 选图
Intent innerIntent = new Intent(Intent.ACTION_GET_CONTENT);
innerIntent.setType("image/*");

// CaptureActivity:186 —— 解码
scanBitmap = BitmapUtil.decodeUri(this, uri, 500, 500);
//   → ContentResolver.openInputStream(uri)   （只吃系统授予的单次 URI 读权限）
//   → BitmapFactory.decodeStream(...)
```

**全程不接触真实文件路径**，所以任何 Android 版本都不需要存储权限。
反过来说，旧代码里那个 `UriUtil.getRealPathFromUri`（`DocumentsContract.getDocumentId` +
`MediaStore.Images.Media.DATA` 查询）在 `ACTION_GET_CONTENT` 返回的 `content://` URI 上
会直接抛 `IllegalArgumentException` —— 这才是它当年被标 `@Deprecated` 并换成 `BitmapUtil` 的原因。

本轮核查结果：**`UriUtil` 已经没有任何外部调用点**，于是整类删除，杜绝以后有人再捡起来用。

### 权限清理清单

| 位置 | 处理 |
|---|---|
| `AndroidManifest.xml:8-14` | 删除 4 个存储权限 |
| `MainActivity:602` | 删除扫码前的存储权限硬门控（**若不删，删权限后启动流程会 `return` 掉后续检查**） |
| `MainActivity:checkStoragePermissions()` | 整方法删除 |
| `MainActivity:showManageStoragePermissionDialog()` | 整方法删除 |
| `MainActivity:hasStoragePermission()` | 整方法删除 |
| `MainActivity:manageStoragePermissionLauncher` | 字段与注册一并删除 |
| `MainActivity:onActivityResult` | 删除 `REQ_PERM_MANAGE_EXTERNAL_STORAGE` 分支 |
| `MainActivity:onRequestPermissionsResult` | 删除 `REQ_PERM_EXTERNAL_STORAGE` 分支 |
| `Constant` | 删除 11004 / 11005 两个 requestCode，**编号留空不复用** |
| `util/UriUtil.java` | 整文件删除（死代码 + `MediaStore` 依赖） |

### 存储访问现状（确认过全部落点）

唯一的外部存储写入是 `getExternalFilesDir()`（**应用私有目录**，`/sdcard/Android/data/<pkg>/`）：
`Utils` 的日志目录、`SplashScreenLogger` / `SplashScreenPerformanceOptimizer` 的启动日志。
全仓库没有任何 `/sdcard`、`/storage/emulated` 直写，也没有 `MediaStore` 写入。

**结论：移除这四个权限后，扫码解析、日志落盘、配置读写全部不受影响。**

---

## 变更 3（R4）：定时重启进程（实验性，默认关闭）

### 要绕过什么

Android 15 起 `dataSync` 类型的前台服务有**累计 6 小时/天**上限，超限后系统拒绝再次启动
（`ForegroundServiceStartNotAllowedException`）。本应用的「前台重试窗口」正是 `dataSync` 类型，
一旦当天额度用尽，心跳失败后的前台重试链路整体失效，只能等次日重置。

### 必须先说清楚：这个开关多半没用

**配额记在系统侧的 uid 维度，跨进程通常保留**，所以「重启进程」并不能可靠地重置额度。
部分 ROM 按新进程计时、重启确实有效；部分机型完全无效。

因此这个开关的定位是 —— **留给用户在特定机型上自行验证的实验选项**，不是保证有效的解法。
界面上明确写着「实验性」「多数机型无效」。

### 三重前置校验（缺一不可）

1. **通知监听授权必须有效**（读 `Settings.Secure.enabled_notification_listeners` 的 ground truth）。
   没有它，进程死了就没人把服务绑回来，应用会彻底停摆到用户下次手动打开 —— **比不重启糟糕得多**。
2. **监听与上报不能都关**（`RunSwitch.isAnyActive`）。都关时进程重启没有任何意义。
3. **静默窗口 90 秒**：最近有过收款推送或心跳收发就跳过，等下一轮（约 5 分钟）再试。
   **杀进程最直接的代价是可能丢一条收款通知** —— 通知监听事件不会被系统重投递，
   重启窗口内到达的推送就永久丢了。这一条就是为它加的。

### 一个细节：必须 `commit()` 不能 `apply()`

重启时间戳要在进程被杀死**之前**真正落盘。`apply()` 是异步写，进程一死就丢了 ——
重启后读到的仍是旧时间戳，立刻又满足「该重启了」，形成**重启风暴**。
所以这里用同步的 `commit()`，落盘失败就放弃本次重启。

### 默认值

默认**关闭**；间隔可选 30 / 60 / 120 / **180** / 360 / 720 分钟（默认 3 小时，下限 30 分钟防呆）。
开启、改间隔都会把计时起点重置为当前时刻，避免「一开就重启」。
执行位置放在存活检查**之后**，不会在 `pidof` / `am start` 中途把进程杀掉。

---

## 关于 R5 / R6（明确不改）

**R5 · 密钥明文存 prefs → 不动依赖。**
没有引入 `androidx.security:security-crypto`。原因是它会带进一整套 AndroidX 传递依赖，
而且 `EncryptedSharedPreferences` 会改写整个 prefs 文件格式，与 `vone.xml` 里已有的
几十个键（心跳时间戳、存活状态、开关）混用风险更大。
本版只在**值的层面**加解密，prefs 结构完全不变，零新增依赖（见「变更 1」）。

**R6 · `Splash*` 11 个类 2400 行占 22% → 不动代码。**
逐个核对过引用：11 个类**全部有活跃调用**（`App`、`MainActivity`、`LegacySplashScreenHelper`），
不是死代码。改造收益（省一点包体和启动耗时）远低于把启动画面改崩的风险。维持现状。

---

## 回归清单（v3.3.8）

### 必测（本次改动的直接影响面）

- [ ] **不授权任何存储权限**，从主页点「扫码」→ 相机预览正常、扫二维码能出结果
- [ ] **不授权任何存储权限**，扫码页点「相册」→ 选一张二维码图片 → 能识别出内容
- [ ] 选一张**非二维码的普通照片** → 提示「识别失败」，**不闪退**
- [ ] 相机权限第一次弹框点「允许」→ 直接进扫码页，**不闪退**
- [ ] 系统设置 → 应用信息 → 权限，**不应再出现「所有文件访问」「照片和视频」「文件」** 三项
- [ ] 升级安装（**不要清数据**）后：主页服务器地址与心跳状态正常，说明密钥迁移成功
- [ ] 升级后进入设置页，密钥输入框里是**明文密钥**（不是 `v1:...`）
- [ ] 重新扫码配置一次 → 收款推送正常
- [ ] **主页**扫码配置成功后**杀掉进程重开** → 主页仍显示服务器地址与心跳状态（验证修复 3）
- [ ] 用 MT 管理器 / adb 看 `shared_prefs/vone.xml`，`key` 的值应为 `v1:` 开头的密文
- [ ] 设置页开「定时重启进程（实验性）」→ 提示到期时间 → 手动把间隔改成 30 分钟观察

### 不宜回归（改了公共代码，需确认没连带影响）

- [ ] 断网收一笔款 → 仍走前台重试，恢复网络后推送成功
- [ ] 微信 / 支付宝存活监控仍能显示「运行中 / 未运行 / 无法检测」
- [ ] 关掉「通知监听」与「心跳上报」→ 保活循环休眠、无唤醒；重开立即恢复
- [ ] 运行日志能正常滚动、导出、清除
- [ ] 后台弹出页面 / 锁屏显示两项权限仍在（前台提醒页需要）

---

## 升级方式

覆盖安装即可，**不需要清除数据、不需要重新扫码**。
密钥会在升级后第一次打开应用时自动从明文迁移为密文。

> 唯一例外：如果设备做过 OTA 大版本升级或恢复出厂导致 Keystore 密钥失效，
> 设置页会显示「未配置」，此时重新扫一次二维码即可。

## 校验值

Release 页面的 `app-release.apk` 即本版安装包，签名与前序版本一致，可直接覆盖安装。
