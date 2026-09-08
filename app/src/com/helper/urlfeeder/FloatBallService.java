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
    private static FloatBallService inst;
    // 吸附动画(拖动松手平滑滑向左右边缘)
    private android.animation.ValueAnimator snapAnim=null;
    private int ballSz=0;          // 悬浮球当前像素尺寸
    private TextView ballTv=null;  // 球面图标
    // 收起悬浮菜单(避免遮挡其它界面按钮)
    public static void collapseMenu(){ try{ if(inst!=null) inst.hideMenu(); }catch(Exception e){} }
    private Handler hd=new Handler();

    public static boolean overlayOk(Context c){
        return Build.VERSION.SDK_INT<23 || android.provider.Settings.canDrawOverlays(c);
    }

    public IBinder onBind(Intent i){ return null; }

    public int onStartCommand(Intent i,int f,int s){
        Log.i("FloatBall","onStartCommand ball="+(ball!=null)+" overlayOk="+FloatBallService.overlayOk(this));
        running=true; inst=this;
        try{
            String a=i==null?null:i.getAction();
            if("reload".equals(a)&&ball!=null){
                // 重建菜单(排序/内容变更热生效)
                try{ if(menu!=null){ wm.removeView(menu); menu=null; } }catch(Exception e){}
                menu=newMenuView();
                buildMenu();
                menuLp.height=menuContentHeight();   // 固定像素高,避免WRAP+GONE测量卡在矮高度
                try{ wm.addView(menu,menuLp); }catch(Exception e){}
                Log.i("FloatBall","menu reloaded h="+menuLp.height+" rows="+menu.getChildCount());
                return START_STICKY;
            }
            if("resize".equals(a)&&ball!=null){
                // 悬浮球大小变更(设置里选完即时生效, 保持球心不动)
                try{
                    if(snapAnim!=null){ snapAnim.cancel(); snapAnim=null; }
                    int oldW=Math.max(1,ballLp.width);
                    styleBall();                     // 读取 pref float_size 重做外观
                    ballLp.x+=(oldW-ballSz)/2;       // 保持球心
                    ballLp.y+=(oldW-ballSz)/2;
                    clampBallOnScreen();
                    wm.updateViewLayout(ball,ballLp);
                    Log.i("FloatBall","resize ballSz="+ballSz+" x="+ballLp.x+" y="+ballLp.y);
                }catch(Exception e){ Log.e("FloatBall","resize err",e); }
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

        ballSz=dp(prefBallSize());
        ball=new LinearLayout(this);
        ball.setOrientation(LinearLayout.VERTICAL);
        ball.setGravity(Gravity.CENTER);
        ballTv=new TextView(this);
        ballTv.setText("◉");
        ballTv.setTextColor(0xFFFFFFFF);
        ball.addView(ballTv);
        ballLp=new WindowManager.LayoutParams(
            ballSz,ballSz,
            Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        ballLp.gravity=Gravity.TOP|Gravity.START;
        ballLp.x=w-dp(60); ballLp.y=h-dp(160);
        styleBall();   // 渐变外观 + 字号
        ball.setOnTouchListener(new View.OnTouchListener(){
            float dx,dy,downX,downY;
            public boolean onTouch(View v,android.view.MotionEvent e){
                switch(e.getAction()){
                    case MotionEvent.ACTION_DOWN:
                        if(snapAnim!=null){ snapAnim.cancel(); snapAnim=null; }
                        dx=e.getRawX()-ballLp.x; dy=e.getRawY()-ballLp.y;
                        downX=e.getRawX(); downY=e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        ballLp.x=(int)(e.getRawX()-dx); ballLp.y=(int)(e.getRawY()-dy);
                        clampBallOnScreen();
                        try{ wm.updateViewLayout(ball,ballLp); }catch(Exception ex){}
                        return true;
                    case MotionEvent.ACTION_UP:
                        // 用位移判断: 没真正拖动=点按开关菜单; 拖动过(超过触摸滑动阈值)=吸附到边缘
                        float dist=(float)Math.hypot(e.getRawX()-downX,e.getRawY()-downY);
                        int slop=android.view.ViewConfiguration.get(FloatBallService.this).getScaledTouchSlop();
                        if(dist<slop){ toggleMenu(); Log.i("FloatBall","tap menu dist="+(int)dist); }
                        else { snapBall(); Log.i("FloatBall","drag snap dist="+(int)dist); }
                        return true;
                }
                return false;
            }
        });
        Log.i("FloatBall","add ball x="+ballLp.x+" y="+ballLp.y+" w="+w+" h="+h);
        try{ wm.addView(ball,ballLp); Log.i("FloatBall","ball added OK"); }catch(Exception e){ Log.e("FloatBall","add ball fail",e);}

        // 菜单(初始隐藏, 悬浮球上方)
        menu=newMenuView();
        buildMenu();
        menuLp=new WindowManager.LayoutParams(
            dp(140),WindowManager.LayoutParams.WRAP_CONTENT,
            Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        menuLp.gravity=Gravity.TOP|Gravity.START;
        menuLp.x=ballLp.x+dp(52); menuLp.y=Math.max(dp(40),ballLp.y-dp(42)*6);
        menuLp.height=menuContentHeight();   // 固定像素高,避免WRAP+GONE测量卡在矮高度
        Log.i("FloatBall","createBall menu h="+menuLp.height+" rows="+menu.getChildCount());
        try{ wm.addView(menu,menuLp); Log.i("FloatBall","menu added"); }catch(Exception e){ Log.e("FloatBall","add menu fail",e);}
    }

    // 悬浮球大小(dp), 来自设置 pref float_size, 默认 46
    int prefBallSize(){
        int d=46;
        try{ d=getSharedPreferences("pf",0).getInt("float_size",0); }catch(Exception e){}
        if(d<=0) d=46;
        if(d<28) d=28;
        if(d>96) d=96;
        return d;
    }
    // 渐变玻璃球体: 斜向亮→深渐变 + 白描边
    GradientDrawable makeOrb(){
        GradientDrawable g=new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        g.setOrientation(GradientDrawable.Orientation.TL_BR);
        g.setColors(new int[]{0xFFC7D9FF,0xFF5276DB,0xFF162A63});
        g.setStroke(Math.max(1,dp(1)),0xE6FFFFFF);
        return g;
    }
    // 应用当前 pref 到球体外观(尺寸/渐变/描边/图标字号)
    void styleBall(){
        if(ball==null||ballLp==null) return;
        ballSz=dp(prefBallSize());
        ballLp.width=ballSz; ballLp.height=ballSz;
        if(ballTv!=null){
            ballTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ballSz*0.42f);
            ballTv.setShadowLayer(Math.max(2,ballSz*0.03f),0,Math.max(1,ballSz*0.02f),0xB0000000);
        }
        ball.setBackground(makeOrb());
    }

    // 新建菜单容器(必须纵向+内边距+圆角背景; 遗漏orientation会横排塌成矮窗口)
    LinearLayout newMenuView(){
        LinearLayout m=new LinearLayout(this);
        m.setOrientation(LinearLayout.VERTICAL);
        m.setPadding(dp(6),dp(6),dp(6),dp(6));
        GradientDrawable mbg=new GradientDrawable();
        mbg.setCornerRadius(dp(18));
        mbg.setColor(0xE0223355);
        mbg.setStroke(dp(1),0x66FFFFFF);
        m.setBackground(mbg);
        m.setVisibility(View.GONE);
        return m;
    }

    // 主动测量菜单内容高度(不受窗口未显示/GONE 影响),用于固定窗口高度
    int menuContentHeight(){
        int rows=(menu==null)?0:menu.getChildCount();
        int fb=dp(16)+rows*dp(48);
        try{
            menu.measure(View.MeasureSpec.makeMeasureSpec(dp(140),View.MeasureSpec.EXACTLY),
                         View.MeasureSpec.makeMeasureSpec(0,View.MeasureSpec.UNSPECIFIED));
            int mh=menu.getMeasuredHeight();
            if(mh>dp(8)) return mh;
        }catch(Exception e){}
        return fb;
    }

    // 当前状态栏高度(状态栏显示时 overlay 坐标空间会整体下移这么多)
    int topInsetNow(){
        int t=0;
        try{
            if(Build.VERSION.SDK_INT>=30){
                android.graphics.Insets sy=wm.getCurrentWindowMetrics().getWindowInsets()
                    .getInsets(android.view.WindowInsets.Type.systemBars());
                t=sy.top;
            }
        }catch(Exception e){}
        return t;
    }

    // 悬浮球在屏幕可视区内的合法位置范围 {minX,maxX,minY,maxY}
    int[] ballBounds(){
        int bw=(ballSz>0)?ballSz:dp(46);
        int visH=h-topInsetNow();
        if(visH<dp(100)) visH=h;
        int minY=dp(4), maxY=visH-bw-dp(4);
        if(maxY<minY) maxY=minY;
        return new int[]{0,w-bw,minY,maxY};
    }
    // 拖动过程中把球限制在可视区内(不会拖丢)
    void clampBallOnScreen(){
        int[] bd=ballBounds();
        if(ballLp.x<bd[0]) ballLp.x=bd[0];
        if(ballLp.x>bd[1]) ballLp.x=bd[1];
        if(ballLp.y<bd[2]) ballLp.y=bd[2];
        if(ballLp.y>bd[3]) ballLp.y=bd[3];
    }
    // 松手吸附: 只能停靠左右边缘, 中间松手平滑滑向最近边缘并保持边距
    void snapBall(){
        int bw=(ballSz>0)?ballSz:dp(46);
        int[] bd=ballBounds();
        int cx=ballLp.x+bw/2;
        int margin=dp(12);                       // 与屏幕边缘保持的距离
        int targetX;
        if(cx<w/2) targetX=margin;               // 球心偏左 → 贴左
        else targetX=w-bw-margin;                // 球心偏右 → 贴右
        if(targetX<bd[0]) targetX=bd[0];
        if(targetX>bd[1]) targetX=bd[1];
        int targetY=ballLp.y;
        if(targetY<bd[2]) targetY=bd[2];
        if(targetY>bd[3]) targetY=bd[3];
        if(targetX==ballLp.x&&targetY==ballLp.y) return;
        final int fx=ballLp.x,fy=ballLp.y,tx=targetX,ty=targetY;
        if(snapAnim!=null) snapAnim.cancel();
        snapAnim=android.animation.ValueAnimator.ofInt(fx,tx);
        snapAnim.setDuration(320);
        snapAnim.setInterpolator(new android.view.animation.DecelerateInterpolator(2.2f));
        snapAnim.addUpdateListener(new android.animation.ValueAnimator.AnimatorUpdateListener(){
            public void onAnimationUpdate(android.animation.ValueAnimator a){
                int v=((Integer)a.getAnimatedValue()).intValue();
                float f=a.getAnimatedFraction();
                ballLp.x=v;
                ballLp.y=fy+Math.round((ty-fy)*f);
                try{ wm.updateViewLayout(ball,ballLp); }catch(Exception e){}
            }
        });
        snapAnim.addListener(new android.animation.AnimatorListenerAdapter(){
            public void onAnimationEnd(android.animation.Animator a){ snapAnim=null; Log.i("FloatBall","snap done x="+ballLp.x+" y="+ballLp.y); }
            public void onAnimationCancel(android.animation.Animator a){ snapAnim=null; }
        });
        snapAnim.start();
        Log.i("FloatBall","snap x "+fx+"->"+tx+" y "+fy+"->"+ty);
    }

    // 可排序菜单项 id → 标签/动作 (注意: 不能以 c 开头, c 前缀留给自定义应用 c0..cN)
    String[] ORDER_ID={"back","home","app","recent","sweep","net"};
    void buildMenu(){
        String saved=null;
        try{ saved=getSharedPreferences("pf",0).getString("float_order",null); }catch(Exception e){}
        java.util.List<String> ord=new java.util.ArrayList<String>();
        if(saved!=null&&saved.length()>0){
            String[] arr=saved.split(",");
            for(String x:arr){ if(x!=null&&x.trim().length()>0) ord.add(x.trim()); }
        }
        for(String id:ORDER_ID){ if(!ord.contains(id)) ord.add(id); }
        for(String id:ord){
            if(id.startsWith("c")){
                int n=-1; try{ n=Integer.parseInt(id.substring(1)); }catch(Exception e){}
                String[] c=customItem(n);
                if(c!=null) addMenuItem(c[0],new Runnable(){ public void run(){ hideMenu(); launchApp(c[1]); } });
                continue;
            }
            addActionItem(id);
        }
        addActionItem("close");
        Log.i("FloatBall","buildMenu items="+menu.getChildCount()+" order="+ord.toString());
    }
    // 读自定义项 label|pkg(第n项)
    String[] customItem(int n){
        try{
            String saved=getSharedPreferences("pf",0).getString("float_custom","");
            if(saved==null||saved.length()==0) return null;
            String[] lines=saved.split("\n");
            if(n<0||n>=lines.length) return null;
            String[] f=lines[n].split("\\|",-1);
            if(f.length<2) return null;
            return new String[]{f[0],f[1]};
        }catch(Exception e){ return null; }
    }
    // 启动任意应用(悬浮菜单自定义项)
    void launchApp(String pkg){
        try{
            android.content.pm.PackageManager pm=getPackageManager();
            Intent li=pm.getLaunchIntentForPackage(pkg);
            if(li==null){
                li=new Intent(Intent.ACTION_MAIN); li.addCategory(Intent.CATEGORY_LAUNCHER);
                li.setPackage(pkg);
            }
            li.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(li);
            Log.i("FloatBall","launch "+pkg);
        }catch(Exception e){ Log.e("FloatBall","launch fail "+pkg,e); }
    }
    void addActionItem(String id){
        if("back".equals(id)) addMenuItem("◀ 返回",new Runnable(){public void run(){ shellKey("4"); hideMenu(); }});
        else if("home".equals(id)) addMenuItem("● 主页",new Runnable(){public void run(){ goHome(); hideMenu(); }});
        else if("app".equals(id)) addMenuItem("🧰 打开主界面",new Runnable(){public void run(){ openApp(); hideMenu(); }});
        else if("recent".equals(id)) addMenuItem("▦ 最近",new Runnable(){public void run(){ shellKey("187"); hideMenu(); }});
        else if("sweep".equals(id)) addMenuItem("🧹 清理后台",new Runnable(){public void run(){ hideMenu(); clearBackground(); }});
        else if("net".equals(id)) addMenuItem("🔓 开网",new Runnable(){public void run(){ fireOpenNet(); hideMenu(); }});
        else if("close".equals(id)) addMenuItem("✕ 关闭悬浮球",new Runnable(){public void run(){ hideMenu(); stopBall(); }});
    }
    // 清理后台: 只结束系统允许杀的后台/缓存进程, 不依赖 Shizuku
    void clearBackground(){
        final Context c=this;
        new Thread(new Runnable(){ public void run(){
            try{
                final int n=BgCleaner.clear(c);
                hd.post(new Runnable(){ public void run(){
                    Log.i("FloatBall","clearBackground done n="+n);
                    toast("已清理后台：处理 "+n+" 个应用（当前/系统不杀）");
                }});
            }catch(Exception e){}
        }}).start();
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
            int mh=menuLp.height;                 // 窗口实际固定高度
            if(mh<=0) mh=menu.getChildCount()*dp(48)+dp(16);   // 兜底估算
            // 状态栏显示时 overlay 坐标空间会被整体下移 topInset,
            // 可视区高度 = 屏高 - topInset, 否则贴底会把菜单底部推出屏幕
            int topInset=topInsetNow();
            int visH=h-topInset;
            if(visH<dp(100)) visH=h;
            if(mh>visH-dp(20)) mh=visH-dp(20);
            int gx=ballLp.x+((ballSz>0)?ballSz:dp(46))+dp(8);   // 默认放球右侧
            int gy=ballLp.y+ballLp.height/2-mh/2;
            // 右侧放不下放左侧
            if(gx+mw>w-dp(8)) gx=ballLp.x-dp(8)-mw;
            if(gy<mh/2+dp(8)) gy=dp(8);                    // 顶部裁剪则贴顶
            if(gy+mh>visH-dp(8)) gy=visH-dp(8)-mh;         // 底部裁剪则贴底(可视区内)
            menuLp.x=gx; menuLp.y=gy;
            Log.i("FloatBall","showMenu mh="+mh+" topInset="+topInset+" gy="+gy+" visH="+visH);
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
        if(snapAnim!=null){ try{ snapAnim.cancel(); }catch(Exception e){} snapAnim=null; }
        if(inst==this) inst=null;
        super.onDestroy();
    }
    int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
}
