# V免签 v3.1.4 发布说明

> 发布日期：2026-11-12
> Release 地址：https://github.com/chm413/vmqApk/releases/tag/V3.1.4

## ✨ 新功能：可选的服务器证书校验

### 背景
v3.1.3 起 OkHttp 客户端无条件信任所有证书（为兼容个人部署的自签证书）。
本版把证书校验做成用户可选开关：

| 状态 | 行为 | 适用场景 |
|---|---|---|
| **关闭**（默认） | 信任自签证书（加密传输、不校验 CA） | 自签证书的个人部署，与 v3.1.3 行为一致 |
| **开启** | 系统 CA 严格校验，证书无效直接断开（SSLHandshakeException） | 正式 CA 证书（Let's Encrypt / 商业证书） |

### 使用方法
配置卡（服务端地址/通讯密钥下方）新增「**验证服务器证书（自签证书请保持关闭）**」开关，
切换实时保存、立即生效（客户端按开关选取，无需重启应用）。

### 实现
- 配置持久化：`vone/verify_ssl`（布尔，默认 false）
- `Utils.getOkHttpClient(Context)` 按开关返回对应客户端：
  - `getInsecureClient()`：trust-all + hostnameVerifier 全放行（缓存单例）
  - `getVerifiedClient()`：系统默认 TLS 校验（缓存单例）
- 两个客户端共用同一套超时/连接池/重试/日志拦截器（`baseClientBuilder()`）

## ⚠️ 升级说明

- 从 v3.1.x 覆盖安装即可（同一把 vmqfox.keystore 签名）
- 老配置 `verify_ssl` 缺省为关闭，行为与 v3.1.3 完全一致
- 自签证书用户**不要**开启此开关，否则心跳/推送会全部握手失败

---

**相关文档**：[RELEASE_NOTES_v3.1.3.md](RELEASE_NOTES_v3.1.3.md) · [README.md](README.md)
