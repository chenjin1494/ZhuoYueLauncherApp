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
 * 截图服务(MediaProjection): 由主界面拿到用户授权后启动,
 * 抓取一帧并保存到相册 Pictures/Screenshots, 完成后自动停止。
 * Android 10+ 必须以前台服务(mediaProjection 类型)运行。
 */
public class ShotService extends Service {
    static final int NOTI_ID=9001;
    static final String CH_ID="shot";

    public IBinder onBind(Intent i){ return null; }

    public int onStartCommand(Intent it,int f,int s){
        startForegroundSafely();
        try{
            int code=(it==null)?0:it.getIntExtra("code",0);
            Intent data=(it==null)?null:(Intent)it.getParcelableExtra("data");
            if(data==null){ toast("截图授权数据丢失"); stopSelf(); return START_NOT_STICKY; }
            MediaProjectionManager mpm=(MediaProjectionManager)getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            final MediaProjection proj=mpm.getMediaProjection(code,data);
            if(proj==null){ toast("截图授权失败"); stopSelf(); return START_NOT_STICKY; }
            capture(proj);
        }catch(Exception e){
            Log.e("Shot","start err",e);
            toast("截图失败: "+e.getClass().getSimpleName());
            stopSelf();
        }
        return START_NOT_STICKY;
    }

    void capture(final MediaProjection proj){
        new Thread(new Runnable(){ public void run(){
            ImageReader reader=null; VirtualDisplay vd=null;
            try{
                DisplayMetrics dm=new DisplayMetrics();
                android.view.WindowManager wm=(android.view.WindowManager)getSystemService(Context.WINDOW_SERVICE);
                wm.getDefaultDisplay().getRealMetrics(dm);
                int w=dm.widthPixels, h=dm.heightPixels, dpi=dm.densityDpi;
                reader=ImageReader.newInstance(w,h,PixelFormat.RGBA_8888,2);
                vd=proj.createVirtualDisplay("wft-shot",w,h,dpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,reader.getSurface(),null,null);
                // 等一帧(最多约1.6s), 期间系统画面已镜像进来
                Image img=null;
                for(int i=0;i<16&&img==null;i++){
                    try{ Thread.sleep(100); }catch(Exception e){}
                    img=reader.acquireLatestImage();
                }
                if(img==null){ toast("截图超时, 请重试"); return; }
                Bitmap bmp=null;
                try{
                    Image.Plane p=img.getPlanes()[0];
                    java.nio.ByteBuffer buf=p.getBuffer();
                    int pixelStride=p.getPixelStride(), rowStride=p.getRowStride();
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
                if(saved!=null) toast("截图已保存到相册: "+saved);
                else toast("截图保存失败");
            }catch(Exception e){
                Log.e("Shot","capture err",e);
                toast("截图失败: "+e.getClass().getSimpleName());
            }finally{
                try{ if(vd!=null) vd.release(); }catch(Exception e){}
                try{ if(reader!=null) reader.close(); }catch(Exception e){}
                try{ proj.stop(); }catch(Exception e){}
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
        try{ android.os.Handler h=new android.os.Handler(getMainLooper());
            h.post(new Runnable(){ public void run(){ try{ Toast.makeText(ShotService.this,m,Toast.LENGTH_LONG).show(); }catch(Exception e){} }});
        }catch(Exception e){}
    }
}
