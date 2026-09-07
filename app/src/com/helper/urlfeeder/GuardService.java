package com.helper.urlfeeder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.Handler;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.Parcel;

public class GuardService extends Service {
    static final String FW_PKG="cn.com.microtrust.firewall";
    static final String FW_IFACE="cn.com.microtrust.firewall.aidl.IAFWService";
    static final String FW_ACTION="cn.com.microtrust.firewall.IAFWService";
    static final String CH="guard_ch";
    static final int NID=1001;
    static final long CHECK_FAST=3500L;   // blocked state: retry fast
    static final long CHECK_SLOW=20000L;  // healthy state: light polling

    SharedPreferences prefs;
    NotificationManager nm;
    Handler h=new Handler();
    final Runnable ticker=new Runnable(){ public void run(){ tick(); } };
    boolean alive=false;
    ServiceConnection fwConn=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){
            new Thread(new Runnable(){ public void run(){
                String r="";
                try{ r+=call(b,9,null)+" "; r+=call(b,10,null)+" "; r+=call(b,1,1)+" "; r+=call(b,11,null); }catch(Exception e){ r="ERR"; }
                final String fr=r;
                h.post(new Runnable(){ public void run(){ notif("已自动开网 ✓","防火墙规则已清空 ("+fr.trim()+")，无需手动点按"); schedule(CHECK_FAST); } });
                try{ unbindService(fwConn); }catch(Exception e){}
            }}).start();
        }
        public void onServiceDisconnected(ComponentName n){}
        public void onBindingDied(ComponentName n){}
        public void onNullBinding(ComponentName n){ schedule(CHECK_FAST); }
    };

    public void onCreate(){
        super.onCreate();
        nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
        prefs=getSharedPreferences("pf",Context.MODE_PRIVATE);
        if(Build.VERSION.SDK_INT>=26){
            NotificationChannel c=new NotificationChannel(CH,"网络守护",NotificationManager.IMPORTANCE_LOW);
            c.setShowBadge(false);
            if(nm!=null) nm.createNotificationChannel(c);
        }
    }

    public int onStartCommand(Intent i,int flags,int sid){
        String a=i==null?null:i.getAction();
        if("stop".equals(a)){
            alive=false;
            prefs.edit().putBoolean("guard_on",false).commit();
            h.removeCallbacks(ticker);
            stopForeground(true);
            stopSelf();
            return START_NOT_STICKY;
        }
        prefs.edit().putBoolean("guard_on",true).commit();
        if(!alive){
            alive=true;
            Intent open=new Intent(this,MainActivity.class);
            PendingIntent pi=PendingIntent.getActivity(this,0,open,
                (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0)|PendingIntent.FLAG_UPDATE_CURRENT);
            Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
            nb.setSmallIcon(android.R.drawable.stat_notify_sync)
              .setContentTitle("网络守护 运行中")
              .setContentText("自动开网 + 保持默认浏览器 · 监测中")
              .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(pi);
            if(Build.VERSION.SDK_INT<26) nb.setPriority(Notification.PRIORITY_LOW);
            startForeground(NID,nb.build());
            h.removeCallbacks(ticker);
            h.postDelayed(ticker,1000);
        } else {
            h.removeCallbacks(ticker);
            h.postDelayed(ticker,1000);
        }
        return START_STICKY;
    }

    void tick(){
        if(!alive) return;
        final long t0=System.currentTimeMillis();
        new Thread(new Runnable(){ public void run(){
            boolean ok=probe("www.bilibili.com",443)||probe("www.qq.com",443);
            h.post(new Runnable(){ public void run(){
                if(!alive) return;
                if(ok){
                    boolean def=isDefaultBrowser();
                    if(def) notif("网络正常 · 守护中","白名单外可连 ✓  ·  默认浏览器=转发器 ✓");
                    else notifFix("网络正常 · 守护中","白名单外可连 ✓  ·  默认浏览器不是转发器","点此设回默认浏览器");
                    schedule(CHECK_SLOW);
                }
                else { notif("检测到拦截 · 自动开网中…","白名单规则生效，正在清规则"); doOpenNet(); schedule(CHECK_FAST); }
            }});
        }}).start();
    }

    void schedule(long ms){ if(!alive) return; h.removeCallbacks(ticker); h.postDelayed(ticker,ms); }

    void doOpenNet(){
        try{ Intent s=new Intent(FW_ACTION); s.setPackage(FW_PKG); bindService(s,fwConn,Context.BIND_AUTO_CREATE); }
        catch(Exception e){ notif("自动开网失败","无法连接管控服务："+e.getMessage()); schedule(CHECK_SLOW); }
    }

    boolean isDefaultBrowser(){
        if(Build.VERSION.SDK_INT>=29){
            try{ android.app.role.RoleManager rm=(android.app.role.RoleManager)getSystemService("role");
                 if(rm!=null&&rm.isRoleHeld("android.app.role.BROWSER")) return true;
            }catch(Throwable t){}
        }
        try{
            Intent v=new Intent(Intent.ACTION_VIEW,android.net.Uri.parse("https://example.com"));
            android.content.pm.ResolveInfo ri=getPackageManager().resolveActivity(v,PackageManager.MATCH_DEFAULT_ONLY);
            if(ri!=null&&ri.activityInfo!=null&&getPackageName().equals(ri.activityInfo.packageName)) return true;
        }catch(Throwable t){}
        return false;
    }
    PendingIntent mainPi(){
        Intent open=new Intent(this,MainActivity.class);
        return PendingIntent.getActivity(this,0,open,
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0)|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    PendingIntent fixPi(){
        Intent fix=new Intent(this,MainActivity.class);
        fix.setAction(MainActivity.ACT_FIX_BROWSER);
        fix.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
        return PendingIntent.getActivity(this,1,fix,
            (Build.VERSION.SDK_INT>=23?PendingIntent.FLAG_IMMUTABLE:0)|PendingIntent.FLAG_UPDATE_CURRENT);
    }
    void notifFix(String t,String d,String actTxt){
        try{
            Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
            nb.setSmallIcon(android.R.drawable.stat_notify_sync)
              .setContentTitle(t).setContentText(d)
              .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(mainPi())
              .addAction(android.R.drawable.ic_menu_edit,actTxt,fixPi());
            if(Build.VERSION.SDK_INT<26) nb.setPriority(Notification.PRIORITY_LOW);
            if(nm!=null) nm.notify(NID,nb.build());
        }catch(Exception e){}
    }
    void notif(String t,String d){
        try{
            Notification.Builder nb=Build.VERSION.SDK_INT>=26?new Notification.Builder(this,CH):new Notification.Builder(this);
            nb.setSmallIcon(android.R.drawable.stat_notify_sync)
              .setContentTitle(t).setContentText(d)
              .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(mainPi());
            if(Build.VERSION.SDK_INT<26) nb.setPriority(Notification.PRIORITY_LOW);
            if(nm!=null) nm.notify(NID,nb.build());
        }catch(Exception e){}
    }

    boolean probe(String host,int port){
        java.net.Socket s=null;
        try{
            s=new java.net.Socket();
            s.connect(new java.net.InetSocketAddress(host,port),2500);
            return true;
        }catch(Exception e){ return false; }
        finally{ try{ if(s!=null) s.close(); }catch(Exception e){} }
    }

    String call(IBinder b,int code,Integer arg){
        Parcel d=Parcel.obtain(),r=Parcel.obtain();
        try{
            d.writeInterfaceToken(FW_IFACE);
            if(arg!=null) d.writeInt(arg);
            b.transact(code,d,r,0);
            r.readException();
            int res=r.readInt();
            return "c"+code+"="+res;
        }catch(Exception e){ return "c"+code+"ERR"; }
        finally{ d.recycle(); r.recycle(); }
    }

    public IBinder onBind(Intent i){ return null; }
    public void onDestroy(){ alive=false; h.removeCallbacks(ticker); try{ unbindService(fwConn); }catch(Exception e){} super.onDestroy(); }
}
