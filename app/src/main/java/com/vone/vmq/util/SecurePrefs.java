package com.vone.vmq.util;

import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.text.TextUtils;
import android.util.Base64;
import android.util.Log;

import java.nio.charset.Charset;
import java.security.KeyStore;
import java.security.SecureRandom;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/**
 * 通讯密钥的加密存取。
 *
 * <h3>为什么需要它</h3>
 * 密钥原先以明文写在 {@code /data/data/<pkg>/shared_prefs/vone.xml}，
 * 而清单里 {@code allowBackup="true"}（含云端备份）——只要拿到备份文件或做过一次
 * 能读应用私有目录的提权，密钥就整串泄露，拿到它就能伪造收款推送。
 *
 * <h3>为什么不用 androidx.security:security-crypto</h3>
 * 那是现成方案，但会引入一个 <b>AndroidX 依赖+其传递依赖</b>。本项目长期贴在
 * minSdk 24 / targetSdk 34 上做小体积包，为一个字符串引入整套依赖不划算，
 * 而且 EncryptedSharedPreferences 会改写整个 prefs 文件格式，与 {@code vone.xml}
 * 里已有的几十个键（心跳时间戳、存活状态、开关）混用风险更大。
 * 这里只在<b>值的层面</b>加解密，prefs 结构完全不变。
 *
 * <h3>密钥从哪来</h3>
 * {@code AndroidKeyStore} 里的 AES-256 密钥，生成后<b>私钥材料不出 TEE/StrongBox</b>，
 * 应用自己都读不到原始字节，更不会被 {@code allowBackup} 打包带走。
 * 因此把密文备份到别的设备上也解不开 —— 这是特性，不是缺陷。
 *
 * <h3>存量数据兼容</h3>
 * 老版本的明文密钥没有 {@value #PREFIX} 前缀。{@link #readKey} 读到无前缀的值时
 * 直接当作明文使用（用户无感），并顺手用密文覆盖回写，完成一次性迁移。
 * 不需要任何升级迁移代码，也不需要清数据重配。
 *
 * <h3>失败时的取舍</h3>
 * 个别 ROM 的 Keystore 会损坏（OTA 后密钥失效是已知问题）。此时：
 * <ul>
 *   <li>加密失败 → 退回明文保存。即"没有加密保护"，但配置仍可用，用户不会突然收不到推送。</li>
 *   <li>解密失败 → 返回空串。密文已经解不开了，只能让界面显示"未配置"引导重新扫码，
 *       绝不把密文当成密钥去签名（那样服务端只会一直返回签名错误，更难排查）。</li>
 * </ul>
 */
public final class SecurePrefs {

    private static final String TAG = "SecurePrefs";

    /** 通讯密钥在 SharedPreferences 里的键名。 */
    public static final String KEY_COMM_KEY = "key";

    /** 密文前缀。存量明文没有这个前缀，据此区分，不需要额外的版本字段。 */
    private static final String PREFIX = "v1:";

    private static final String KEYSTORE = "AndroidKeyStore";
    private static final String KEY_ALIAS = "vmq_comm_key_v1";
    private static final String TRANSFORMATION = "AES/GCM/NoPadding";

    /** GCM 推荐 IV 长度，12 字节（96 bit）。 */
    private static final int IV_BYTES = 12;
    /** 认证标签长度，单位是 bit。 */
    private static final int TAG_BITS = 128;

    private static final Charset UTF8 = Charset.forName("UTF-8");

    private static final Object LOCK = new Object();

    /**
     * 缓存 SecretKey 句柄。
     *
     * <p>{@code KeyStore.getKey} 每次都要跨 Binder 进 keystore 守护进程，约 1~5ms。
     * 心跳线程每 30 秒读一次密钥、收款推送每分钟可能读几次，缓存后只剩一次
     * AES-GCM 运算（微秒级）。SecretKey 句柄本身不含密钥材料，缓存它没有额外风险。</p>
     */
    private static volatile SecretKey sKey;

    private SecurePrefs() {
    }

    // ---------------- 对外 ----------------

    /**
     * 读取通讯密钥（自动完成明文 → 密文的一次性迁移）。
     *
     * @return 明文密钥；未配置或密文已无法解开时返回空串
     */
    public static String readKey(SharedPreferences prefs) {
        if (prefs == null) {
            return "";
        }
        String raw = prefs.getString(KEY_COMM_KEY, "");
        if (TextUtils.isEmpty(raw)) {
            return "";
        }
        if (!raw.startsWith(PREFIX)) {
            // 存量明文：先用起来，同时升级成密文，下一轮读取就走解密分支
            String upgraded = encrypt(raw);
            if (upgraded.startsWith(PREFIX)) {
                prefs.edit().putString(KEY_COMM_KEY, upgraded).apply();
            }
            return raw;
        }
        return decrypt(raw);
    }

