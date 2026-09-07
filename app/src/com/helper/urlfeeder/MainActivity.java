package com.helper.urlfeeder;

import android.app.Activity;
import android.app.WallpaperManager;
import android.content.ClipboardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.IBinder;
import android.text.Editable;
import android.text.TextWatcher;
import android.os.Parcel;
import android.view.Gravity;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    static final String EXTRA_URL="extra_url";
    static final String FW_PKG="cn.com.microtrust.firewall";
    static final String FW_IFACE="cn.com.microtrust.firewall.aidl.IAFWService";
    static final String FW_ACTION="cn.com.microtrust.firewall.IAFWService";
    static final String ACT_FIX_BROWSER="com.helper.urlfeeder.action.FIX_DEFAULT_BROWSER";
    static final String ROLE_BROWSER="android.app.role.BROWSER";
    static final String ROLE_HOME="android.app.role.HOME";
    String pendingUrl;
    LinearLayout pageWeb,pageApps,pageSet,appsList,settingsBody;
    EditText urlInput;
    TextView tabWeb,tabApps,tabSet,netResult;
    FrameLayout rootF;
    FrameLayout aboutPage;
    FrameLayout checkPage;
    ImageView bgWall;
    View content, dim;
    ScrollView webScroll;
    ScrollView appsScroll;
    EditText appSearch;
    List<AppEntry> allApps=new ArrayList<AppEntry>();
    String appQuery="";
    LinearLayout pullHead;
    TextView pullTv;
    boolean pulling=false,refreshing=false;
    int curTab=0;
    List<String> logLines=new ArrayList<String>();
    TextView logView;
    java.util.List<String> hist=new java.util.ArrayList<String>();
    LinearLayout histRow;
    RefreshArrow arrowView;
    boolean spinning=false, hapticFired=false;
    int triggerPx=52, lockPx=150;
    android.os.Handler uiH=new android.os.Handler();
    Runnable spinTick=null;
    float spinDeg=0;
    float downY;
    SharedPreferences prefs;

    protected void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences("pf",0);
        loadLogLines();
        loadHist();
        if(Build.VERSION.SDK_INT>=21){
            getWindow().setStatusBarColor(0x00000000);
            getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_LAYOUT_STABLE|View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN);
        }
        buildUi();
        applyInsets();
        applyBg();
        boolean fromLink=handleIntent(getIntent());
        selectTab(0);
        if(fromLink&&pendingUrl!=null) urlInput.setText(pendingUrl);
        animateIn(content);
        if(getIntent()!=null&&ACT_FIX_BROWSER.equals(getIntent().getAction())){
            content.postDelayed(new Runnable(){public void run(){ fixDefaultBrowser(); }},700);
        }
        // 自动开启网络守护：延后执行不占用首帧，已运行则不重复
        content.postDelayed(new Runnable(){public void run(){ autoEnsureGuard(); }},1200);
    }
    void autoEnsureGuard(){
        try{
            boolean running=false;
            android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(android.content.Context.ACTIVITY_SERVICE);
            if(am!=null){
                try{
                    java.util.List<android.app.ActivityManager.RunningServiceInfo> rl=am.getRunningServices(300);
                    if(rl!=null) for(android.app.ActivityManager.RunningServiceInfo si:rl){
                        if(si!=null&&si.service!=null&&"com.helper.urlfeeder.GuardService".equals(si.service.getClassName())){ running=true; break; }
                    }
                }catch(Exception e){ running=false; }
            }
            if(running) return;
            if(prefs.getBoolean("guard_off",false)) return;   // 用户手动停止过 → 不再自动拉起
            Intent s=new Intent(this,GuardService.class); s.setAction("start");
            if(Build.VERSION.SDK_INT>=26) startForegroundService(s); else startService(s);
            prefs.edit().putBoolean("guard_on",true).commit();
            addLog("网络守护已自动启动");
        }catch(Exception e){}
    }

    protected void onResume(){ super.onResume(); try{ com.helper.urlfeeder.FloatBallService.collapseMenu(); }catch(Exception e){} }
    protected void onPause(){ super.onPause(); }
    int dp(int v){ return (int)(v*getResources().getDisplayMetrics().density+0.5f); }
    // 玻璃透明度档位 0..4 → 各层白色强度缩放系数
    float glassS(){ int l=prefs.getInt("ga",2);
        if(l==0) return 0.38f; if(l==1) return 0.62f; if(l==2) return 0.95f; if(l==3) return 1.3f; return 1.6f; }
    int ws(int argb,float s){ int a=(argb>>>24); a=Math.min(255,Math.max(0,(int)(a*s))); return (a<<24)|(argb&0xFFFFFF); }
    Drawable glass(){
        float s=glassS();
        int r=dp(28);
        // 基底：半透明白，随对角渐深（体积感）
        GradientDrawable base=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{ws(0x78FFFFFF,s),ws(0x40FFFFFF,s),ws(0x1EFFFFFF,s)});
        base.setCornerRadius(r);
        // 顶部菲涅尔高光：玻璃边缘聚集入射光
        GradientDrawable fres=new GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,new int[]{ws(0x99FFFFFF,s*0.8f),ws(0x14FFFFFF,s*0.8f),0x00FFFFFF});
        fres.setCornerRadius(r);
        // 折射斜光：模拟内部光线经左上→右下折射
        GradientDrawable slant=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{ws(0x59FFFFFF,s),ws(0x0AFFFFFF,s),0x00FFFFFF,ws(0x2EFFFFFF,s)});
        slant.setCornerRadius(r);
        // 底部内反光（液态玻璃下缘回光）
        GradientDrawable bott=new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{ws(0x0DFFFFFF,s*0.7f),ws(0x45FFFFFF,s*0.7f)});
        bott.setCornerRadius(r);
        // 厚度内阴影：给玻璃"厚度"
        GradientDrawable shade=new GradientDrawable(GradientDrawable.Orientation.BOTTOM_TOP,new int[]{0x00000000,ws(0x24000000,Math.min(1.5f,s*0.8f))});
        shade.setCornerRadius(r);
        // 高亮描边
        GradientDrawable stroke=new GradientDrawable(); stroke.setCornerRadius(r); stroke.setStroke(dp(1),0x8AFFFFFF);
        LayerDrawable ld=new LayerDrawable(new Drawable[]{base,fres,slant,bott,shade,stroke});
        return ld;
    }
    // 液态按压特效：按下内凹(压入玻璃)，松手带弹性回弹(鼓起)，观感像液态玻璃受压力
    void liquidFx(final View v){
        v.addOnLayoutChangeListener(new View.OnLayoutChangeListener(){
            public void onLayoutChange(View vv,int l,int t,int r,int b,int ol,int ot,int or,int ob){
                if(r-l>0&&b-t>0){ vv.setPivotX((r-l)/2f); vv.setPivotY((b-t)/2f); }
            }});
        v.setOnTouchListener(new View.OnTouchListener(){
            public boolean onTouch(final View vv,android.view.MotionEvent e){
                int a=e.getAction();
                if(a==android.view.MotionEvent.ACTION_DOWN){
                    vv.animate().cancel();
                    vv.animate().scaleX(0.94f).scaleY(0.94f).alpha(0.93f)
                      .setDuration(90).setInterpolator(new DecelerateInterpolator(1.2f)).start();
                } else if(a==android.view.MotionEvent.ACTION_UP||a==android.view.MotionEvent.ACTION_CANCEL){
                    vv.animate().cancel();
                    // 回弹不超调(scale 目标恒为 1.0)，避免放大溢出容器边缘
                    vv.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
                      .setInterpolator(new DecelerateInterpolator(1.6f)).start();
                }
                return false;
            }});
    }
    GradientDrawable gradBg(int[] cs){ return new GradientDrawable(GradientDrawable.Orientation.TL_BR,cs); }
    View gap(int h){ View v=new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(h))); return v; }
    View sp(int w){ View v=new View(this); v.setLayoutParams(new LinearLayout.LayoutParams(dp(w),1)); return v; }

    void buildUi(){
        rootF=new FrameLayout(this);
        bgWall=new ImageView(this); bgWall.setScaleType(ImageView.ScaleType.CENTER_CROP);
        rootF.addView(bgWall,new FrameLayout.LayoutParams(-1,-1));
        content=new LinearLayout(this);
        final LinearLayout root=(LinearLayout)content; root.setOrientation(LinearLayout.VERTICAL);

        LinearLayout header=new LinearLayout(this); header.setOrientation(LinearLayout.VERTICAL);
        header.setPadding(dp(18),dp(14),dp(18),dp(14)); header.setBackground(glass());
        TextView h1=new TextView(this); h1.setText("🌐 万能转发器"); h1.setTextColor(Color.WHITE); h1.setTextSize(21); h1.setTypeface(null,Typeface.BOLD);
        TextView h2=new TextView(this); h2.setText("网页转发 · 全应用抽屉 · 一键开网"); h2.setTextColor(0xDDFFFFFF); h2.setTextSize(11);
        header.addView(h1); header.addView(h2);
        LinearLayout hwrap=new LinearLayout(this); hwrap.setOrientation(LinearLayout.VERTICAL);
        hwrap.setPadding(dp(14),dp(10),dp(14),dp(6));
        hwrap.addView(header,new LinearLayout.LayoutParams(-1,-2));
        root.addView(hwrap);

        LinearLayout tabs=new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL);
        tabs.setPadding(dp(12),dp(10),dp(12),dp(6));
        tabWeb=tab("🏠 首页"); tabApps=tab("📱 应用"); tabSet=tab("⚙️ 设置");
        tabWeb.setOnClickListener(new View.OnClickListener(){public void onClick(View v){selectTab(0);}});
        tabApps.setOnClickListener(new View.OnClickListener(){public void onClick(View v){selectTab(1);buildApps();}});
        tabSet.setOnClickListener(new View.OnClickListener(){public void onClick(View v){selectTab(2);}});
        tabs.addView(tabWeb,new LinearLayout.LayoutParams(0,-1,1f));
        tabs.addView(sp(8));
        tabs.addView(tabApps,new LinearLayout.LayoutParams(0,-1,1f));
        tabs.addView(sp(8));
        tabs.addView(tabSet,new LinearLayout.LayoutParams(0,-1,1f));
        root.addView(tabs,new LinearLayout.LayoutParams(-1,dp(48)));

        // web
        webScroll=new ScrollView(this); webScroll.setSmoothScrollingEnabled(true); ScrollView sw=webScroll;
        pageWeb=new LinearLayout(this); pageWeb.setOrientation(LinearLayout.VERTICAL); pageWeb.setPadding(dp(14),dp(8),dp(14),dp(18));
        pageWeb.addView(cardWeb());
        // 最近打开行
        LinearLayout histCard=new LinearLayout(this); histCard.setOrientation(LinearLayout.VERTICAL);
        histCard.setPadding(dp(12),dp(8),dp(12),dp(8)); histCard.setBackground(glass());
        TextView hl=new TextView(this); hl.setText("🕘 最近打开"); hl.setTextColor(0xCCFFFFFF); hl.setTextSize(12);
        histCard.addView(hl);
        histRow=new LinearLayout(this); histRow.setOrientation(LinearLayout.HORIZONTAL);
        histRow.setPadding(0,dp(6),0,0);
        histCard.addView(histRow);
        refreshHist();
        LinearLayout.LayoutParams hclp=new LinearLayout.LayoutParams(-1,-2); hclp.topMargin=dp(10);
        histCard.setLayoutParams(hclp);
        pageWeb.addView(histCard);
        LinearLayout logCard=new LinearLayout(this); logCard.setOrientation(LinearLayout.VERTICAL);
        logCard.setPadding(dp(12),dp(10),dp(12),dp(10)); logCard.setBackground(glass());
        LinearLayout.LayoutParams loglp=new LinearLayout.LayoutParams(-1,-2);
        loglp.topMargin=dp(12);   // 与上方转发卡片拉开距离，避免黏连/重合
        logCard.setLayoutParams(loglp);
        TextView lg=new TextView(this); lg.setText("📋 运行日志"); lg.setTextColor(Color.WHITE); lg.setTextSize(13); lg.setTypeface(null,Typeface.BOLD);
        LinearLayout lgRow=new LinearLayout(this); lgRow.setOrientation(LinearLayout.HORIZONTAL); lgRow.setGravity(Gravity.CENTER_VERTICAL);
        lgRow.addView(lg,new LinearLayout.LayoutParams(0,-2,1f));
        Btn clearLg=gbtn("清空",shp(10,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ clearLog(); }});
        clearLg.setTextSize(11);
        clearLg.setPadding(dp(10),dp(4),dp(10),dp(4));
        lgRow.addView(clearLg,new LinearLayout.LayoutParams(-2,-2));
        Btn copyLg=gbtn("复制",shp(10,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ copyLog(); }});
        copyLg.setTextSize(11);
        copyLg.setPadding(dp(10),dp(4),dp(10),dp(4));
        LinearLayout.LayoutParams clp=new LinearLayout.LayoutParams(-2,-2); clp.leftMargin=dp(6);
        lgRow.addView(copyLg,clp);
        logCard.addView(lgRow);
        logView=new TextView(this); logView.setTextColor(0xBBFFFFFF); logView.setTextSize(11);
        logView.setTypeface(Typeface.MONOSPACE,Typeface.NORMAL);
        logView.setPadding(0,dp(8),0,0);
        logCard.addView(logView);
        pageWeb.addView(logCard);
        refreshLogView();
        TextView tw=new TextView(this); tw.setText("设为默认浏览器后，任何 App 的链接都会先落到这里；开网后第三方即可联网。");
        tw.setTextSize(12); tw.setTextColor(0xEEFFFFFF); tw.setPadding(dp(6),dp(14),dp(6),dp(4));
        pageWeb.addView(tw);
        sw.addView(pageWeb);
        root.addView(sw,new LinearLayout.LayoutParams(-1,0,1f));

        // apps
        LinearLayout aw=new LinearLayout(this); aw.setOrientation(LinearLayout.VERTICAL); aw.setPadding(dp(12),dp(8),dp(12),dp(14));
        ScrollView sv=new ScrollView(this); sv.setSmoothScrollingEnabled(true);
        appsScroll=sv;
        makePullHead();
        appsList=new LinearLayout(this); appsList.setOrientation(LinearLayout.VERTICAL);
        if(pullHead!=null) appsList.addView(pullHead);
        sv.addView(appsList);
        sv.setOverScrollMode(View.OVER_SCROLL_NEVER);
        appSearch=new EditText(this);
        appSearch.setHint("\uD83D\uDD0D 搜索应用（名称 / 包名）");
        appSearch.setTextSize(14); appSearch.setSingleLine(true); appSearch.setTextColor(Color.WHITE);
        appSearch.setHintTextColor(0xAAFFFFFF);
        appSearch.setBackground(shp(12,0x66000000)); appSearch.setPadding(dp(12),dp(10),dp(12),dp(10));
        final Btn clearBtn=gbtn("✕",shp(12,0x33888888),new View.OnClickListener(){public void onClick(View v){ appSearch.setText(""); }});
        clearBtn.setTextSize(14);
        final android.widget.LinearLayout.LayoutParams cblp=new android.widget.LinearLayout.LayoutParams(dp(46),dp(40));
        cblp.leftMargin=dp(8);
        clearBtn.setLayoutParams(cblp);
        clearBtn.setVisibility(View.GONE);
        appSearch.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence c,int a,int b,int d){}
            public void onTextChanged(CharSequence c,int a,int b,int d){ appQuery=c==null?"":c.toString().trim().toLowerCase(); renderApps(); }
            public void afterTextChanged(Editable e){
                clearBtn.setVisibility(e!=null&&e.length()>0?View.VISIBLE:View.GONE);
            }
        });
        appSearch.setOnFocusChangeListener(new View.OnFocusChangeListener(){
            public void onFocusChange(View v,boolean has){
                clearBtn.setVisibility(has&&appSearch.getText().length()>0?View.VISIBLE:View.GONE);
            }
        });
        LinearLayout searchRow=new LinearLayout(this); searchRow.setOrientation(LinearLayout.HORIZONTAL); searchRow.setGravity(Gravity.CENTER_VERTICAL);
        searchRow.addView(appSearch,new LinearLayout.LayoutParams(0,-2,1f));
        searchRow.addView(clearBtn);
        LinearLayout.LayoutParams srlp=new LinearLayout.LayoutParams(-1,-2); srlp.bottomMargin=dp(10);
        aw.addView(searchRow,srlp);
        PullLayout pl=new PullLayout(this);
        pl.addView(sv,new FrameLayout.LayoutParams(-1,-1));
        aw.addView(pl,new LinearLayout.LayoutParams(-1,0,1f));
        pageApps=aw;

        // settings
        LinearLayout pw=new LinearLayout(this); pw.setOrientation(LinearLayout.VERTICAL); pw.setPadding(dp(12),dp(8),dp(12),dp(16));
        ScrollView ss=new ScrollView(this); ss.setSmoothScrollingEnabled(true);
        settingsBody=new LinearLayout(this); settingsBody.setOrientation(LinearLayout.VERTICAL);
        ss.addView(settingsBody); pw.addView(ss,new LinearLayout.LayoutParams(-1,-1));
        pageSet=pw;

        root.addView(pageApps,new LinearLayout.LayoutParams(-1,0,1f));
        root.addView(pageSet,new LinearLayout.LayoutParams(-1,0,1f));
        rootF.addView(content,new FrameLayout.LayoutParams(-1,-1));
        setContentView(rootF);
        // 设置页改为首次切入时才构建(懒加载)，显著加快冷启动
    }

    TextView tab(String s){ TextView t=new TextView(this); t.setText(s); t.setTextSize(15); t.setGravity(Gravity.CENTER); t.setTextColor(Color.WHITE); t.setBackground(glass()); liquidFx(t); return t; }

    LinearLayout cardWeb(){
        LinearLayout c=new LinearLayout(this); c.setOrientation(LinearLayout.VERTICAL); c.setPadding(dp(16),dp(16),dp(16),dp(16));
        c.setBackground(glass());
        urlInput=new EditText(this); urlInput.setHint("输入网址，如 www.bilibili.com");
        urlInput.setTextSize(15); urlInput.setSingleLine(true); urlInput.setPadding(dp(12),dp(10),dp(12),dp(10));
        urlInput.setBackground(shp(12,0x66000000)); urlInput.setTextColor(Color.WHITE); urlInput.setHintTextColor(0xAAFFFFFF);
        c.addView(urlInput);
        c.addView(gap(10));
        LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
        r.addView(gbtn("📋 粘贴",grad(14,new int[]{0xFF8E9EAB,0xFF64748B}),new View.OnClickListener(){public void onClick(View v){paste();}}),new LinearLayout.LayoutParams(0,-2,1f));
        r.addView(sp(10));
        r.addView(gbtn("🌐 智能浏览器",grad(14,new int[]{0xFF4CAF50,0xFF2E7D32}),new View.OnClickListener(){public void onClick(View v){openWeb(urlInput.getText().toString().trim());}}),new LinearLayout.LayoutParams(0,-2,1f));
        c.addView(r);
        c.addView(gap(12));
        c.addView(gbtn("🔓 一键开网（重启后点一次）",grad(14,new int[]{0xFF10B981,0xFF059669}),new View.OnClickListener(){public void onClick(View v){doOpenNet();}}));
        c.addView(gap(10));
        c.addView(gbtn("🔧 一键打开 ADB / USB 调试",grad(14,new int[]{0xFFF59E0B,0xFFD97706}),new View.OnClickListener(){public void onClick(View v){openAdb();}}));
        c.addView(gap(10));
        c.addView(gbtn("🌐 检测：能否连白名单外网站(B站等)",grad(14,new int[]{0xFFA78BFA,0xFF7C3AED}),new View.OnClickListener(){public void onClick(View v){runNetDetect();}}));
        netResult=new TextView(this); netResult.setText("未检测 · 点上方按钮实测连接"); netResult.setTextColor(0xDDFFFFFF); netResult.setTextSize(13);
        netResult.setPadding(dp(4),dp(10),dp(4),0);
        c.addView(netResult);
        return c;
    }
    GradientDrawable shp(int r,int c){ GradientDrawable g=new GradientDrawable(); g.setCornerRadius(dp(r)); g.setColor(c); return g; }
    GradientDrawable grad(int r,int[] cs){ GradientDrawable g=new GradientDrawable(GradientDrawable.Orientation.TL_BR,cs); g.setCornerRadius(dp(r)); g.setStroke(dp(1),0x66FFFFFF); return g; }

    Btn gbtn(String t,Drawable bg,View.OnClickListener l){ Btn b=new Btn(this); b.setText(t); b.setTextColor(Color.WHITE); b.setTextSize(15); b.setAllCaps(false);
        b.setBackground(bg); b.setPadding(dp(12),dp(13),dp(12),dp(13)); b.setOnClickListener(l);
        liquidFx(b);
        return b; }

    void buildSettings(){
        if(settingsBody==null) return;
        settingsBody.removeAllViews();
        settingsBody.addView(secOpt("📄 关于本应用（版本 · 开发者）",new Runnable(){public void run(){openAbout();}}));
        settingsBody.addView(secOpt("🔍 设备自检与一键修复",new Runnable(){public void run(){openCheck();}}));
        settingsBody.addView(gap(2));
        settingsBody.addView(secTitle("📖 使用说明（简短版）"));
        TextView help=new TextView(this);
        help.setText(
            "▍网页页\n"+
            "· 输入网址 → 「🚀内置白名单浏览器」打开（可上任意网站）\n"+
            "· 「🟢 Chrome」用 Chrome 打开；「📋粘贴」快速贴入链接\n"+
            "· 「🌐检测」实测能否连接白名单外网站(B站等)\n"+
            "· 「🔓一键开网」被拦时点击清规则恢复网络\n"+
            "\n▍应用页\n"+
            "· 顶部搜索框：按名称/包名过滤，✕ 一键清空\n"+
            "· 列表最顶再往下拉 = 刷新应用列表\n"+
            "· 点按 = 打开应用；长按 = 详情（含卸载）\n"+
            "\n▍设置页\n"+
            "· 🎨 界面背景 / 🖼 壁纸：换背景与桌面壁纸\n"+
            "· 🧊 玻璃透明度：5 档实时调整按钮玻璃质感\n"+
            "· 🛡 网络守护：开启后被拦自动开网（重启自启）\n"+
            "· 🛠 Shizuku 桌面管理：切换 Lawnchair/卓越 桌面\n"+
            "· ⚡ 工具：设默认浏览器、开 ADB、手动开网\n"+
            "\n▍Shizuku 使用前提\n"+
            "· 先装 Shizuku 并以 adb 启动一次，App 内「🔑授权」\n"+
            "· 设备重启后 Shizuku 需重新用 adb 启动");
        help.setTextSize(13); help.setTextColor(0xE6FFFFFF); help.setBackground(glass());
        help.setPadding(dp(14),dp(12),dp(14),dp(12));
        settingsBody.addView(help);
        settingsBody.addView(gap(6));
        settingsBody.addView(secTitle("🎨 界面背景"));
        settingsBody.addView(secOpt("渐变星空（默认）",new Runnable(){public void run(){setBgStyle(0);}}));
        settingsBody.addView(secOpt("薄荷清新",new Runnable(){public void run(){setBgStyle(1);}}));
        settingsBody.addView(secOpt("深蓝夜穹",new Runnable(){public void run(){setBgStyle(2);}}));
        settingsBody.addView(secOpt("系统壁纸 · 毛玻璃",new Runnable(){public void run(){setBgStyle(3);}}));
        settingsBody.addView(gap(2));
        settingsBody.addView(secTitle("🧊 玻璃透明度（点选即时生效）"));
        int ga=prefs.getInt("ga",2);
        settingsBody.addView(secOpt((ga==0?"▣ 当前：很透明":"▢ 很透明"),new Runnable(){public void run(){setGlassAlpha(0);}}));
        settingsBody.addView(secOpt((ga==1?"▣ 当前：偏透明":"▢ 偏透明"),new Runnable(){public void run(){setGlassAlpha(1);}}));
        settingsBody.addView(secOpt((ga==2?"▣ 当前：默认":"▢ 默认"),new Runnable(){public void run(){setGlassAlpha(2);}}));
        settingsBody.addView(secOpt((ga==3?"▣ 当前：偏实":"▢ 偏实"),new Runnable(){public void run(){setGlassAlpha(3);}}));
        settingsBody.addView(secOpt((ga==4?"▣ 当前：最实":"▢ 最实"),new Runnable(){public void run(){setGlassAlpha(4);}}));
        settingsBody.addView(gap(4));
        settingsBody.addView(secTitle("🖼 桌面壁纸（点按即设）"));
        settingsBody.addView(secOpt("紫霞渐变",new Runnable(){public void run(){setWall(0);}}));
        settingsBody.addView(secOpt("蔚蓝海洋",new Runnable(){public void run(){setWall(1);}}));
        settingsBody.addView(secOpt("暖橙黄昏",new Runnable(){public void run(){setWall(2);}}));
        settingsBody.addView(secOpt("墨绿森林",new Runnable(){public void run(){setWall(3);}}));
        settingsBody.addView(gap(4));
        settingsBody.addView(secTitle("🛡 网络守护（自动开网）"));
        boolean gd=prefs.getBoolean("guard_on",false);
        TextView gs=new TextView(this); gs.setText(gd?"守护运行中：被拦后自动开网（重启后也会自动拉起）":"守护未启动：被拦后需手动点「一键开网」");
        gs.setTextColor(0xDDFFFFFF); gs.setTextSize(12); gs.setBackground(glass()); gs.setPadding(dp(14),dp(10),dp(14),dp(10));
        settingsBody.addView(gs);
        settingsBody.addView(gap(6));
        settingsBody.addView(secOpt(gd?"🟢 守护已开启 · 点此重启":"▶ 启动网络守护",new Runnable(){public void run(){startGuard();}}));
        settingsBody.addView(secOpt("⏹ 停止网络守护",new Runnable(){public void run(){stopGuard();}}));
        settingsBody.addView(gap(6));
        settingsBody.addView(secTitle("🛠 桌面管理（Shizuku）"));
        boolean sz=ShizukuUtil.running();
        boolean authed=sz&&ShizukuUtil.permission()==0;
        TextView st=new TextView(this); st.setText(
            !sz?"Shizuku 未运行：先用 adb 启动，或重启后需重新启动。":
            (!authed?"Shizuku 运行中 · 未授权 → 点「授权」":"Shizuku 运行中 · 已授权 ✓"));
        st.setTextColor(sz?0xDDFFFFFF:0x99FFAAAA); st.setTextSize(12); st.setBackground(glass()); st.setPadding(dp(14),dp(10),dp(14),dp(10));
        settingsBody.addView(st);
        settingsBody.addView(gap(6));
        settingsBody.addView(secOpt("🔑 授权 Shizuku（首次/重装后）",new Runnable(){public void run(){ShizukuUtil.requestPerm();}}));
        settingsBody.addView(secOpt("🔀 切到 Lawnchair 桌面（停用卓越）",new Runnable(){public void run(){szToLawnchair();}}));
        settingsBody.addView(secOpt("↩️ 恢复卓越Launcher 桌面",new Runnable(){public void run(){szRestoreZy();}}));
        settingsBody.addView(secOpt("📊 查询桌面状态",new Runnable(){public void run(){szStatus();}}));
        settingsBody.addView(gap(6));
        boolean fbok=android.provider.Settings.canDrawOverlays(this);
        settingsBody.addView(secTitle("🪄 悬浮球导航（返回/主页/最近/开网）"));
        TextView fb=new TextView(this); fb.setText(fbok?"悬浮窗已授权":"未授权悬浮窗 → 点下方「授权」");
        fb.setTextColor(fbok?0xDDFFFFFF:0xFFFFB199); fb.setTextSize(12); fb.setBackground(glass()); fb.setPadding(dp(14),dp(10),dp(14),dp(10));
        settingsBody.addView(fb);
        settingsBody.addView(gap(6));
        settingsBody.addView(secOpt(fbok?"🟢 启动悬浮球（退出App也常驻）":"🔑 授权悬浮窗",new Runnable(){public void run(){ if(fbok){ startFloatBall(); } else { requestOverlay(); } }}));
        settingsBody.addView(secOpt("⏹ 停止悬浮球",new Runnable(){public void run(){ stopFloatBall(); }}));
        settingsBody.addView(gap(4));
        settingsBody.addView(secTitle("📝 悬浮球菜单排序（▲上移 ▼下移，即时生效）"));
        settingsBody.addView(secOpt("➕ 添加要打开的应用",new Runnable(){public void run(){ pickCustomApp(); }}));
        floatOrderList=new LinearLayout(this); floatOrderList.setOrientation(LinearLayout.VERTICAL);
        settingsBody.addView(floatOrderList);
        refreshFloatOrder();
        settingsBody.addView(gap(6));
        settingsBody.addView(secTitle("⚡ 默认应用 / 工具"));
        settingsBody.addView(secOpt("🌐 设为默认浏览器（守护会保持，需授权一次）",new Runnable(){public void run(){fixDefaultBrowser();}}));
        settingsBody.addView(secOpt("🏠 设为桌面/主页（抢回 HOME，需授权一次）",new Runnable(){public void run(){fixDefaultHome();}}));
        settingsBody.addView(secOpt("打开系统默认应用设置",new Runnable(){public void run(){openDefaultAppsSettings();}}));
        settingsBody.addView(secOpt("🔓 一键开网（手动）",new Runnable(){public void run(){doOpenNet();}}));
        settingsBody.addView(secOpt("🔧 一键打开 ADB / USB 调试",new Runnable(){public void run(){openAdb();}}));
        settingsBody.addView(gap(4));
        settingsBody.addView(secTitle("ℹ️ 提示"));
        TextView tip=new TextView(this); tip.setText("想了解版本、开发者与功能？点顶部「📄 关于本应用」"); tip.setTextSize(12); tip.setTextColor(0xBBFFFFFF); tip.setPadding(dp(8),dp(4),dp(8),dp(6));
        settingsBody.addView(tip);
    }
    TextView secTitle(String s){ TextView t=new TextView(this); t.setText(s); t.setTextSize(13); t.setTextColor(0xCCFFFFFF); t.setPadding(dp(4),dp(6),dp(4),dp(4)); return t; }
    Btn secOpt(String label,final Runnable act){ Btn b=new Btn(this); b.setText(label); b.setTextColor(Color.WHITE); b.setTextSize(14); b.setAllCaps(false);
        b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL); b.setPadding(dp(14),dp(12),dp(14),dp(12)); b.setBackground(glass());
        b.setOnClickListener(new View.OnClickListener(){public void onClick(View v){act.run();}});
        liquidFx(b);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); lp.bottomMargin=dp(6); b.setLayoutParams(lp); return b; }


    void openAbout(){ try{ com.helper.urlfeeder.FloatBallService.collapseMenu(); }catch(Exception e){} if(aboutPage==null) buildAbout(); try{ aboutPage.bringToFront(); }catch(Exception e){} aboutPage.setVisibility(View.VISIBLE); }
    void closeAbout(){ if(aboutPage!=null) aboutPage.setVisibility(View.GONE); }
    // ---------- 设备自检页 ----------
    LinearLayout checkBody;
    LinearLayout floatOrderList;
    String[] FO_ID={"back","home","app","recent","net"};
    String[] FO_NAME={"◀ 返回","● 主页","🧰 打开主界面","▦ 最近","🔓 开网"};
    void openCheck(){ try{ com.helper.urlfeeder.FloatBallService.collapseMenu(); }catch(Exception e){} if(checkPage==null) buildCheck(); try{ checkPage.bringToFront(); }catch(Exception e){} checkPage.setVisibility(View.VISIBLE); refreshCheck(); }
    void closeCheck(){ if(checkPage!=null) checkPage.setVisibility(View.GONE); }
    void buildCheck(){
        if(rootF==null) return;
        checkPage=new FrameLayout(this);
        checkPage.setBackground(gradBg(new int[]{0xFF0E1428,0xFF1B2345,0xFF101736}));
        // 内容放滚动容器，避免超高/与状态栏挤压
        ScrollView csv=new ScrollView(this); csv.setSmoothScrollingEnabled(true);
        checkPage.addView(csv,new FrameLayout.LayoutParams(-1,-1));
        final LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        // fallback：初始即用主内容的状态栏高度(insets 对初始 GONE 的页面不派发)
        int top0=(content!=null&&content.getPaddingTop()>0)?content.getPaddingTop():dp(10);
        body.setPadding(dp(18),top0+dp(2),dp(18),dp(14));
        csv.addView(body);
        checkBody=body;
        if(Build.VERSION.SDK_INT>=20){
            checkPage.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener(){
                public android.view.WindowInsets onApplyWindowInsets(View v,android.view.WindowInsets ins){
                    int top=ins.getSystemWindowInsetTop(); int bot=ins.getSystemWindowInsetBottom();
                    body.setPadding(dp(18),Math.max(dp(6),top+dp(4)),dp(18),bot+dp(10));
                    return ins; }});
        }
        rootF.addView(checkPage,new FrameLayout.LayoutParams(-1,-1));
        checkPage.setVisibility(View.GONE);
    }
    void refreshCheck(){
        if(checkBody==null) return;
        checkBody.removeAllViews();
        LinearLayout bar=new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL);
        Btn back=gbtn("← 返回",glass(),new View.OnClickListener(){public void onClick(View v){closeCheck();}});
        back.setTextSize(13); bar.addView(back);
        TextView t1=new TextView(this); t1.setText("🔍 设备自检"); t1.setTextColor(Color.WHITE); t1.setTextSize(17); t1.setTypeface(null,Typeface.BOLD);
        bar.addView(t1,new LinearLayout.LayoutParams(0,-2,1f)); t1.setGravity(Gravity.CENTER);
        checkBody.addView(bar);
        checkBody.addView(gap(6));
        Btn re=gbtn("🔄 重新检测",shp(12,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ refreshCheck(); }});
        re.setTextSize(12); checkBody.addView(re);
        checkBody.addView(gap(6));

        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL);
        card.setBackground(glass()); card.setPadding(dp(14),dp(12),dp(14),dp(12));
        String fw=pmState("cn.com.microtrust.firewall");
        rowChk(card,"管控防火墙App",fw.indexOf("停用")<0&&fw.indexOf("未装")<0, fw);
        java.util.List<String[]> br=webBrowsers();
        rowChk(card,"可用浏览器(轮换)",br.size()>0, "数量 "+br.size());
        String home="?";
        try{ Intent h=new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri=getPackageManager().resolveActivity(h,PackageManager.MATCH_DEFAULT_ONLY);
            home=(ri!=null&&ri.activityInfo!=null)?ri.activityInfo.packageName:"(无)";
        }catch(Exception e){}
        rowChk(card,"默认桌面",home.indexOf("lawnchair")>=0||home.indexOf("urlfeeder")>=0, home);
        boolean guard=gRunning();
        rowChk(card,"网络守护",guard, guard?"运行中":"未运行");
        boolean sz=ShizukuUtil.running();
        boolean authed=sz&&ShizukuUtil.permission()==0;
        rowChk(card,"Shizuku",authed, sz?(authed?"运行·已授权":"运行·未授权"):"未运行");
        checkBody.addView(card);
        checkBody.addView(gap(8));

        TextView fx=new TextView(this); fx.setText("一键修复（需 Shizuku 授权）"); fx.setTextColor(0xCCFFFFFF); fx.setTextSize(13); fx.setTypeface(null,Typeface.BOLD);
        checkBody.addView(fx);
        checkBody.addView(gap(4));
        Btn f1=gbtn("🔧 启用管控防火墙App（若被停用）",shp(12,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ szFix("enable","cn.com.microtrust.firewall"); }});
        f1.setTextSize(13); checkBody.addView(f1);
        Btn f2=gbtn("🔓 一键开网（清规则）",shp(12,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ doOpenNet(); }});
        f2.setTextSize(13); checkBody.addView(f2);
        Btn f3=gbtn("🛡 启动/重启守护",shp(12,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ if(!gRunning()) launchGuardSilent(); else toast("守护已在运行"); }});
        f3.setTextSize(13); checkBody.addView(f3);
        TextView note=new TextView(this); note.setText("自检仅读取状态；修复需 Shizuku 授权后执行。"); note.setTextSize(11); note.setTextColor(0x88FFFFFF);
        checkBody.addView(note);
    }
    void rowChk(LinearLayout c,String k,boolean ok){ rowChk(c,k,ok,ok?"正常":""); }
    void rowChk(LinearLayout c,String k,boolean ok,String extra){
        LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setGravity(Gravity.CENTER_VERTICAL);
        TextView kk=new TextView(this); kk.setText(k); kk.setTextColor(0xE6FFFFFF); kk.setTextSize(13);
        r.addView(kk,new LinearLayout.LayoutParams(0,-2,1f));
        String disp=(extra!=null&&extra.length()>0)?extra:"";
        TextView vv=new TextView(this); vv.setText((ok?"✅ ":"⚠️ ")+disp); vv.setTextColor(ok?0xFF6EE7B7:0xFFFFB199); vv.setTextSize(12);
        r.addView(vv,new LinearLayout.LayoutParams(-2,-2));
        c.addView(r);
    }
    boolean gRunning(){
        try{ android.app.ActivityManager am=(android.app.ActivityManager)getSystemService(Context.ACTIVITY_SERVICE);
            java.util.List<android.app.ActivityManager.RunningServiceInfo> l=am.getRunningServices(300);
            if(l!=null) for(android.app.ActivityManager.RunningServiceInfo si:l) if(si.service!=null&&"com.helper.urlfeeder.GuardService".equals(si.service.getClassName())) return true;
        }catch(Exception e){}
        return false;
    }
    String pmState(String pkg){
        try{
            int st=getPackageManager().getApplicationEnabledSetting(pkg);
            if(st==PackageManager.COMPONENT_ENABLED_STATE_DISABLED||st==PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER) return "停用";
            return "启用";
        }catch(Exception e){ return "未装"; }
    }
    void szFix(final String action,final String pkg){
        if(!ShizukuUtil.running()){ toast("Shizuku 未运行"); return; }
        if(ShizukuUtil.permission()!=0){ toast("请先授权 Shizuku"); return; }
        toast("执行中…");
        new Thread(new Runnable(){ public void run(){
            String r=ShizukuUtil.pm(action,pkg);
            final String res=r;
            runOnUiThread(new Runnable(){ public void run(){ toast("结果: "+res); refreshCheck(); }});
        }}).start();
    }
    void launchGuardSilent(){
        try{ Intent s=new Intent(this,GuardService.class); s.setAction("start");
            if(Build.VERSION.SDK_INT>=26) startForegroundService(s); else startService(s);
            prefs.edit().putBoolean("guard_on",true).putBoolean("guard_off",false).commit();
            toast("守护已启动"); addLog("网络守护已启动");
        }catch(Exception e){ toast("启动失败:"+e.getMessage()); }
    }

    public void onBackPressed(){
        if(checkPage!=null&&checkPage.getVisibility()==View.VISIBLE){ closeCheck(); return; }
        if(aboutPage!=null&&aboutPage.getVisibility()==View.VISIBLE){ closeAbout(); return; }
        super.onBackPressed();
    }
    void buildAbout(){
        if(rootF==null) return;
        aboutPage=new FrameLayout(this);
        aboutPage.setBackground(gradBg(new int[]{0xFF0E1428,0xFF1B2345,0xFF101736}));
        final LinearLayout body=new LinearLayout(this); body.setOrientation(LinearLayout.VERTICAL);
        final int fallbackTop=(content!=null&&content.getPaddingTop()>0)?content.getPaddingTop()+dp(4):dp(18);
        body.setPadding(dp(18),fallbackTop,dp(18),dp(14));
        aboutPage.addView(body,new FrameLayout.LayoutParams(-1,-1));
        if(Build.VERSION.SDK_INT>=20){
            aboutPage.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener(){
                public android.view.WindowInsets onApplyWindowInsets(View v,android.view.WindowInsets ins){
                    int top=ins.getSystemWindowInsetTop(); int bot=ins.getSystemWindowInsetBottom();
                    body.setPadding(dp(18),top>0?top+dp(4):fallbackTop,dp(18),bot+dp(10));
                    return ins; }});
        }
        // header with back
        LinearLayout bar=new LinearLayout(this); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        Btn back=gbtn("← 返回",glass(),new View.OnClickListener(){public void onClick(View v){closeAbout();}});
        back.setTextSize(13);
        bar.addView(back,new LinearLayout.LayoutParams(-2,-2));
        body.addView(bar);
        body.addView(gap(6));
        // icon + name
        LinearLayout head=new LinearLayout(this); head.setOrientation(LinearLayout.VERTICAL); head.setGravity(Gravity.CENTER_HORIZONTAL);
        head.setBackground(glass()); head.setPadding(dp(18),dp(22),dp(18),dp(18));
        ImageView ic=new ImageView(this);
        try{ ic.setImageDrawable(getPackageManager().getApplicationIcon(getPackageName())); }catch(Exception e){}
        head.addView(ic,new LinearLayout.LayoutParams(dp(72),dp(72)));
        TextView nm=new TextView(this); nm.setText("万能转发器"); nm.setTextColor(Color.WHITE); nm.setTextSize(20); nm.setTypeface(null,Typeface.BOLD);
        LinearLayout.LayoutParams nmp=new LinearLayout.LayoutParams(-2,-2); nmp.topMargin=dp(8);
        head.addView(nm,nmp);
        TextView sub=new TextView(this); sub.setText("网址转发 · 应用抽屉 · 一键开网 · 网络守护"); sub.setTextColor(0xBBFFFFFF); sub.setTextSize(12);
        head.addView(sub);
        body.addView(head);
        body.addView(gap(10));
        // version info card
        String vn="",vc="";
        try{ android.content.pm.PackageInfo pi=getPackageManager().getPackageInfo(getPackageName(),0); vn=pi.versionName; vc=""+pi.versionCode; }catch(Exception e){}
        if(vn==null||vn.length()==0) vn="?";
        LinearLayout info=new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL);
        info.setBackground(glass()); info.setPadding(dp(14),dp(10),dp(14),dp(10));
        meta(info,"程序版本","v"+vn);
        meta(info,"版本代码",vc);
        meta(info,"开发者","dctc1494");
        meta(info,"包名","com.helper.urlfeeder");
        meta(info,"适用系统","Android 7.0+ (API 24)");
        meta(info,"目标版本","API 34 · Android 13/14");
        body.addView(info);
        body.addView(gap(10));
        // description card
        LinearLayout desc=new LinearLayout(this); desc.setOrientation(LinearLayout.VERTICAL);
        desc.setBackground(glass()); desc.setPadding(dp(14),dp(12),dp(14),dp(12));
        TextView d1=new TextView(this); d1.setText("功能简介"); d1.setTextColor(Color.WHITE); d1.setTextSize(14); d1.setTypeface(null,Typeface.BOLD);
        desc.addView(d1); desc.addView(gap(4));
        TextView d2=new TextView(this); d2.setText("· 任意网址 → 白名单浏览器 / Chrome 打开\n· 应用抽屉：长按应用查看详细信息\n· 一键开网：清空管控白名单规则\n· 网络守护：被拦截时自动恢复上网\n· 网络检测：实测能否连接白名单外网站"); d2.setTextColor(0xDDFFFFFF); d2.setTextSize(13);
        desc.addView(d2);
        body.addView(desc);
        body.addView(gap(10));
        TextView foot=new TextView(this); foot.setText("本工具仅供学习研究，请遵守设备管理方规定合规使用。\n© 2025 dctc1494");
        foot.setTextSize(11); foot.setTextColor(0x99FFFFFF); foot.setGravity(Gravity.CENTER);
        body.addView(foot);
        rootF.addView(aboutPage,new FrameLayout.LayoutParams(-1,-1));
        aboutPage.setVisibility(View.GONE);
    }
    void applyInsets(){
        if(Build.VERSION.SDK_INT>=20){
            content.setOnApplyWindowInsetsListener(new android.view.View.OnApplyWindowInsetsListener(){
                public android.view.WindowInsets onApplyWindowInsets(View v, android.view.WindowInsets insets){
                    int top=insets.getSystemWindowInsetTop();
                    int bottom=insets.getSystemWindowInsetBottom();
                    v.setPadding(0,top,0,bottom+dp(6));
                    return insets;
                }});
        }
    }
    void selectTab(int idx){
        curTab=idx;
        if(idx==2&&settingsBody!=null&&settingsBody.getChildCount()==0){ buildSettings(); }
        tabWeb.setBackground(idx==0?grad(22,new int[]{0xFF4F8DFF,0xFF6C5CE7}):glass());
        tabApps.setBackground(idx==1?grad(22,new int[]{0xFF4F8DFF,0xFF6C5CE7}):glass());
        tabSet.setBackground(idx==2?grad(22,new int[]{0xFF4F8DFF,0xFF6C5CE7}):glass());
        webScroll.setVisibility(idx==0?View.VISIBLE:View.GONE);
        pageWeb.setVisibility(idx==0?View.VISIBLE:View.GONE);
        pageApps.setVisibility(idx==1?View.VISIBLE:View.GONE);
        pageSet.setVisibility(idx==2?View.VISIBLE:View.GONE);
        View cur = idx==0?pageWeb:(idx==1?pageApps:pageSet);
        animateIn(cur);
    }
    void animateIn(final View v){ if(v==null) return; v.animate().cancel();
        v.setAlpha(0f); v.setTranslationY(dp(18));
        v.animate().alpha(1f).translationY(0).setDuration(360).setInterpolator(new DecelerateInterpolator()).start(); }

    void setBgStyle(int s){ prefs.edit().putInt("bg",s).commit(); applyBg(); }
    void setGlassAlpha(int l){
        prefs.edit().putInt("ga",l).commit();
        toast("玻璃透明度已更新 · 正在刷新界面…");
        rebuildUi();
    }
    // 重建整个 UI：用于 glass 等构建期取值的变化即时生效（保留当前标签与网页输入）
    void rebuildUi(){
        String u=""; if(urlInput!=null){ try{u=urlInput.getText().toString();}catch(Exception e){} }
        int t=curTab;
        dim=null; aboutPage=null; netResult=null; // 旧引用指向旧视图树，重建后置空防误用
        buildUi();
        applyInsets();
        applyBg();
        if(urlInput!=null&&u.length()>0) urlInput.setText(u);
        selectTab(t);
        if(t==1) buildApps();
    }
    void applyBg(){
        int s=prefs.getInt("bg",0);
        bgWall.setVisibility(View.GONE);
        if(dim!=null) dim.setVisibility(View.GONE);
        if(s==3){ content.setBackground(null); wallpaperBackdrop(); }
        else if(s==0) content.setBackground(gradBg(new int[]{0xFF2B3A67,0xFF574B90,0xFF4B2E83}));
        else if(s==1) content.setBackground(gradBg(new int[]{0xFF0F766E,0xFF14B8A6,0xFF0EA5E9}));
        else content.setBackground(gradBg(new int[]{0xFF0F172A,0xFF1E3A8A,0xFF172554}));
    }
    void wallpaperBackdrop(){
        try{
            WallpaperManager wm=WallpaperManager.getInstance(this);
            Drawable wd=wm.getDrawable();
            if(wd==null){ toast("无系统壁纸"); return; }
            int w=1080,h=1920;
            Bitmap bmp=Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
            Canvas c=new Canvas(bmp); wd.setBounds(0,0,w,h); wd.draw(c);
            bgWall.setImageBitmap(bmp);
            if(Build.VERSION.SDK_INT>=31){
                try{ android.graphics.RenderEffect ef=android.graphics.RenderEffect.createChainEffect(
                        android.graphics.RenderEffect.createBlurEffect(30f,30f,Shader.TileMode.CLAMP),
                        android.graphics.RenderEffect.createColorFilterEffect(new ColorMatrixColorFilter(sat(1.35f))));
                    bgWall.setRenderEffect(ef); }
                catch(Throwable ignored){}
            }
            bgWall.setVisibility(View.VISIBLE);
            if(dim==null){ dim=new View(this); rootF.addView(dim,new FrameLayout.LayoutParams(-1,-1)); }
            dim.setBackgroundColor(0x55000000); dim.setVisibility(View.VISIBLE);
        }catch(Throwable e){ toast("壁纸背景不可用"); }
    }

    void setWall(int preset){
        int[] cs;
        if(preset==0) cs=new int[]{0xFF8E2DE2,0xFF4A00E0};
        else if(preset==1) cs=new int[]{0xFF00C9FF,0xFF92FE9D};
        else if(preset==2) cs=new int[]{0xFFF7971E,0xFFFFD200};
        else cs=new int[]{0xFF11998E,0xFF38EF7D};
        Bitmap bmp=Bitmap.createBitmap(1080,1920,Bitmap.Config.ARGB_8888);
        Canvas c=new Canvas(bmp);
        Paint p=new Paint(); p.setShader(new LinearGradient(0,0,0,1920,cs[0],cs[1],Shader.TileMode.CLAMP));
        c.drawRect(0,0,1080,1920,p);
        try{ WallpaperManager.getInstance(this).setBitmap(bmp); toast("壁纸已设置 ✓"); }
        catch(Exception e){ toast("设置失败"); }
    }
    boolean isDefaultBrowser(){
        if(Build.VERSION.SDK_INT>=29){
            try{ android.app.role.RoleManager rm=(android.app.role.RoleManager)getSystemService("role");
                 if(rm!=null&&rm.isRoleHeld(ROLE_BROWSER)) return true;
            }catch(Throwable t){}
        }
        try{
            Intent v=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com"));
            android.content.pm.ResolveInfo ri=getPackageManager().resolveActivity(v,PackageManager.MATCH_DEFAULT_ONLY);
            if(ri!=null&&ri.activityInfo!=null&&getPackageName().equals(ri.activityInfo.packageName)) return true;
        }catch(Throwable t){}
        return false;
    }
    void fixDefaultBrowser(){
        if(isDefaultBrowser()){ toast("万能转发器已是默认浏览器 ✓"); return; }
        if(Build.VERSION.SDK_INT>=29){
            try{
                android.app.role.RoleManager rm=(android.app.role.RoleManager)getSystemService("role");
                if(rm!=null){
                    Intent i=rm.createRequestRoleIntent(ROLE_BROWSER);
                    startActivityForResult(i,88);
                    return;
                }
            }catch(Throwable t){}
        }
        try{ startActivity(new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")); }
        catch(Exception e){ openDefaultAppsSettings(); }
    }
    public void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req==88){ toast(res==RESULT_OK?"已设为默认浏览器 ✓ 后续无需再确认":"未设为默认（下次可再试）"); }
        if(req==89){ toast(res==RESULT_OK?"已设为默认桌面 ✓ 按 HOME 键生效":"未设为桌面（下次可再试）"); }
    }
    boolean isDefaultHome(){
        if(Build.VERSION.SDK_INT>=29){
            try{ android.app.role.RoleManager rm=(android.app.role.RoleManager)getSystemService("role");
                 if(rm!=null&&rm.isRoleHeld(ROLE_HOME)) return true;
            }catch(Throwable t){}
        }
        try{
            Intent h=new Intent(Intent.ACTION_MAIN); h.addCategory(Intent.CATEGORY_HOME);
            android.content.pm.ResolveInfo ri=getPackageManager().resolveActivity(h,PackageManager.MATCH_DEFAULT_ONLY);
            if(ri!=null&&ri.activityInfo!=null&&getPackageName().equals(ri.activityInfo.packageName)) return true;
        }catch(Throwable t){}
        return false;
    }
    void fixDefaultHome(){
        if(isDefaultHome()){ toast("万能转发器已是默认桌面 ✓ 按 HOME 键生效"); return; }
        if(Build.VERSION.SDK_INT>=29){
            try{
                android.app.role.RoleManager rm=(android.app.role.RoleManager)getSystemService("role");
                if(rm!=null){
                    Intent i=rm.createRequestRoleIntent(ROLE_HOME);
                    startActivityForResult(i,89);
                    return;
                }
            }catch(Throwable t){}
        }
        try{ startActivity(new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")); }
        catch(Exception e){ openDefaultAppsSettings(); }
    }
    void openDefaultAppsSettings(){
        try{ startActivity(new Intent("android.settings.MANAGE_DEFAULT_APPS_SETTINGS")); }
        catch(Exception e){ try{ startActivity(new Intent(android.provider.Settings.ACTION_SETTINGS)); }catch(Exception e2){ toast("无法打开设置"); } }
    }

    // ---------- Shizuku 桌面管理 ----------
    void szCheck(final String who){
        if(!ShizukuUtil.running()){ toast("Shizuku 未运行"); return; }
        if(ShizukuUtil.permission()!=0){ toast("请先点「授权 Shizuku」"); return; }
        new Thread(new Runnable(){ public void run(){
            final String r=ShizukuUtil.cmd("role","get-role-holder","--user","0","android.app.role.HOME");
            runOnUiThread(new Runnable(){ public void run(){ toast((who==null?"":who+"\n")+r); }});
        }}).start();
    }
    void szToLawnchair(){
        if(!ShizukuUtil.running()){ toast("Shizuku 未运行"); return; }
        if(ShizukuUtil.permission()!=0){ toast("请先点「授权 Shizuku」"); return; }
        toast("正在切换到 Lawnchair…");
        new Thread(new Runnable(){ public void run(){
            StringBuilder sb=new StringBuilder();
            sb.append("1停用卓越:").append(ShizukuUtil.pm("disable-user","--user","0","com.zy.ai.launcher")).append("\n");
            sb.append("2设HOME:").append(ShizukuUtil.pm("set-home-activity","app.lawnchair")).append("\n");
            sb.append("3role:").append(ShizukuUtil.cmd("role","add-role-holder","--user","0","android.app.role.HOME","app.lawnchair")).append("\n");
            final String res=sb.toString();
            runOnUiThread(new Runnable(){ public void run(){ toast("结果:\n"+res); }});
        }}).start();
    }
    void szRestoreZy(){
        if(!ShizukuUtil.running()){ toast("Shizuku 未运行"); return; }
        if(ShizukuUtil.permission()!=0){ toast("请先点「授权 Shizuku」"); return; }
        toast("正在恢复卓越Launcher…");
        new Thread(new Runnable(){ public void run(){
            StringBuilder sb=new StringBuilder();
            sb.append("1启用:").append(ShizukuUtil.pm("enable","com.zy.ai.launcher")).append("\n");
            sb.append("2设HOME:").append(ShizukuUtil.pm("set-home-activity","com.zy.ai.launcher")).append("\n");
            sb.append("3role:").append(ShizukuUtil.cmd("role","add-role-holder","--user","0","android.app.role.HOME","com.zy.ai.launcher")).append("\n");
            final String res=sb.toString();
            runOnUiThread(new Runnable(){ public void run(){ toast("结果:\n"+res); }});
        }}).start();
    }
    void szStatus(){
        if(!ShizukuUtil.running()){ toast("Shizuku 未运行"); return; }
        if(ShizukuUtil.permission()!=0){ toast("请先点「授权 Shizuku」"); return; }
        new Thread(new Runnable(){ public void run(){
            StringBuilder sb=new StringBuilder();
            sb.append("HOME=").append(ShizukuUtil.cmd("role","get-role-holder","--user","0","android.app.role.HOME")).append("\n");
            sb.append("卓越enabled:").append(ShizukuUtil.pm("list","packages","-d").contains("com.zy.ai.launcher")?"已停用":"启用中");
            final String res=sb.toString();
            runOnUiThread(new Runnable(){ public void run(){ toast(res); }});
        }}).start();
    }

    void runNetDetect(){
        netResult.setText("检测中…（连 bilibili/bing/qq/163）");
        new Thread(new Runnable(){ public void run(){
            final String[][] hs={ {"www.bilibili.com","443"},{"www.bing.com","443"},{"www.qq.com","443"},{"www.163.com","443"} };
            final StringBuilder sb=new StringBuilder(); int ok=0;
            for(int i=0;i<hs.length;i++){
                boolean up=probe(hs[i][0],Integer.parseInt(hs[i][1]));
                if(up) ok++;
                sb.append(hs[i][0].replace("www.","")).append(" ").append(up?"✓":"✗").append("  ");
            }
            final int fok=ok; final String det=sb.toString();
            runOnUiThread(new Runnable(){ public void run(){
                if(fok==hs.length) netResult.setText("✅ 全通("+fok+"/"+hs.length+") · 开网有效，可上任意网站\n"+det);
                else if(fok>0) netResult.setText("⚠️ 部分通("+fok+"/"+hs.length+") · 可能半开\n"+det);
                else netResult.setText("❌ 全被拦 · 白名单规则生效中 → 请点「一键开网」\n"+det);
                String st=fok==hs.length?"全通":(fok>0?"部分通":"被拦");
                addLog("网络检测("+st+") "+det.trim());
            }});
        }}).start();
    }
    boolean probe(String host,int port){
        java.net.Socket sck=null;
        try{ sck=new java.net.Socket(); sck.connect(new java.net.InetSocketAddress(host,port),3000); return true; }
        catch(Exception e){ return false; }
        finally{ try{ if(sck!=null) sck.close(); }catch(Exception e){} }
    }
    void openAdb(){
        boolean ok=false;
        try{ ok=android.provider.Settings.Global.putInt(getContentResolver(),"adb_enabled",1); }catch(SecurityException e){ ok=false; }catch(Exception e){ ok=false; }
        try{
            if(ok) toast("ADB 已开启 ✓（adb_enabled=1）");
            else toast("无系统权限直写，请在下页打开「USB 调试」");
            startActivity(new Intent("android.settings.APPLICATION_DEVELOPMENT_SETTINGS"));
        }catch(Exception e){ toast("无法打开开发者选项"); }
    }
    void doOpenNet(){ try{ Intent i=new Intent(FW_ACTION); i.setPackage(FW_PKG); bindService(i,conn,Context.BIND_AUTO_CREATE); addLog("发起一键开网…"); }catch(Exception e){ toast("开网失败(bind)"); addLog("一键开网失败: bind"); } }
    void startGuard(){
        if(Build.VERSION.SDK_INT>=33){
            try{
                boolean has=checkSelfPermission("android.permission.POST_NOTIFICATIONS")==android.content.pm.PackageManager.PERMISSION_GRANTED;
                if(!has){ pendingGuard=true; requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},77); return; }
            }catch(Exception e){}
        }
        launchGuard();
    }
    boolean pendingGuard=false;
    void launchGuard(){
        try{
            Intent s=new Intent(this,GuardService.class); s.setAction("start");
            if(Build.VERSION.SDK_INT>=26) startForegroundService(s); else startService(s);
            prefs.edit().putBoolean("guard_on",true).putBoolean("guard_off",false).commit();
            toast("守护已启动 · 被拦后自动开网");
            addLog("网络守护已启动");
        }catch(Exception e){ toast("守护启动失败:"+e.getMessage()); addLog("守护启动失败: "+e.getMessage()); }
        buildSettings();
    }
    void stopGuard(){
        try{ Intent s=new Intent(this,GuardService.class); s.setAction("stop"); startService(s); }catch(Exception e){}
        prefs.edit().putBoolean("guard_on",false).putBoolean("guard_off",true).commit();
        toast("守护已停止");
        addLog("网络守护已停止");
        buildSettings();
    }
    void startFloatBall(){
        try{
            if(!android.provider.Settings.canDrawOverlays(this)){
                requestOverlay(); return;
            }
            Intent s=new Intent(this,FloatBallService.class);
            startService(s);
            prefs.edit().putBoolean("float_on",true).commit();
            toast("悬浮球已启动（开机也会自动开启）"); addLog("悬浮球导航已启动");
        }catch(Exception e){ toast("启动失败:"+e.getMessage()); }
    }
    void stopFloatBall(){
        try{ Intent s=new Intent(this,FloatBallService.class); stopService(s); prefs.edit().putBoolean("float_on",false).commit(); toast("悬浮球已停止"); addLog("悬浮球已停止"); }catch(Exception e){}
    }
    java.util.List<String> floatOrder(){
        java.util.List<String> ord=new java.util.ArrayList<String>();
        String saved=prefs.getString("float_order",null);
        if(saved!=null&&saved.length()>0){
            String[] arr=saved.split(",");
            for(String x:arr){ if(x!=null&&x.trim().length()>0) ord.add(x.trim()); }
        }
        for(String id:FO_ID){ if(!ord.contains(id)) ord.add(id); }
        return ord;
    }
    void saveFloatOrder(java.util.List<String> ord){
        StringBuilder sb=new StringBuilder();
        for(String x:ord){ sb.append(x).append(","); }
        prefs.edit().putString("float_order",sb.toString()).commit();
        // 通知悬浮球热重载
        try{ Intent s=new Intent(this,FloatBallService.class); s.setAction("reload"); startService(s); }catch(Exception e){}
    }
    void refreshFloatOrder(){
        if(floatOrderList==null) return;
        floatOrderList.removeAllViews();
        java.util.List<String> ord=floatOrder();
        for(int i=0;i<ord.size();i++){
            final int pos=i;
            final String id=ord.get(i);
            String nm=id;
            for(int k=0;k<FO_ID.length;k++){ if(FO_ID[k].equals(id)){ nm=FO_NAME[k]; break; } }
            if(id.startsWith("c")){ String cl=customLabel(id); if(cl!=null) nm=cl; else continue; }
            if("close".equals(id)) nm="✕ 关闭悬浮球（固定末位）";
            LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackground(glass()); row.setPadding(dp(10),dp(4),dp(6),dp(4));
            TextView lb=new TextView(this); lb.setText((pos+1)+". "+nm); lb.setTextColor(0xEFFFFFFF); lb.setTextSize(13);
            row.addView(lb,new LinearLayout.LayoutParams(0,-2,1f));
            if(!("close".equals(id))){
                Btn up=gbtn("▲",shp(10,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ moveFloatItem(pos,-1); }});
                up.setTextSize(11); row.addView(up,new LinearLayout.LayoutParams(-2,-2));
            }
            if(pos<ord.size()-1){
                Btn dn=gbtn("▼",shp(10,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ moveFloatItem(pos,1); }});
                dn.setTextSize(11);
                LinearLayout.LayoutParams dlp=new LinearLayout.LayoutParams(-2,-2); dlp.leftMargin=dp(4);
                row.addView(dn,dlp);
            }
            if(id.startsWith("c")){
                Btn del=gbtn("🗑",shp(10,0x66FF5555),new View.OnClickListener(){public void onClick(View v){ delCustom(id); }});
                del.setTextSize(11);
                LinearLayout.LayoutParams dlp2=new LinearLayout.LayoutParams(-2,-2); dlp2.leftMargin=dp(4);
                row.addView(del,dlp2);
            }
            LinearLayout.LayoutParams rlp=new LinearLayout.LayoutParams(-1,-2); if(pos>0) rlp.topMargin=dp(4);
            floatOrderList.addView(row,rlp);
        }
    }
    void moveFloatItem(int pos,int dir){
        java.util.List<String> ord=floatOrder();
        int to=pos+dir;
        if(to<0||to>=ord.size()) return;
        if("close".equals(ord.get(to))&&dir>0) return;      // close 不可插入(保持在末)
        String id=ord.remove(pos);
        if(dir<0&&"close".equals(ord.get(0))) { // 不移到 close 前? close 恒末, 直接放其前位置 to
        }
        if(to>=ord.size()) ord.add(id); else ord.add(to,id);
        // close 固定末尾
        if(ord.contains("close")){ ord.remove("close"); ord.add("close"); }
        saveFloatOrder(ord);
        refreshFloatOrder();
        toast("已调整，悬浮球菜单已更新");
    }
    // ---------- 自定义应用项 ----------
    java.util.List<String[]> customList(){
        java.util.List<String[]> out=new java.util.ArrayList<String[]>();
        String saved=prefs.getString("float_custom","");
        if(saved!=null&&saved.length()>0){
            String[] lines=saved.split("\n");
            for(String ln:lines){
                String[] f=ln.split("\\|",-1);
                if(f.length>=2&&f[0].trim().length()>0) out.add(new String[]{f[0],f[1]});
            }
        }
        return out;
    }
    void saveCustom(java.util.List<String[]> list){
        StringBuilder sb=new StringBuilder();
        for(String[] it:list) sb.append(it[0]).append("|").append(it[1]).append("\n");
        prefs.edit().putString("float_custom",sb.toString()).commit();
    }
    String customLabel(String id){
        try{
            int n=Integer.parseInt(id.substring(1));
            java.util.List<String[]> l=customList();
            if(n>=0&&n<l.size()) return l.get(n)[0];
        }catch(Exception e){}
        return null;
    }
    void delCustom(String id){
        int n=-1; try{ n=Integer.parseInt(id.substring(1)); }catch(Exception e){}
        if(n<0) return;
        java.util.List<String[]> l=customList();
        if(n>=l.size()) return;
        l.remove(n);
        saveCustom(l);
        // 更新顺序: 删除 cN 并把之后的 cM 改 cM-1
        java.util.List<String> ord=floatOrder();
        ord.remove("c"+n);
        java.util.List<String> nw=new java.util.ArrayList<String>();
        for(String x:ord){
            if(x.startsWith("c")){
                try{ int m=Integer.parseInt(x.substring(1)); if(m>n) nw.add("c"+(m-1)); else nw.add(x); }
                catch(Exception e){ nw.add(x); }
            } else nw.add(x);
        }
        saveFloatOrder(nw);
        refreshFloatOrder();
        toast("已移除该菜单项");
    }
    void addCustomApp(String label,String pkg){
        java.util.List<String[]> l=customList();
        // 去重
        for(String[] it:l){ if(it[1].equals(pkg)){ toast("该应用已在菜单中"); return; } }
        l.add(new String[]{label,pkg});
        saveCustom(l);
        java.util.List<String> ord=floatOrder();
        // close 之前插入 c{size-1}
        ord.add("c"+(l.size()-1));
        if(ord.contains("close")){ ord.remove("close"); ord.add("close"); }
        saveFloatOrder(ord);
        refreshFloatOrder();
        toast("已添加:「"+label+"」到悬浮球菜单");
    }

    void askCustomName(final String defLabel,final String pkg){
        try{
            final EditText et=new EditText(this);
            et.setHint("自定义名称（留空用应用原名）");
            et.setText(defLabel);
            et.setTextSize(15); et.setTextColor(Color.WHITE); et.setHintTextColor(0xAAFFFFFF);
            et.setBackground(shp(12,0x66000000)); et.setPadding(dp(12),dp(8),dp(12),dp(8));
            LinearLayout host=new LinearLayout(this); host.setOrientation(LinearLayout.VERTICAL);
            host.setPadding(dp(2),dp(8),dp(2),0);
            host.addView(et,new LinearLayout.LayoutParams(-1,-2));
            final android.app.AlertDialog dlg=new android.app.AlertDialog.Builder(this)
                .setTitle("添加到悬浮球菜单")
                .setMessage("应用: "+defLabel+"\n可自定义按钮名称")
                .setView(host)
                .setPositiveButton("添加",null)
                .setNegativeButton("取消",null)
                .create();
            dlg.setOnShowListener(new android.content.DialogInterface.OnShowListener(){ public void onShow(android.content.DialogInterface d){
                dlg.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(new View.OnClickListener(){ public void onClick(View v){
                    String nm=et.getText()==null?"":et.getText().toString().trim();
                    if(nm.length()==0) nm=defLabel;
                    try{ dlg.dismiss(); }catch(Exception e){}
                    addCustomApp(nm,pkg);
                }});
            }});
            dlg.show();
        }catch(Exception e){ addCustomApp(defLabel,pkg); }
    }
    void pickCustomApp(){
        try{
            try{ com.helper.urlfeeder.FloatBallService.collapseMenu(); }catch(Exception e){}
            // 弹应用选择对话框
            final android.app.Dialog d=new android.app.Dialog(this);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            if(d.getWindow()!=null){ d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000)); d.getWindow().setDimAmount(0.35f); }
            LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable gd=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xF2253160,0xF2121A40});
            gd.setCornerRadius(dp(24)); gd.setStroke(dp(1),0x66FFFFFF);
            p.setBackground(gd); p.setPadding(dp(18),dp(16),dp(18),dp(14));
            TextView tt=new TextView(this); tt.setText("选择要加入悬浮球菜单的应用"); tt.setTextColor(Color.WHITE); tt.setTextSize(16); tt.setTypeface(null,Typeface.BOLD);
            p.addView(tt);
            ScrollView sv=new ScrollView(this);
            LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL);
            sv.addView(list);
            p.addView(sv,new LinearLayout.LayoutParams(-1,dp(380)));
            try{
                Intent mi=new Intent(Intent.ACTION_MAIN); mi.addCategory(Intent.CATEGORY_LAUNCHER);
                final java.util.List<ResolveInfo> ri=getPackageManager().queryIntentActivities(mi,0);
                java.util.List<String[]> apps=new java.util.ArrayList<String[]>();
                for(ResolveInfo rr:ri){
                    try{ apps.add(new String[]{rr.loadLabel(getPackageManager()).toString(),rr.activityInfo.packageName}); }catch(Exception e){}
                }
                Collections.sort(apps,new Comparator<String[]>(){public int compare(String[] a,String[] b){return a[0].compareToIgnoreCase(b[0]);}});
                for(final String[] a:apps){
                    Btn b=gbtn(a[0],glass(),new View.OnClickListener(){public void onClick(View v){ try{ d.dismiss(); }catch(Exception e){} askCustomName(a[0],a[1]); }});
                    b.setTextSize(13); b.setGravity(Gravity.LEFT|Gravity.CENTER_VERTICAL);
                    LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-1,-2);
                    blp.bottomMargin=dp(2);
                    b.setLayoutParams(blp);
                    list.addView(b);
                }
            }catch(Exception e){}
            d.setContentView(p,new android.widget.FrameLayout.LayoutParams(dp(360),-2));
            d.setCanceledOnTouchOutside(true);
            d.show();
        }catch(Exception e){ toast("无法打开应用列表"); }
    }
    void requestOverlay(){
        try{
            Intent i=new Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:"+getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
        }catch(Exception e){ toast("无法打开悬浮窗授权页"); }
    }
    public void onRequestPermissionsResult(int code,String[] perms,int[] gr){
        super.onRequestPermissionsResult(code,perms,gr);
        if(code==77){ if(pendingGuard){ pendingGuard=false; launchGuard(); } }
    }
    ServiceConnection conn=new ServiceConnection(){
        public void onServiceConnected(ComponentName n,IBinder b){ new Thread(new Runnable(){public void run(){
            String r=""; r+=call(b,9,null)+" "; r+=call(b,10,null)+" "; r+=call(b,1,1)+" "; r+=call(b,11,null);
            final String fr=r; runOnUiThread(new Runnable(){public void run(){ toast("开网完成 "+fr); addLog("一键开网 → "+fr); }});
            try{ unbindService(conn); }catch(Exception e){}
        }}).start(); }
        public void onServiceDisconnected(ComponentName n){}
        public void onBindingDied(ComponentName n){}
        public void onNullBinding(ComponentName n){}
    };
    String call(IBinder b,int code,Integer arg){ Parcel d=Parcel.obtain(),r=Parcel.obtain();
        try{ d.writeInterfaceToken(FW_IFACE); if(arg!=null)d.writeInt(arg); boolean t=b.transact(code,d,r,0); r.readException(); int res=r.readInt(); return "c"+code+"="+res; }
        catch(Exception e){ return "c"+code+"ERR"; } finally{ d.recycle(); r.recycle(); } }

    void makePullHead(){
        pullHead=new LinearLayout(this); pullHead.setOrientation(LinearLayout.VERTICAL);
        pullHead.setGravity(Gravity.CENTER);
        triggerPx=dp(52); lockPx=dp(150);
        LinearLayout inner=new LinearLayout(this); inner.setOrientation(LinearLayout.HORIZONTAL);
        inner.setGravity(Gravity.CENTER_VERTICAL);
        arrowView=new RefreshArrow(this);
        inner.addView(arrowView,new LinearLayout.LayoutParams(dp(26),dp(26)));
        TextView spc=new TextView(this); spc.setText(" "); spc.setTextSize(8);
        inner.addView(spc);
        pullTv=new TextView(this); pullTv.setText("\u2193 下拉刷新应用列表");
        pullTv.setTextColor(0xCCFFFFFF); pullTv.setTextSize(13); pullTv.setGravity(Gravity.CENTER);
        inner.addView(pullTv);
        pullHead.addView(inner,new LinearLayout.LayoutParams(-2,-2));
        pullHead.setLayoutParams(new LinearLayout.LayoutParams(-1,0));
    }
    void setPullH(int px){
        if(pullHead==null) return;
        if(px<0) px=0;
        android.view.ViewGroup.LayoutParams lp=pullHead.getLayoutParams();
        if(lp==null){ lp=new LinearLayout.LayoutParams(-1,0); pullHead.setLayoutParams(lp); }
        lp.height=px; pullHead.requestLayout();
        if(!spinning) syncArrow(px);
    }
    int getPullH(){ if(pullHead==null||pullHead.getLayoutParams()==null) return 0; return pullHead.getLayoutParams().height; }
    // 下拉进度→箭头 0..360°，跨过阈值触发一次触觉震动
    void syncArrow(int h){
        if(arrowView==null) return;
        float p=h/(float)triggerPx; if(p>1f) p=1f; if(p<0f) p=0f;
        arrowView.setAngle(p*360f);
        boolean reached=p>=1f;
        if(reached&&!hapticFired&&!refreshing){
            hapticFired=true;
            try{ if(pullHead!=null) pullHead.performHapticFeedback(android.view.HapticFeedbackConstants.CONTEXT_CLICK); }catch(Throwable t){}
        } else if(!reached){ hapticFired=false; }
    }
    // 松手回弹：真实弹簧模拟（欠阻尼 ζ=0.78，带轻微过冲惯性）
    void collapsePull(){
        if(pullHead==null) return;
        if(refreshing){ return; }
        if(getPullH()<=0){ if(pullTv!=null) pullTv.setText("\u2193 下拉刷新应用列表"); if(arrowView!=null) arrowView.setAngle(0); return; }
        final float omega=11.2f;          // 自然频率 rad/s
        final float zeta=0.78f;           // 阻尼比
        final float k=omega*omega;        // 刚度(质量=1)
        final float c=2*zeta*omega;       // 阻尼
        final float[] x={getPullH()};     // 位置(px)
        final float[] v={0f};             // 速度
        final long[] last={android.os.SystemClock.uptimeMillis()};
        final Runnable tick=new Runnable(){ public void run(){
            long now=android.os.SystemClock.uptimeMillis();
            float dt=Math.min(0.033f,(now-last[0])/1000f); last[0]=now;
            float a= -k*(x[0]) - c*v[0];
            v[0]+=a*dt; x[0]+=v[0]*dt;
            if(x[0]<=0f){                 // 底部边界：反弹一次制造轻微过冲，再收敛
                x[0]=0f;
                if(v[0]<-60f) v[0]=-v[0]*0.24f; else v[0]=0f;
            }
            int h=(int)x[0];
            setPullH(h);
            boolean stop= (Math.abs(x[0])<0.8f && Math.abs(v[0])<14f);
            if(!stop) uiH.postDelayed(this,16);
            else {
                setPullH(0);
                if(arrowView!=null) arrowView.setAngle(0);
                if(pullTv!=null) pullTv.setText("\u2193 下拉刷新应用列表");
            }
        }};
        uiH.postDelayed(tick,16);
    }
    void doRefreshApps(){
        refreshing=true;
        spinning=true; hapticFired=false;
        if(pullTv!=null) pullTv.setText("正在刷新…");
        setPullH(dp(56));
        spinDeg=0;
        spinTick=new Runnable(){ public void run(){
            if(!spinning) return;
            spinDeg=(spinDeg+14f)%360f;
            if(arrowView!=null) arrowView.setAngle(spinDeg);
            uiH.postDelayed(this,16);
        }};
        uiH.postDelayed(spinTick,16);
        uiH.postDelayed(new Runnable(){ public void run(){
            try{ buildApps(); }catch(Exception e){}
            if(pullTv!=null) pullTv.setText("✓ 已刷新");
            if(appsList!=null){ appsList.post(new Runnable(){public void run(){ appsScroll.scrollTo(0,0); }}); }
            toast("应用列表已刷新 ✓");
            uiH.postDelayed(new Runnable(){ public void run(){
                refreshing=false;
                spinning=false; spinDeg=0;
                if(arrowView!=null) arrowView.setAngle(0);
                collapsePull();
            }},1200);
        }},350);
    }
    void buildApps(){
        if(appsList==null) return;
        allApps.clear();
        try{
            PackageManager pm=getPackageManager();
            Intent mi=new Intent(Intent.ACTION_MAIN); mi.addCategory(Intent.CATEGORY_LAUNCHER);
            List<ResolveInfo> ri=pm.queryIntentActivities(mi,0);
            for(ResolveInfo rr:ri){ try{ String pkg=rr.activityInfo.packageName; String act=rr.activityInfo.name; String lb=rr.loadLabel(pm).toString(); Drawable ic=null; try{ic=rr.loadIcon(pm);}catch(Exception e){} allApps.add(new AppEntry(lb,pkg,act,ic)); }catch(Exception e){} }
            Collections.sort(allApps,new Comparator<AppEntry>(){public int compare(AppEntry x,AppEntry y){return x.label.compareToIgnoreCase(y.label);}});
        }catch(Exception e){}
        renderApps();
    }
    void renderApps(){
        if(appsList==null) return;
        appsList.removeAllViews();
        if(pullHead!=null){ appsList.addView(pullHead); }
        String q=appQuery;
        int shown=0;
        for(int i=0;i<allApps.size();i++){
            AppEntry e=allApps.get(i);
            if(q!=null&&q.length()>0){
                String hay=(e.label+" "+e.pkg).toLowerCase();
                if(!hay.contains(q)) continue;
            }
            addAppRow(e,shown);
            shown++;
        }
        TextView c=new TextView(this);
        if(q!=null&&q.length()>0) c.setText("共 "+shown+" 个匹配 / "+allApps.size()+" 个应用（点击启动，长按看详情）");
        else c.setText("共 "+shown+" 个应用（点击启动，长按看详情）");
        c.setTextSize(12); c.setTextColor(0xBBFFFFFF); c.setPadding(dp(4),dp(12),dp(4),2);
        appsList.addView(c);
    }
    void addAppRow(final AppEntry e,int idx){
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL); row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(10),dp(8),dp(10),dp(8)); row.setBackground(glass());
        ImageView iv=new ImageView(this); if(e.icon!=null) iv.setImageDrawable(e.icon);
        row.addView(iv,new LinearLayout.LayoutParams(dp(44),dp(44)));
        LinearLayout tx=new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL); tx.setPadding(dp(10),0,0,0);
        TextView nn=new TextView(this); nn.setText(e.label); nn.setTextColor(Color.WHITE); nn.setTextSize(15); nn.setTypeface(null,Typeface.BOLD);
        TextView pp=new TextView(this); pp.setText(e.pkg); pp.setTextColor(0x99FFFFFF); pp.setTextSize(11);
        tx.addView(nn); tx.addView(pp);
        row.addView(tx,new LinearLayout.LayoutParams(0,-2,1f));
        row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){launch(e);}});
        row.setOnLongClickListener(new View.OnLongClickListener(){public boolean onLongClick(View v){ showInfo(e); return true; }});
        liquidFx(row);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2); if(idx>0) lp.topMargin=dp(6);
        row.setLayoutParams(lp);
        appsList.addView(row);
    }
    void launch(AppEntry e){ try{ Intent i=new Intent(Intent.ACTION_MAIN); i.addCategory(Intent.CATEGORY_LAUNCHER); i.setComponent(new ComponentName(e.pkg,e.act)); i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK); startActivity(i); addLog("打开应用 "+e.label);}catch(Exception x){ toast("启动失败: "+e.label); addLog("应用启动失败 "+e.label); } }
    void uninstallApp(AppEntry e){
        try{
            if(e.pkg!=null&&e.pkg.equals(getPackageName())){ toast("不能卸载本应用（可用系统设置卸载）"); return; }
            Intent i=new Intent(Intent.ACTION_DELETE,Uri.parse("package:"+e.pkg));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i); addLog("发起卸载 "+e.label+" ("+e.pkg+")");
        }catch(Exception x){ toast("无法发起卸载: "+e.label); }
    }

    boolean handleIntent(Intent i){ if(i==null) return false;
        try{ String a=i.getAction(); Uri d=i.getData(); String ex=i.getStringExtra(EXTRA_URL);
            if(ex!=null&&ex.length()>0){ pendingUrl=ex; return true; }
            if(d!=null&&(d.getScheme().equalsIgnoreCase("http")||d.getScheme().equalsIgnoreCase("https"))){ pendingUrl=d.toString(); return true; }
        }catch(Exception e){}
        return false; }
    protected void onNewIntent(Intent i){ super.onNewIntent(i); setIntent(i);
        if(i!=null&&ACT_FIX_BROWSER.equals(i.getAction())){ runOnUiThread(new Runnable(){public void run(){ fixDefaultBrowser(); }}); return; }
        if(i!=null&&"com.helper.urlfeeder.action.OPEN_MAIN".equals(i.getAction())){ runOnUiThread(new Runnable(){public void run(){ selectTab(0); }}); return; }
        if(handleIntent(i)){ runOnUiThread(new Runnable(){public void run(){ selectTab(0); if(pendingUrl!=null) urlInput.setText(pendingUrl); }}); } }


    // 智能浏览器轮换：自动收集可用浏览器，每次点击轮换下一个（排除自身与被拉黑的白名单浏览器）
    void openWeb(String raw){
        String u=norm(raw); if(u==null){toast("请输入网址");return;}
        java.util.List<String[]> bs=webBrowsers();
        if(bs.isEmpty()){ toast("没有可用浏览器"); addLog("无可用浏览器打开 "+u); return; }
        int last=prefs.getInt("lastBrowser",-1);
        int idx=(last+1)%bs.size();   // 轮换到下一个
        prefs.edit().putInt("lastBrowser",idx).commit();
        String[] b=bs.get(idx);
        try{
            Intent i=new Intent(Intent.ACTION_VIEW,Uri.parse(u));
            i.setComponent(new ComponentName(b[0],b[1]));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(i);
            addLog("浏览器["+(idx+1)+"/"+bs.size()+" "+b[0]+"] 打开 "+u);
            addHist(u);
        }catch(Exception e){ addLog("浏览器打开失败 "+b[0]); toast("该浏览器不可用，再点一次换下一个"); }
    }
    java.util.List<String[]> webBrowsers(){
        java.util.List<String[]> out=new java.util.ArrayList<String[]>();
        try{
            Intent v=new Intent(Intent.ACTION_VIEW,Uri.parse("https://example.com"));
            java.util.List<ResolveInfo> ri=getPackageManager().queryIntentActivities(v,PackageManager.MATCH_ALL);
            // Chrome 优先
            for(ResolveInfo r:ri){
                String p=r.activityInfo.packageName;
                if("com.android.chrome".equals(p)){ out.add(0,new String[]{p,r.activityInfo.name}); break; }
            }
            for(ResolveInfo r:ri){
                String p=r.activityInfo.packageName;
                if(p.equals(getPackageName())) continue;         // 排除自身(默认浏览器处理器)
                if("com.zy.ai.browser".equals(p)) continue;       // 已被管控拉黑
                if("com.android.chrome".equals(p)) continue;      // 已放首位
                out.add(new String[]{p,r.activityInfo.name});
            }
        }catch(Exception e){}
        return out;
    }
    void paste(){ try{ ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE); if(cm!=null&&cm.hasPrimaryClip()&&cm.getPrimaryClip()!=null&&cm.getPrimaryClip().getItemAt(0)!=null){ CharSequence t=cm.getPrimaryClip().getItemAt(0).coerceToText(this); if(t!=null)urlInput.setText(t.toString().trim()); } else toast("剪贴板为空"); }catch(Exception e){toast("读取失败");} }
    String norm(String raw){ if(raw==null||raw.length()==0)return null; String s=raw.trim(); if(!s.startsWith("http://")&&!s.startsWith("https://"))s="https://"+s; return s; }
    ColorMatrix sat(float v){ ColorMatrix m=new ColorMatrix(); m.setSaturation(v); return m; }
    void loadHist(){
        hist.clear();
        try{
            String saved=prefs.getString("hist","");
            if(saved!=null&&saved.length()>0){
                String[] arr=saved.split("\n");
                for(int i=0;i<arr.length;i++){ if(arr[i].trim().length()>0) hist.add(arr[i]); }
            }
        }catch(Exception e){}
    }
    void addHist(String url){
        try{
            if(url==null||url.length()==0) return;
            hist.remove(url);
            hist.add(0,url);
            while(hist.size()>6) hist.remove(hist.size()-1);
            StringBuilder sb=new StringBuilder();
            for(String h:hist) sb.append(h).append("\n");
            prefs.edit().putString("hist",sb.toString()).commit();
            refreshHist();
        }catch(Exception e){}
    }
    void refreshHist(){
        if(histRow==null) return;
        histRow.removeAllViews();
        if(hist.isEmpty()){
            TextView e=new TextView(this); e.setText("（暂无记录）"); e.setTextColor(0x88FFFFFF); e.setTextSize(11);
            histRow.addView(e); return;
        }
        for(int i=0;i<hist.size();i++){
            final String u=hist.get(i);
            String show=u.replace("https://","").replace("http://","");
            if(show.length()>14) show=show.substring(0,14)+"…";
            Btn b=gbtn(show,shp(10,0x2EFFFFFF),new View.OnClickListener(){public void onClick(View v){ openWeb(u); }});
            b.setTextSize(11);
            LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-2,-2);
            if(i>0){ blp.leftMargin=dp(6); } blp.rightMargin=0;
            histRow.addView(b,blp);
        }
    }
    void clearLog(){
        logLines.clear();
        try{ prefs.edit().remove("runlog").commit(); }catch(Exception e){}
        refreshLogView();
        toast("日志已清空");
    }
    void copyLog(){
        try{
            StringBuilder sb=new StringBuilder();
            for(String l:logLines) sb.append(l).append("\n");
            String txt=sb.toString();
            if(txt.length()==0){ toast("暂无日志可复制"); return; }
            ClipboardManager cm=(ClipboardManager)getSystemService(Context.CLIPBOARD_SERVICE);
            if(cm!=null){ cm.setPrimaryClip(android.content.ClipData.newPlainText("runlog",txt)); toast("日志已复制到剪贴板"); }
        }catch(Exception e){ toast("复制失败"); }
    }
    void addLog(String m){
        try{
            String ts=android.text.format.DateFormat.format("HH:mm:ss",new java.util.Date()).toString();
            String line="["+ts+"] "+m;
            logLines.add(line);
            while(logLines.size()>80) logLines.remove(0);
            StringBuilder sb=new StringBuilder();
            for(int i=0;i<logLines.size();i++){ sb.append(logLines.get(i)).append("\n"); }
            prefs.edit().putString("runlog",sb.toString()).commit();
            refreshLogView();
        }catch(Exception e){}
    }
    void loadLogLines(){
        try{
            String saved=prefs.getString("runlog","");
            logLines.clear();
            if(saved!=null&&saved.length()>0){
                String[] arr=saved.split("\n");
                for(int i=0;i<arr.length;i++){ if(arr[i].trim().length()>0) logLines.add(arr[i]); }
            }
        }catch(Exception e){}
    }
    void refreshLogView(){
        if(logView==null) return;
        String saved=prefs.getString("runlog","");
        StringBuilder sb=new StringBuilder(saved);
        // 兜底:内存行多于持久化(本会话新加)
        if(sb.length()==0&&logLines.size()>0){ for(String l:logLines) sb.append(l).append("\n"); }
        String txt=sb.toString().trim();
        logView.setText(txt.length()==0?"（暂无操作日志）":txt);
    }
    void toast(String m){ Toast.makeText(this,m,Toast.LENGTH_SHORT).show(); }


    void showInfo(final AppEntry e){
        try{
            android.app.Dialog d=new android.app.Dialog(this);
            d.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE);
            if(d.getWindow()!=null){ d.getWindow().setBackgroundDrawable(new android.graphics.drawable.ColorDrawable(0x00000000)); d.getWindow().setDimAmount(0.35f); }
            LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL);
            GradientDrawable gd=new GradientDrawable(GradientDrawable.Orientation.TL_BR,new int[]{0xF2253160,0xF2121A40});
            gd.setCornerRadius(dp(26)); gd.setStroke(dp(1),0x66FFFFFF);
            p.setBackground(gd); p.setPadding(dp(24),dp(22),dp(24),dp(16));
            ImageView ic=new ImageView(this); if(e.icon!=null) ic.setImageDrawable(e.icon);
            LinearLayout.LayoutParams ilp=new LinearLayout.LayoutParams(dp(62),dp(62)); ilp.gravity=Gravity.CENTER_HORIZONTAL;
            p.addView(ic,ilp);
            TextView tt=new TextView(this); tt.setText(e.label); tt.setTextColor(Color.WHITE); tt.setTextSize(18); tt.setTypeface(null,Typeface.BOLD); tt.setGravity(Gravity.CENTER);
            p.addView(tt);
            String ver="未知",uid="未知";
            try{ android.content.pm.PackageInfo pi=getPackageManager().getPackageInfo(e.pkg,0);
                ver=(pi.versionName==null?"?":pi.versionName)+" · code "+pi.versionCode;
                uid="uid "+pi.applicationInfo.uid;
            }catch(Exception ex){}
            meta(p,"包名",e.pkg);
            meta(p,"版本",ver);
            meta(p,"UID",uid);
            meta(p,"入口",e.act);
            LinearLayout btns=new LinearLayout(this); btns.setOrientation(LinearLayout.HORIZONTAL);
            LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(-1,-2); blp.topMargin=dp(16);
            Btn open=gbtn("🚀 打开",grad(14,new int[]{0xFF4F8DFF,0xFF6C5CE7}),new View.OnClickListener(){public void onClick(View v){ d.dismiss(); launch(e); }});
            Btn del=gbtn("🗑 卸载",grad(14,new int[]{0xFFEF4444,0xFFB91C1C}),new View.OnClickListener(){public void onClick(View v){ d.dismiss(); uninstallApp(e); }});
            Btn close=gbtn("关闭",glass(),new View.OnClickListener(){public void onClick(View v){ d.dismiss(); }});
            btns.addView(open,new LinearLayout.LayoutParams(0,-2,1f));
            LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(0,-2,1f); slp.leftMargin=dp(6); slp.rightMargin=0;
            btns.addView(del,slp);
            LinearLayout.LayoutParams clp2=new LinearLayout.LayoutParams(0,-2,1f); clp2.leftMargin=dp(6);
            btns.addView(close,clp2);
            p.addView(btns,blp);
            d.setContentView(p,new android.widget.FrameLayout.LayoutParams(dp(320),-2));
            d.setCanceledOnTouchOutside(true);
            d.show();
        }catch(Throwable x){ toast("无法显示详情"); }
    }
    void meta(LinearLayout p,String k,String v){
        if(v==null) v="—";
        LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL); r.setPadding(0,dp(4),0,dp(4));
        TextView a=new TextView(this); a.setText(k); a.setTextColor(0x99FFFFFF); a.setTextSize(12.5f);
        TextView b=new TextView(this); b.setText(v); b.setTextColor(0xEEFFFFFF); b.setTextSize(12.5f);
        r.addView(a,new LinearLayout.LayoutParams(dp(56),-2));
        r.addView(b,new LinearLayout.LayoutParams(0,-2,1f));
        p.addView(r);
    }


    // 自绘旋转刷新箭头：angle 0..360 驱动整图标连续旋转
    class RefreshArrow extends View{
        float angle=0f;
        Paint p1=new Paint(Paint.ANTI_ALIAS_FLAG), p2=new Paint(Paint.ANTI_ALIAS_FLAG);
        public RefreshArrow(android.content.Context c){
            super(c);
            p1.setStyle(Paint.Style.STROKE); p1.setColor(0xE6FFFFFF);
            p1.setStrokeCap(Paint.Cap.ROUND); p1.setStrokeWidth(dp(3));
            p2.setStyle(Paint.Style.FILL); p2.setColor(0xF0FFFFFF);
        }
        void setAngle(float a){ angle=a; invalidate(); }
        protected void onDraw(Canvas cv){
            super.onDraw(cv);
            int cx=getWidth()/2, cy=getHeight()/2;
            float r=Math.min(cx,cy)-dp(4);
            if(r<=1) return;
            cv.save();
            cv.rotate(angle,cx,cy);
            // 主体弧(留 60° 缺口)
            android.graphics.RectF o=new android.graphics.RectF(cx-r,cy-r,cx+r,cy+r);
            cv.drawArc(o,-90f,300f,false,p1);
            // 弧终点(=210°)处的指向箭头
            double end=Math.toRadians(210);
            float ex=cx+(float)(r*Math.cos(end)), ey=cy+(float)(r*Math.sin(end));
            double back=Math.toRadians(196);            // 往回一点作为箭尾基线方向
            float bx=cx+(float)(r*Math.cos(back)), by=cy+(float)(r*Math.sin(back));
            float dx=ex-bx, dy=ey-by;
            float len=(float)Math.sqrt(dx*dx+dy*dy); if(len<0.1f) len=1f;
            dx/=len; dy/=len;                          // 运动方向单位向量
            float nx=-dy, ny=dx;                       // 垂直
            float al=r*0.28f, half=al*0.55f;
            android.graphics.Path path=new android.graphics.Path();
            path.moveTo(ex,ey);
            path.lineTo(ex-dx*al+nx*half, ey-dy*al+ny*half);
            path.lineTo(ex-dx*al-nx*half, ey-dy*al-ny*half);
            path.close();
            cv.drawPath(path,p2);
            cv.restore();
        }
    }
    class PullLayout extends FrameLayout{
        float downY=0; boolean pulling=false;
        PullLayout(Context c){ super(c); }
        public boolean onInterceptTouchEvent(android.view.MotionEvent e){
            int a=e.getAction();
            if(a==android.view.MotionEvent.ACTION_DOWN){
                downY=e.getRawY(); pulling=false;
                return false;
            }
            if(a==android.view.MotionEvent.ACTION_MOVE){
                if(pulling) return true;
                float dy=e.getRawY()-downY;
                if(!refreshing && dy>dp(8) && appsScroll!=null && appsScroll.getScrollY()<=0){
                    pulling=true;
                    return true;
                }
                return false;
            }
            return super.onInterceptTouchEvent(e);
        }
        public boolean onTouchEvent(android.view.MotionEvent e){
            int a=e.getAction();
            if(a==android.view.MotionEvent.ACTION_MOVE){
                if(pulling){
                    float dy=Math.max(e.getRawY()-downY,0f);
                    // 非线性阻尼：起步约 offset=drag*0.5，随距离阻力增大，位移锁死在上限
                    int h=(int)Math.min(dy*0.5f, lockPx);
                    setPullH(h);
                    appsScroll.scrollTo(0,0);
                }
                return true;
            }
            if(a==android.view.MotionEvent.ACTION_UP||a==android.view.MotionEvent.ACTION_CANCEL){
                if(pulling){
                    pulling=false;
                    if(!refreshing&&getPullH()>=dp(52)) doRefreshApps();
                    else collapsePull();
                }
                return true;
            }
            return true;
        }
    }
    static class AppEntry{ String label,pkg,act; Drawable icon; AppEntry(String l,String p,String a,Drawable ic){label=l;pkg=p;act=a;icon=ic;} }
    static class Btn extends android.widget.Button{ public Btn(Context c){ super(c); } }
}
