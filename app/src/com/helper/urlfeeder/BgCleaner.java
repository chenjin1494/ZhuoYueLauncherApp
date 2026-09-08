package com.helper.urlfeeder;

import android.app.ActivityManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

/**
 * 一键清理后台：不依赖 Shizuku/root，仅用系统普通 API killBackgroundProcesses，
 * 只结束系统允许结束的后台/缓存进程。
 * 前台应用、带前台服务的应用由系统自动豁免；
 * 这里再显式跳过关键/系统应用，避免误伤防火墙、Launcher、管控、浏览器等。
 */
public class BgCleaner {
    // 绝不尝试结束的关键包(本应用/防火墙/Shizuku/桌面/管控/白名单浏览器)
    static final String[] KEEP = {
        "com.helper.urlfeeder",
        "cn.com.microtrust.firewall",
        "moe.shizuku.privileged.api",
        "com.zy.ai.launcher",       // 卓越Launcher(系统uid1000)
        "app.lawnchair",            // Lawnchair 桌面
        "com.zy.ai.browser",        // 白名单浏览器
        "com.zui.safecenter",       // 联想安全中心(管控)
        "com.android.systemui",
        "com.android.settings"
    };

    public static boolean isKeep(String p){
        for(String k:KEEP){ if(k.equals(p)) return true; }
        return false;
    }

    /** 遍历所有可启动用户应用并结束后台进程。返回尝试处理的应用数(尽力而为)。 */
    public static int clear(Context c){
        int triedN=0;
        final List<String> tried=new ArrayList<String>();
        try{
            ActivityManager am=(ActivityManager)c.getSystemService(Context.ACTIVITY_SERVICE);
            PackageManager pm=c.getPackageManager();
            Intent mi=new Intent(Intent.ACTION_MAIN); mi.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> apps=pm.queryIntentActivities(mi,0);
            for(ResolveInfo ai:apps){
                String pkg=(ai==null||ai.activityInfo==null)?null:ai.activityInfo.packageName;
                if(pkg==null||pkg.length()==0||isKeep(pkg)) continue;
                try{
                    ApplicationInfo info=pm.getApplicationInfo(pkg,0);
                    if(info==null) continue;
                    if((info.flags&ApplicationInfo.FLAG_SYSTEM)!=0) continue; // 系统应用不动
                }catch(Exception e){ continue; }
                tried.add(pkg);
                try{ am.killBackgroundProcesses(pkg); }catch(Exception e){}
            }
            triedN=tried.size();
            Log.i("BgCleaner","clear done: tried="+triedN+" pkgs="+tried.toString());
        }catch(Exception e){ Log.e("BgCleaner","clear err",e); }
        return triedN;
    }
}
