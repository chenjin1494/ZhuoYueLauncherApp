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

    /**
     * 下载地址(按顺序尝试)：
     *  ① 仓库 dist/ 目录里的最新 APK(raw 直链, 不依赖 CI/Release, 公开免登录)
     *  ② GitHub latest Release 的资源(CI 成功时会更新)
     */
    public static final String[] APK_URLS = {
        "https://raw.githubusercontent.com/chenjin1494/ZhuoYueLauncherApp/main/dist/urlfeeder-latest.apk",
        "https://github.com/chenjin1494/ZhuoYueLauncherApp/releases/latest/download/urlfeeder-latest.apk"
    };
    public static final String APK_URL = APK_URLS[0];
    public static final String AUTHORITY = "com.helper.urlfeeder.apk";
    public static final String FILE_NAME = "urlfeeder-update.apk";

    /** 进度/结果回调(主界面用它写运行日志, 便于排查网络被拦等) */
    public interface Cb { void log(String msg); }

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
        OperationLog.Span span=OperationLog.begin("UPDATE","HTTP_DOWNLOAD","url="+url+" destination="+out.getAbsolutePath());
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new URL(url).openConnection();
            c.setConnectTimeout(15000);
            c.setReadTimeout(60000);
            c.setInstanceFollowRedirects(true);
            c.setRequestProperty("User-Agent", "urlfeeder-updater");
            int code = c.getResponseCode();
            if (code != 200) {
                String error=code == 404 ? "HTTP 404：仓库还没有可下载的 Release（需先让 CI 构建一次）" : "HTTP " + code;
                OperationLog.fail(span,"url="+url+" "+error);
                return error;
            }
            InputStream in = c.getInputStream();
            FileOutputStream fo = new FileOutputStream(out);
            byte[] b = new byte[16384];
            int n; long total = 0;
            while ((n = in.read(b)) > 0) { fo.write(b, 0, n); total += n; }
            try { fo.flush(); fo.close(); } catch (Exception e) {}
            try { in.close(); } catch (Exception e) {}
            if (total < 1024) { String error="下载内容过小(" + total + " 字节)"; OperationLog.fail(span,error); return error; }
            OperationLog.ok(span,"http=200 bytes="+total);
            return null;
        } catch (Throwable t) {
            OperationLog.fail(span,t);
            return "网络错误: " + t;
        } finally {
            if (c != null) try { c.disconnect(); } catch (Exception e) {}
        }
    }

    /** 检查更新：后台按顺序尝试下载地址 → 比对版本 → 主线程弹窗询问是否安装 */
    public static void checkAndPrompt(final Activity act) { checkAndPrompt(act, null); }
    public static void checkAndPrompt(final Activity act, final Cb cb) {
        OperationLog.event("UPDATE","CHECK","START","currentVersion="+myVersionName(act)+"("+myVersionCode(act)+")");
        Toast.makeText(act, "正在检查更新…", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() { public void run() {
            final File tmp = new File(act.getCacheDir(), FILE_NAME + ".tmp");
            final File dst = new File(act.getCacheDir(), FILE_NAME);
            String err = null;
            boolean got = false;
            for (int i = 0; i < APK_URLS.length && !got; i++) {
                try { err = httpGet(APK_URLS[i], tmp); } catch (Throwable t) { err = "网络错误: " + t; }
                if (err == null) {
                    got = true;
                    if (cb != null) cb.log("检查更新: 下载成功(" + host(APK_URLS[i]) + ")");
                } else if (cb != null) {
                    cb.log("检查更新: " + host(APK_URLS[i]) + " 失败 → " + err);
                }
            }
            if (got) {
                try {
                    PackageInfo pi = act.getPackageManager().getPackageArchiveInfo(tmp.getAbsolutePath(), 0);
                    if (pi == null) {
                        err = "下载内容不是有效 APK（资源可能未就绪）";
                    } else if (!act.getPackageName().equals(pi.packageName)) {
                        err = "APK 包名不匹配: " + pi.packageName;
                    } else if (!sameSigner(act, tmp)) {
                        err = "APK 签名与当前安装版本不一致";
                    } else {
                        final int nv = pi.versionCode;
                        final String nvn = pi.versionName;
                        final int mv = myVersionCode(act);
                        final String mvn = myVersionName(act);
                        if (cb != null) cb.log("检查更新: 远端 v" + nvn + "(" + nv + ") 本机 v" + mvn + "(" + mv + ")");
                        if (nv <= mv) {
                            err = "已是最新版本 v" + mvn;
                        } else {
                            if (dst.exists()) dst.delete();
                            if (!tmp.renameTo(dst)) { err = "缓存文件写入失败"; }
                            else {
                                final String nn = nvn;
                                OperationLog.event("UPDATE","CHECK","OK","updateAvailable=true remote="+nvn+"("+nv+") local="+mvn+"("+mv+")");
                                act.runOnUiThread(new Runnable() { public void run() { promptInstall(act, dst, nn, mvn); } });
                                return;
                            }
                        }
                    }
                } catch (Throwable t) { err = "校验失败: " + t; }
            }
            try { tmp.delete(); } catch (Exception e) {}
            final String m = err == null ? "检查失败" : err;
            OperationLog.event("UPDATE","CHECK",got?"INFO":"FAIL",m);
            if (cb != null) cb.log("检查更新结果: " + m);
            act.runOnUiThread(new Runnable() { public void run() {
                Toast.makeText(act, m, Toast.LENGTH_LONG).show();
            }});
        }}).start();
    }
    static boolean sameSigner(Activity act, File apk) {
        try {
            int flags = Build.VERSION.SDK_INT >= 28
                ? android.content.pm.PackageManager.GET_SIGNING_CERTIFICATES
                : android.content.pm.PackageManager.GET_SIGNATURES;
            PackageInfo remote = act.getPackageManager().getPackageArchiveInfo(apk.getAbsolutePath(), flags);
            PackageInfo local = act.getPackageManager().getPackageInfo(act.getPackageName(), flags);
            android.content.pm.Signature[] a, b;
            if (Build.VERSION.SDK_INT >= 28) {
                a = remote == null || remote.signingInfo == null ? null : remote.signingInfo.getApkContentsSigners();
                b = local.signingInfo == null ? null : local.signingInfo.getApkContentsSigners();
            } else {
                a = remote == null ? null : remote.signatures;
                b = local.signatures;
            }
            if (a == null || b == null || a.length == 0 || a.length != b.length) return false;
            for (int i = 0; i < a.length; i++) {
                boolean found = false;
                for (int j = 0; j < b.length; j++) if (a[i].equals(b[j])) { found = true; break; }
                if (!found) return false;
            }
            return true;
        } catch (Throwable t) { return false; }
    }

    static String host(String url) {
        try { return new URL(url).getHost(); } catch (Throwable t) { return url; }
    }

    static void promptInstall(final Activity act, final File apk, final String nvn, final String mvn) {
        OperationLog.event("UPDATE","INSTALL_PROMPT","START","remote="+nvn+" local="+mvn);
        try {
            new AlertDialog.Builder(act)
                .setTitle("发现新版本 v" + nvn)
                .setMessage("当前版本 v" + mvn + "\n安装包已下载完成，是否立即安装？\n\n"
                          + "· 升级不会清空设置（同签名覆盖安装）\n"
                          + "· 若提示“未知来源”，请允许本应用安装应用")
                .setPositiveButton("立即安装", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { OperationLog.event("UPDATE","INSTALL_PROMPT","OK","choice=install"); install(act, apk); }
                })
                .setNegativeButton("以后再说", new DialogInterface.OnClickListener() {
                    public void onClick(DialogInterface d, int w) { OperationLog.event("UPDATE","INSTALL_PROMPT","CANCELED","choice=later"); }
                })
                .setOnCancelListener(new DialogInterface.OnCancelListener() {
                    public void onCancel(DialogInterface d) { OperationLog.event("UPDATE","INSTALL_PROMPT","CANCELED","choice=outside_or_back"); }
                })
                .show();
        } catch (Throwable t) { OperationLog.event("UPDATE","INSTALL_PROMPT","FAIL",OperationLog.stack(t)); Toast.makeText(act, "提示失败: " + t, Toast.LENGTH_LONG).show(); }
    }

    public static void install(Activity act, File apk) {
        OperationLog.Span span=OperationLog.begin("UPDATE","START_INSTALL","apk="+apk.getAbsolutePath()+" bytes="+apk.length());
        try {
            if (Build.VERSION.SDK_INT >= 26 && !act.getPackageManager().canRequestPackageInstalls()) {
                Toast.makeText(act, "请先允许「安装未知应用」，再回来重新点检查更新", Toast.LENGTH_LONG).show();
                try {
                    act.startActivity(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                        Uri.parse("package:" + act.getPackageName())));
                } catch (Exception e) {}
                OperationLog.fail(span,"REQUEST_INSTALL_PACKAGES permission missing; settings opened");
                return;
            }
            Uri u = Uri.parse("content://" + AUTHORITY + "/" + apk.getName());
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setDataAndType(u, "application/vnd.android.package-archive");
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
            act.startActivity(i);
            OperationLog.result(span,"DISPATCHED","installer intent dispatched");
        } catch (Throwable t) {
            OperationLog.fail(span,t);
            Toast.makeText(act, "安装启动失败: " + t, Toast.LENGTH_LONG).show();
        }
    }
}
