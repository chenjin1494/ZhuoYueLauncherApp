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
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 悬浮球导航：点悬浮球 → 屏幕中央弹出半透明圆形轮盘(类似 iOS 辅助触控)，
 * 功能项围绕一圈、中心 ✕ 收起；球本体吸附屏幕左右边缘可拖动。
 */
public class FloatBallService extends Service {
    private WindowManager wm;
    private WindowManager.LayoutParams ballLp;
    private LinearLayout ball;
    private FrameLayout menu;              // 中央圆形轮盘容器
    private WindowManager.LayoutParams menuLp;
    private boolean menuVisible=false;
    private int w,h; // 屏幕
    public static volatile boolean running=false;
    private static FloatBallService inst;
    // 吸附动画(拖动松手平滑滑向左右边缘)
    private android.animation.ValueAnimator snapAnim=null;
    private int ballSz=0;          // 悬浮球当前像素尺寸
    private TextView ballTv=null;  // 球面图标
    private long lastBallDown=0;   // 最近一次按住悬浮球的时刻(用于忽略点球瞬间的"外部收起")
    private long lastHide=0;       // 最近一次收起菜单的时刻(抑制外部收起与点球同一手势的重开)
    private Runnable longPressShot=null; // 悬浮球长按回调(清理后台)
    private Runnable pendingSingleTap=null;
    private long lastTapUp=0;
    private static final int LONG_PRESS_MS=520;
    private static final int DOUBLE_TAP_MS=280;
    private int subMode=0;         // 二级圆盘内容: 0=我的应用, 1=快捷动作
    private boolean subVisible=false;     // 二级"应用"菜单是否显示
    private boolean transitioning=false;  // 一二级切换动画进行中(防止重复触发)
    private FrameLayout sub=null;     // 二级"应用"圆盘窗口
    private WindowManager.LayoutParams subLp;
    private LinearLayout scrim=null;      // 透明拦截屏(盘外点击只收起, 不误触下层)
    private WindowManager.LayoutParams scrimLp;
    private int lastVisH=0;               // 上次方向下的可视高度(旋转时按比例映射球的位置)
    private android.hardware.display.DisplayManager dmgr=null;
    private android.hardware.display.DisplayManager.DisplayListener dlistener=null;
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
                // 重建轮盘(排序/内容变更热生效)
                try{ if(menu!=null){ wm.removeView(menu); menu=null; } }catch(Exception e){}
                menu=newMenuView();
                buildMenu();
                try{ wm.addView(menu,menuLp); }catch(Exception e){}
                Log.i("FloatBall","wheel reloaded");
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
        refreshScreen();
        registerDisplayListener();

        ballSz=dp(prefBallSize());
        ball=new LinearLayout(this);
        ball.setOrientation(LinearLayout.VERTICAL);
        ball.setGravity(Gravity.CENTER);
        ballTv=new TextView(this);
        ballTv.setText("◆");
        ballTv.setTextColor(0xFFFFFFFF);
        ball.addView(ballTv);
        ballLp=new WindowManager.LayoutParams(
            ballSz,ballSz,
            Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT);
        ballLp.gravity=Gravity.TOP|Gravity.START;
        ballLp.x=w-dp(60); ballLp.y=h-dp(160);
        // 记忆停靠位置: 优先还原到上次的屏幕边缘+高度
        try{
            android.content.SharedPreferences pp=getSharedPreferences("pf",0);
            if(pp.contains("float_edge")){
                int edge=pp.getInt("float_edge",1);
                int margin=dp(12);
                int[] bd=ballBounds();
                ballLp.x=(edge==0)?Math.max(bd[0],margin):Math.max(bd[0],w-ballSz-margin);
                int sy=pp.getInt("float_y",ballLp.y);
                if(sy<bd[2]) sy=bd[2]; if(sy>bd[3]) sy=bd[3];
                ballLp.y=sy;
                Log.i("FloatBall","restore pos edge="+edge+" x="+ballLp.x+" y="+ballLp.y);
            }
        }catch(Exception e){}
        styleBall();   // 渐变外观 + 字号
        ball.setOnTouchListener(new View.OnTouchListener(){
            float dx,dy,downX,downY;
            boolean downMenuVis,longFired,secondTap;
            public boolean onTouch(View v,android.view.MotionEvent e){
                switch(e.getAction()){
                    case MotionEvent.ACTION_DOWN:
                        if(snapAnim!=null){ snapAnim.cancel(); snapAnim=null; }
                        lastBallDown=System.currentTimeMillis();
                        dx=e.getRawX()-ballLp.x; dy=e.getRawY()-ballLp.y;
                        downX=e.getRawX(); downY=e.getRawY();
                        downMenuVis=menuVisible;
                        longFired=false;
                        secondTap=pendingSingleTap!=null && System.currentTimeMillis()-lastTapUp<=DOUBLE_TAP_MS;
                        if(secondTap) hd.removeCallbacks(pendingSingleTap);
                        cancelLongPressShot();
                        longPressShot=new Runnable(){ public void run(){
                            longFired=true; longPressShot=null;
                            if(pendingSingleTap!=null){ hd.removeCallbacks(pendingSingleTap); pendingSingleTap=null; }
                            lastTapUp=0;
                            Log.i("FloatBall","ball long-press -> clear background");
                            clearBackground();
                        }};
                        ball.postDelayed(longPressShot,LONG_PRESS_MS);
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        ballLp.x=(int)(e.getRawX()-dx); ballLp.y=(int)(e.getRawY()-dy);
                        clampBallOnScreen();
                        try{ wm.updateViewLayout(ball,ballLp); }catch(Exception ex){}
                        float md=(float)Math.hypot(e.getRawX()-downX,e.getRawY()-downY);
                        if(md>android.view.ViewConfiguration.get(FloatBallService.this).getScaledTouchSlop()) cancelLongPressShot();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        cancelLongPressShot();
                        if(secondTap){ pendingSingleTap=null; lastTapUp=0; }
                        try{ if(ball!=null) ball.setAlpha(1f); }catch(Exception ex){}
                        return true;
                    case MotionEvent.ACTION_UP:
                        cancelLongPressShot();
                        if(longFired){ try{ if(ball!=null) ball.setAlpha(1f); }catch(Exception ex){} return true; }
                        float dist=(float)Math.hypot(e.getRawX()-downX,e.getRawY()-downY);
                        int slop=android.view.ViewConfiguration.get(FloatBallService.this).getScaledTouchSlop();
                        if(dist<slop){
                            if(secondTap){
                                pendingSingleTap=null; lastTapUp=0;
                                Log.i("FloatBall","ball double-tap -> shot");
                                captureScreen();
                            }else{
                                final boolean wasSub=subVisible, wasMenu=downMenuVis;
                                lastTapUp=System.currentTimeMillis();
                                pendingSingleTap=new Runnable(){ public void run(){
                                    pendingSingleTap=null;
                                    if(wasSub){ hideSubNow(); Log.i("FloatBall","tap close apps sub"); }
                                    else if(wasMenu){ hideMenu(); Log.i("FloatBall","tap close menu"); }
                                    else if(!menuVisible && System.currentTimeMillis()-lastHide>=120){
                                        menuVisible=true; showMenu(true); Log.i("FloatBall","tap open menu");
                                    }
                                }};
                                hd.postDelayed(pendingSingleTap,DOUBLE_TAP_MS);
                            }
                        }else{
                            if(secondTap){ pendingSingleTap=null; lastTapUp=0; }
                            snapBall(); Log.i("FloatBall","drag snap dist="+(int)dist);
                        }
                        return true;
                }
                return false;
            }
        });
        Log.i("FloatBall","add ball x="+ballLp.x+" y="+ballLp.y+" w="+w+" h="+h);
        try{ wm.addView(ball,ballLp); Log.i("FloatBall","ball added OK"); }catch(Exception e){ Log.e("FloatBall","add ball fail",e);}

