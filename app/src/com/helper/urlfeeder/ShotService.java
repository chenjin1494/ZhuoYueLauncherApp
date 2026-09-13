package com.helper.urlfeeder;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.provider.MediaStore;
import android.util.DisplayMetrics;
import android.util.Log;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 截图服务(MediaProjection): 抓一帧保存到相册 Pictures/Screenshots。
 * 首次由 ShotActivity 取到授权后, 授权(投影+虚拟屏)会保留复用,
 * 之后截图只需以前台服务(mediaProjection 类型)运行即可, 不再弹授权框。
 */
public class ShotService extends Service {
    static final int NOTI_ID=9001;
    static final String CH_ID="shot";

    // 复用中的投影资源(进程存活期间保留)
    private static MediaProjection sProj=null;
    private static VirtualDisplay sVd=null;
    private static ImageReader sReader=null;
    private static int sW=0,sH=0,sDpi=0;
    private static boolean sBusy=false;

    public static boolean hasProjection(){
        return sProj!=null&&sVd!=null&&sReader!=null;
    }
    static void releaseAll(){
        try{ if(sVd!=null) sVd.release(); }catch(Exception e){}
        try{ if(sReader!=null) sReader.close(); }catch(Exception e){}
        try{ if(sProj!=null) sProj.stop(); }catch(Exception e){}
        sVd=null; sReader=null; sProj=null; sBusy=false;
    }

    public IBinder onBind(Intent i){ return null; }

    public int onStartCommand(Intent it,int f,int s){
        startForegroundSafely();
        String act=(it==null)?null:it.getAction();
        try{
            if("capture".equals(act)){
                if(!hasProjection()){ toast("截图授权已失效, 请重试"); stopSelf(); return START_NOT_STICKY; }
                grab();
                return START_NOT_STICKY;
            }
            int code=(it==null)?0:it.getIntExtra("code",0);
            Intent data=(it==null)?null:(Intent)it.getParcelableExtra("data");
            if(data==null){ toast("截图授权数据丢失"); stopSelf(); return START_NOT_STICKY; }
            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            releaseAll();
            sProj=mpm.getMediaProjection(code,data);
            if(sProj==null){ toast("截图授权失败"); stopSelf(); return START_NOT_STICKY; }
            try{
                sProj.registerCallback(new MediaProjection.Callback(){
                    public void onStop(){ Log.i("Shot","projection stopped by system"); releaseAll(); }
                },new Handler(getMainLooper()));
            }catch(Exception e){}
            setupDisplay();
            grab();
        }catch(Exception e){
            Log.e("Shot","start err",e);
            toast("截图失败: "+e.getClass().getSimpleName());
            releaseAll();
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    void setupDisplay(){
        DisplayMetrics dm=new DisplayMetrics();
        android.view.WindowManager wm=(android.view.WindowManager)getSystemService(Context.WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        sW=dm.widthPixels; sH=dm.heightPixels; sDpi=dm.densityDpi;
        sReader=ImageReader.newInstance(sW,sH,PixelFormat.RGBA_8888,2);
        sVd=sProj.createVirtualDisplay("wft-shot",sW,sH,sDpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,sReader.getSurface(),null,null);
    }

    void grab(){
        if(sBusy){ toast("正在截图…"); stopSelf(); return; }
        sBusy=true;
        new Thread(new Runnable(){ public void run(){
            boolean ok=false;
            try{
                Image img=null;
                for(int i=0;i<16&&img==null;i++){
                    try{ Thread.sleep(100); }catch(Exception e){}
                    img=sReader.acquireLatestImage();
                }
                if(img==null){ toast("截图超时, 请重试"); return; }
                Bitmap bmp=null;
                try{
                    Image.Plane p=img.getPlanes()[0];
                    java.nio.ByteBuffer buf=p.getBuffer();
                    int pixelStride=p.getPixelStride(), rowStride=p.getRowStride();
                    int w=sW,h=sH;
                    int rowPadding=rowStride-pixelStride*w;
                    bmp=Bitmap.createBitmap(w+rowPadding/pixelStride,h,Bitmap.Config.ARGB_8888);
                    bmp.copyPixelsFromBuffer(buf);
                    if(rowPadding!=0){
                        Bitmap cropped=Bitmap.createBitmap(bmp,0,0,w,h);
                        bmp.recycle(); bmp=cropped;
                    }
                }finally{ try{ img.close(); }catch(Exception e){} }
                final String saved=save(bmp);
                try{ bmp.recycle(); }catch(Exception e){}
                ok=(saved!=null);
                if(ok) toast("截图已保存到相册: "+saved);
                else toast("截图保存失败");
            }catch(Exception e){
                Log.e("Shot","capture err",e);
                toast("截图失败: "+e.getClass().getSimpleName());
            }finally{
                releaseAll();                  // 用完即释放(前台服务停止时系统也会收回投影)
                sBusy=false;
                stopSelf();
            }
        }},"shot-worker").start();
    }

    // 保存到相册(Android 10+ 用 MediaStore, 旧版写文件后通知扫描)
    String save(Bitmap bmp){
        String name="WFT_"+new SimpleDateFormat("yyyyMMdd_HHmmss").format(new Date())+".png";
        try{
            if(Build.VERSION.SDK_INT>=29){
                ContentResolver cr=getContentResolver();
                ContentValues v=new ContentValues();
                v.put(MediaStore.Images.Media.DISPLAY_NAME,name);
                v.put(MediaStore.Images.Media.MIME_TYPE,"image/png");
                v.put(MediaStore.Images.Media.RELATIVE_PATH,"Pictures/Screenshots");
                Uri uri=cr.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI,v);
                if(uri==null) return null;
                OutputStream os=cr.openOutputStream(uri);
                bmp.compress(Bitmap.CompressFormat.PNG,100,os);
                os.flush(); os.close();
                return "Pictures/Screenshots/"+name;
            }else{
                File dir=new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),"Screenshots");
                if(!dir.exists()) dir.mkdirs();
                File f=new File(dir,name);
                FileOutputStream fo=new FileOutputStream(f);
                bmp.compress(Bitmap.CompressFormat.PNG,100,fo);
                fo.flush(); fo.close();
                android.media.MediaScannerConnection.scanFile(this,new String[]{f.getAbsolutePath()},new String[]{"image/png"},null);
                return f.getAbsolutePath();
            }
        }catch(Exception e){ Log.e("Shot","save err",e); return null; }
    }

    void startForegroundSafely(){
        try{
            if(Build.VERSION.SDK_INT>=26){
                NotificationManager nm=(NotificationManager)getSystemService(Context.NOTIFICATION_SERVICE);
                if(nm!=null&&nm.getNotificationChannel(CH_ID)==null){
                    NotificationChannel ch=new NotificationChannel(CH_ID,"截图",NotificationManager.IMPORTANCE_LOW);
                    ch.setShowBadge(false);
                    nm.createNotificationChannel(ch);
                }
            }
            Notification.Builder b=(Build.VERSION.SDK_INT>=26)?new Notification.Builder(this,CH_ID):new Notification.Builder(this);
            b.setContentTitle("万能转发器")
             .setContentText("正在截图…")
             .setSmallIcon(android.R.drawable.ic_menu_camera)
             .setOngoing(true);
            startForeground(NOTI_ID,b.build());
        }catch(Exception e){ Log.e("Shot","fg err",e); }
    }
    void toast(final String m){
        try{
            new Handler(getMainLooper()).post(new Runnable(){ public void run(){
                try{ Toast.makeText(ShotService.this,m,Toast.LENGTH_LONG).show(); }catch(Exception e){}
            }});
        }catch(Exception e){}
    }
}
