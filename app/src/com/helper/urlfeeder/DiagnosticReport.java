package com.helper.urlfeeder;

import android.app.ActivityManager;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.net.VpnService;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Build;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.provider.Settings;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Collects privacy-safe device state and renders a support report. */
public final class DiagnosticReport {
    public static final int OK = 0;
    public static final int WARN = 1;
    public static final int INFO = 2;
    private static final String CACHE_PREFIX = "urlfeeder-diagnostic-";

    private DiagnosticReport() {}

    public static final class Item {
        public final String group;
        public final String label;
        public final String value;
        public final String detail;
        public final int level;

        Item(String group, String label, String value, String detail, int level) {
            this.group = group;
            this.label = label;
            this.value = value;
            this.detail = detail;
            this.level = level;
        }
    }

    public static final class Snapshot {
        public final long generatedAt;
        public final ArrayList<Item> items = new ArrayList<Item>();
        public String appVersion = "?";
        public int appVersionCode;
        public int okCount;
        public int warningCount;

        Snapshot(long generatedAt) { this.generatedAt = generatedAt; }

        void add(String group, String label, String value, String detail, int level) {
            items.add(new Item(group, label, value, detail, level));
            if (level == OK) okCount++;
            else if (level == WARN) warningCount++;
        }
    }

    public static Snapshot collect(Context context) {
        Context app = context.getApplicationContext();
        Snapshot out = new Snapshot(System.currentTimeMillis());
        PackageManager pm = app.getPackageManager();
        SharedPreferences prefs = app.getSharedPreferences("pf", 0);

        try {
            PackageInfo pi = pm.getPackageInfo(app.getPackageName(), 0);
            out.appVersion = pi.versionName == null ? "?" : pi.versionName;
            out.appVersionCode = pi.versionCode;
        } catch (Exception ignored) {}

        boolean shizukuRunning = safeShizukuRunning();
        boolean shizukuGranted = shizukuRunning && safeShizukuPermission();
        out.add("核心能力", "Shizuku",
                shizukuGranted ? "运行并已授权" : (shizukuRunning ? "运行但未授权" : "未运行"),
                "系统级动作与设备管理能力", shizukuGranted ? OK : WARN);

        boolean overlay = Build.VERSION.SDK_INT < 23 || Settings.canDrawOverlays(app);
        out.add("核心能力", "悬浮窗权限", overlay ? "已授权" : "未授权",
                "悬浮球后台显示权限", overlay ? OK : WARN);

        boolean notifications = Build.VERSION.SDK_INT < 33
                || app.checkSelfPermission("android.permission.POST_NOTIFICATIONS") == PackageManager.PERMISSION_GRANTED;
        out.add("核心能力", "通知权限", notifications ? "已授权" : "未授权",
                "网络守护前台状态通知", notifications ? OK : WARN);

        boolean shot = false;
        try { shot = ShotAccessibilityService.ready(); } catch (Throwable ignored) {}
        out.add("核心能力", "截图辅助", shot ? "可用" : "未开启",
                "无障碍截图服务", shot ? OK : INFO);

        boolean guard = serviceRunning(app, GuardService.class.getName());
        boolean guardWanted = prefs.getBoolean("guard_on", false) && !prefs.getBoolean("guard_off", false);
        out.add("核心能力", "网络守护", guard ? "运行中" : (guardWanted ? "应运行但未检测到" : "已停止"),
                guardWanted ? "配置为自动守护" : "未配置自动守护", guard ? OK : (guardWanted ? WARN : INFO));

        boolean ball = FloatBallService.running;
        out.add("核心能力", "悬浮球服务", ball ? "运行中" : "未运行",
                "仅表示当前应用进程检测结果", ball ? OK : INFO);

        int network = networkState(app);
        out.add("网络与控制", "网络连接", network == 2 ? "互联网已验证" : (network == 1 ? "已连接但未验证" : "未连接"),
                networkTransport(app), network == 2 ? OK : WARN);

        boolean anyVpn = vpnActive(app);
        out.add("网络与控制", "系统 VPN", anyVpn ? "存在活动连接" : "未检测到",
                "包含本应用或其他 VPN，不采集应用身份", INFO);

        boolean vpnAuthorized = false;
        try { vpnAuthorized = VpnService.prepare(app) == null; } catch (Exception ignored) {}
        out.add("网络与控制", "内置 VPN 授权", vpnAuthorized ? "已授权" : "未授权",
                "仅检查系统授权，不触发授权界面", vpnAuthorized ? OK : INFO);

        boolean blockWanted = prefs.getBoolean("vpn_block_zy", false);
        String blockValue = BlockVpnService.running ? "运行中" : (BlockVpnService.paused ? "已让位暂停" : (blockWanted ? "已配置但未运行" : "未配置"));
        int blockLevel = BlockVpnService.running ? OK : (blockWanted && !BlockVpnService.paused ? WARN : INFO);
        out.add("网络与控制", "上报域名拦截", blockValue,
                "配置域名数 " + BlockVpnService.BLOCK_HOSTS.length, blockLevel);

        boolean firewallWanted = prefs.getBoolean("fw_block_zy", false);
        boolean firewallEnabled = packageEnabled(pm, "cn.com.microtrust.firewall");
        int firewallRules = -1, firewallExpected = 0, firewallMatched = 0;
        if (firewallWanted && firewallEnabled) {
            try {
                java.util.LinkedHashSet<String> expected = new java.util.LinkedHashSet<String>();
                for (String host : BlockVpnService.BLOCK_HOSTS) {
                    try { for (java.net.InetAddress address : java.net.InetAddress.getAllByName(host)) expected.add(address.getHostAddress()); }
                    catch (Exception ignored) {}
                }
                for (String ip : BlockVpnService.BLOCK_IPS_STATIC) expected.add(ip);
                firewallExpected = expected.size();
                FwRules.Res rules = FwRules.getBlackRules(app);
                if (rules.good()) {
                    firewallRules = rules.data.size();
                    java.util.HashSet<String> actual = new java.util.HashSet<String>();
                    for (String rule : rules.data) if (rule != null) actual.add(rule.trim());
                    for (String ip : expected) if (actual.contains(ip)) firewallMatched++;
                }
            } catch (Exception ignored) {}
        }
        boolean firewallEffective = firewallWanted && firewallEnabled && firewallExpected > 0
                && firewallRules >= 0 && firewallMatched == firewallExpected;
        String firewallValue = !firewallWanted ? "未配置" : (firewallEffective ? "规则已验证" : "已配置但未验证生效");
        String firewallDetail = "防火墙应用：" + packageState(pm, "cn.com.microtrust.firewall")
                + (firewallRules >= 0 ? "；总规则 " + firewallRules + " 条，目标匹配 " + firewallMatched + "/" + firewallExpected : "；规则状态不可读");
        out.add("网络与控制", "防火墙规则控制", firewallValue, firewallDetail,
                !firewallWanted ? INFO : (firewallEffective ? OK : WARN));

        String defaultHome = resolvePackage(pm, homeIntent());
        boolean lawnchairEnabled = packageEnabled(pm, "app.lawnchair");
        boolean lawnchairHome = "app.lawnchair".equals(defaultHome);
        boolean suppress = prefs.getBoolean("suppress_zy", false);
        boolean suppressEffective = suppress && guard && shizukuGranted && lawnchairEnabled && lawnchairHome;
        String suppressValue = !suppress ? "未配置" : (suppressEffective ? "依赖已就绪" : "已配置但依赖缺失");
        String suppressDetail = "网络守护 " + (guard ? "正常" : "缺失") + "；Shizuku "
                + (shizukuGranted ? "正常" : "缺失") + "；Lawnchair "
                + (!lawnchairEnabled ? "未启用" : (lawnchairHome ? "当前 HOME" : "不是当前 HOME"));
        out.add("网络与控制", "监控抑制", suppressValue, suppressDetail,
                !suppress ? INFO : (suppressEffective ? OK : WARN));

        String defaultBrowser = resolvePackage(pm, webIntent());
        boolean browserOk = app.getPackageName().equals(defaultBrowser);
        out.add("系统关联", "默认浏览器", friendlyPackage(defaultBrowser),
                browserOk ? "链接默认进入万能转发器" : "可在系统默认应用中修改", browserOk ? OK : WARN);

        boolean homeKnown = defaultHome.length() > 0;
        out.add("系统关联", "默认桌面", friendlyPackage(defaultHome),
                "当前 HOME 处理应用", homeKnown ? OK : WARN);

        out.add("系统关联", "Lawnchair", packageState(pm, "app.lawnchair"),
                "可选桌面环境", packageAvailable(pm, "app.lawnchair") ? OK : INFO);
        out.add("系统关联", "卓越桌面", packageState(pm, "com.zy.ai.launcher"),
                "设备原始桌面组件", packageAvailable(pm, "com.zy.ai.launcher") ? OK : INFO);

        File backup = newestBackup(app);
        out.add("数据与运行", "最近设置备份", backup == null ? "未找到" : formatTime(backup.lastModified()),
                backup == null ? "应用专属备份目录中没有文件" : "已检测到应用专属备份，不导出文件名", backup == null ? WARN : OK);

        List<AuditLog.Entry> audit = AuditLog.read(app, Integer.MAX_VALUE);
        int failures = 0;
        for (AuditLog.Entry entry : audit) {
            String result = entry.result == null ? "" : entry.result.toUpperCase(Locale.US);
            if (result.contains("FAIL") || result.contains("ERROR") || result.contains("DENIED")
                    || result.contains("INTERRUPTED") || result.contains("TIMED_OUT") || result.contains("失败")) failures++;
        }
        out.add("数据与运行", "审计日志", audit.size() + " 条，异常 " + failures + " 条",
                "仅统计数量，不导出任何审计记录字段", failures > 0 ? WARN : INFO);

        long usable = app.getFilesDir().getUsableSpace() / (1024L * 1024L);
        out.add("数据与运行", "可用存储", usable + " MB",
                "应用数据分区剩余空间", usable >= 256 ? OK : WARN);

        int[] battery = battery(app);
        String batteryValue = battery[0] < 0 ? "未知" : battery[0] + "%";
        String batteryDetail = battery[1] < 0 ? "充电状态未知" : (battery[1] == 1 ? "正在充电" : "未充电");
        out.add("数据与运行", "电量", batteryValue,
                batteryDetail, battery[0] >= 20 || battery[0] < 0 ? INFO : WARN);

        long uptimeMinutes = SystemClock.elapsedRealtime() / 60000L;
        out.add("数据与运行", "系统运行时间", formatDuration(uptimeMinutes),
                "自本次启动以来", INFO);

        out.add("数据与运行", "设置项", prefs.getAll().size() + " 项",
                "报告仅统计数量，不导出设置值", INFO);
        return out;
    }

