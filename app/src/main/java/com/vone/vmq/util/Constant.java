package com.vone.vmq.util;

/**
 * 常量
 */
public class Constant {
    // request参数
    public static final int REQ_QR_CODE = 11002; // // 打开扫描界面请求码
    public static final int REQ_PERM_CAMERA = 11003; // 打开摄像头
    // 11004 / 11005 曾用于「读写文件」「管理所有文件权限」，两项权限已移除（不再需要存储权限），
    // 编号留空不复用，避免与旧版本残留的 requestCode 撞车
    public static final int REQ_PERM_POST_NOTIFICATIONS = 11006; // 通知权限

    public static final String INTENT_EXTRA_KEY_QR_SCAN = "qr_scan_result";
}
