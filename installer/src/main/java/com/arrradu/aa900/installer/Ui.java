package com.arrradu.aa900.installer;

import android.animation.ArgbEvaluator;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.ClipDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.LayerDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Build;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.Window;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

/**
 * The installer's look: warm paper, dark ink, one terracotta accent, a serif for headings.
 * Everything is drawn in code, so the app needs no support library and no resource files.
 */
final class Ui {

    /** How the status card should read at a glance. */
    enum Mood { IDLE, BUSY, OK, PROBLEM }

    final boolean dark;
    final int paperTop;
    final int paperBottom;
    final int surface;
    final int border;
    final int ink;
    final int inkSoft;
    final int accent;
    final int accentDeep;
    final int accentWash;
    final int inset;
    final int insetText;
    final int ok;
    final int problem;
    final int idle;

    final Typeface serif = Typeface.create("serif", Typeface.NORMAL);
    final Typeface sans = Typeface.create("sans-serif", Typeface.NORMAL);
    final Typeface sansMedium = Typeface.create("sans-serif-medium", Typeface.NORMAL);

    private final Context context;
    private final float density;

    Ui(Context context) {
        this.context = context;
        this.density = context.getResources().getDisplayMetrics().density;
        int night = context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        dark = night == Configuration.UI_MODE_NIGHT_YES;

        paperTop = dark ? 0xFF262624 : 0xFFFAF9F5;
        paperBottom = dark ? 0xFF1F1E1D : 0xFFF0EEE6;
        surface = dark ? 0xFF30302E : 0xFFFFFFFF;
        border = dark ? 0xFF3E3E3A : 0xFFE8E6DC;
        ink = dark ? 0xFFF5F4EE : 0xFF141413;
        inkSoft = dark ? 0xFFA6A39A : 0xFF73726C;
        accent = 0xFFD97757;
        accentDeep = 0xFFC6613F;
        accentWash = dark ? 0xFF4A3229 : 0xFFF6E7E0;
        inset = dark ? 0xFF1A1915 : 0xFF1F1E1D;
        insetText = 0xFFD1CFC5;
        ok = dark ? 0xFF8DBF8B : 0xFF4F8A4D;
        problem = dark ? 0xFFE8857A : 0xFFBF4A3C;
        idle = dark ? 0xFF77756D : 0xFFB0AEA5;
    }

    int dp(float value) {
        return (int) (value * density + 0.5f);
    }

    // ------------------------------------------------------------------ window

