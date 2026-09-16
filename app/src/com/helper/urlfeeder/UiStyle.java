package com.helper.urlfeeder;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.os.Build;
import android.view.View;
import android.widget.TextView;

/** Shared visual language for the app's programmatic UI. */
public final class UiStyle {
    public static final int BG_TOP = 0xFF0B1113;
    public static final int BG_BOTTOM = 0xFF121B1D;
    public static final int SURFACE = 0xED182225;
    public static final int SURFACE_2 = 0xF2212D30;
    public static final int SURFACE_SOFT = 0xCC263336;
    public static final int STROKE = 0x594A6265;
    public static final int STROKE_SOFT = 0x334A6265;
    public static final int TEXT = 0xFFF2F6F5;
    public static final int TEXT_2 = 0xFFB4C2C0;
    public static final int TEXT_3 = 0xFF82918F;
    public static final int ACCENT = 0xFF42C8A5;
    public static final int ACCENT_DARK = 0xFF1A806B;
    public static final int AMBER = 0xFFE0B35B;
    public static final int AMBER_DARK = 0xFF9D7026;
    public static final int DANGER = 0xFFE46F65;
    public static final int BLUE = 0xFF64A7D8;

    private UiStyle() {}

    public static int dp(Context c, int value) {
        return (int) (value * c.getResources().getDisplayMetrics().density + .5f);
    }

    public static Drawable appBackground() {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR,
                new int[]{BG_TOP, 0xFF0F181A, BG_BOTTOM});
    }

    public static GradientDrawable panel(Context c, int fill) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(fill);
        out.setCornerRadius(dp(c, 8));
        out.setStroke(Math.max(1, dp(c, 1)), STROKE_SOFT);
        return out;
    }

    public static GradientDrawable panel(Context c) { return panel(c, SURFACE); }

    public static GradientDrawable field(Context c) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(0xE611191B);
        out.setCornerRadius(dp(c, 6));
        out.setStroke(Math.max(1, dp(c, 1)), STROKE);
        return out;
    }

    public static GradientDrawable button(Context c, int fill) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(fill);
        out.setCornerRadius(dp(c, 6));
        out.setStroke(Math.max(1, dp(c, 1)), brighten(fill, 0.22f));
        return out;
    }

    public static Drawable primaryButton(Context c) {
        GradientDrawable base = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{ACCENT_DARK, ACCENT});
        base.setCornerRadius(dp(c, 6));
        GradientDrawable line = new GradientDrawable();
        line.setCornerRadius(dp(c, 6));
        line.setStroke(Math.max(1, dp(c, 1)), 0x884CE2BC);
        return new LayerDrawable(new Drawable[]{base, line});
    }

    public static GradientDrawable activeSegment(Context c) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(0xFF233734);
        out.setCornerRadius(dp(c, 6));
        out.setStroke(Math.max(1, dp(c, 1)), ACCENT);
        return out;
    }

    public static GradientDrawable idleSegment(Context c) {
        GradientDrawable out = new GradientDrawable();
        out.setColor(0x99151E20);
        out.setCornerRadius(dp(c, 6));
        out.setStroke(Math.max(1, dp(c, 1)), STROKE_SOFT);
        return out;
    }

    public static void applyText(TextView view, int color) {
        if (view == null) return;
        view.setTextColor(color);
        view.setLetterSpacing(0);
        Fonts.apply(view);
    }

    public static void systemBars(Activity activity) {
        if (activity == null) return;
        activity.getWindow().setStatusBarColor(BG_TOP);
        activity.getWindow().setNavigationBarColor(BG_TOP);
        if (Build.VERSION.SDK_INT >= 23) {
            View decor = activity.getWindow().getDecorView();
            decor.setSystemUiVisibility(decor.getSystemUiVisibility() & ~View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
    }

    private static int brighten(int color, float amount) {
        int a = Color.alpha(color);
        int r = Math.min(255, (int) (Color.red(color) + (255 - Color.red(color)) * amount));
        int g = Math.min(255, (int) (Color.green(color) + (255 - Color.green(color)) * amount));
        int b = Math.min(255, (int) (Color.blue(color) + (255 - Color.blue(color)) * amount));
        return Color.argb(a, r, g, b);
    }
}
