package com.helper.urlfeeder;

import android.os.IBinder;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;

import java.io.BufferedReader;
import java.io.FileInputStream;
import java.io.InputStreamReader;

import moe.shizuku.server.IRemoteProcess;
import moe.shizuku.server.IShizukuService;

/**
 * 通过 Shizuku(shell 权限)执行命令的封装。
 * 需要先安装 Shizuku 并通过 adb 启动，且在 App 内完成一次授权。
 */
public class ShizukuUtil {
    public static final int REQ_SHIZUKU = 1001;
    private static volatile Boolean lastRunning;
    private static volatile Integer lastPermission;

    static IShizukuService getService() throws RemoteException {
        IBinder b = rikka.shizuku.Shizuku.getBinder();
        if (b == null) throw new RemoteException("Shizuku 未运行");
        return IShizukuService.Stub.asInterface(b);
    }

    public static boolean running() {
        boolean value = false;
        Throwable error = null;
        try { value = rikka.shizuku.Shizuku.pingBinder(); }
        catch (Throwable t) { error = t; }
        Boolean previous = lastRunning;
        if (previous == null || previous.booleanValue() != value || error != null) {
            lastRunning = Boolean.valueOf(value);
            OperationLog.event("SHIZUKU", "BINDER_STATE", error == null ? "OK" : "FAIL",
                    "running=" + value + (error == null ? "" : "\n" + OperationLog.stack(error)));
        }
        return value;
    }

    public static int permission() {
        int value = -1;
        Throwable error = null;
        try { value = rikka.shizuku.Shizuku.checkSelfPermission(); }
        catch (Throwable t) { error = t; }
        Integer previous = lastPermission;
        if (previous == null || previous.intValue() != value || error != null) {
            lastPermission = Integer.valueOf(value);
            OperationLog.event("SHIZUKU", "PERMISSION_STATE", error == null ? "OK" : "FAIL",
                    "permissionResult=" + value + (error == null ? "" : "\n" + OperationLog.stack(error)));
        }
        return value;
    }

    /** 请求授权（需在 Activity 内调用；结果通过 Shizuku.addRequestPermissionResultListener 回调） */
    public static void requestPerm() {
        OperationLog.Span span = OperationLog.begin("SHIZUKU", "REQUEST_PERMISSION", "requestCode=" + REQ_SHIZUKU);
        try {
            rikka.shizuku.Shizuku.requestPermission(REQ_SHIZUKU);
            OperationLog.result(span, "DISPATCHED", "request dispatched");
        } catch (Throwable t) { OperationLog.fail(span, t); }
    }

    /** 执行命令并返回完整退出码、stdout 和 stderr。 */
    public static String run(String[] cmdArgs) {
        String command = OperationLog.command(cmdArgs);
        OperationLog.Span span = OperationLog.begin("SHIZUKU", "RUN_COMMAND", "command=" + command);
        IRemoteProcess process = null;
        try {
            IShizukuService service = getService();
            process = service.newProcess(cmdArgs, null, null);
            if (process == null) {
                String result = "ERROR:null-process";
                OperationLog.fail(span, "command=" + command + "\n" + result);
                return result;
            }
            final StringBuilder stdout = new StringBuilder();
            final StringBuilder stderr = new StringBuilder();
            ParcelFileDescriptor stdoutFd = process.getInputStream();
            ParcelFileDescriptor stderrFd = process.getErrorStream();
            Thread outReader = streamReader(stdoutFd, stdout, "shizuku-stdout");
            Thread errReader = streamReader(stderrFd, stderr, "shizuku-stderr");
            outReader.start(); errReader.start();
            boolean interrupted = false;
            int code = -1;
            while (true) {
                if (Thread.interrupted()) {
                    interrupted = true;
                    try { process.destroy(); } catch (Throwable ignored) {}
                    try { if (stdoutFd != null) stdoutFd.close(); } catch (Exception ignored) {}
                    try { if (stderrFd != null) stderrFd.close(); } catch (Exception ignored) {}
                    break;
                }
                if (process.waitForTimeout(250L, java.util.concurrent.TimeUnit.MILLISECONDS.name())) {
                    code = process.exitValue();
                    break;
                }
            }
            interrupted = joinReaders(process, outReader, errReader, stdoutFd, stderrFd) || interrupted;
            if (interrupted) Thread.currentThread().interrupt();
            String result = "[exit=" + code + "]" + (interrupted ? "\n[interrupted=true]" : "") + "\n[stdout]\n" + stdout.toString()
                    + (stdout.length() > 0 && stdout.charAt(stdout.length() - 1) != '\n' ? "\n" : "")
                    + "[stderr]\n" + stderr.toString();
            String detail = "command=" + command + "\n" + result;
            if (interrupted) OperationLog.result(span, "INTERRUPTED", detail);
            else if (code == 0) OperationLog.ok(span, detail); else OperationLog.fail(span, detail);
            return result;
        } catch (Throwable error) {
            String result = "EXCEPTION: " + error;
            OperationLog.fail(span, "command=" + command + "\n" + OperationLog.stack(error));
            return result;
        } finally {
            if (process != null) try { process.destroy(); } catch (Throwable ignored) {}
        }
    }

