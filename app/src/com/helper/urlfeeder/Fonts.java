package com.helper.urlfeeder;

import android.content.Context;
import android.graphics.Typeface;
import android.util.Log;
import android.widget.TextView;

/**
 * 字体: Fira Code Nerd Font Mono（英文/数字/Nerd 图标），
 * 中文字符由系统字体自动回退，不影响可读性。
 */
public class Fonts {
    private static Typeface NF=null;
    private static boolean tried=false;

    public static Typeface nerd(Context c){
        if(!tried){
            tried=true;
            try{
                NF=Typeface.createFromAsset(c.getAssets(),"fonts/FiraCodeNerdFontMono-Regular.ttf");
                Log.i("Fonts","Fira Code Nerd Font Mono loaded");
            }catch(Exception e){
                NF=Typeface.MONOSPACE;
                Log.e("Fonts","load font fail, fallback MONOSPACE",e);
            }
        }
        return NF==null?Typeface.MONOSPACE:NF;
    }
    public static void apply(TextView tv){
        try{ if(tv!=null) tv.setTypeface(nerd(tv.getContext())); }catch(Exception e){}
    }
}
