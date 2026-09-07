package com.helper.urlfeeder;

import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮球导航：提供 返回/主页/最近任务/一键开网 快捷操作，
 * 不依赖系统导航栏与无障碍（适配通知栏被禁、手势条失效的设备）。
 */
public class FloatBallService extends Service {
    private WindowManager wm;
    private WindowManager.LayoutParams ballLp;
    private LinearLayout ball;
    private LinearLayout menu;
    private WindowManager.LayoutParams menuLp;
    private boolean menuVisible=false;
    private int w,h; // 屏幕
    public static volatile boolean running=false;
    private Handler hd=new Handler();

    public static boolean overlayOk(Context c){
        return Build.VERSION.SDK_INT<23 || android.provider.Settings.canDrawOverlays(c);
    }

    public IBinder onBind(Intent i){ return null; }

    public int onStartCommand(Intent i,int f,int s){
        Log.i("FloatBall","onStartCommand ball="+(ball!=null)+" overlayOk="+FloatBallService.overlayOk(this));
        running=true;
        try{
            String a=i==null?null:i.getAction();
            if("reload".equals(a)&&ball!=null){
                // 重建菜单(排序/内容变更热生效)
                try{ if(menu!=null){ wm.removeView(menu); menu=null; } }catch(Exception e){}
                menu=new LinearLayout(this); menu.setVisibility(View.GONE);
                buildMenu();
                try{ wm.addView(menu,menuLp); }catch(Exception e){}
                Log.i("FloatBall","menu reloaded");
                return START_STICKY;
            }
            if(ball==null) createBall();
            Log.i("FloatBall","after createBall ball="+(ball!=null));
        }catch(Exception e){ Log.e("FloatBall","start err",e); stopSelf(); }
        return START_STICKY;
    }