    public static Map<String, List<Item>> grouped(Snapshot snapshot) {
        LinkedHashMap<String, List<Item>> groups = new LinkedHashMap<String, List<Item>>();
        for (Item item : snapshot.items) {
            List<Item> list = groups.get(item.group);
            if (list == null) { list = new ArrayList<Item>(); groups.put(item.group, list); }
            list.add(item);
        }
        return groups;
    }

    public static String text(Snapshot snapshot) {
        StringBuilder out = new StringBuilder();
        out.append("万能转发器 诊断报告\n");
        out.append("生成时间：").append(formatTime(snapshot.generatedAt)).append('\n');
        out.append("应用版本：v").append(snapshot.appVersion).append(" (").append(snapshot.appVersionCode).append(")\n");
        out.append("包名：com.helper.urlfeeder\n");
        out.append("设备：").append(safe(Build.MANUFACTURER)).append(' ').append(safe(Build.MODEL)).append('\n');
        out.append("系统：Android ").append(safe(Build.VERSION.RELEASE)).append(" / API ").append(Build.VERSION.SDK_INT).append('\n');
        out.append("状态摘要：正常 ").append(snapshot.okCount).append("，需关注 ").append(snapshot.warningCount).append('\n');
        out.append("隐私说明：不包含网址历史、剪贴板、密钥、规则正文、设置值或审计详情。\n");

        String current = "";
        for (Item item : snapshot.items) {
            if (!current.equals(item.group)) {
                current = item.group;
                out.append("\n[").append(current).append("]\n");
            }
            out.append(item.level == OK ? "OK" : (item.level == WARN ? "WARN" : "INFO"));
            out.append("  ").append(item.label).append("：").append(item.value);
            if (item.detail != null && item.detail.length() > 0) out.append("；").append(item.detail);
            out.append('\n');
        }
        return out.toString();
    }