    /** 把明文密钥加密后写进 editor（不 apply，交给调用方统一提交）。 */
    public static SharedPreferences.Editor putKey(SharedPreferences.Editor editor, String plain) {
        if (editor == null) {
            return null;
        }
        return editor.putString(KEY_COMM_KEY, encrypt(plain));
    }

    // ---------------- 加解密 ----------------

    /**
     * 加密。返回 {@code v1:<base64(iv||ciphertext)>}；失败则原样返回明文。
     */
    public static String encrypt(String plain) {
        if (TextUtils.isEmpty(plain)) {
            return "";
        }
        try {
            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.ENCRYPT_MODE, loadKey());
            byte[] iv = cipher.getIV();
            if (iv == null || iv.length != IV_BYTES) {
                // 理论上 GCM 一定会回 IV；真遇到不回 IV 的实现就自己造一个并显式传入
                iv = new byte[IV_BYTES];
                new SecureRandom().nextBytes(iv);
                cipher.init(Cipher.ENCRYPT_MODE, loadKey(), new GCMParameterSpec(TAG_BITS, iv));
            }
            byte[] body = cipher.doFinal(plain.getBytes(UTF8));
            byte[] packed = new byte[iv.length + body.length];
            System.arraycopy(iv, 0, packed, 0, iv.length);
            System.arraycopy(body, 0, packed, iv.length, body.length);
            return PREFIX + Base64.encodeToString(packed, Base64.NO_WRAP);
        } catch (Throwable t) {
            // 降级为明文：宁可少一层保护，也不能让用户存不下配置
            Log.w(TAG, "密钥加密失败，本次以明文保存: " + t.getClass().getSimpleName(), t);
            return plain;
        }
    }

    /**
     * 解密。输入若是存量明文（无前缀）则原样返回。
     */
    public static String decrypt(String stored) {
        if (TextUtils.isEmpty(stored)) {
            return "";
        }
        if (!stored.startsWith(PREFIX)) {
            return stored;
        }
        try {
            byte[] packed = Base64.decode(stored.substring(PREFIX.length()), Base64.NO_WRAP);
            if (packed.length <= IV_BYTES) {
                throw new IllegalArgumentException("密文长度异常: " + packed.length);
            }
            byte[] iv = new byte[IV_BYTES];
            System.arraycopy(packed, 0, iv, 0, IV_BYTES);
            byte[] body = new byte[packed.length - IV_BYTES];
            System.arraycopy(packed, IV_BYTES, body, 0, body.length);

            Cipher cipher = Cipher.getInstance(TRANSFORMATION);
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), new GCMParameterSpec(TAG_BITS, iv));
            return new String(cipher.doFinal(body), UTF8);
        } catch (Throwable t) {
            // 密钥被系统失效（OTA / 恢复出厂后 Keystore 重置）时不可恢复，
            // 返回空串让界面显示"未配置"，引导用户重新扫码
            Log.e(TAG, "密钥解密失败，需重新扫码配置: " + t.getClass().getSimpleName(), t);
            return "";
        }
    }

    /**
     * 取（或首次生成）Keystore 里的 AES 密钥。
     *
     * <p>不设 {@code setUserAuthenticationRequired}：本应用要在息屏、锁屏状态下
     * 后台读取密钥做签名，一旦要求用户认证，后台链路会全部失败。</p>
     */
    private static SecretKey loadKey() throws Exception {
        SecretKey cached = sKey;
        if (cached != null) {
            return cached;
        }
        synchronized (LOCK) {
            if (sKey != null) {
                return sKey;
            }
            KeyStore ks = KeyStore.getInstance(KEYSTORE);
            ks.load(null);
            java.security.Key existing = ks.getKey(KEY_ALIAS, null);
            if (existing instanceof SecretKey) {
                sKey = (SecretKey) existing;
                return sKey;
            }
            SecretKey generated;
            try {
                generated = generateKey(256);
            } catch (Throwable t) {
                // 个别设备的 Keymaster 只支持 AES-128，256 位会抛 InvalidAlgorithmParameterException。
                // 失败时可能已留下半成品条目，先删掉再按 128 位重试。
                Log.w(TAG, "生成 AES-256 密钥失败，降级为 AES-128: "
                        + t.getClass().getSimpleName());
                try {
                    ks.deleteEntry(KEY_ALIAS);
                } catch (Throwable ignored) {
                    // 没有条目或删不掉都不影响下一步生成
                }
                generated = generateKey(128);
            }
            sKey = generated;
            Log.i(TAG, "已生成通讯密钥加密用密钥（AndroidKeyStore）");
            return sKey;
        }
    }

    private static SecretKey generateKey(int keySize) throws Exception {
        KeyGenerator gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, KEYSTORE);
        gen.init(new KeyGenParameterSpec.Builder(KEY_ALIAS,
                KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(keySize)
                .setRandomizedEncryptionRequired(true)
                .build());
        return gen.generateKey();
    }
}
