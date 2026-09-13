package com.helper.urlfeeder;

import android.accessibilityservice.AccessibilityService;
import android.content.Context;
import android.graphics.Bitmap;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.os.Handler;
import android.util.Log;
import android.view.Display;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Toast;

/**
 * 无障碍截图服务(Android 11+): 用户只需在系统设置里一次性开启,
 * 之后截图无需录屏授权弹窗、无需 Shizuku、也不会有系统投影过场动画。
 */
public class ShotAccessibilityService extends AccessibilityService {
    private static ShotAccessibilityService inst=null;

    /** 是否已开启且具备截图能力 */
    public static boolean ready(){
        try{
            if(Build.VERSION.SDK_INT<30||inst==null) return false;
            android.accessibilityservice.AccessibilityServiceInfo info=inst.getServiceInfo();
            return info!=null
                && (info.getCapabilities()&android.accessibilityservice.AccessibilityServiceInfo.CAPABILITY_CAN_TAKE_SCREENSHOT)!=0;
        }catch(Exception e){ return false; }
    }

    /** 触发一次截图; 返回 false 表示不可用(调用方应回退其它方案); onDone 无论成败都会回调 */
    public static boolean capture(final Context ctx,final Runnable onDone){
        if(!ready()) return false;
        final ShotAccessibilityService s=inst;
        try{
            s.takeScreenshot(Display.DEFAULT_DISPLAY,s.getMainExecutor(),new TakeScreenshotCallback(){
                public void onSuccess(ScreenshotResult r){
                    try{
                        Log.i("ShotA11y","screenshot ok");
                        HardwareBuffer buf=r.getHardwareBuffer();
                        Bitmap hb=Bitmap.wrapHardwareBuffer(buf,r.getColorSpace());
                        Bitmap sw=null;
                        if(hb!=null){ sw=hb.copy(Bitmap.Config.ARGB_8888,false); hb.recycle(); }
                        try{ buf.close(); }catch(Exception e){}
                        if(sw!=null){
                            final String saved=ShotService.saveBitmap(ctx,sw);
                            sw.recycle();
                            toast(ctx,saved!=null?("截图已保存到相册: "+saved):"截图保存失败");
                        }else toast(ctx,"截图失败");
                    }catch(Exception e){
                        Log.e("ShotA11y","save err",e);
                        toast(ctx,"截图失败: "+e.getClass().getSimpleName());
                    }finally{
                        if(onDone!=null){ try{ onDone.run(); }catch(Exception e){} }
                    }
                }
                public void onFailure(int err){
                    Log.e("ShotA11y","takeScreenshot fail code="+err);
                    toast(ctx,"截图失败(错误码 "+err+")");
                    if(onDone!=null){ try{ onDone.run(); }catch(Exception e){} }
                }
            });
            return true;
        }catch(Exception e){
            Log.e("ShotA11y","capture err",e);
            return false;
        }
    }

    public void onServiceConnected(){
        super.onServiceConnected();
        inst=this;
        Log.i("ShotA11y","service connected, ready="+ready());
    }
    public void onAccessibilityEvent(AccessibilityEvent e){ /* 不处理事件, 仅用于截图能力 */ }
    public void onInterrupt(){}
    public boolean onUnbind(android.content.Intent i){
        if(inst==this) inst=null;
        return super.onUnbind(i);
    }
    public void onDestroy(){
        if(inst==this) inst=null;
        super.onDestroy();
    }
    static void toast(final Context c,final String m){
        try{
            new Handler(c.getMainLooper()).post(new Runnable(){ public void run(){
                try{ Toast.makeText(c,m,Toast.LENGTH_LONG).show(); }catch(Exception e){}
            }});
        }catch(Exception e){}
    }
}
