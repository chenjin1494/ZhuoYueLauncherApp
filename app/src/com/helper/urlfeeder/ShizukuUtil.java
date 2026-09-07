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

    static IShizukuService getService() throws RemoteException {
        IBinder b = rikka.shizuku.Shizuku.getBinder();
        if (b == null) throw new RemoteException("Shizuku 未运行");
        return IShizukuService.Stub.asInterface(b);
    }

    public static boolean running() {
        try { return rikka.shizuku.Shizuku.pingBinder(); }
        catch (Throwable t) { return false; }
    }

    public static int permission() {
        try { return rikka.shizuku.Shizuku.checkSelfPermission(); }
        catch (Throwable t) { return 0; }
    }

    /** 请求授权（需在 Activity 内调用；结果通过 Shizuku.addRequestPermissionResultListener 回调） */
    public static void requestPerm() {
        try { rikka.shizuku.Shizuku.requestPermission(REQ_SHIZUKU); }
        catch (Throwable t) { }
    }

    /** 执行命令并返回退出码+输出；cmdArgs 例如 {"pm","disable-user","--user","0","pkg"} */
    public static String run(String[] cmdArgs) {
        StringBuilder sb = new StringBuilder();
        IRemoteProcess p = null;
        try {
            IShizukuService svc = getService();
            // 3. newProcess(cmd, env, dir)
            p = svc.newProcess(cmdArgs, null, null);
            if (p == null) return "ERROR:null-process";
            // 读输出(合并 stdout/stderr 分两步, 简化先读 stdout)
            ParcelFileDescriptor out = p.getInputStream();
            ParcelFileDescriptor err = p.getErrorStream();
            if (out != null) {
                FileInputStream fis = new FileInputStream(out.getFileDescriptor());
                BufferedReader r = new BufferedReader(new InputStreamReader(fis));
                String line;
                while ((line = r.readLine()) != null) { sb.append(line).append("\n"); }
                try { fis.close(); } catch (Exception e) {}
            }
            int code = p.waitFor();
            if (err != null && sb.length() == 0) {
                FileInputStream fis = new FileInputStream(err.getFileDescriptor());
                BufferedReader r = new BufferedReader(new InputStreamReader(fis));
                String line;
                while ((line = r.readLine()) != null) { sb.append(line).append("\n"); }
                try { fis.close(); } catch (Exception e) {}
            }
            sb.insert(0, "[exit=" + code + "] ");
        } catch (Throwable t) {
            sb.append("EXCEPTION: ").append(t);
        }
        return sb.toString().trim();
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
}
