package com.helper.urlfeeder;

import android.content.Intent;
import android.net.VpnService;
import android.os.Build;
import android.os.Handler;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileInputStream;
import java.net.InetAddress;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * 选择性拦截「卓越Launcher」的上报/追踪类主机：
 *   · 只把 com.zy.ai.launcher 的流量接入本 VPN（addAllowedApplication），其它应用不受影响；
 *   · 路由只加"被拦截主机的 /32"→ 只有发往这些 IP 的包会进隧道并被丢弃；
 *   · 其余流量（AI/内容/更新/支付等）根本不经过 VPN，功能保持可用；
 *   · 定期重新解析域名，IP 变化时重建隧道。
 * 另外：应用使用时长(GET_USAGE_STATS)与屏幕采集(PROJECT_MEDIA)已在系统 appops 层拒绝，
 *       采集端无法取数，属于源头阻断。
 */
public class BlockVpnService extends VpnService {
    static final String TARGET="com.zy.ai.launcher";
    public static volatile boolean running=false;
    public static volatile boolean paused=false;   // 被其它 VPN 占用时挂起, 对方关闭后自动恢复

    // 需要黑洞的上报/追踪主机（保留 jz.zy.com / zxx.zy.com 等业务主机）
    static final String[] BLOCK_HOSTS={
        "dev01.supagent.cn",
        "supagent.cn",
        "oaid.wocloud.cn",
        "sdk.api.oaid.wocloud.cn",
        "zy-pad-test-1253336831.cos.ap-guangzhou.myqcloud.com"
    };
    // 已知固定 IP 兜底（可留空）
    static final String[] BLOCK_IPS_STATIC={ };

    private ParcelFileDescriptor tun=null;
    private Thread worker=null;
    private Handler hd=new Handler();
    private final Set<String> curIps=new LinkedHashSet<String>();
    private final Runnable refresher=new Runnable(){ public void run(){ refreshAsync(); } };

    public int onStartCommand(Intent i,int f,int s){
        OperationLog.init(this);
        OperationLog.event("VPN","START_COMMAND","START","startId="+s+" flags="+f+"\n"+OperationLog.intent(i));
        String a=(i==null)?null:i.getAction();
        if("stop".equals(a)){ stopAll(); stopSelf(); return START_NOT_STICKY; }
        ensureTunnel();
        return START_STICKY;
    }

    // 是否已有别的 VPN 在运行(我们自己没建立时才需要判断)
    boolean otherVpnActive(){
        try{
            android.net.ConnectivityManager cm=(android.net.ConnectivityManager)getSystemService(CONNECTIVITY_SERVICE);
            if(cm==null) return false;
            for(android.net.Network n:cm.getAllNetworks()){
                android.net.NetworkCapabilities c=cm.getNetworkCapabilities(n);
                if(c!=null&&c.hasTransport(android.net.NetworkCapabilities.TRANSPORT_VPN)) return true;
            }
        }catch(Exception e){}
        return false;
    }
    // 已有别的 VPN → 挂起并定时重试; 否则正常建立
    void ensureTunnel(){
        if(running) return;
        if(otherVpnActive()){
            boolean wasPaused=paused;
            paused=true;
            if(!wasPaused) OperationLog.event("VPN","PAUSE_FOR_OTHER_VPN","OK","retryMs=30000");
            Log.i("BlockVpn","other VPN active -> paused, retry in 30s");
            hd.removeCallbacks(retryRun);
            hd.postDelayed(retryRun,30000);
            return;
        }
        paused=false;
        refreshAsync();
    }
    final Runnable retryRun=new Runnable(){ public void run(){
        if(running){ return; }
        if(otherVpnActive()){ paused=true; hd.postDelayed(retryRun,30000); return; }
        paused=false;
        Log.i("BlockVpn","other VPN gone -> resuming");
        refreshAsync();
    }};

    void refreshAsync(){
        new Thread(new Runnable(){ public void run(){
            Set<String> ips=new LinkedHashSet<String>();
            for(String h:BLOCK_HOSTS){
                try{
                    InetAddress[] arr=InetAddress.getAllByName(h);
                    for(InetAddress a:arr) ips.add(a.getHostAddress());
                }catch(Exception e){ Log.w("BlockVpn","resolve fail "+h); }
            }
            for(String s:BLOCK_IPS_STATIC) ips.add(s);
            boolean changed;
            synchronized(curIps){ changed=!ips.equals(curIps); if(changed){ curIps.clear(); curIps.addAll(ips);} }
            if(changed||tun==null){
                OperationLog.event("VPN","RESOLVE_BLOCK_TARGETS",ips.isEmpty()?"FAIL":"OK","count="+ips.size()+" ips="+ips);
                rebuildTunnel(ips);
            }
            hd.postDelayed(refresher,5*60*1000L);
        }},"vpn-resolve").start();
    }