    /** Paints the window and matches the system bars to the paper. */
    void dressWindow(Activity activity) {
        Window window = activity.getWindow();
        window.setBackgroundDrawable(new GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM, new int[]{paperTop, paperBottom}));
        window.setStatusBarColor(paperTop);
        window.setNavigationBarColor(paperBottom);
        if (!dark) {
            int flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR;
            if (Build.VERSION.SDK_INT >= 26) flags |= View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;
            window.getDecorView().setSystemUiVisibility(flags);
        }
    }

    // ------------------------------------------------------------------ text

    TextView text(String value, float sp, int color, Typeface face) {
        TextView view = new TextView(context);
        view.setText(value);
        view.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        view.setTextColor(color);
        view.setTypeface(face);
        view.setIncludeFontPadding(false);
        return view;
    }

    /** The small spaced-out capitals that name a section. */
    TextView label(String value) {
        TextView view = text(value.toUpperCase(), 11, inkSoft, sansMedium);
        view.setLetterSpacing(0.14f);
        return view;
    }

    // ------------------------------------------------------------------ shapes

    GradientDrawable rounded(int color, float radiusDp) {
        GradientDrawable shape = new GradientDrawable();
        shape.setColor(color);
        shape.setCornerRadius(dp(radiusDp));
        return shape;
    }

    /** A raised sheet of paper with a hairline edge. */
    LinearLayout card() {
        LinearLayout card = new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable shape = rounded(surface, 24);
        shape.setStroke(dp(1), border);
        card.setBackground(shape);
        card.setElevation(dp(dark ? 0 : 3));
        card.setClipToOutline(true);
        return card;
    }

    LinearLayout.LayoutParams fill() {
        return new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
    }

    LinearLayout.LayoutParams fillWithTop(float topDp) {
        LinearLayout.LayoutParams params = fill();
        params.topMargin = dp(topDp);
        return params;
    }

    View divider() {
        View line = new View(context);
        line.setBackgroundColor(border);
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(1));
        params.leftMargin = dp(76);
        line.setLayoutParams(params);
        return line;
    }

    // ------------------------------------------------------------------ controls

    /** The one big action: a terracotta slab with a title and a line under it. */
    LinearLayout primaryButton(String title, String subtitle) {
        LinearLayout button = new LinearLayout(context);
        button.setOrientation(LinearLayout.HORIZONTAL);
        button.setGravity(Gravity.CENTER_VERTICAL);
        button.setPadding(dp(22), dp(18), dp(22), dp(18));

        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR, new int[]{0xFFE08A6B, accent, accentDeep});
        fill.setCornerRadius(dp(22));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x33FFFFFF), fill, rounded(Color.WHITE, 22)));
        button.setElevation(dp(8));
        if (Build.VERSION.SDK_INT >= 28) {
            button.setOutlineSpotShadowColor(accentDeep);
            button.setOutlineAmbientShadowColor(accentDeep);
        }

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.addView(text(title, 18, Color.WHITE, sansMedium));
        TextView under = text(subtitle, 13, 0xE6FFFFFF, sans);
        under.setPadding(0, dp(5), 0, 0);
        words.addView(under);
        button.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        TextView arrow = text("↓", 24, Color.WHITE, sansMedium);
        arrow.setGravity(Gravity.CENTER);
        arrow.setBackground(rounded(0x33FFFFFF, 22));
        button.addView(arrow, new LinearLayout.LayoutParams(dp(44), dp(44)));

        makePressable(button);
        return button;
    }

    /** One line in the list of smaller actions: a tinted badge, two lines of text, a chevron. */
    LinearLayout actionRow(String badge, String title, String subtitle) {
        LinearLayout row = new LinearLayout(context);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(dp(18), dp(16), dp(18), dp(16));
        row.setBackground(new RippleDrawable(
                ColorStateList.valueOf(dark ? 0x22FFFFFF : 0x14000000), null, rounded(Color.WHITE, 0)));

        TextView mark = text(badge, 18, accentDeep, sansMedium);
        if (dark) mark.setTextColor(0xFFE9A48C);
        mark.setGravity(Gravity.CENTER);
        mark.setBackground(rounded(accentWash, 14));
        row.addView(mark, new LinearLayout.LayoutParams(dp(42), dp(42)));

        LinearLayout words = new LinearLayout(context);
        words.setOrientation(LinearLayout.VERTICAL);
        words.setPadding(dp(16), 0, dp(8), 0);
        words.addView(text(title, 16, ink, sansMedium));
        TextView under = text(subtitle, 13, inkSoft, sans);
        under.setPadding(0, dp(4), 0, 0);
        words.addView(under);
        row.addView(words, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));

        row.addView(text("›", 22, inkSoft, sans));
        row.setClickable(true);
        row.setFocusable(true);
        return row;
    }

    /** A quiet button for a secondary step inside a card. */
    TextView tonalButton(String title) {
        TextView button = text(title, 15, dark ? 0xFFE9A48C : accentDeep, sansMedium);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(18), dp(14), dp(18), dp(14));
        button.setBackground(new RippleDrawable(
                ColorStateList.valueOf(0x22D97757), rounded(accentWash, 16), rounded(Color.WHITE, 16)));
        button.setClickable(true);
        button.setFocusable(true);
        makePressable(button);
        return button;
    }

    /** A thin rounded bar that fills with the accent. */
    ProgressBar progressBar() {
        ProgressBar bar = new ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal);
        Drawable track = rounded(dark ? 0xFF3E3E3A : 0xFFEDEBE3, 4);
        GradientDrawable fill = new GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT, new int[]{0xFFE08A6B, accentDeep});
        fill.setCornerRadius(dp(4));
        LayerDrawable layers = new LayerDrawable(new Drawable[]{
                track, new ClipDrawable(fill, Gravity.START, ClipDrawable.HORIZONTAL)});
        layers.setId(0, android.R.id.background);
        layers.setId(1, android.R.id.progress);
        bar.setProgressDrawable(layers);
        bar.setIndeterminateTintList(ColorStateList.valueOf(accent));
        bar.setMax(100);
        return bar;
    }

    int moodColor(Mood mood) {
        switch (mood) {
            case BUSY:
                return accent;
            case OK:
                return ok;
            case PROBLEM:
                return problem;
            default:
                return idle;
        }
    }

    String moodWord(Mood mood) {
        switch (mood) {
            case BUSY:
                return "Lucrez";
            case OK:
                return "Gata";
            case PROBLEM:
                return "Atenție";
            default:
                return "Pregătit";
        }
    }

    /** Slides a dot from its current colour to the mood's colour. */
    void tintDot(final GradientDrawable dot, int from, int to) {
        ValueAnimator animator = ValueAnimator.ofObject(new ArgbEvaluator(), from, to);
        animator.setDuration(350);
        animator.addUpdateListener(new ValueAnimator.AnimatorUpdateListener() {
            public void onAnimationUpdate(ValueAnimator animation) {
                dot.setColor((Integer) animation.getAnimatedValue());
            }
        });
        animator.start();
    }

    /** A control that sinks a little under the finger. The click itself is left alone. */
    void makePressable(final View view) {
        view.setClickable(true);
        view.setFocusable(true);
        view.setOnTouchListener(new View.OnTouchListener() {
            public boolean onTouch(View v, MotionEvent event) {
                int action = event.getActionMasked();
                if (action == MotionEvent.ACTION_DOWN) {
                    v.animate().scaleX(0.975f).scaleY(0.975f).setDuration(90).start();
                } else if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) {
                    v.animate().scaleX(1f).scaleY(1f).setDuration(160).start();
                }
                return false;
            }
        });
    }

    /** Greys a control out while a job runs, or brings it back. */
    void setEnabled(View view, boolean enabled) {
        view.setEnabled(enabled);
        view.animate().alpha(enabled ? 1f : 0.45f).setDuration(200).start();
    }

    TextView singleLine(TextView view) {
        view.setSingleLine(true);
        view.setEllipsize(TextUtils.TruncateAt.END);
        return view;
    }
}