    public static File writeCache(Context context, Snapshot snapshot) throws IOException {
        cleanupCache(context);
        File file = new File(context.getCacheDir(), CACHE_PREFIX + snapshot.generatedAt + "-" + System.currentTimeMillis() + ".txt");
        write(file, text(snapshot).getBytes("UTF-8"));
        return file;
    }

    public static String reportFileName() {
        return "万能转发器诊断报告_" + new SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(new Date()) + ".txt";
    }

    public static String export(Context context, Snapshot snapshot) throws IOException {
        if (Build.VERSION.SDK_INT < 29) throw new IOException("system file picker required");
        String name = reportFileName();
        byte[] data = text(snapshot).getBytes("UTF-8");
        ContentResolver resolver = context.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, name);
        values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, "Download/万能转发器诊断");
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法创建下载文件");
        OutputStream stream = null;
        boolean done = false;
        try {
            stream = resolver.openOutputStream(uri, "w");
            if (stream == null) throw new IOException("无法写入下载文件");
            stream.write(data); stream.flush(); done = true;
        } finally {
            if (stream != null) try { stream.close(); } catch (Exception ignored) {}
            if (!done) resolver.delete(uri, null, null);
        }
        ContentValues ready = new ContentValues(); ready.put(MediaStore.MediaColumns.IS_PENDING, 0); resolver.update(uri, ready, null, null);
        return "下载/万能转发器诊断/" + name;
    }

    public static void writeTextUri(Context context, Uri uri, String reportText) throws IOException {
        if (uri == null) throw new IOException("导出位置为空");
        if (reportText == null) throw new IOException("诊断报告为空");
        OutputStream stream = context.getContentResolver().openOutputStream(uri, "w");
        if (stream == null) throw new IOException("无法打开导出位置");
        try { stream.write(reportText.getBytes("UTF-8")); stream.flush(); }
        finally { try { stream.close(); } catch (Exception ignored) {} }
    }

    private static void cleanupCache(Context context) {
        try {
            File[] files = context.getCacheDir().listFiles();
            long cutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
            if (files != null) for (File file : files) {
                if (file.getName().startsWith(CACHE_PREFIX) && file.lastModified() < cutoff) file.delete();
            }
        } catch (Exception ignored) {}
    }

    private static void write(File file, byte[] data) throws IOException {
        FileOutputStream out = new FileOutputStream(file, false);
        try { out.write(data); out.flush(); }
        finally { try { out.close(); } catch (Exception ignored) {} }
    }

    private static boolean safeShizukuRunning() { try { return ShizukuUtil.running(); } catch (Throwable e) { return false; } }
    private static boolean safeShizukuPermission() { try { return ShizukuUtil.permission() == 0; } catch (Throwable e) { return false; } }

    private static boolean serviceRunning(Context context, String className) {
        try {
            ActivityManager manager = (ActivityManager) context.getSystemService(Context.ACTIVITY_SERVICE);
            if (manager == null) return false;
            List<ActivityManager.RunningServiceInfo> services = manager.getRunningServices(300);
            if (services != null) for (ActivityManager.RunningServiceInfo service : services) {
                if (service != null && service.service != null && className.equals(service.service.getClassName())) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static int networkState(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return 0;
            Network network = manager.getActiveNetwork();
            NetworkCapabilities caps = manager.getNetworkCapabilities(network);
            if (caps == null || !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return 0;
            return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) ? 2 : 1;
        } catch (Exception ignored) { return 0; }
    }

    private static String networkTransport(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            NetworkCapabilities caps = manager == null ? null : manager.getNetworkCapabilities(manager.getActiveNetwork());
            if (caps == null) return "没有活动网络";
            ArrayList<String> names = new ArrayList<String>();
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) names.add("Wi-Fi");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR)) names.add("移动网络");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)) names.add("以太网");
            if (caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) names.add("VPN");
            return names.isEmpty() ? "其他网络" : join(names);
        } catch (Exception ignored) { return "状态不可用"; }
    }

    private static boolean vpnActive(Context context) {
        try {
            ConnectivityManager manager = (ConnectivityManager) context.getSystemService(Context.CONNECTIVITY_SERVICE);
            if (manager == null) return false;
            for (Network network : manager.getAllNetworks()) {
                NetworkCapabilities caps = manager.getNetworkCapabilities(network);
                if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    private static String resolvePackage(PackageManager pm, Intent intent) {
        try {
            ResolveInfo info = pm.resolveActivity(intent, PackageManager.MATCH_DEFAULT_ONLY);
            if (info != null && info.activityInfo != null && info.activityInfo.packageName != null) return info.activityInfo.packageName;
        } catch (Exception ignored) {}
        return "";
    }

    private static Intent webIntent() { return new Intent(Intent.ACTION_VIEW, Uri.parse("https://example.com")); }
    private static Intent homeIntent() { Intent intent = new Intent(Intent.ACTION_MAIN); intent.addCategory(Intent.CATEGORY_HOME); return intent; }

    private static boolean packageAvailable(PackageManager pm, String packageName) {
        try { pm.getApplicationInfo(packageName, 0); return true; } catch (Exception ignored) { return false; }
    }

    private static boolean packageEnabled(PackageManager pm, String packageName) {
        try {
            ApplicationInfo info = pm.getApplicationInfo(packageName, 0);
            int setting = pm.getApplicationEnabledSetting(packageName);
            return info.enabled && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER
                    && setting != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_UNTIL_USED;
        } catch (Exception ignored) { return false; }
    }

    private static String packageState(PackageManager pm, String packageName) {
        if (!packageAvailable(pm, packageName)) return "未安装";
        return packageEnabled(pm, packageName) ? "已安装并启用" : "已安装但停用";
    }

    private static File newestBackup(Context context) {
        try {
            File root = context.getExternalFilesDir(null);
            File dir = root == null ? null : new File(root, "backup");
            File[] files = dir == null ? null : dir.listFiles();
            File newest = null;
            if (files != null) for (File file : files) {
                if (file.isFile() && (newest == null || file.lastModified() > newest.lastModified())) newest = file;
            }
            return newest;
        } catch (Exception ignored) { return null; }
    }

    private static int[] battery(Context context) {
        int level = -1, charging = -1;
        try {
            Intent state = context.registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
            if (state != null) {
                int raw = state.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
                int scale = state.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
                if (raw >= 0 && scale > 0) level = raw * 100 / scale;
                int status = state.getIntExtra(BatteryManager.EXTRA_STATUS, -1);
                if (status != -1) charging = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL ? 1 : 0;
            }
        } catch (Exception ignored) {}
        return new int[]{level, charging};
    }

    private static String friendlyPackage(String value) { return value == null || value.length() == 0 ? "未设置" : value; }
    private static String safe(String value) { return value == null || value.length() == 0 ? "未知" : value; }
    private static String formatTime(long timestamp) { return new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(new Date(timestamp)); }
    private static String formatDuration(long minutes) { long days = minutes / 1440L; long hours = (minutes % 1440L) / 60L; long mins = minutes % 60L; return (days > 0 ? days + " 天 " : "") + hours + " 小时 " + mins + " 分"; }
    private static String join(List<String> values) { StringBuilder out = new StringBuilder(); for (String value : values) { if (out.length() > 0) out.append(" / "); out.append(value); } return out.toString(); }
}
