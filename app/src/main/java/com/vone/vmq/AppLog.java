package com.vone.vmq;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.util.Log;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 统一操作日志。
 *
 * <p>此前日志分两套：{@link Log} 只进 logcat（用户看不到），另一套通过广播塞进设置页的
 * TextView（只在内存，退出即失，且无级别、无时间戳、无脱敏）。本类将它们合一：</p>
 *
 * <ul>
 *   <li><b>内存环形缓冲</b>：最近 {@value #MAX_MEMORY} 条，供设置页实时展示</li>
 *   <li><b>按天滚动文件</b>：{@code files/logs/vmq-yyyy-MM-dd.log}，保留 {@value #KEEP_DAYS} 天，
 *       单文件超过 {@value #MAX_FILE_BYTES} 字节后停止追加（防止异常刷屏打爆存储）</li>
 *   <li><b>异步落盘</b>：所有文件 IO 走单线程 executor，<b>主线程绝不碰磁盘</b></li>
 *   <li><b>广播</b>：{@link #ACTION_APPEND}，供 Activity 实时刷新</li>
 *   <li><b>脱敏</b>：调用方负责把敏感字段过一遍 {@link Desensitizer}；
 *       本类在 {@link #event} 里对 detail 做了兜底脱敏</li>
 * </ul>
 *
 * <p>本类不记录任何用户可见文案以外的数据，日志内容本身也不外传。</p>
 */
public final class AppLog {

    /** 日志追加广播，Extra: {@link #EXTRA_LINE} */
    public static final String ACTION_APPEND = "com.vone.vmq.LOG_APPEND";
    public static final String EXTRA_LINE = "log_line";
    public static final String EXTRA_LEVEL = "log_level";

    public static final String PREFS = "vone";
    public static final String PREF_LEVEL = "log_level";

    private static final int MAX_MEMORY = 500;
    private static final int KEEP_DAYS = 3;
    private static final long MAX_FILE_BYTES = 1024 * 1024L;
    /** 单条异常堆栈的最大字符数，避免一条崩溃把日志文件撑爆 */
    private static final int MAX_STACK_CHARS = 4000;
    /** 冷启动回填时最多读取的文件尾部字节数 */
    private static final int TAIL_BYTES = 64 * 1024;

    public enum Level {
        DEBUG("D", 0), INFO("I", 1), WARN("W", 2), ERROR("E", 3);

        final String shortName;
        final int rank;

        Level(String shortName, int rank) {
            this.shortName = shortName;
            this.rank = rank;
        }

        static Level parse(String name, Level fallback) {
            if (name == null) {
                return fallback;
            }
            for (Level l : values()) {
                // 同时兼容 "INFO" 与文件里的单字母 "I"
                if (l.name().equalsIgnoreCase(name) || l.shortName.equalsIgnoreCase(name)) {
                    return l;
                }
            }
            return fallback;
        }
    }

    /** 一条日志。line 为预格式化结果，避免 UI 重复格式化。 */
    public static final class Entry {
        public final long time;
        public final Level level;
        public final String tag;
        public final String msg;
        public final String line;

        Entry(long time, Level level, String tag, String msg, String line) {
            this.time = time;
            this.level = level;
            this.tag = tag;
            this.msg = msg;
            this.line = line;
        }

        /**
         * 从磁盘回读时用：文件里只有格式化好的一行，反解出级别与 tag。
         * 反解失败不影响展示，`line` 原样保留即可（UI 只渲染 line）。
         */
        static Entry fromLine(String raw) {
            Level level = Level.INFO;
            String tag = "disk";
            // 形如 "11-15 12:00:00.123 I/Tag: msg"
            int firstSpace = raw.indexOf(' ');
            int secondSpace = firstSpace < 0 ? -1 : raw.indexOf(' ', firstSpace + 1);
            if (firstSpace > 0 && secondSpace > firstSpace + 1) {
                String seg = raw.substring(firstSpace + 1, secondSpace);
                int slash = seg.indexOf('/');
                if (slash > 0) {
                    level = Level.parse(seg.substring(0, slash), Level.INFO);
                    tag = seg.substring(slash + 1);
                }
            }
            return new Entry(0L, level, tag, raw, raw);
        }
    }

    private static final Object LOCK = new Object();
    private static final LinkedList<Entry> MEMORY = new LinkedList<>();
    private static final SimpleDateFormat TIME_FMT =
            new SimpleDateFormat("MM-dd HH:mm:ss.SSS", Locale.US);
    private static final SimpleDateFormat DAY_FMT =
            new SimpleDateFormat("yyyy-MM-dd", Locale.US);
    private static final ExecutorService WRITER = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "vmq-applog");
        t.setDaemon(true);
        return t;
    });

    private static Context appContext;
    private static volatile Level minLevel = Level.DEBUG;

    private AppLog() {
    }

    public static void init(Context context) {
        if (context == null) {
            return;
        }
        appContext = context.getApplicationContext();
        minLevel = readLevel(appContext);
        // 内存缓冲是空的，必须先从今天的文件回填，否则重启后设置页只剩"进程启动"这一行，
        // 用户口中的"日志没了"就是这么来的。只读尾部 64KB，避免拖慢冷启动。
        loadTailFromToday(appContext);
        WRITER.execute(() -> purgeOldFiles(appContext));
    }

    public static void setLevel(Context context, Level level) {
        if (level == null) {
            return;
        }
        minLevel = level;
        if (context != null) {
            SharedPreferences.Editor editor =
                    context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit();
            editor.putString(PREF_LEVEL, level.name());
            editor.apply();
        }
    }

    public static Level getLevel() {
        return minLevel;
    }

    private static Level readLevel(Context context) {
        try {
            SharedPreferences prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            return Level.parse(prefs.getString(PREF_LEVEL, null), Level.INFO);
        } catch (Throwable t) {
            return Level.INFO;
        }
    }

    public static void d(String tag, String msg) {
        log(Level.DEBUG, tag, msg);
    }

    public static void i(String tag, String msg) {
        log(Level.INFO, tag, msg);
    }

    public static void w(String tag, String msg) {
        log(Level.WARN, tag, msg);
    }

    public static void w(String tag, String msg, Throwable t) {
        log(Level.WARN, tag, appendThrowable(msg, t));
    }

    public static void e(String tag, String msg) {
        log(Level.ERROR, tag, msg);
    }

    public static void e(String tag, String msg, Throwable t) {
        log(Level.ERROR, tag, appendThrowable(msg, t));
    }

    /**
     * 把异常压成「类名: message + 堆栈」附在日志正文后面。
     * 崩溃没有堆栈等于没记，所以这里必带 {@link Log#getStackTraceString}，
     * 并截断到 {@link #MAX_STACK_CHARS}，避免一次 OOM 级堆栈把日志文件撑爆。
     */
    private static String appendThrowable(String msg, Throwable t) {
        String head = msg == null ? "" : msg;
        if (t == null) {
            return head;
        }
        String stack = Log.getStackTraceString(t);
        if (stack.length() > MAX_STACK_CHARS) {
            stack = stack.substring(0, MAX_STACK_CHARS) + "\n…(已截断)";
        }
        return head + " | " + t.getClass().getSimpleName()
                + (t.getMessage() == null ? "" : (": " + t.getMessage())) + "\n" + stack;
    }

    /**
     * 结构化操作日志。用于"谁在什么模块做了什么"这类审计性事件，
     * detail 会做一次兜底脱敏，避免调用方漏处理。
     */
    public static void event(String module, String action, String detail) {
        String text = detail == null ? "" : Desensitizer.any(detail);
        log(Level.INFO, module, action + (text.isEmpty() ? "" : " | " + text));
    }

    /**
     * 日志落地。
     *
     * <p><b>logcat 始终完整输出，级别只过滤"应用内日志"（内存 / 文件 / 广播）。</b>
     * 原因是大多数人调这种机型问题时还是接 adb 看 logcat，
     * 如果用户把应用内级别设成 INFO 就把 DEBUG 从 logcat 也抹掉，
     * 等于把现场唯一的原始通道也关了。</p>
     */
    private static void log(Level level, String tag, String msg) {
        String safeTag = tag == null ? "App" : tag;
        String safeMsg = msg == null ? "" : msg;

        // 1) logcat：无视级别，永远写
        switch (level) {
            case ERROR:
                Log.e(safeTag, safeMsg);
                break;
            case WARN:
                Log.w(safeTag, safeMsg);
                break;
            case DEBUG:
                Log.d(safeTag, safeMsg);
                break;
            case INFO:
            default:
                Log.i(safeTag, safeMsg);
                break;
        }

        // 2) 应用内：受级别控制
        if (level.rank < minLevel.rank) {
            return;
        }
        long now = System.currentTimeMillis();
        String line;
        synchronized (TIME_FMT) {
            line = TIME_FMT.format(new Date(now)) + " " + level.shortName + "/" + safeTag
                    + ": " + safeMsg;
        }
        Entry entry = new Entry(now, level, safeTag, safeMsg, line);

        synchronized (LOCK) {
            MEMORY.addLast(entry);
            while (MEMORY.size() > MAX_MEMORY) {
                MEMORY.removeFirst();
            }
        }

        broadcast(entry);
        WRITER.execute(() -> appendToFile(entry));
    }

    private static void broadcast(Entry entry) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            Intent intent = new Intent(ACTION_APPEND);
            intent.setPackage(ctx.getPackageName());
            intent.putExtra(EXTRA_LINE, entry.line);
            intent.putExtra(EXTRA_LEVEL, entry.level.name());
            ctx.sendBroadcast(intent);
        } catch (Throwable ignored) {
        }
    }

    // ---------------- 文件持久化 ----------------

    private static File logDir(Context context) {
        File dir = new File(context.getFilesDir(), "logs");
        if (!dir.exists() && !dir.mkdirs()) {
            return null;
        }
        return dir;
    }

    private static File todayFile(Context context) {        File dir = logDir(context);
        if (dir == null) {
            return null;
        }
        String day;
        synchronized (DAY_FMT) {
            day = DAY_FMT.format(new Date());
        }
        return new File(dir, "vmq-" + day + ".log");
    }

    /**
     * 从今天的日志文件尾部回填内存缓冲。
     *
     * <p>只读末尾 {@link #TAIL_BYTES} 字节：这个方法的调用点在
     * {@code Application.onCreate} 的最前面，不能因为读日志拖慢冷启动。
     * 64KB 大约覆盖上千行，足够"刚重启想看上一条记录"这种最常见的诉求。</p>
     */
    private static void loadTailFromToday(Context context) {
        try {
            File file = todayFile(context);
            if (file == null || !file.isFile() || file.length() == 0L) {
                return;
            }
            long length = file.length();
            long start = Math.max(0L, length - TAIL_BYTES);
            byte[] buf = new byte[(int) (length - start)];
            try (RandomAccessFile raf = new RandomAccessFile(file, "r")) {
                raf.seek(start);
                raf.readFully(buf);
            }
            String text = new String(buf, "UTF-8");
            String[] lines = text.split("\n");
            // 从文件中段开始读时，第一行一定是被切断的半行，直接丢弃
            int begin = start > 0L ? 1 : 0;
            List<Entry> loaded = new ArrayList<>();
            for (int i = begin; i < lines.length; i++) {
                if (!lines[i].isEmpty()) {
                    loaded.add(Entry.fromLine(lines[i]));
                }
            }
            if (loaded.isEmpty()) {
                return;
            }
            int from = Math.max(0, loaded.size() - MAX_MEMORY);
            synchronized (LOCK) {
                // 插到最前面：这些行都比当前内存里的旧
                MEMORY.addAll(0, loaded.subList(from, loaded.size()));
                while (MEMORY.size() > MAX_MEMORY) {
                    MEMORY.removeFirst();
                }
            }
        } catch (Throwable ignored) {
            // 回填失败不影响正常记录，只是少看到历史
        }
    }

    /** 全部磁盘日志（按文件名升序即日期升序）。导出用，可能较大，勿在主线程调用。 */
    public static String fileText() {
        Context ctx = appContext;
        if (ctx == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        try {
            File dir = logDir(ctx);
            if (dir == null) {
                return "";
            }
            File[] files = dir.listFiles();
            if (files == null) {
                return "";
            }
            Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
            for (File f : files) {
                if (!f.isFile() || !f.getName().endsWith(".log")) {
                    continue;
                }
                sb.append("===== ").append(f.getName()).append(" =====\n");
                try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                        new FileInputStream(f), "UTF-8"))) {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        sb.append(line).append('\n');
                    }
                }
            }
        } catch (Throwable ignored) {
        }
        return sb.toString();
    }

    private static void appendToFile(Entry entry) {
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        try {
            File file = todayFile(ctx);
            if (file == null) {
                return;
            }
            if (file.length() > MAX_FILE_BYTES) {
                return;
            }
            try (Writer writer = new OutputStreamWriter(
                    new FileOutputStream(file, true), "UTF-8")) {
                writer.write(entry.line);
                writer.write('\n');
            }
        } catch (IOException | RuntimeException ignored) {
        }
    }

    private static void purgeOldFiles(Context context) {
        File dir = logDir(context);
        if (dir == null) {
            return;
        }
        File[] files = dir.listFiles();
        if (files == null) {
            return;
        }
        long cutoff = System.currentTimeMillis() - KEEP_DAYS * 24L * 60L * 60L * 1000L;
        for (File f : files) {
            if (f.isFile() && f.lastModified() < cutoff && !f.delete()) {
                Log.w("AppLog", "旧日志删除失败: " + f.getName());
            }
        }
    }

    // ---------------- 供 UI 使用 ----------------

    /** 内存中的日志快照（按时间正序）。 */
    public static List<Entry> snapshot() {
        synchronized (LOCK) {
            return new ArrayList<>(MEMORY);
        }
    }

    /** 导出为纯文本，供分享/复制。 */
    public static String dumpText() {
        StringBuilder sb = new StringBuilder();
        for (Entry e : snapshot()) {
            sb.append(e.line).append('\n');
        }
        return sb.toString();
    }

    /** 清空内存与磁盘日志。清空动作本身会被记录（由调用方写入）。 */
    public static void clear() {
        synchronized (LOCK) {
            MEMORY.clear();
        }
        Context ctx = appContext;
        if (ctx == null) {
            return;
        }
        WRITER.execute(() -> {
            try {
                File dir = logDir(ctx);
                if (dir == null) {
                    return;
                }
                File[] files = dir.listFiles();
                if (files == null) {
                    return;
                }
                for (File f : files) {
                    if (f.isFile()) {
                        //noinspection ResultOfMethodCallIgnored
                        f.delete();
                    }
                }
            } catch (RuntimeException ignored) {
            }
        });
    }

    /** 日志文件所在目录，供设置页展示路径。 */
    public static String logDirPath() {
        Context ctx = appContext;
        if (ctx == null) {
            return "";
        }
        File dir = logDir(ctx);
        return dir == null ? "" : dir.getAbsolutePath();
    }
}
