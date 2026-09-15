package com.helper.urlfeeder;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;

/**
 * 只把「卓越Launcher」的流量接进本 VPN 隧道并全部丢弃 → 它的云端上报(使用时长/日志/截屏等)发不出去，
 * 其它应用不受影响（addAllowedApplication 仅接管目标包）。
 */
public class BlockVpnService extends VpnService {
    static final String TARGET="com.zy.ai.launcher";
    public static volatile boolean running=false;

    private ParcelFileDescriptor tun=null;
    private Thread worker=null;

    public int onStartCommand(Intent i,int f,int s){
        String a=(i==null)?null:i.getAction();
        if("stop".equals(a)){ stopTunnel(); stopSelf(); return START_NOT_STICKY; }
        startTunnel();
        return START_STICKY;
    }

    void startTunnel(){
        if(running) return;
        try{
            Builder b=new Builder();
            b.setSession("万能转发器 · 拦截卓越上报");
            b.addAddress("10.111.222.1",32);
            b.addRoute("0.0.0.0",0);
            try{ b.addAllowedApplication(TARGET); }catch(Exception e){ Log.e("BlockVpn","allow app fail",e); }
            if(Build.VERSION.SDK_INT>=29){ try{ b.setMetered(false); }catch(Exception e){} }
            tun=b.establish();
            if(tun==null){ Log.e("BlockVpn","establish returned null (未授权?)"); return; }
            running=true;
            worker=new Thread(new Runnable(){ public void run(){
                byte[] buf=new byte[32767];
                try{
                    FileInputStream in=new FileInputStream(tun.getFileDescriptor());
                    while(running){
                        int n=in.read(buf);
                        if(n<0) break;      // 读到即丢, 不做任何转发
                    }
                }catch(Exception e){}
                Log.i("BlockVpn","blackhole loop exit");
            }},"vpn-blackhole");
            worker.start();
            Log.i("BlockVpn","tunnel up, blackholing "+TARGET);
        }catch(Exception e){ Log.e("BlockVpn","start err",e); }
    }

    void stopTunnel(){
        running=false;
        try{ if(worker!=null) worker.interrupt(); }catch(Exception e){}
        worker=null;
        try{ if(tun!=null) tun.close(); }catch(Exception e){}
        tun=null;
        Log.i("BlockVpn","tunnel down");
    }

    public void onRevoke(){ stopTunnel(); stopSelf(); }
    public void onDestroy(){ stopTunnel(); super.onDestroy(); }
}
