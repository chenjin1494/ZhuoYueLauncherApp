package com.helper.urlfeeder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.IBinder;
import android.os.Parcel;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 管控防火墙(cn.com.microtrust.firewall / RootUTService / IAFWService)客户端。
 * 事务码由反汇编得到：1 setEnable, 2 isEnable, 3 isDefalutAllowed, 4 addWhiteRule,
 * 5 getWhiteRules, 6 addBlackRule, 7 getBlackRules, 8 clearIpHostRules, 9 clearRules,
 * 10 clearAppRules, 11 writeToFile, 12 addAppWhiteRule, 13 addAppBlackRule。
 * 返回 AFWRes{Parcel: StringList data, int operationResult}。
 */
public class FwRules {
    static final String PKG="cn.com.microtrust.firewall";
    static final String ACTION="cn.com.microtrust.firewall.IAFWService";
    static final String IFACE="cn.com.microtrust.firewall.aidl.IAFWService";

    public static class Res{
        public List<String> data=null;
        public boolean ok=false;
        public String err=null;
        public boolean good(){ return data!=null; }
    }

    static Res call(Context ctx,final int code,final String arg,final Integer intArg){
        final Res res=new Res();
        final CountDownLatch latch=new CountDownLatch(1);
        ServiceConnection conn=new ServiceConnection(){
            public void onServiceConnected(ComponentName n,IBinder b){
                Parcel d=null,r=null;
                try{
                    d=Parcel.obtain(); r=Parcel.obtain();
                    d.writeInterfaceToken(IFACE);
                    if(arg!=null) d.writeString(arg);
                    if(intArg!=null) d.writeInt(intArg.intValue());
                    b.transact(code,d,r,0);
                    r.readException();
                    int present=r.readInt();
                    if(present!=0){
                        res.data=r.createStringArrayList();
                        res.ok=(r.readInt()!=0);
                    }
                }catch(Exception e){ res.err=e.toString(); Log.e("FwRules","call "+code+" err",e); }
                finally{
                    try{ if(d!=null) d.recycle(); }catch(Exception e){}
                    try{ if(r!=null) r.recycle(); }catch(Exception e){}
                    try{ ctx.unbindService(this); }catch(Exception e){}
                    latch.countDown();
                }
            }
            public void onServiceDisconnected(ComponentName n){}
            public void onBindingDied(ComponentName n){ latch.countDown(); }
            public void onNullBinding(ComponentName n){ latch.countDown(); }
        };
        try{
            Intent s=new Intent(ACTION); s.setPackage(PKG);
            if(!ctx.bindService(s,conn,Context.BIND_AUTO_CREATE)){ res.err="bind failed"; return res; }
            latch.await(4,TimeUnit.SECONDS);
        }catch(Exception e){ res.err=e.toString(); }
        return res;
    }

    public static Res getBlackRules(Context c){ return call(c,7,null,null); }
    public static Res getWhiteRules(Context c){ return call(c,5,null,null); }
    public static Res addBlackRule(Context c,String rule){ return call(c,6,rule,null); }
    public static Res addWhiteRule(Context c,String rule){ return call(c,4,rule,null); }
    public static Res clearIpHostRules(Context c){ return call(c,8,null,null); }
    public static Res writeToFile(Context c){ return call(c,11,null,null); }
    public static Res setEnable(Context c,boolean on){ return call(c,1,null,on?1:0); }

    public static String join(List<String> l){
        StringBuilder sb=new StringBuilder();
        if(l!=null) for(String x:l){ if(x!=null&&x.length()>0) sb.append(x).append("\n"); }
        return sb.toString();
    }
    public static List<String> split(String s){
        List<String> out=new ArrayList<String>();
        if(s!=null&&s.length()>0){ for(String x:s.split("\n")){ if(x.trim().length()>0) out.add(x.trim()); } }
        return out;
    }
}
