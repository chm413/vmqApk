# V免签 发布检查清单（v3.1.3 起）

> v3.0 的历史检查清单已被本流程取代；v3.0 发布说明见 [RELEASE_NOTES_v3.0.md](RELEASE_NOTES_v3.0.md)。

## 🚀 发布流程（当前版本，CI 自动化）

### 1. 提交代码并推送
```bash
git push origin material-you-style
```

### 2. 打 tag 触发构建
```bash
git tag -a "V3.1.4" -m "V3.1.4: 变更摘要"
git push origin V3.1.4
```
- 推送 `V*` 开头的 tag 自动触发 GitHub Actions 构建 + 签名 + 发布 Release
- Release 的 `tag_name` 自动取 tag 名（workflow_dispatch 手动触发时填 `tag_name` 输入）
- 同一 tag 重发用 `overwrite_files: true` 直接替换旧包

### 3. 盯构建结果
- Actions 页面：https://github.com/chm413/vmqApk/actions
- API 轮询：`GET /repos/chm413/vmqApk/actions/runs?per_page=1`
- 失败排查：jobs steps 状态定位失败步骤（logs API 匿名 403，需登录网页看日志）

### 4. 验收
- [ ] Release 资产 `app-release.apk` 已挂到对应 tag
- [ ] APK 签名指纹 = `vmqfox.keystore` 的 SHA256（见本地 `keystore-info.txt`）
- [ ] 真机安装启动正常（Android 12+ 走 v31 主题）
- [ ] 心跳检测、到账推送全链路通过

## 🔑 签名与 Secrets

| 项 | 值 |
|---|---|
| keystore | `vmqfox.keystore`（PKCS12，RSA 2048，有效期至 2056） |
| 别名 | `vmqfox` |
| Secrets | `KEYSTORE_BASE64`（keystore 的 base64）/ `KEYSTORE_ALIAS` / `KEYSTORE_PASSWORD` |
| 密码/指纹 | 见本地 `D:\git\vmq\keystore-info.txt`，**不入库** |

⚠️ v3.1.1 起使用全新自签 keystore，与旧版作者签名不同：
- 老用户升级必须卸载重装（见 [RELEASE_NOTES_v3.1.3.md](RELEASE_NOTES_v3.1.3.md)）
- 后续发版必须用同一把 keystore，丢失后无法覆盖安装

## 📋 代码质量卡点（每次发版前）

- [x] `./gradlew assembleRelease` 编译通过（CI 即验证）
- [x] lambda/匿名类捕获的局部变量必须是 effectively final
- [x] 资源变更后在 Android 12+ 真机验证启动（windowBackground/splash 相关改动必须真机过一遍）
- [x] 网络协议相关改动回归心跳 + 推送两条链路

## 📊 版本信息（当前）

- **版本号**: 3.1（versionCode 3）
- **最低 SDK**: 23 (Android 6.0)
- **目标 SDK**: 34 (Android 14)
- **当前发布**: V3.2.3（2026-11-13，主页仪表盘 + 设置独立页 + 断联指数退避 + 保活设计；V3.2.0～V3.2.2 为 CI 排查期空构建。此前 V3.1.4 = 证书校验开关，V3.1.3 = 启动崩溃修复 + HTTPS）

## ⚠️ 已知注意事项

- Android 15+ 需要 adb 授予 `RECEIVE_SENSITIVE_NOTIFICATIONS`（见 README 开头）
- 部分 Android 12+ 设备的厂商 ROM 对通知监听有额外限制
- 服务端二维码若为裸 `host/key` 格式，扫码默认走 http；要走 https 需服务端生成 `https://host/key` 或用户手动输入前缀

---

**发布负责人**: 开发团队
**最近更新**: 2026-11-12（V3.1.3）