        // 透明拦截屏: 放在球之上、盘之下 → 盘外点击只收起菜单, 不会误触到下层应用按钮
        try{
            scrim=new LinearLayout(this);
            scrim.setBackgroundColor(0x26000000);
            scrimLp=new WindowManager.LayoutParams(
                w,h,
                Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                PixelFormat.TRANSLUCENT);
            scrimLp.gravity=Gravity.TOP|Gravity.START;
            scrimLp.x=0; scrimLp.y=0;
            scrim.setVisibility(View.GONE);
            scrim.setOnTouchListener(new View.OnTouchListener(){
                public boolean onTouch(View v,android.view.MotionEvent e){
                    Log.i("FloatBall","scrim tap -> hide");
                    hideMenu();
                    return true;
                }
            });
            wm.addView(scrim,scrimLp);
            Log.i("FloatBall","scrim added");
        }catch(Exception e){ Log.e("FloatBall","add scrim fail",e);}

        // 中央圆形轮盘(初始隐藏, 屏幕居中)
        int dq=discSize();
        menu=newMenuView();
        buildMenu();
        menuLp=new WindowManager.LayoutParams(
            dq,dq,
            Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
            PixelFormat.TRANSLUCENT);
        menuLp.gravity=Gravity.TOP|Gravity.START;
        menuLp.x=discCenterX(dq); menuLp.y=discCenterY(dq);
        Log.i("FloatBall","createBall disc="+dq+" items="+menu.getChildCount());
        try{ wm.addView(menu,menuLp); Log.i("FloatBall","wheel added"); }catch(Exception e){ Log.e("FloatBall","add wheel fail",e);}
    }

    // 重新读取当前方向下的屏幕尺寸(旋转后必须刷新, 否则球/轮盘会跑到屏幕外)
    void refreshScreen(){
        try{
            android.util.DisplayMetrics dm=new android.util.DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(dm);
            if(dm.widthPixels>0) w=dm.widthPixels;
            if(dm.heightPixels>0) h=dm.heightPixels;
            int inset=topInsetNow();
            int vh=h-inset;
            if(vh<dp(80)) vh=h;
            lastVisH=vh;
        }catch(Exception e){}
    }
    // 保存停靠(边+高度), 供旋转后与重启后还原
    void saveBallPos(int edge,int y){
        try{ getSharedPreferences("pf",0).edit().putInt("float_edge",edge).putInt("float_y",y).commit(); }catch(Exception e){}
    }
    // 旋转/尺寸变化: 同侧重停靠 + 纵向按比例映射, 并把显示中的窗口重新居中
    void reanchorForRotation(){
        try{
            int oldVisH=lastVisH;
            int oldW=w, oldH=h;
            refreshScreen();
            if(w==oldW&&h==oldH) return;            // 尺寸没变, 不用处理
            if(ball!=null){
                int bw=(ballSz>0)?ballSz:dp(46);
                int margin=dp(12);
                int edge=(ballLp.x+bw/2<oldW/2)?0:1;      // 用旋转前位置判断原停靠边
                float ratio=0.5f;
                if(oldVisH>bw) ratio=(ballLp.y-dp(4))/(float)(oldVisH-bw);
                ratio=Math.max(0f,Math.min(1f,ratio));
                int[] bd=ballBounds();
                int ny=bd[2]+Math.round(ratio*(bd[3]-bd[2]));
                ballLp.x=(edge==0)?Math.max(bd[0],margin):Math.max(bd[0],w-bw-margin);
                ballLp.y=Math.max(bd[2],Math.min(bd[3],ny));
                try{ wm.updateViewLayout(ball,ballLp); }catch(Exception e){}
                saveBallPos(edge,ballLp.y);
                Log.i("FloatBall","reanchor w="+w+" h="+h+" ball x="+ballLp.x+" y="+ballLp.y);
            }
            int dq=discSize();
            if(menuVisible&&menu!=null){ menuLp.x=discCenterX(dq); menuLp.y=discCenterY(dq); try{ wm.updateViewLayout(menu,menuLp);}catch(Exception e){} }
            if(subVisible&&sub!=null){ subLp.x=discCenterX(dq); subLp.y=discCenterY(dq); try{ wm.updateViewLayout(sub,subLp);}catch(Exception e){} }
            if(scrim!=null){ scrimLp.width=w; scrimLp.height=h; scrimLp.x=0; scrimLp.y=0; try{ wm.updateViewLayout(scrim,scrimLp);}catch(Exception e){} }
        }catch(Exception e){}
    }
    // 注册显示变化监听(旋转时 Service 的 onConfigurationChanged 不一定回调, 用它更可靠)
    void registerDisplayListener(){
        try{
            if(dmgr!=null||wm==null) return;
            dmgr=(android.hardware.display.DisplayManager)getSystemService(DISPLAY_SERVICE);
            if(dmgr==null) return;
            dlistener=new android.hardware.display.DisplayManager.DisplayListener(){
                public void onDisplayAdded(int id){}
                public void onDisplayRemoved(int id){}
                public void onDisplayChanged(int id){ if(id==0) reanchorForRotation(); }
            };
            dmgr.registerDisplayListener(dlistener,hd);
            Log.i("FloatBall","display listener registered");
        }catch(Exception e){}
    }
    public void onConfigurationChanged(android.content.res.Configuration c){
        super.onConfigurationChanged(c);
        reanchorForRotation();
    }
    // 轮盘居中(用与悬浮球相同的布局坐标空间: 该空间从状态栏下沿开始)
    int discCenterX(int dq){ return Math.max(0,(w-dq)/2); }
    int discCenterY(int dq){
        int inset=topInsetNow();
        int visH=h-inset;
        if(visH<dq+dp(8)) visH=h;
        return Math.max(0,(visH-dq)/2);
    }
    // 轮盘直径(px)
    int discSize(){ return dp(330); }

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
        g.setColors(new int[]{0xFF44635E,0xFF1E3D39,0xFF0C1919});
        g.setStroke(Math.max(1,dp(1)),0xCC74E0C2);
        return g;
    }
    // 应用当前 pref 到球体外观(尺寸/渐变/描边/图标字号)
    void styleBall(){
        if(ball==null||ballLp==null) return;
        ballSz=dp(prefBallSize());
        ballLp.width=ballSz; ballLp.height=ballSz;
        if(ballTv!=null){
            ballTv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,ballSz*0.42f);
            ballTv.setShadowLayer(Math.max(1,ballSz*0.02f),0,Math.max(1,ballSz*0.015f),0x80000000);
        }
        ball.setBackground(makeOrb());
        if(Build.VERSION.SDK_INT>=21) ball.setElevation(dp(6));
    }

    GradientDrawable makeDiscBackground(){
        GradientDrawable bg=new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setColor(0xEB11191B);
        bg.setStroke(Math.max(1,dp(1)),0x88516E70);
        return bg;
    }

    // 新建中央圆盘容器: 稳定高对比石墨圆盘
    FrameLayout newMenuView(){
        FrameLayout m=new FrameLayout(this);
        m.setBackground(makeDiscBackground());
        m.setVisibility(View.GONE);
        // 点圆盘以外自动收起
        m.setOnTouchListener(new View.OnTouchListener(){
            public boolean onTouch(View v,android.view.MotionEvent e){
                int act=e.getAction();
                if(act==MotionEvent.ACTION_OUTSIDE){
                    if(System.currentTimeMillis()-lastBallDown<400){
                        Log.i("FloatBall","outside while ball pressed - ignore");
                        return true;   // 这次外部事件来自点悬浮球, 交给球处理
                    }
                    Log.i("FloatBall","outside touch -> hide");
                    hideMenu();
                    return true;
                }
                if(act==MotionEvent.ACTION_DOWN){
                    // 窗口是正方形、圆盘内切: 点进四角(圆外)等同点外面 → 收起
                    int dq=discSize(); float cx=dq/2f, cy=cx;
                    float dx=e.getX()-cx, dy=e.getY()-cy;
                    if(dx*dx+dy*dy > (cx-dp(2))*(cx-dp(2))){
                        Log.i("FloatBall","corner tap -> hide");
                        hideMenu();
                        return true;
                    }
                    return true;   // 圆内空白处: 吃掉事件即可(不收起)
                }
                return false;
            }
        });
        return m;
    }

    // ---------------- 轮盘渲染 ----------------
    // 可排序菜单项 id → 标签/动作 (注意: 不能以 c 开头, c 前缀留给自定义应用 c0..cN)
    String[] ORDER_ID={"home","app","sweep","shot","net"};   // 返回/最近已移除
    void buildMenu(){
        String saved=null;
        try{ saved=getSharedPreferences("pf",0).getString("float_order",null); }catch(Exception e){}
        java.util.List<String> ord=new java.util.ArrayList<String>();
        if(saved!=null&&saved.length()>0){
            String[] arr=saved.split(",");
            for(String x:arr){ if(x!=null&&x.trim().length()>0) ord.add(x.trim()); }
        }
        for(String id:ORDER_ID){ if(!ord.contains(id)) ord.add(id); }
        final java.util.List<String> labs=new java.util.ArrayList<String>();
        final java.util.List<Runnable> acts=new java.util.ArrayList<Runnable>();
        // 自定义应用统一收进二级"应用"菜单, 不在主环占位
        java.util.List<String[]> customs=customList();
        for(String id:ord){
            if("close".equals(id)) continue;      // 关闭改到盘心
            if("back".equals(id)||"recent".equals(id)) continue;   // 已移除的功能项(清旧prefs)
            if(id.startsWith("c")) continue;      // 自定义项 → 二级菜单
            String lb=labelOf(id);
            if(lb==null) continue;
            labs.add(lb);
            acts.add(actOf(id));
        }
        if(customs.size()>0){
            labs.add("📱 应用");
            acts.add(new Runnable(){ public void run(){ openAppsSub(); } });
        }
        // 快捷动作(音量/静音/亮度/锁屏)统一收进二级"快捷"盘, 不占主环
        boolean showQuick=true;
        try{ showQuick=getSharedPreferences("pf",0).getBoolean("float_quick",true); }catch(Exception e){}
        if(showQuick){
            labs.add("🎛 快捷");
            acts.add(new Runnable(){ public void run(){ openQuickSub(); } });
        }
        if(labs.size()>0) renderWheel(labs,acts);
        Log.i("FloatBall","buildMenu wheel items="+labs.size()+" customs="+customs.size()+" order="+ord.toString());
    }
    String labelOf(String id){
        if("back".equals(id)) return "◀ 返回";
        if("home".equals(id)) return "● 主页";
        if("app".equals(id)) return "🧰 打开主界面";
        if("recent".equals(id)) return "▦ 最近";
        if("sweep".equals(id)) return "🧹 清理后台";
        if("shot".equals(id)) return "📸 截图";
        if("net".equals(id)) return "🔓 开网";
        return null;
    }
    Runnable actOf(final String id){
        if("back".equals(id)) return new Runnable(){public void run(){ shellKey("4"); hideMenu(); }};
        if("home".equals(id)) return new Runnable(){public void run(){ goHome(); hideMenu(); }};
        if("app".equals(id)) return new Runnable(){public void run(){ openApp(); hideMenu(); }};
        if("recent".equals(id)) return new Runnable(){public void run(){ shellKey("187"); hideMenu(); }};
        if("sweep".equals(id)) return new Runnable(){public void run(){ hideMenu(); clearBackground(); }};
        if("shot".equals(id)) return new Runnable(){public void run(){ hideMenu(); captureScreen(); }};
        if("net".equals(id)) return new Runnable(){public void run(){ fireOpenNet(); hideMenu(); }};
        return new Runnable(){public void run(){ hideMenu(); }};
    }
    // 按项数自适应: 项越多按钮越小、半径略大(保持单圈不重叠)
    int[] ringFor(int n){
        int d=70,r=104;   // dp
        if(n>8){ d=58; r=108; }
        if(n>13){ d=50; r=114; }
        if(n>18){ d=42; r=118; }
        int dd=dp(d), rr=dp(r);
        int dq=discSize();
        if(rr+dd/2>dq/2-dp(6)) rr=dq/2-dd/2-dp(6);
        return new int[]{dd,rr};
    }
    // ---- 极坐标命中层: 外圈按扇区可点(整块), 中心圆整块可点 ----
    void addDiscTouch(FrameLayout host,int n,int itemD,int R,final Runnable centerAct,final Runnable[] acts,final Runnable[] longActs){
        try{
            int dq=discSize();
            int innerEdge=R-itemD/2;
            DiscTouchView tv=new DiscTouchView(this);
            tv.n=n;
            tv.hubR=Math.max(dp(10),innerEdge-dp(2));
            tv.outerR=dq/2-dp(4);
            tv.centerAct=centerAct;
            tv.acts=acts;
            tv.longActs=longActs;
            host.addView(tv,new FrameLayout.LayoutParams(dq,dq));
        }catch(Exception e){ Log.e("FloatBall","addDiscTouch err",e); }
    }
    class DiscTouchView extends View {
        int n=0,hubR=0,outerR=0;
        Runnable centerAct;
        Runnable[] acts,longActs;
        float dx0,dy0;
        boolean moved,longDone;
        Runnable pendingLong;
        DiscTouchView(Context c){ super(c); }
        void cancelLong(){ if(pendingLong!=null){ removeCallbacks(pendingLong); pendingLong=null; } }
        int sectorOf(float x,float y){
            int dq=getWidth(); if(dq<=0) dq=discSize();
            float cx=dq/2f, cy=dq/2f;
            float ddx=x-cx, ddy=y-cy;
            double r=Math.sqrt(ddx*ddx+ddy*ddy);
            if(r<=hubR) return -1;                 // 中心圆
            if(r>outerR) return -2;                // 盘外(方角)
            if(n<=0||acts==null) return -2;
            double deg=Math.toDegrees(Math.atan2(ddy,ddx));   // 0=+x
            double a=deg+90.0; if(a<0) a+=360.0;              // 0=正上方, 顺时针
            int idx=(int)Math.round(a/(360.0/n))%n;
            if(idx<0) idx+=n;
            return idx;
        }
        public boolean onTouchEvent(MotionEvent e){
            float x=e.getX(), y=e.getY();
            int idx=sectorOf(x,y);
            switch(e.getActionMasked()){
                case MotionEvent.ACTION_DOWN:
                    dx0=x; dy0=y; moved=false; longDone=false;
                    if(idx>=0&&longActs!=null&&idx<longActs.length&&longActs[idx]!=null){
                        final int fi=idx;
                        pendingLong=new Runnable(){ public void run(){
                            longDone=true;
                            try{ longActs[fi].run(); }catch(Exception ex){}
                        }};
                        postDelayed(pendingLong,480);
                    }
                    return true;
                case MotionEvent.ACTION_MOVE:
                    if(Math.hypot(x-dx0,y-dy0)>dp(14)){ moved=true; cancelLong(); }
                    return true;
                case MotionEvent.ACTION_UP:
                    cancelLong();
                    if(!moved&&!longDone){
                        if(idx==-1){ if(centerAct!=null) centerAct.run(); }        // 中间圆整块=✕/返回
                        else if(idx==-2){ hideMenu(); }                            // 盘外方角=收起
                        else if(acts!=null&&idx<acts.length&&acts[idx]!=null) acts[idx].run();
                    }
                    return true;
                case MotionEvent.ACTION_CANCEL:
                    cancelLong();
                    return true;
            }
            return true;
        }
    }

    // 悬浮球应用盘长按 → 交给主界面管理(改名/移除)
    void openManageApp(int idx){
        hideMenu();
        try{
            Intent mg=new Intent(FloatBallService.this,MainActivity.class);
            mg.setAction("com.helper.urlfeeder.action.MANAGE_FLOAT_APP");
            mg.putExtra("idx",idx);
            mg.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED);
            startActivity(mg);
            Log.i("FloatBall","manage app idx="+idx);
        }catch(Exception e){ Log.e("FloatBall","manage fail",e); }
    }

    // 把各功能项摆到一圈上, 盘心放 ✕ 收起
    void renderWheel(final java.util.List<String> labs, final java.util.List<Runnable> acts){
        if(menu==null) return;
        menu.removeAllViews();
        int n=labs.size();
        if(n==0) return;
        int dq=discSize(); int cx=dq/2, cy=dq/2;
        int[] ring=ringFor(n); int itemD=ring[0], R=ring[1];
        decorateDisc(menu,n,itemD,R);   // 盘心光环+扇区分隔线(美化分层)
        int tw=labelBoxW(itemD,R,n), th=itemD+dp(20);
        int bias=dp(6);
        for(int i=0;i<n;i++){
            double a=Math.toRadians(-90.0+360.0*i/n);   // 从顶部开始顺时针
            int px=cx+(int)Math.round((R+bias)*Math.cos(a))-tw/2;
            int py=cy+(int)Math.round((R+bias)*Math.sin(a))-th/2;
            View t=wheelTile(labs.get(i),acts.get(i),itemD,false);
            FrameLayout.LayoutParams flp=new FrameLayout.LayoutParams(tw,th);
            flp.leftMargin=px; flp.topMargin=py;
            menu.addView(t,flp);
        }
        int cd=dp(74);
        View cc=wheelTile("✕ 收起",new Runnable(){public void run(){ hideMenu(); }},cd,true);
        FrameLayout.LayoutParams clp=new FrameLayout.LayoutParams(cd,cd);
        clp.leftMargin=cx-cd/2; clp.topMargin=cy-cd/2;
        menu.addView(cc,clp);
        // 外圈整块可点(按扇区) + 中间圆整块可点
        Runnable[] arr=new Runnable[acts.size()];
        for(int i=0;i<arr.length;i++) arr[i]=acts.get(i);
        addDiscTouch(menu,n,itemD,R,new Runnable(){public void run(){ hideMenu(); }},arr,null);
        Log.i("FloatBall","wheel rendered n="+n);
    }
    // 名称容器宽度: 字号放大后避免被小方块裁剪, 同时不与相邻项重叠
    int labelBoxW(int itemD,int R,int n){
        int arc=(int)(2*Math.PI*R/Math.max(1,n));
        int w=(int)(itemD*1.9f);
        int lim=(int)(arc*0.95f);
        return Math.max(itemD,Math.min(w,lim));
    }
    // 圆盘分层装饰: 盘心光环 + 项内侧圆环 + 扇区分隔线 → 视觉上"中间圆 + 周围区域"
    void decorateDisc(final FrameLayout host,int n,int itemD,int R){
        try{
            int dq=discSize(); int cx=dq/2;
            int innerEdge=R-itemD/2;                 // 项内缘
            // 1) "中间的圆": 从盘心铺到项内缘的柔和浅圈 + 外描边
            int hubR=Math.max(dp(10),innerEdge-dp(12));
            View hv=circleView(hubR*2,0x183FC8A5,0x9942C8A5);
            FrameLayout.LayoutParams hlp=new FrameLayout.LayoutParams(hubR*2,hubR*2);
            hlp.leftMargin=cx-hubR; hlp.topMargin=cx-hubR;
            host.addView(hv,hlp);
            // 2) 周围区域外沿细环
            int outerEdge=dq/2-dp(4);
            View rim=circleView(outerEdge*2,0x00000000,0x40516E70);
            FrameLayout.LayoutParams rlp=new FrameLayout.LayoutParams(outerEdge*2,outerEdge*2);
            rlp.leftMargin=cx-outerEdge; rlp.topMargin=cx-outerEdge;
            host.addView(rim,rlp);
            // 3) 扇区分隔线: 从项内缘到盘外缘, 每个扇区边界一条淡线
            int outer=outerEdge;
            if(n<=0) n=6;
            for(int i=0;i<n;i++){
                double deg=-90.0+360.0*(i+0.5)/n;
                drawSpoke(host,deg,innerEdge+dp(1),outer,cx);
            }
        }catch(Exception e){}
    }
    // 纯色/描边圆(装饰用)
    View circleView(int d,int fill,int stroke){
        GradientDrawable g=new GradientDrawable();
        g.setShape(GradientDrawable.OVAL);
        if(fill!=0) g.setColor(fill);
        g.setStroke(Math.max(1,dp(1)),stroke);
        View v=new View(this);
        v.setBackground(g);
        return v;
    }
    // 从 fromR 到 toR、方向为 deg 的细线(以盘心为轴)
    void drawSpoke(FrameLayout host,double deg,int fromR,int toR,int cx){
        try{
            double a=Math.toRadians(deg);
            int len=toR-fromR;
            int rmid=(fromR+toR)/2;
            int px=cx+(int)Math.round(rmid*Math.cos(a));
            int py=cx+(int)Math.round(rmid*Math.sin(a));
            View ln=new View(this);
            ln.setBackgroundColor(0x2842C8A5);
            int w=dp(1);
            FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(w,len);
            lp.leftMargin=px-w/2; lp.topMargin=py-len/2;
            ln.setRotation((float)(deg-90.0));   // 自身中心为盘心 → 绕盘心旋转对齐半径
            host.addView(ln,lp);
        }catch(Exception e){}
    }
    // 单个圆形按钮(图标+名称)
    View wheelTile(String label,final Runnable act,int d,boolean center){
        String[] p=splitLabel(label);
        LinearLayout t=new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        if(center){
            // 盘心按钮保留白色圆底(作为"中间的圆"的中心)
            GradientDrawable bg=new GradientDrawable();
            bg.setShape(GradientDrawable.OVAL);
            bg.setColor(0xF2213432);
            bg.setStroke(dp(1),0xFF42C8A5);
            t.setBackground(bg);
        }else{
            t.setBackground(null);   // 去掉功能项外面的白色圆底
        }
        t.setContentDescription(label);
        TextView tv=new TextView(this);
        tv.setText(p[0]);
        tv.setTextColor(center?0xFFF2F6F5:0xFFF2F6F5);
        int iconPx=Math.max(dp(22),Math.min(dp(32),(int)(d*(center?0.34f:0.44f))));
        tv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,iconPx);
        tv.setGravity(Gravity.CENTER);
        tv.setIncludeFontPadding(false);
        Fonts.apply(tv);
        if(!center) tv.setShadowLayer(Math.max(2,dp(2)),0,Math.max(1,dp(1)),0xCC000000);
        t.addView(tv);
        if(p[1]!=null&&p[1].length()>0){
            TextView nv=new TextView(this);
            nv.setText(p[1]);
            nv.setTextColor(center?0xFFBDEBDF:0xFFD2DFDC);
            int namePx=Math.max(dp(11),Math.min(dp(14),(int)(d*(center?0.18f:0.20f))));
            nv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,namePx);
            nv.setGravity(Gravity.CENTER);
            nv.setMaxLines(1); nv.setSingleLine(true); nv.setIncludeFontPadding(false);
            nv.setMaxWidth((int)(d*(center?1.0f:1.9f)));
            nv.setEllipsize(android.text.TextUtils.TruncateAt.END);
            Fonts.apply(nv);
            if(!center) nv.setShadowLayer(Math.max(2,dp(2)),0,Math.max(1,dp(1)),0xCC000000);
            t.addView(nv);
        }
        t.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ try{ act.run(); }catch(Exception e){} } });
        return t;
    }
    // 标签拆成 图标 + 名称 (空格分隔, 图标≤2码点; 否则整体当图标)
    String[] splitLabel(String lab){
        if(lab==null||lab.length()==0) return new String[]{"?",""};
        int sp=lab.indexOf(' ');
        if(sp>0&&sp+1<lab.length()){
            String ic=lab.substring(0,sp);
            if(ic.codePointCount(0,ic.length())<=2) return new String[]{ic,lab.substring(sp+1).trim()};
        }
        return new String[]{lab,""};
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
    // 启动任意应用(自定义项)
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

    // 截图: Shizuku 可用则静默 screencap; 否则交给主界面走 MediaProjection 授权
    // 截图前临时隐藏悬浮球(避免被抓进画面), 抓完恢复
    void cancelLongPressShot(){
        if(longPressShot!=null){
            try{ if(ball!=null) ball.removeCallbacks(longPressShot); }catch(Exception e){}
            longPressShot=null;
        }
    }
    void hideBallForShot(){
        try{ if(ball!=null) ball.setVisibility(View.GONE); }catch(Exception e){}
        try{ if(scrim!=null) scrim.setVisibility(View.GONE); }catch(Exception e){}
        try{ if(menu!=null) menu.setVisibility(View.GONE); }catch(Exception e){}
        try{ if(sub!=null) sub.setVisibility(View.GONE); }catch(Exception e){}
    }
    void showBallAfterShot(){
        try{ if(ball!=null){ ball.setAlpha(1f); ball.setVisibility(View.VISIBLE); } }catch(Exception e){}
    }
    void captureScreen(){
        hideBallForShot();
        hd.postDelayed(new Runnable(){ public void run(){ doCaptureScreen(); } },170);
    }
    void doCaptureScreen(){
        // 1) 无障碍截图(免授权框/免 Shizuku/无系统过场)
        try{
            if(ShotAccessibilityService.ready()){
                if(ShotAccessibilityService.capture(this,new Runnable(){ public void run(){ showBallAfterShot(); } })){
                    toast("正在截图…"); return;
                }
            }
        }catch(Exception e){}
        // 2) Shizuku 静默 screencap
        try{
            if(ShizukuUtil.running()&&ShizukuUtil.permission()==0){
                final String name="WFT_"+new java.text.SimpleDateFormat("yyyyMMdd_HHmmss").format(new java.util.Date())+".png";
                final String path="/sdcard/Pictures/Screenshots/"+name;
                new Thread(new Runnable(){ public void run(){
                    String msg=null;
                    try{
                        ShizukuUtil.cmd("sh","-c","mkdir -p /sdcard/Pictures/Screenshots && screencap -p "+path);
                        msg="截图已保存: Pictures/Screenshots/"+name;
                    }catch(Exception e){ msg="截图失败(Shizuku)"; }
                    final String m=msg;
                    hd.post(new Runnable(){ public void run(){ toast(m); Log.i("FloatBall","shot "+m); showBallAfterShot(); }});
                }},"shot-shizuku").start();
                toast("正在截图…");
                return;
            }
        }catch(Exception e){}
        try{
            hd.postDelayed(new Runnable(){ public void run(){ showBallAfterShot(); } },1600);   // 兜底恢复
            // 用透明 Activity 申请授权(不拉起主界面); 已授权则直接复用, 全程静默
            Intent a=new Intent(this,ShotActivity.class);
            a.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS|Intent.FLAG_ACTIVITY_NO_ANIMATION);
            startActivity(a);
            Log.i("FloatBall","shot -> "+(ShotService.hasProjection()?"reuse":"ask permission"));
        }catch(Exception e){ toast("无法发起截图"); }
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

    // ---------------- 显示/收起 ----------------
    void toggleMenu(){
        menuVisible=!menuVisible;
        showMenu(menuVisible);
    }
    void showMenu(boolean show){
        if(menu==null) return;
        if(show){
            refreshScreen();
            try{ clampBallOnScreen(); wm.updateViewLayout(ball,ballLp); }catch(Exception e){}
            try{ menu.animate().cancel(); }catch(Exception e){}   // 取消可能残留的收起动画
            try{ menu.clearAnimation(); }catch(Exception e){}
            menu.setVisibility(View.VISIBLE);
            try{
                int dq2=discSize();
                menuLp.x=discCenterX(dq2); menuLp.y=discCenterY(dq2);
                wm.updateViewLayout(menu,menuLp);
            }catch(Exception e){}
            menu.setAlpha(0f); menu.setScaleX(0.55f); menu.setScaleY(0.55f);
            menu.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(200)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f)).start();
            scrimShow();   // 盘外点击由拦截屏接收 → 只收起不误触
            Log.i("FloatBall","wheel show");
        }else{
            try{ menu.animate().cancel(); }catch(Exception e){}
            try{ menu.clearAnimation(); }catch(Exception e){}
            menu.setAlpha(1f); menu.setScaleX(1f); menu.setScaleY(1f);
            menu.setVisibility(View.GONE);
            Log.i("FloatBall","wheel hide");
        }
    }
    // 收起动画: 缩小+淡出, 结束后隐藏并复位(after 可选回调)
    void animHide(final View v){ animHide(v,null); }
    void animHide(final View v,final Runnable after){
        try{ v.animate().cancel(); }catch(Exception e){}
        try{ v.clearAnimation(); }catch(Exception e){}
        v.animate().alpha(0f).scaleX(0.6f).scaleY(0.6f).setDuration(160)
            .setInterpolator(new android.view.animation.AccelerateInterpolator(1.5f))
            .withEndAction(new Runnable(){ public void run(){
                try{ v.setVisibility(View.GONE); }catch(Exception e){}
                try{ v.setAlpha(1f); v.setScaleX(1f); v.setScaleY(1f); }catch(Exception e){}
                if(after!=null){ try{ after.run(); }catch(Exception e){} }
            }}).start();
    }
    // 拦截屏显隐
    void scrimShow(){ try{ if(scrim!=null) scrim.setVisibility(View.VISIBLE); }catch(Exception e){} }
    void scrimHideSoon(){
        try{
            hd.postDelayed(new Runnable(){ public void run(){
                try{ if(scrim!=null&&!menuVisible&&!subVisible) scrim.setVisibility(View.GONE); }catch(Exception e){}
            }},230);
        }catch(Exception e){}
    }
    void hideSubNow(){
        if(subVisible){
            subVisible=false;
            if(sub!=null) animHide(sub);
            scrimHideSoon();
            Log.i("FloatBall","apps sub hide");
        }
    }
    void hideMenu(){
        boolean any=false;
        if(menuVisible){
            menuVisible=false; lastHide=System.currentTimeMillis();
            if(menu!=null){ animHide(menu); any=true; }
        }
        if(subVisible){
            subVisible=false;
            if(sub!=null){ animHide(sub); any=true; }
        }
        if(!any&&menu!=null&&menu.getVisibility()==View.VISIBLE){ animHide(menu); }  // 兜底
        transitioning=false;   // 取消任何切换动画
        scrimHideSoon();
        Log.i("FloatBall","wheel hide");
    }

    // ---------------- 二级"应用"菜单 ----------------
    // 读全部自定义应用 label|pkg
    java.util.List<String[]> customList(){
        java.util.List<String[]> out=new java.util.ArrayList<String[]>();
        try{
            String saved=getSharedPreferences("pf",0).getString("float_custom","");
            if(saved!=null&&saved.length()>0){
                String[] lines=saved.split("\n");
                for(String ln:lines){
                    String[] f=ln.split("\\|",-1);
                    if(f.length>=2&&f[0].trim().length()>0) out.add(new String[]{f[0].trim(),f[1].trim()});
                }
            }
        }catch(Exception e){}
        return out;
    }
    void createAppsSubWindow(){
        try{
            FrameLayout m=new FrameLayout(this);
            m.setBackground(makeDiscBackground());
            m.setVisibility(View.GONE);
            // 点盘外/四角收起(点悬浮球瞬间交给球处理)
            m.setOnTouchListener(new View.OnTouchListener(){
                public boolean onTouch(View v,android.view.MotionEvent e){
                    int act=e.getAction();
                    if(act==MotionEvent.ACTION_OUTSIDE){
                        if(System.currentTimeMillis()-lastBallDown<400){ return true; }
                        hideSubNow();
                        return true;
                    }
                    if(act==MotionEvent.ACTION_DOWN){
                        int dq=discSize(); float cx=dq/2f, cy=cx;
                        float dx=e.getX()-cx, dy=e.getY()-cy;
                        if(dx*dx+dy*dy>(cx-dp(2))*(cx-dp(2))){ hideSubNow(); return true; }
                        return true;
                    }
                    return false;
                }
            });
            sub=m;
            subLp=new WindowManager.LayoutParams(
                discSize(),discSize(),
                Build.VERSION.SDK_INT>=26?WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY:WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE|WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                    |WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL|WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH,
                PixelFormat.TRANSLUCENT);
            subLp.gravity=Gravity.TOP|Gravity.START;
            subLp.x=discCenterX(discSize()); subLp.y=discCenterY(discSize());
            try{ wm.addView(sub,subLp); Log.i("FloatBall","apps sub disc added"); }catch(Exception e){ Log.e("FloatBall","add sub fail",e);}
        }catch(Exception e){ Log.e("FloatBall","createSub err",e); }
    }
    void openAppsSub(){ openSub(0); }      // 二级: 我的应用
    void openQuickSub(){ openSub(1); }     // 二级: 快捷动作
    // ---------------- 快捷动作(二级盘, 依赖 Shizuku shell 权限) ----------------
    String[] QUICK_ID={"volup","voldown","mute","brightup","brightdown","lock"};
    String quickLabel(String id){
        if("volup".equals(id)) return "🔊 音量+";
        if("voldown".equals(id)) return "🔉 音量-";
        if("mute".equals(id)) return "🔇 静音";
        if("brightup".equals(id)) return "☀ 亮度+";
        if("brightdown".equals(id)) return "🌥 亮度-";
        if("lock".equals(id)) return "🔒 锁屏";
        return id;
    }
    Runnable quickAct(final String id){
        return new Runnable(){ public void run(){
            if(!ShizukuUtil.ready()){ toast("快捷动作需要 Shizuku：设置 → Shizuku 引导（复制启动命令）"); return; }
            new Thread(new Runnable(){ public void run(){
                try{
                    if("volup".equals(id)) ShizukuUtil.key(24);            // VOLUME_UP
                    else if("voldown".equals(id)) ShizukuUtil.key(25);     // VOLUME_DOWN
                    else if("mute".equals(id)) ShizukuUtil.key(164);       // VOLUME_MUTE
                    else if("lock".equals(id)) ShizukuUtil.key(26);        // POWER
                    else if("brightup".equals(id)) ShizukuUtil.brightnessStep(28);
                    else if("brightdown".equals(id)) ShizukuUtil.brightnessStep(-28);
                    Log.i("FloatBall","quick act "+id+" done");
                }catch(Throwable t){ Log.e("FloatBall","quick act "+id+" err",t); }
            }}).start();
        }};
    }
    void openSub(final int mode){
        try{
            if(sub==null) createAppsSubWindow();
            if(sub==null) return;
            final java.util.List<String[]> apps=(mode==0)?customList():new java.util.ArrayList<String[]>();
            if(mode==0&&apps.size()==0){ toast("还没有添加应用：设置 → 悬浮球菜单排序 → ＋ 添加要打开的应用"); return; }
            subMode=mode;
            sub.removeAllViews();
            int n=(mode==0)?apps.size():QUICK_ID.length;
            int dq=discSize(); int cx=dq/2, cy=dq/2;
            int[] ring=ringFor(n); int itemD=ring[0], R=ring[1];
            decorateDisc(sub,n,itemD,R);   // 与主轮盘同款分层美化
            // 应用真实图标围成一圈
            final Runnable[] acts=new Runnable[n];
            final Runnable[] longActs=new Runnable[n];
            int tw2=labelBoxW(itemD,R,n), th2=itemD+dp(20);
            int bias2=dp(6);
            for(int i=0;i<n;i++){
                double ang=Math.toRadians(-90.0+360.0*i/n);
                int px=cx+(int)Math.round((R+bias2)*Math.cos(ang))-tw2/2;
                int py=cy+(int)Math.round((R+bias2)*Math.sin(ang))-th2/2;
                final int ix=i;
                View t;
                if(mode==0){
                    final String[] a=apps.get(i);
                    t=appTile(a,i,itemD);
                    acts[i]=new Runnable(){ public void run(){ hideSubNow(); launchApp(a[1]); } };
                    longActs[i]=new Runnable(){ public void run(){ openManageApp(ix); } };
                }else{
                    final String qid=QUICK_ID[i];
                    t=wheelTile(quickLabel(qid),quickAct(qid),itemD,false);
                    acts[i]=new Runnable(){ public void run(){ hideSubNow(); quickAct(qid).run(); } };
                }
                FrameLayout.LayoutParams flp=new FrameLayout.LayoutParams(tw2,th2);
                flp.leftMargin=px; flp.topMargin=py;
                sub.addView(t,flp);
            }
            // 盘心: ‹ 返回主轮盘 (整块中间圆可点)
            int cd=dp(74);
            View back=wheelTile("‹ 返回",new Runnable(){ public void run(){ backToWheel(); } },cd,true);
            FrameLayout.LayoutParams blp2=new FrameLayout.LayoutParams(cd,cd);
            blp2.leftMargin=cx-cd/2; blp2.topMargin=cy-cd/2;
            sub.addView(back,blp2);
            // 外圈整块可点(按扇区) + 扇区长按=改名/移除 + 中间圆整块=返回
            addDiscTouch(sub,n,itemD,R,new Runnable(){ public void run(){ backToWheel(); } },acts,longActs);
            Log.i("FloatBall","sub disc mode="+mode+" n="+n);
            // 下钻动画: 主轮盘缩小淡出 → 二级圆盘放大淡入
            if(transitioning){ Log.i("FloatBall","transition busy"); return; }
            transitioning=true;
            if(menuVisible&&menu!=null){
                menuVisible=false;
                try{ menu.animate().cancel(); }catch(Exception e){}
                menu.animate().alpha(0f).scaleX(0.75f).scaleY(0.75f).setDuration(150)
                    .setInterpolator(new android.view.animation.AccelerateInterpolator())
                    .withEndAction(new Runnable(){ public void run(){
                        try{ menu.setVisibility(View.GONE); }catch(Exception e){}
                        menu.setAlpha(1f); menu.setScaleX(1f); menu.setScaleY(1f);
                        showAppsSubIn();
                    }}).start();
            }else showAppsSubIn();
        }catch(Exception e){ transitioning=false; Log.e("FloatBall","openAppsSub err",e); }
    }
    // 二级圆盘放大淡入
    void showAppsSubIn(){
        try{
            subVisible=true;
            try{ sub.animate().cancel(); }catch(Exception e){}   // 取消残留收起动画
            try{ sub.clearAnimation(); }catch(Exception e){}
            sub.setAlpha(0f); sub.setScaleX(0.5f); sub.setScaleY(0.5f);
            try{
                int dq3=discSize();
                subLp.x=discCenterX(dq3); subLp.y=discCenterY(dq3);
                wm.updateViewLayout(sub,subLp);
            }catch(Exception e){}
            sub.setVisibility(View.VISIBLE);
            sub.animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(230)
                .setInterpolator(new android.view.animation.DecelerateInterpolator(1.6f))
                .withEndAction(new Runnable(){ public void run(){ transitioning=false; } }).start();
            scrimShow();
            Log.i("FloatBall","apps sub in");
        }catch(Exception e){ transitioning=false; }
    }
    // 返回主轮盘: 二级缩小淡出 → 主轮盘放大淡入
    void backToWheel(){
        if(transitioning) return;
        transitioning=true;
        if(subVisible&&sub!=null){
            subVisible=false;
            try{ sub.animate().cancel(); }catch(Exception e){}
            sub.animate().alpha(0f).scaleX(0.75f).scaleY(0.75f).setDuration(140)
                .setInterpolator(new android.view.animation.AccelerateInterpolator())
                .withEndAction(new Runnable(){ public void run(){
                    try{ sub.setVisibility(View.GONE); }catch(Exception e){}
                    sub.setAlpha(1f); sub.setScaleX(1f); sub.setScaleY(1f);
                    showWheelIn();
                }}).start();
        }else showWheelIn();
    }
    void showWheelIn(){
        if(menu!=null){
            buildMenu();
            menuVisible=true;
            showMenu(true);   // showMenu 自带放大淡入
        }
        transitioning=false;
    }
    // 应用圆钮: 真实图标 + 名称 (点击打开, 长按管理)
    View appTile(final String[] a,final int idx,int d){
        LinearLayout t=new LinearLayout(this);
        t.setOrientation(LinearLayout.VERTICAL);
        t.setGravity(Gravity.CENTER);
        t.setBackground(null);        // 不要白色圆底, 图标直接浮在盘面
        t.setContentDescription(a[0]);
        ImageView iv=new ImageView(this);
        int is=(int)(d*0.64f);
        android.graphics.drawable.Drawable ic=null;
        try{ ic=getPackageManager().getApplicationIcon(a[1]); }catch(Exception e){}
        if(ic!=null) iv.setImageDrawable(ic);
        else{ iv.setBackground(makeOrb()); }
        t.addView(iv,new LinearLayout.LayoutParams(is,is));
        TextView nv=new TextView(this);
        nv.setText(a[0]); nv.setTextColor(0xFFD2DFDC);
        nv.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,Math.max(dp(11),Math.min(dp(14),(int)(d*0.20f))));
        nv.setGravity(Gravity.CENTER);
        nv.setMaxLines(1); nv.setSingleLine(true); nv.setMaxWidth((int)(d*1.9f));
        nv.setEllipsize(android.text.TextUtils.TruncateAt.END);
        nv.setIncludeFontPadding(false);
        Fonts.apply(nv);
        nv.setShadowLayer(Math.max(2,dp(2)),0,Math.max(1,dp(1)),0xCC000000);
        t.addView(nv);
        t.setOnClickListener(new View.OnClickListener(){ public void onClick(View v){ hideSubNow(); launchApp(a[1]); } });
        t.setOnLongClickListener(new View.OnLongClickListener(){
            public boolean onLongClick(View v){ openManageApp(idx); return true; }
        });
        return t;
    }

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

    // ---------------- 悬浮球几何(吸附) ----------------
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
        // 记忆停靠: 贴边(0左/1右) + 高度
        saveBallPos((cx<w/2)?0:1,targetY);
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

    void stopBall(){
        running=false;
        try{ getSharedPreferences("pf",0).edit().putBoolean("float_on",false).commit(); }catch(Exception e){}
        try{ if(ball!=null){ wm.removeView(ball); ball=null; } }catch(Exception e){}
        try{ if(menu!=null){ wm.removeView(menu); menu=null; } }catch(Exception e){}
        try{ if(sub!=null){ wm.removeView(sub); sub=null; } }catch(Exception e){}
        try{ if(scrim!=null){ wm.removeView(scrim); scrim=null; } }catch(Exception e){}
        subVisible=false;
        stopSelf();
    }
    public void onDestroy(){
        running=false;
        cancelLongPressShot();
        if(pendingSingleTap!=null){ hd.removeCallbacks(pendingSingleTap); pendingSingleTap=null; }
        lastTapUp=0;
        try{ if(dmgr!=null&&dlistener!=null) dmgr.unregisterDisplayListener(dlistener); }catch(Exception e){}
        dmgr=null; dlistener=null;
        // 关键: 停止服务时把所有悬浮窗从 WindowManager 摘掉,
        // 否则(如设置里"停止悬浮球"走 stopService)窗口会残留, 再启动会叠加成多个球
        try{ if(snapAnim!=null){ snapAnim.cancel(); } }catch(Exception e){}
        snapAnim=null;
        if(wm!=null){
            try{ if(ball!=null){ wm.removeView(ball); ball=null; } }catch(Exception e){ ball=null; }
            try{ if(menu!=null){ wm.removeView(menu); menu=null; } }catch(Exception e){ menu=null; }
            try{ if(sub!=null){ wm.removeView(sub); sub=null; } }catch(Exception e){ sub=null; }
            try{ if(scrim!=null){ wm.removeView(scrim); scrim=null; } }catch(Exception e){ scrim=null; }
        }
        menuVisible=false; subVisible=false; transitioning=false;
        Log.i("FloatBall","service destroyed, overlays removed");
        if(inst==this) inst=null;
        super.onDestroy();
    }
    int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
}