    void rebuildTunnel(Set<String> ips){
        try{
            if(!running && otherVpnActive()){          // 别的 VPN 在跑 → 不抢槽位
                paused=true;
                hd.removeCallbacks(retryRun);
                hd.postDelayed(retryRun,30000);
                Log.i("BlockVpn","skip establish: other VPN active");
                return;
            }
            if(running) stopTunnel();
            if(ips.isEmpty()){ OperationLog.event("VPN","ESTABLISH_TUNNEL","FAIL","no blocked IP resolved"); Log.i("BlockVpn","no blocked IP resolved, tunnel idle"); return; }
            Builder b=new Builder();
            b.setSession("万能转发器 · 拦截卓越上报");
            b.addAddress("10.111.222.1",32);
            try{ b.addAllowedApplication(TARGET); }catch(Exception e){ Log.e("BlockVpn","allow app fail",e); }
            for(String ip:ips){
                try{
                    if(ip.contains(":")) b.addRoute(ip,128); else b.addRoute(ip,32);
                }catch(Exception e){}
            }
            if(Build.VERSION.SDK_INT>=29){ try{ b.setMetered(false); }catch(Exception e){} }
            tun=b.establish();
            if(tun==null){ OperationLog.event("VPN","ESTABLISH_TUNNEL","FAIL","Builder.establish returned null; authorization may be missing"); Log.e("BlockVpn","establish returned null(未授权?)"); return; }
            running=true;
            worker=new Thread(new Runnable(){ public void run(){
                byte[] buf=new byte[32767];
                try{
                    FileInputStream in=new FileInputStream(tun.getFileDescriptor());
                    while(running){ int n=in.read(buf); if(n<0) break; }   // 只可能是被拦 IP 的包 → 直接丢弃
                }catch(Exception e){}
                Log.i("BlockVpn","blackhole loop exit");
            }},"vpn-blackhole");
            worker.start();
            if(paused){ paused=false; notifyUser("其它 VPN 已关闭，卓越上报拦截已自动恢复"); }
            OperationLog.event("VPN","ESTABLISH_TUNNEL","OK","target="+TARGET+" routes="+ips.size()+" ips="+ips);
            Log.i("BlockVpn","selective tunnel up, routes="+ips.size());
        }catch(Exception e){ OperationLog.event("VPN","ESTABLISH_TUNNEL","FAIL",OperationLog.stack(e)); Log.e("BlockVpn","rebuild err",e); }
    }

    void notifyUser(final String msg){
        try{
            new android.os.Handler(getMainLooper()).post(new Runnable(){ public void run(){
                try{ android.widget.Toast.makeText(BlockVpnService.this,msg,android.widget.Toast.LENGTH_LONG).show(); }catch(Exception e){}
            }});
        }catch(Exception e){}
    }
    void stopTunnel(){
        running=false;
        try{ if(worker!=null) worker.interrupt(); }catch(Exception e){}
        worker=null;
        try{ if(tun!=null) tun.close(); }catch(Exception e){}
        tun=null;
        OperationLog.event("VPN","STOP_TUNNEL","OK","running=false");
        Log.i("BlockVpn","tunnel down");
    }
    void stopAll(){
        try{ hd.removeCallbacks(refresher); }catch(Exception e){}
        try{ hd.removeCallbacks(retryRun); }catch(Exception e){}
        paused=false;
        stopTunnel();
    }

    public void onRevoke(){
        // 被别的 VPN 顶掉: 挂起并等待对方关闭后自动恢复(不退出服务)
        stopTunnel();
        paused=true;
        OperationLog.event("VPN","REVOKED","WARN","revoked by another VPN; retryMs=15000");
        Log.i("BlockVpn","revoked by another VPN -> paused");
        notifyUser("已让位给其它 VPN，卓越上报拦截暂停；对方关闭后会自动恢复");
        hd.removeCallbacks(retryRun);
        hd.postDelayed(retryRun,15000);
    }
    public void onCreate(){ super.onCreate(); OperationLog.init(this); OperationLog.event("VPN","ON_CREATE","OK",""); }
    public void onDestroy(){ OperationLog.event("VPN","ON_DESTROY","OK",""); stopAll(); super.onDestroy(); }
}
