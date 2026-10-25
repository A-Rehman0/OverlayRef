package com.example.overlayref;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Start screen: allow overlay permission, start / stop the overlay. Images are built into the app. */
public class MainActivity extends Activity {
    static final String PREFS = "overlayref";
    static final String ASSET_DIR = "references";

    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF2F4F7);
        root.setPadding(dp(20), dp(36), dp(20), dp(24));

        TextView title = text("Reference Overlay", 26, 0xFF111111, true);
        root.addView(title);
        root.addView(text("Transparent reference image over any app", 14, 0xFF666666, false),
                lp(0, 4, 0, 20));

        status = text("", 15, 0xFF222222, false);
        status.setLineSpacing(0, 1.25f);
        status.setPadding(dp(16), dp(14), dp(16), dp(14));
        GradientDrawable card = new GradientDrawable();
        card.setColor(0xFFFFFFFF);
        card.setCornerRadius(dp(14));
        status.setBackground(card);
        root.addView(status, lp(0, 0, 0, 20));

        root.addView(button("1   Allow display over other apps", 0xFF455A64, v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))), lp(0, 0, 0, 12));

        root.addView(button("2   Start overlay", 0xFF2E7D32, v -> startOverlay()), lp(0, 0, 0, 12));

        root.addView(button("Stop overlay", 0xFFB71C1C, v -> {
            stopService(new Intent(this, OverlayService.class));
            toast("Overlay stopped");
        }), lp(0, 0, 0, 24));

        TextView help = text("Floating ball:\n"
                + "• Tap: open the control panel\n"
                + "• Long-press: overlay on / off\n"
                + "• Drag: move it (snaps to the edge)"
                + "• Dev : Abdul Rehman T.", 14, 0xFF555555, false);
        help.setLineSpacing(0, 1.3f);
        root.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.setFillViewport(true);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, 2);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        int n = 0;
        try {
            String[] files = getAssets().list(ASSET_DIR);
            if (files != null) for (String f : files) if (OverlayService.isImage(f)) n++;
        } catch (Exception ignored) {
        }
        status.setText("Overlay permission:  " + (Settings.canDrawOverlays(this) ? "✔ allowed" : "✖ not allowed")
                + "\nReference images:  " + n + (n == 0 ? "  (add them to app/src/main/assets/references)" : " built in"));
    }

    private void startOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast("Do step 1 first");
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        toast("Overlay started. Open your camera app.");
        moveTaskToBack(true);
    }

    // ------------------------------------------------------------ ui helpers
    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(null, Typeface.BOLD);
        return t;
    }

    private Button button(String s, int color, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(s);
        btn.setAllCaps(false);
        btn.setTextSize(16);
        btn.setTextColor(0xFFFFFFFF);
        btn.setStateListAnimator(null);
        btn.setMinimumHeight(dp(54));
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(14));
        btn.setBackground(g);
        btn.setOnClickListener(l);
        return btn;
    }

    private LinearLayout.LayoutParams lp(int l, int t, int r, int b) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(l), dp(t), dp(r), dp(b));
        return p;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
