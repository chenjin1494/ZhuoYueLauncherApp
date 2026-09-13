package com.helper.urlfeeder;

import android.content.Context;
import android.graphics.Typeface;
import android.graphics.fonts.Font;
import android.graphics.fonts.FontFamily;
import android.os.Build;
import android.util.Log;
import android.widget.TextView;

/**
 * 字体方案（随 APK 内置）：
 *   · 英文 / 数字 / 符号 → Fira Code Nerd Font Mono
 *   · 中文              → 思源黑体 Source Han Sans CN
 * API 29+ 用 CustomFallbackBuilder 组成回退链；更低版本退回 Fira Code(中文由系统回退)。
 */
public class Fonts {
    private static Typeface NF=null;
    private static boolean tried=false;

    public static Typeface nerd(Context c){
        if(!tried){
            tried=true;
            try{
                if(Build.VERSION.SDK_INT>=29){
                    Font fira=new Font.Builder(c.getAssets(),"fonts/FiraCodeNerdFontMono-Regular.ttf").build();
                    Font han =new Font.Builder(c.getAssets(),"fonts/SourceHanSansCN-Regular.otf").build();
                    Typeface.CustomFallbackBuilder b=new Typeface.CustomFallbackBuilder(
                        new FontFamily.Builder(fira).build());
                    b.addCustomFallback(new FontFamily.Builder(han).build());
                    NF=b.build();
                    Log.i("Fonts","fallback chain loaded: FiraCode + SourceHanSansCN");
                }else{
                    NF=Typeface.createFromAsset(c.getAssets(),"fonts/FiraCodeNerdFontMono-Regular.ttf");
                    Log.i("Fonts","Fira Code loaded (legacy path)");
                }
            }catch(Exception e){
                NF=null;
                Log.e("Fonts","load font fail, fallback MONOSPACE",e);
            }
        }
        return NF==null?Typeface.MONOSPACE:NF;
    }
    public static void apply(TextView tv){
        try{ if(tv!=null) tv.setTypeface(nerd(tv.getContext())); }catch(Exception e){}
    }
}
