package com.march7th.hkrpg.third;

import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.io.PrintStream;

/**
 * Log both to Android logger (so that logs are visible in "adb logcat") and standard output/error (so that they are visible in the terminal
 * directly).
 */
public final class Ln {

    private static final String TAG = "MaaMeow";
    private static final String PREFIX = "[MC] ";

    private static final PrintStream CONSOLE_OUT = new PrintStream(new FileOutputStream(FileDescriptor.out));
    private static final PrintStream CONSOLE_ERR = new PrintStream(new FileOutputStream(FileDescriptor.err));
    private static Level threshold = Level.DEBUG;

    /**
     * 额外的日志接收器 —— 把引擎内部日志转发到 App 的「日志」面板。
     *
     * ## 为什么需要它
     *
     * [Ln] 原来只写 Android logcat 和进程 stdout，这两者在手机上
     * 用户**根本看不到**：
     * - logcat 需要 adb 才能读；
     * - 特权进程的 stdout 落在 /data/local/tmp 下的调试文件里。
     *
     * 结果就是用户在 App 里只看到「开始任务:清体力」「任务出错:清体力」
     * 这种非常精简的框架日志，引擎内部的每一步（OCR 读了什么、
     * 点到了哪个坐标、为什么失败）全都看不到，出问题只能靠抓 logcat。
     *
     * 这里加一个回调，由引擎在初始化时注册，把每条日志同时送进
     * App 的会话日志。
     *
     * ⚠️ 回调必须**非常轻**（只入队、不阻塞），因为它在引擎的关键
     * 路径上被调用（每次取帧、每次 OCR 都会打日志）。
     */
    public interface Sink {
        void onLog(Level level, String message);
    }

    private static volatile Sink sink;

    public static void setSink(Sink s) {
        sink = s;
    }

    /** 转发给 Sink；任何异常都吞掉，绝不能让日志把业务逻辑带崩。 */
    private static void forward(Level level, String message) {
        Sink s = sink;
        if (s == null) return;
        try {
            s.onLog(level, message);
        } catch (Throwable ignored) {
            // 日志转发失败不能影响主流程
        }
    }

    private Ln() {
        // not instantiable
    }

    public static void disableSystemStreams() {
        PrintStream nullStream = new PrintStream(new NullOutputStream());
        System.setOut(nullStream);
        System.setErr(nullStream);
    }

    /**
     * Initialize the log level.
     * <p>
     * Must be called before starting any new thread.
     *
     * @param level the log level
     */
    public static void initLogLevel(Level level) {
        threshold = level;
    }

    public static boolean isEnabled(Level level) {
        return level.ordinal() >= threshold.ordinal();
    }

    public static void v(String message) {
        if (isEnabled(Level.VERBOSE)) {
            Log.v(TAG, message);
            CONSOLE_OUT.print(PREFIX + "VERBOSE: " + message + '\n');
            forward(Level.VERBOSE, message);
        }
    }

    public static void d(String message) {
        if (isEnabled(Level.DEBUG)) {
            Log.d(TAG, message);
            CONSOLE_OUT.print(PREFIX + "DEBUG: " + message + '\n');
            forward(Level.DEBUG, message);
        }
    }

    public static void i(String message) {
        if (isEnabled(Level.INFO)) {
            Log.i(TAG, message);
            CONSOLE_OUT.print(PREFIX + "INFO: " + message + '\n');
            forward(Level.INFO, message);
        }
    }

    public static void w(String message, Throwable throwable) {
        if (isEnabled(Level.WARN)) {
            Log.w(TAG, message, throwable);
            CONSOLE_ERR.print(PREFIX + "WARN: " + message + '\n');
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR);
            }
            forward(Level.WARN, message);
        }
    }

    public static void w(String message) {
        w(message, null);
    }

    public static void e(String message, Throwable throwable) {
        if (isEnabled(Level.ERROR)) {
            Log.e(TAG, message, throwable);
            CONSOLE_ERR.print(PREFIX + "ERROR: " + message + '\n');
            if (throwable != null) {
                throwable.printStackTrace(CONSOLE_ERR);
            }
            forward(Level.ERROR, message);
        }
    }

    public static void e(String message) {
        e(message, null);
    }

    public enum Level {
        VERBOSE, DEBUG, INFO, WARN, ERROR
    }

    static class NullOutputStream extends OutputStream {
        @Override
        public void write(byte[] b) {
            // ignore
        }

        @Override
        public void write(byte[] b, int off, int len) {
            // ignore
        }

        @Override
        public void write(int b) {
            // ignore
        }
    }
}
