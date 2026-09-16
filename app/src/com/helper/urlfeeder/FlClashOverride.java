package com.helper.urlfeeder;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;

/** Generates the FlClash JavaScript override that mirrors the built-in VPN block list. */
public final class FlClashOverride {
    private static final String PACKAGE = "com.follow.clash";
    private static final String FILE_NAME = "urlfeeder-flclash-override.js";
    private static final String RELEASES = "https://github.com/chen08209/FlClash/releases/latest";

    private FlClashOverride() {}

    public static String script() {
        StringBuilder hosts = new StringBuilder();
        for (int i = 0; i < BlockVpnService.BLOCK_HOSTS.length; i++) {
            if (i > 0) hosts.append(",\n  ");
            hosts.append('"').append(js(BlockVpnService.BLOCK_HOSTS[i])).append('"');
        }
        return "// 万能转发器 · FlClash 覆写脚本\n"
            + "// 与内置 VPN 共用拦截域名；重复执行不会添加重复规则。\n\n"
            + "const URLFEEDER_BLOCK_HOSTS = [\n  " + hosts + "\n];\n\n"
            + "const main = (config) => {\n"
            + "  if (!config || typeof config !== \"object\") config = {};\n"
            + "  if (!Array.isArray(config.rules)) config.rules = [];\n"
            + "  const rules = config.rules;\n"
            + "  const existing = new Set(rules.map(rule => String(rule).toUpperCase().trim()));\n"
            + "  let insertAt = rules.findIndex(rule => {\n"
            + "    const value = String(rule).toUpperCase().trim();\n"
            + "    return value === \"MATCH\" || value.startsWith(\"MATCH,\")\n"
            + "      || value === \"FINAL\" || value.startsWith(\"FINAL,\");\n"
            + "  });\n"
            + "  if (insertAt < 0) insertAt = rules.length;\n"
            + "  for (const host of URLFEEDER_BLOCK_HOSTS) {\n"
            + "    const rule = `DOMAIN,${host},REJECT`;\n"
            + "    if (!existing.has(rule.toUpperCase())) {\n"
            + "      rules.splice(insertAt++, 0, rule);\n"
            + "      existing.add(rule.toUpperCase());\n"
            + "    }\n"
            + "  }\n"
            + "  console.log(`[万能转发器] 已加载 ${URLFEEDER_BLOCK_HOSTS.length} 条拦截规则`);\n"
            + "  return config;\n"
            + "};\n";
    }

    public static void showImportDialog(final Activity activity) {
        new AlertDialog.Builder(activity)
            .setTitle("导入 FlClash 覆写脚本")
            .setMessage("脚本会把内置 VPN 的上报域名拦截规则交给 FlClash。Android 同时只能运行一个 VPN；FlClash 运行时，内置 VPN 会自动暂停。\n\n导入后请在 FlClash 的覆写脚本编辑器中粘贴并保存。")
            .setPositiveButton("复制并打开", new android.content.DialogInterface.OnClickListener(){ public void onClick(android.content.DialogInterface d,int w){ copyAndOpen(activity); }})
            .setNeutralButton("分享脚本文件", new android.content.DialogInterface.OnClickListener(){ public void onClick(android.content.DialogInterface d,int w){ shareFile(activity); }})
            .setNegativeButton("取消", null)
            .show();
    }

    static void copyAndOpen(Activity activity) {
        try {
            ClipboardManager cm = (ClipboardManager) activity.getSystemService(Context.CLIPBOARD_SERVICE);
            if (cm == null) throw new IllegalStateException("clipboard unavailable");
            cm.setPrimaryClip(ClipData.newPlainText("万能转发器 FlClash 覆写", script()));
        } catch (Exception e) {
            Toast.makeText(activity, "复制脚本失败，请改用“分享脚本文件”", Toast.LENGTH_LONG).show();
            return;
        }
        Intent launch = activity.getPackageManager().getLaunchIntentForPackage(PACKAGE);
        if (launch != null) {
            try {
                activity.startActivity(launch);
                Toast.makeText(activity, "脚本已复制：请进入覆写脚本编辑器，粘贴并保存", Toast.LENGTH_LONG).show();
            } catch (Exception e) {
                Toast.makeText(activity, "脚本已复制，但无法打开 FlClash", Toast.LENGTH_LONG).show();
            }
            return;
        }
        Toast.makeText(activity, "脚本已复制；未检测到 FlClash", Toast.LENGTH_LONG).show();
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(RELEASES))); }
        catch (Exception ignored) {}
    }

    static void shareFile(Activity activity) {
        File file;
        try { file = writeCache(activity); }
        catch (Exception e) {
            Toast.makeText(activity, "生成脚本文件失败", Toast.LENGTH_LONG).show();
            return;
        }
        try {
            Uri uri = Uri.parse("content://" + ApkProvider.AUTHORITY + "/" + file.getName());
            Intent send = new Intent(Intent.ACTION_SEND);
            send.setType("application/javascript");
            send.putExtra(Intent.EXTRA_STREAM, uri);
            send.putExtra(Intent.EXTRA_SUBJECT, "万能转发器 FlClash 覆写脚本");
            send.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            send.setClipData(ClipData.newRawUri("FlClash 覆写脚本", uri));
            activity.startActivity(Intent.createChooser(send, "分享覆写脚本"));
        } catch (Exception e) {
            Toast.makeText(activity, "没有可用的分享应用，无法打开分享面板", Toast.LENGTH_LONG).show();
        }
    }

    static File writeCache(Context context) throws Exception {
        File file = new File(context.getCacheDir(), FILE_NAME);
        FileOutputStream out = new FileOutputStream(file, false);
        try { out.write(script().getBytes("UTF-8")); out.flush(); }
        finally { try { out.close(); } catch (Exception ignored) {} }
        return file;
    }

    private static String js(String value) {
        return value.replace("\\", "\\\\").replace("\"", "\\\"");
    }
}
