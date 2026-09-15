package com.helper.urlfeeder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * APK 自更新：从 GitHub Release 下载固定名字的资源(urlfeeder-latest.apk)，
 * 读取其 versionCode 与已安装版本比较，较新则提示安装（走本应用的 ApkProvider）。
 *
 * 依赖：① 仓库 Release 里存在 urlfeeder-latest.apk（CI 每次 push 更新 latest 标签的 Release）
 *      ② 下载到的 APK 必须与已安装版本同签名（本工程使用仓库内固定 keystore 保证）
 */
public class Updater {

    /** 固定下载地址：仓库 latest Release 的 ASCII 资源名（中文文件名不便 URL 引用，故 CI 另存一份） */
    public static final String APK_URL =
        "https://github.com/chenjin1494/ZhuoYueLauncherApp/releases/latest/download/urlfeeder-latest.apk";
    public static final String AUTHORITY = "com.helper.urlfeeder.apk";
    public static final String FILE_NAME = "urlfeeder-update.apk";

    public static int myVersionCode(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionCode; }
        catch (Throwable t) { return 0; }
    }
    public static String myVersionName(Activity a) {
        try { return a.getPackageManager().getPackageInfo(a.getPackageName(), 0).versionName; }
        catch (Throwable t) { return "?"; }
    }

    /** 下载到 out；返回 null 表示成功，否则返回错误说明 */
    static String httpGet(String url, File out) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "urlfeeder-updater");
            int code = c.getResponseCode();
            if (code != 200) {
                if (code == 404) return "HTTP 404：仓库还没有可下载的 Release（需先让 CI 构建一次）";
                return "HTTP " + code;
            }
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(out);
            byte[] b = new byte[16384];
            int n; long total = 0;
            while ((n = in.read(b)) > 0) { fo.write(b, 0, n); total += n; }
            try { fo.flush(); fo.close(); } catch (Exception e) {}
            try { in.close(); } catch (Exception e) {}
            if (total < 1024) return "下载内容过小(" + total + " 字节)";
            return null;
        } catch (Throwable t) {
            return "网络错误: " + t;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception e) {}
        }
    }

    /** 检查更新：后台下载 → 比对版本 → 主线程弹窗询问是否安装 */
    public static void checkAndPrompt(final Activity act) {
        Toast.makeText(act, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { public void run() {
            final File tmp = new File(act.getCacheDir(), FILE_NAME + ".tmp");
            final File dst = new File(act.getCacheDir(), FILE_NAME);
            String err = null;
            try { err = httpGet(APK_URL, tmp); } catch (Throwable t) { err = "网络错误: " + t; }
            if (err == null) {
                try {
                    PackageInfo pi = act.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
                    if (pi == null) {
                        err = "下载内容不是有效 APK（Release 资源可能未就绪）";
                    } else {
                        final int nv = pi.versionCode;
                        final String nvn = pi.versionName;
                        final int mv = myVersionCode(act);
                        final String mvn = myVersionName(act);
                        if (nv <= mv) {
                            err = "已是最新版本 v" + mvn;
                        } else {
                            if (dst.exists()) dst.delete();
                            if (!tmp.renameTo(dst)) { err = "缓存文件写入失败"; }
                            else {
                                final String nn = nvn;
                                act.runOnUiThread(new Runnable() { public void run() { promptInstall(act, dst, nn, mvn); } });
                                return;
                            }
                        }
                    }
                } catch (Throwable t) { err = "校验失败: " + t; }
            }
            try { tmp.delete(); } catch (Exception e) {}
            final String m = err == null ? "检查失败" : err;
            act.runOnUiThread(new Runnable() { public void run() {
                Toast.makeText(act, m, Toast.LENGTH_LONG).show();
            }});
        }}).start();
    }

    static void promptInstall(final Activity act, final File apk, final String nvn, final String mvn) {
        try {
            new AlertDialog.Builder(act)
                .setTitle("发现新版本 v" + nvn)
                .setMessage("当前版本 v" + mvn + "\n安装包已下载完成，是否立即安装？\n\n"
                          + "· 升级不会清空设置（同签名覆盖安装）\n"
                          + "· 若提示“未知来源”，请允许本应用安装应用")
                .setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { install(act, apk); }
                })
                .setNegativeButton("以后再说", null)
                .show();
        } catch (Throwable t) { Toast.makeText(act, "提示失败: " + t, Toast.LENGTH_LONG).show(); }
    }

    public static void install(Activity act, File apk) {
        try {
            if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(act, "请先允许「安装未知应用」，再回来重新点检查更新", Toast.LENGTH_LONG).show();
                try {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
                } catch (Exception e) {}
                return;
            }
            Uri u = Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
        } catch (Throwable t) {
            Toast.makeText(act, "安装启动失败: " + t, Toast.LENGTH_LONG).show();
        }
    }
}
