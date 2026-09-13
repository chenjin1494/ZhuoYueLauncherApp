package com.helper.urlfeeder;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

/**
 * 透明无界面页面: 仅用于向系统申请录屏授权。
 * 悬浮球是 Service 无法直接申请 MediaProjection, 用它替代"把主界面拉到前台",
 * 用户只会看到系统授权框(首次), 授权后复用, 之后截图全程静默。
 */
public class ShotActivity extends Activity {
    static final int REQ=90;

    protected void onCreate(Bundle b){
        super.onCreate(b);
        try{
            if(ShotService.hasProjection()){ startCapture(); return; }   // 已有授权 → 静默复用
            android.media.projection.MediaProjectionManager mpm=
                (android.media.projection.MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            if(mpm==null){ toast("此设备不支持截图"); finish(); return; }
            startActivityForResult(mpm.createScreenCaptureIntent(),REQ);
        }catch(Exception e){ toast("无法发起截图: "+e.getClass().getSimpleName()); finish(); }
    }

    protected void onActivityResult(int req,int res,Intent data){
        super.onActivityResult(req,res,data);
        if(req!=REQ) return;
        if(res==RESULT_OK&&data!=null){
            try{
                Intent s=new Intent(this,ShotService.class);
                s.putExtra("code",res);
                s.putExtra("data",data);
                if(Build.VERSION.SDK_INT>=26) startForegroundService(s); else startService(s);
                toast("正在截图…");
            }catch(Exception e){ toast("截图服务启动失败"); }
        }else{
            toast("已取消截图");
        }
        finish();
    }

    void startCapture(){
        try{
            Intent s=new Intent(this,ShotService.class);
            s.setAction("capture");
            if(Build.VERSION.SDK_INT>=26) startForegroundService(s); else startService(s);
            toast("正在截图…");
        }catch(Exception e){ toast("截图失败"); }
        finish();
    }
    void toast(String m){ try{ Toast.makeText(this,m,Toast.LENGTH_SHORT).show(); }catch(Exception e){} }
}