    private static boolean joinReaders(IRemoteProcess process, Thread stdout, Thread stderr,
                                    ParcelFileDescriptor stdoutFd, ParcelFileDescriptor stderrFd) {
        boolean interrupted = false;
        while (stdout.isAlive() || stderr.isAlive()) {
            try {
                if (stdout.isAlive()) stdout.join();
                if (stderr.isAlive()) stderr.join();
            } catch (InterruptedException error) {
                interrupted = true;
                try { process.destroy(); } catch (Throwable ignored) {}
                try { if (stdoutFd != null) stdoutFd.close(); } catch (Exception ignored) {}
                try { if (stderrFd != null) stderrFd.close(); } catch (Exception ignored) {}
            }
        }
        if (interrupted) Thread.currentThread().interrupt();
        return interrupted;
    }

    private static Thread streamReader(final ParcelFileDescriptor descriptor, final StringBuilder target,
                                       String name) {
        return new Thread(new Runnable() { public void run() {
            if (descriptor == null) return;
            FileInputStream input = null;
            BufferedReader reader = null;
            try {
                input = new FileInputStream(descriptor.getFileDescriptor());
                reader = new BufferedReader(new InputStreamReader(input));
                String line;
                while ((line = reader.readLine()) != null) target.append(line).append('\n');
            } catch (Throwable error) {
                target.append("[读取输出失败: ").append(error).append("]\n");
            } finally {
                try { if (reader != null) reader.close(); else if (input != null) input.close(); }
                catch (Exception ignored) {}
                try { descriptor.close(); } catch (Exception ignored) {}
            }
        }}, name);
    }

    /** 便捷命令 */
    public static String pm(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "pm";
        System.arraycopy(args, 0, full, 1, args.length);
        return run(full);
    }
    public static String am(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "am";
        System.arraycopy(args, 0, full, 1, args.length);
        return run(full);
    }
    public static String cmd(String... args) {
        String[] full = new String[args.length + 1];
        full[0] = "cmd";
        System.arraycopy(args, 0, full, 1, args.length);
        return run(full);
    }
    /** 通过 sh -c 执行一段脚本(支持管道/变量), 例如 "settings put system screen_brightness 120" */
    public static String sh(String script) {
        return run(new String[]{"sh", "-c", script});
    }
    /** 是否可用(已运行且已授权) */
    public static boolean ready() {
        return running() && permission() == 0;
    }
    /** 按键事件 */
    public static String key(int code) {
        return run(new String[]{"input", "keyevent", String.valueOf(code)});
    }
    /** 读系统亮度, 失败返回 -1 */
    public static int brightnessGet() {
        try {
            String cur = sh("settings get system screen_brightness");
            if (cur == null || !cur.startsWith("[exit=0]")) return -1;
            int begin = cur.indexOf("[stdout]");
            int end = cur.indexOf("[stderr]");
            if (begin < 0) return -1;
            cur = cur.substring(begin + 8, end > begin ? end : cur.length()).trim();
            return Integer.parseInt(cur);
        } catch (Throwable t) { return -1; }
    }
    /** 调节系统亮度: 读当前值 → 加减 → 限制到 [5,255] → 写回。返回写入后的亮度, 失败 -1 */
    public static int brightnessStep(int delta) {
        try {
            int v = brightnessGet();
            if (v < 0) v = 128;
            int nv = v + delta;
            if (nv < 5) nv = 5;
            if (nv > 255) nv = 255;
            sh("settings put system screen_brightness " + nv);
            return nv;
        } catch (Throwable t) { return -1; }
    }
}
