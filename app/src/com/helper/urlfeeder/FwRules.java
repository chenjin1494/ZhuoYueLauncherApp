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
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final java.util.concurrent.ExecutorService TRANSACTIONS=
            java.util.concurrent.Executors.newSingleThreadExecutor(new java.util.concurrent.ThreadFactory(){
                public Thread newThread(Runnable runnable){ Thread thread=new Thread(runnable,"firewall-binder"); thread.setDaemon(true); return thread; }
            });

    public static class Res{
        public List<String> data=null;
        public boolean ok=false;
        public boolean transactReturned=false;
        public int present=0;
        public String err=null;
        public boolean good(){ return data!=null; }
        /** 写操作成功必须同时有 AFWRes 数据且 operationResult=true。 */
        public boolean applied(){ return data!=null&&ok; }
    }

    static Res call(final Context ctx,final int code,final String arg,final Integer intArg){
        final Res res=new Res();
        final OperationLog.Span span=OperationLog.begin("FIREWALL","BINDER_"+operationName(code),
                "transactionCode="+code+" arg="+arg+" intArg="+intArg);
        final CountDownLatch latch=new CountDownLatch(1);
        final AtomicInteger state=new AtomicInteger(0); // 0 waiting, 1 queued, 2 transacting, 3 finished, 4 canceled, 5 indeterminate timeout
        final AtomicBoolean bound=new AtomicBoolean(false);
        ServiceConnection conn=new ServiceConnection(){
            public void onServiceConnected(final ComponentName n,final IBinder b){
                if(!state.compareAndSet(0,1)){
                    safeUnbind(ctx,this,bound); latch.countDown(); return;
                }
                final ServiceConnection self=this;
                try{
                    TRANSACTIONS.execute(new Runnable(){ public void run(){
                        if(!state.compareAndSet(1,2)){
                            safeUnbind(ctx,self,bound); latch.countDown(); return;
                        }
                        Res local=transact(b,code,arg,intArg);
                        if(state.compareAndSet(2,3)) copy(local,res);
                        else if(state.compareAndSet(5,3)){
                            OperationLog.event("FIREWALL","LATE_TRANSACTION_"+operationName(code),
                                    transactionSuccess(code,local)?"LATE_OK":"LATE_FAIL",detail(local));
                        }
                        safeUnbind(ctx,self,bound); latch.countDown();
                    }});
                }catch(Exception error){
                    state.set(4); res.err=OperationLog.stack(error); safeUnbind(ctx,self,bound); latch.countDown();
                }
            }
            public void onServiceDisconnected(ComponentName n){ terminal("service disconnected",this); }
            public void onBindingDied(ComponentName n){ terminal("binding died",this); }
            public void onNullBinding(ComponentName n){ terminal("null binding",this); }
            private void terminal(String error,ServiceConnection self){
                if(state.compareAndSet(0,3)||state.compareAndSet(1,4)){
                    res.err=error; safeUnbind(ctx,self,bound); latch.countDown();
                }else if(state.get()==2||state.get()==5){
                    OperationLog.event("FIREWALL","BINDER_CONNECTION_LOST","WARN",
                            "operation="+operationName(code)+" state="+state.get()+" error="+error);
                }
            }
        };
        if(android.os.Looper.myLooper()==android.os.Looper.getMainLooper()){
            res.err="firewall binder calls must run off the main thread";
            OperationLog.fail(span,res.err);
            return res;
        }
        boolean interrupted=false;
        try{
            Intent service=new Intent(ACTION); service.setPackage(PKG);
            boolean accepted=ctx.bindService(service,conn,Context.BIND_AUTO_CREATE);
            if(!accepted){ state.set(3); res.err="bindService returned false"; }
            else {
                bound.set(true);
                if(state.get()==3||state.get()==4) safeUnbind(ctx,conn,bound);
                boolean completed=false;
                try{ completed=latch.await(4,TimeUnit.SECONDS); }
                catch(InterruptedException e){ interrupted=true; }
                if(!completed){
                    if(state.compareAndSet(0,4)){
                        res.err=interrupted?"interrupted before connection":"timeout waiting for service connection";
                        safeUnbind(ctx,conn,bound);
                    }else if(state.compareAndSet(1,4)){
                        res.err=interrupted?"interrupted while transaction queued":"timeout while transaction queued; canceled before transact";
                        safeUnbind(ctx,conn,bound);
                    }else if(state.compareAndSet(2,5)){
                        res.err=interrupted?"interrupted during in-flight binder transaction; outcome indeterminate"
                                :"binder transaction exceeded 4 seconds; outcome indeterminate and later mutations remain serialized";
                    }
                }
            }
        }catch(Exception e){
            if(state.compareAndSet(0,4)||state.compareAndSet(1,4)) res.err=OperationLog.stack(e);
            safeUnbind(ctx,conn,bound);
        }
        if(interrupted) Thread.currentThread().interrupt();
        boolean success=transactionSuccess(code,res);
        String detail=detail(res);
        if(success&&res.err==null) OperationLog.ok(span,detail);
        else if(state.get()==5) OperationLog.result(span,"INDETERMINATE_TIMEOUT",detail);
        else OperationLog.fail(span,detail);
        return res;
    }

    private static Res transact(IBinder binder,int code,String arg,Integer intArg){
        Res result=new Res();
        Parcel data=null,reply=null;
        try{
            data=Parcel.obtain(); reply=Parcel.obtain(); data.writeInterfaceToken(IFACE);
            if(arg!=null) data.writeString(arg);
            if(intArg!=null) data.writeInt(intArg.intValue());
            result.transactReturned=binder.transact(code,data,reply,0);
            if(!result.transactReturned){ result.err="binder transact returned false"; return result; }
            reply.readException(); result.present=reply.readInt();
            if(result.present!=0){ result.data=reply.createStringArrayList(); result.ok=reply.readInt()!=0; }
            else result.err="response parcel missing";
        }catch(Exception error){ result.err=OperationLog.stack(error); Log.e("FwRules","call "+code+" err",error); }
        finally{
            try{ if(data!=null) data.recycle(); }catch(Exception ignored){}
            try{ if(reply!=null) reply.recycle(); }catch(Exception ignored){}
        }
        return result;
    }

    private static void copy(Res from,Res to){
        to.data=from.data; to.ok=from.ok; to.transactReturned=from.transactReturned;
        to.present=from.present; to.err=from.err;
    }

    private static boolean transactionSuccess(int code,Res result){
        return result!=null&&(isWrite(code)?result.applied():result.good());
    }

    private static String detail(Res result){
        return "transactReturned="+result.transactReturned+" present="+result.present
                +" operationResult="+result.ok+" error="+result.err+" data="+result.data;
    }

    private static void safeUnbind(Context context,ServiceConnection connection,AtomicBoolean bound){
        if(bound.compareAndSet(true,false)) try{ context.unbindService(connection); }catch(Exception ignored){}
    }

    static boolean isWrite(int code){ return code==1||code==4||code==6||(code>=8&&code<=13); }

    static String operationName(int code){
        switch(code){
            case 1:return "SET_ENABLE"; case 2:return "IS_ENABLE"; case 3:return "IS_DEFAULT_ALLOWED";
            case 4:return "ADD_WHITE_RULE"; case 5:return "GET_WHITE_RULES"; case 6:return "ADD_BLACK_RULE";
            case 7:return "GET_BLACK_RULES"; case 8:return "CLEAR_IP_HOST_RULES"; case 9:return "CLEAR_RULES";
            case 10:return "CLEAR_APP_RULES"; case 11:return "WRITE_TO_FILE";
            case 12:return "ADD_APP_WHITE_RULE"; case 13:return "ADD_APP_BLACK_RULE"; default:return "CALL";
        }
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