    void createBall(){
        wm=(WindowManager)getSystemService(WINDOW_SERVICE);
        w=wm.getDefaultDisplay().getWidth(); h=wm.getDefaultDisplay().getHeight();

        ball=new LinearLayout(this);
        ball.setOrientation(LinearLayout.VERTICAL);
        ball.setGravity(Gravity.CENTER);
        GradientDrawable bg=new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xCC223355);
        bg.setStroke(dp(1),0x99FFFFFF);
        ball.setBackground(bg);
        TextView tv=new TextView(this);
        tv.setText("◉");
        tv.setTextColor(0xFFFFFFFF); tv.setTextSize(18);
        ball.addView(tv);
        ballLp=new WindowManager.LayoutParams(
            dp(46),dp(46),
            Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        ballLp.gravity=Gravity.TOP|Gravity.START;
        ballLp.x=w-dp(60); ballLp.y=h-dp(160);
        ball.setOnTouchListener(new View.OnTouchListener(){
            float dx,dy; long downT;
            public boolean onTouch(View v,android.view.MotionEvent e){
                switch(e.getAction()){
                    case MotionEvent.ACTION_DOWN: dx=e.getRawX()-ballLp.x; dy=e.getRawY()-ballLp.y; downT=System.currentTimeMillis(); return true;
                    case MotionEvent.ACTION_MOVE:
                        ballLp.x=(int)(e.getRawX()-dx); ballLp.y=(int)(e.getRawY()-dy);
                        try{ wm.updateViewLayout(ball,ballLp); }catch(Exception ex){}
                        return true;
                    case MotionEvent.ACTION_UP:
                        if(System.currentTimeMillis()-downT<250) toggleMenu();
                        return true;
                }
                return false;
            }
        });
        Log.i("FloatBall","add ball x="+ballLp.x+" y="+ballLp.y+" w="+w+" h="+h);
        try{ wm.addView(ball,ballLp); Log.i("FloatBall","ball added OK"); }catch(Exception e){ Log.e("FloatBall","add ball fail",e);}

        // 菜单(初始隐藏, 悬浮球上方)
        menu=new LinearLayout(this);
        menu.setOrientation(LinearLayout.VERTICAL);
        menu.setPadding(dp(6),dp(6),dp(6),dp(6));
        GradientDrawable mbg=new GradientDrawable();
        mbg.setCornerRadius(dp(18));
        mbg.setColor(0xE0223355);
        mbg.setStroke(dp(1),0x66FFFFFF);
        menu.setBackground(mbg);
        menu.setVisibility(View.GONE);
        buildMenu();
        menuLp=new WindowManager.LayoutParams(
            dp(140),WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        menuLp.gravity=Gravity.TOP|Gravity.START;
        menuLp.x=ballLp.x+dp(52); menuLp.y=Math.max(dp(40),ballLp.y-dp(42)*6);
        try{ wm.addView(menu,menuLp); Log.i("FloatBall","menu added"); }catch(Exception e){ Log.e("FloatBall","add menu fail",e);}
    }

    // 可排序菜单项 id → 标签/动作
    String[] ORDER_ID={"back","home","app","recent","net"};
    void buildMenu(){
        String saved=null;
        try{ saved=getSharedPreferences("pf",0).getString("float_order",null); }catch(Exception e){}
        java.util.List<String> ord=new java.util.ArrayList<String>();
        if(saved!=null&&saved.length()>0){
            String[] arr=saved.split(",");
            for(String x:arr){ if(x!=null&&x.trim().length()>0) ord.add(x.trim()); }
        }
        for(String id:ORDER_ID){ if(!ord.contains(id)) ord.add(id); }
        for(String id:ord){ addActionItem(id); }
        addActionItem("close");
    }
    void addActionItem(String id){
        if("back".equals(id)) addMenuItem("◀ 返回",new Runnable(){public void run(){ shellKey("4"); hideMenu(); }});
        else if("home".equals(id)) addMenuItem("● 主页",new Runnable(){public void run(){ goHome(); hideMenu(); }});
        else if("app".equals(id)) addMenuItem("🧰 打开主界面",new Runnable(){public void run(){ openApp(); hideMenu(); }});
        else if("recent".equals(id)) addMenuItem("▦ 最近",new Runnable(){public void run(){ shellKey("187"); hideMenu(); }});
        else if("net".equals(id)) addMenuItem("🔓 开网",new Runnable(){public void run(){ fireOpenNet(); hideMenu(); }});
        else if("close".equals(id)) addMenuItem("✕ 关闭悬浮球",new Runnable(){public void run(){ hideMenu(); stopBall(); }});
    }
    void addMenuItem(String t,final Runnable act){
        Button b=new Button(this);
        b.setText(t); b.setTextColor(0xFFFFFFFF); b.setTextSize(13); b.setAllCaps(false);
        b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
        b.setPadding(dp(10),dp(8),dp(10),dp(8));
        b.setBackgroundResource(android.R.drawable.list_selector_background);
        b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){ act.run(); }});
        menu.addView(b,new LinearLayout.LayoutParams(-1,-2));
    }

    void toggleMenu(){
        menuVisible=!menuVisible;
        showMenu(menuVisible);
    }
    void showMenu(boolean show){
        if(menu==null) return;
        menu.setVisibility(show?View.VISIBLE:View.GONE);
        if(show){
            int mw=dp(140);
            int mh=menu.getChildCount()*dp(48)+dp(16);   // 估算菜单高度
            if(mh>h-dp(20)) mh=h-dp(20);
            int gx=ballLp.x+dp(46)+dp(8);                 // 默认放球右侧
            int gy=ballLp.y+ballLp.height/2-mh/2;
            // 右侧放不下放左侧
            if(gx+mw>w-dp(8)) gx=ballLp.x-dp(8)-mw;
            if(gy<mh/2+dp(8)) gy=dp(8);                    // 顶部裁剪则贴顶
            if(gy+mh>h-dp(8)) gy=h-dp(8)-mh;               // 底部裁剪则贴底
            menuLp.x=gx; menuLp.y=gy;
            try{ wm.updateViewLayout(menu,menuLp); }catch(Exception e){}
        }
    }
    void hideMenu(){ if(menuVisible){ menuVisible=false; showMenu(false);} }

    void openApp(){
        // 打开万能转发器主界面(回到首页)
        try{
            Intent a=new Intent(this,MainActivity.class);
            a.setAction("com.helper.urlfeeder.action.OPEN_MAIN");
            a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED|Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(a);
            Log.i("FloatBall","openApp sent");
        }catch(Exception e){ Log.e("FloatBall","openApp fail",e);}
    }
    void goHome(){
        // 主页：直接启动 Lawnchair（卓越Launcher 作为 HOME 时会关 ADB，必须绕开它）
        try{
            Intent l=new Intent(Intent.ACTION_MAIN);
            l.addCategory(Intent.CATEGORY_HOME);
            l.setComponent(new android.content.ComponentName("app.lawnchair","app.lawnchair.LawnchairLauncher"));
            l.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(l);
            Log.i("FloatBall","goHome->lawnchair sent");
        }catch(Exception e){
            Log.e("FloatBall","goHome lawnchair fail",e);
            try{
                Intent h=new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
                h.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                startActivity(h);
            }catch(Exception e2){}
        }
    }
    void shellKey(String code){
        // 返回/最近：普通App无法注入按键, 走 Shizuku(shell)执行 input keyevent
        try{
            if(!ShizukuUtil.running()){ toast("返回/最近需 Shizuku 授权"); return; }
            if(ShizukuUtil.permission()!=0){ toast("请先授权 Shizuku"); return; }
            final String c=code;
            new Thread(new Runnable(){ public void run(){ try{ ShizukuUtil.cmd("input","keyevent",c); }catch(Exception e){} }}).start();
        }catch(Exception e){}
    }
    void toast(String m){
        try{ android.widget.Toast.makeText(this,m,android.widget.Toast.LENGTH_SHORT).show(); }catch(Exception e){}
    }
    void fireOpenNet(){
        try{
            Intent s=new Intent("cn.com.microtrust.firewall.IAFWService");
            s.setPackage("cn.com.microtrust.firewall");
            bindService(s,new android.content.ServiceConnection(){
                public void onServiceConnected(android.content.ComponentName n,IBinder b){
                    try{
                        android.os.Parcel d=android.os.Parcel.obtain(),r=android.os.Parcel.obtain();
                        for(int code:new int[]{9,10}){
                            d=android.os.Parcel.obtain(); r=android.os.Parcel.obtain();
                            d.writeInterfaceToken("cn.com.microtrust.firewall.aidl.IAFWService");
                            b.transact(code,d,r,0); r.readException();
                            d.recycle(); r.recycle();
                        }
                    }catch(Exception e){}
                    try{ unbindService(this); }catch(Exception e){}
                }
                public void onServiceDisconnected(android.content.ComponentName n){}
                public void onBindingDied(android.content.ComponentName n){}
                public void onNullBinding(android.content.ComponentName n){}
            },Context.BIND_AUTO_CREATE);
        }catch(Exception e){}
    }
    void stopBall(){
        running=false;
        try{ getSharedPreferences("pf",0).edit().putBoolean("float_on",false).commit(); }catch(Exception e){}
        try{ if(ball!=null){ wm.removeView(ball); ball=null; } }catch(Exception e){}
        try{ if(menu!=null){ wm.removeView(menu); menu=null; } }catch(Exception e){}
        stopSelf();
    }
    public void onDestroy(){
        running=false;
        super.onDestroy();
    }
    int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
}
