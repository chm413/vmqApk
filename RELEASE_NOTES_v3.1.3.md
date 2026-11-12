# V免签 v3.1.3 发布说明

> 发布日期：2026-11-12
> 产物：`app-release.apk`（GitHub Actions 自动构建签名）
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.1.3

## 🐛 关键修复：启动必崩（v3.1.1 引入修复，本版包含）

### 现象
Android 12+ 设备启动后白盾牌启动页不消失，状态栏图标反复闪现（崩溃循环）。

### 根因（logcat 实锤）
```
FATAL EXCEPTION: main
Resources$NotFoundException: Drawable com.vone.qrcode:drawable/splash_screen_legacy
Caused by: XmlPullParserException: <bitmap> requires a valid 'src' attribute (Binary XML line #10)
```
`res/drawable/splash_screen_legacy.xml`（v31 主题 `windowBackground`）用
`<bitmap android:src="@drawable/ic_splash_screen">` 包了一个 **VectorDrawable**。
AOSP `BitmapDrawable` 只接受位图，任何走 v31 主题的设备在 `MainActivity.onCreate` 阶段必崩。

### 修复
layer-list 直接引用矢量 drawable，去掉 `<bitmap>` 包装：
```xml
<item android:drawable="@drawable/ic_splash_screen" android:gravity="center" />
```

## ✨ 新功能：HTTPS 服务端支持

- **协议持久化**：配置协议存入本地（`vone/scheme`），手动输入 `https://` 前缀生效；无前缀沿用已保存协议；老配置默认 http，行为不变
- **全链路贯通**：6 处 API 请求（心跳×4、推送×2）全部按配置协议发起
- **扫码兼容**：二维码格式 `https://host/key` 自动识别；裸 `host/key` 默认 http
- **自签证书**：OkHttp 客户端信任自签证书（个人部署场景，自签 https 严格优于明文 http）；正式 CA 证书同样支持

## ⚠️ 升级须知（破坏性）

1. **新签名，必须卸载重装**：v3.1.1 起改用全新自签 keystore（`vmqfox.keystore`），
   与旧版作者签名不同，无法覆盖安装。卸载旧版后：
   - 重新配置服务端地址与密钥
   - 重新授予通知使用权
   - Android 15+ 需重新执行 `appops set com.vone.qrcode RECEIVE_SENSITIVE_NOTIFICATIONS allow`
2. **keystore 必须妥善保管**：后续所有发版都用这把 keystore，丢失后无法覆盖安装升级。
   别名 `vmqfox`，密码与指纹见本地 `keystore-info.txt`（不入库）。

## 🚀 发布流程变化（CI）

- workflow（`.github/workflows/android-release.yml`）新增 **push `V*` tag 自动触发**
- 手动触发（workflow_dispatch）仍可用，需填 `tag_name`
- Secrets：`KEYSTORE_BASE64` / `KEYSTORE_ALIAS` / `KEYSTORE_PASSWORD`
- 产物固定 `app-release.apk`，自动发到对应 tag 的 Release

## 📋 版本信息

- **版本号**：3.1（versionCode 3）
- **最低 SDK**：23（Android 6.0）
- **目标 SDK**：34（Android 14）
- **已验证环境**：Android 16 / LineageOS 23（真机）

## ✅ 验证清单

- [x] CI 构建成功（run #5）并自动发布 Release
- [x] 启动不再崩溃（根因级修复）
- [ ] 真机卸载旧版 → 安装 V3.1.3 → 配置 → 心跳检测通过
- [ ] https 配置下心跳/推送全链路走 https（抓包或服务端日志确认）
- [ ] 支付宝/微信通知推送正常

---

**相关文档**：[RELEASE_NOTES_v3.0.md](RELEASE_NOTES_v3.0.md) · [RELEASE_CHECKLIST.md](RELEASE_CHECKLIST.md) · [README.md](README.md)
