package com.offlineplayer.cineoffline;

import android.content.Context;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.View;
import android.widget.TextView;

public final class Ui {
    public static final int BG = Color.rgb(246, 247, 253);
    public static final int TEXT = Color.rgb(31, 38, 58);
    public static final int MUTED = Color.rgb(110, 116, 136);
    public static final int BORDER = Color.rgb(234, 235, 244);
    public static final int PURPLE = Color.rgb(104, 91, 214);
    public static final int BLUE = Color.rgb(102, 126, 234);
    public static final int DEEP_PURPLE = Color.rgb(83, 75, 175);
    public static final int NAVY = Color.rgb(20, 27, 48);
    public static final int GREEN = Color.rgb(44, 177, 126);
    public static final int SOFT = Color.rgb(248, 248, 252);

    private Ui() {}

    public static GradientDrawable rounded(int color, float radiusDp, Context c) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(color);
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable roundedStroke(int color, int strokeColor, float radiusDp, float strokeDp, Context c) {
        GradientDrawable d = rounded(color, radiusDp, c);
        d.setStroke(dp(c, strokeDp), strokeColor);
        return d;
    }

    public static GradientDrawable gradient(Context c, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(102,126,234), Color.rgb(118,75,162)}
        );
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static GradientDrawable screenGradient(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(103,128,236), Color.rgb(105,91,214), Color.rgb(119,73,161)}
        );
        d.setGradientType(GradientDrawable.LINEAR_GRADIENT);
        return d;
    }

    public static GradientDrawable topBarGradient(Context c) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{Color.rgb(86,84,176), Color.rgb(89,78,166)}
        );
        return d;
    }

    public static GradientDrawable softGradient(Context c, float radiusDp) {
        GradientDrawable d = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{Color.rgb(245,243,255), Color.rgb(237,241,255)}
        );
        d.setCornerRadius(dp(c, radiusDp));
        return d;
    }

    public static void card(View v, Context c, float radiusDp) {
        v.setBackground(roundedStroke(Color.WHITE, BORDER, radiusDp, 1, c));
        v.setElevation(dp(c, 3));
    }

    public static void button(TextView v, Context c, boolean primary) {
        v.setBackground(primary ? gradient(c, 16) : roundedStroke(Color.WHITE, BORDER, 16, 1, c));
        v.setTextColor(primary ? Color.WHITE : TEXT);
        v.setGravity(android.view.Gravity.CENTER);
        v.setPadding(dp(c, 14), 0, dp(c, 14), 0);
        v.setElevation(dp(c, primary ? 3 : 1));
    }

    public static int dp(Context c, float value) {
        return Math.round(value * c.getResources().getDisplayMetrics().density);
    }
}
