package com.example.overlayref;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
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

/** Setup screen: grant permission, pick the image folder, start / stop the overlay. */
public class MainActivity extends Activity {
    static final String PREFS = "overlayref";
    static final String KEY_TREE = "tree";
    private static final int REQ_FOLDER = 1;
    private static final int REQ_NOTIF = 2;

    private TextView status;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        root.setPadding(p, p, p, p);

        TextView title = new TextView(this);
        title.setText("Reference Overlay");
        title.setTextSize(24);
        root.addView(title);

        status = new TextView(this);
        status.setTextSize(15);
        status.setPadding(0, dp(12), 0, dp(12));
        root.addView(status);

        root.addView(button("1. Allow display over other apps", v ->
                startActivity(new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                        Uri.parse("package:" + getPackageName())))));

        root.addView(button("2. Choose reference image folder", v ->
                startActivityForResult(new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE), REQ_FOLDER)));

        root.addView(button("3. Start overlay", v -> startOverlay()));

        root.addView(button("Stop overlay", v -> {
            stopService(new Intent(this, OverlayService.class));
            toast("Overlay stopped");
        }));

        TextView help = new TextView(this);
        help.setTextSize(13);
        help.setPadding(0, dp(16), 0, 0);
        help.setText("Ball: tap = panel, long-press = overlay on/off, drag = move.");
        root.addView(help);

        ScrollView sv = new ScrollView(this);
        sv.addView(root);
        setContentView(sv);

        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_NOTIF);
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (req == REQ_FOLDER && res == RESULT_OK && data != null && data.getData() != null) {
            Uri uri = data.getData();
            getContentResolver().takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
            getSharedPreferences(PREFS, MODE_PRIVATE).edit()
                    .putString(KEY_TREE, uri.toString())
                    .putInt("index", 0)
                    .apply();
            stopService(new Intent(this, OverlayService.class)); // reload the new folder on next start
            refreshStatus();
        }
    }

    private void startOverlay() {
        SharedPreferences prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        if (!Settings.canDrawOverlays(this)) {
            toast("Do step 1 first");
            return;
        }
        if (prefs.getString(KEY_TREE, null) == null) {
            toast("Do step 2 first");
            return;
        }
        Intent i = new Intent(this, OverlayService.class);
        if (Build.VERSION.SDK_INT >= 26) startForegroundService(i);
        else startService(i);
        toast("Overlay started. Open your camera app.");
        moveTaskToBack(true);
    }

    private void refreshStatus() {
        String tree = getSharedPreferences(PREFS, MODE_PRIVATE).getString(KEY_TREE, null);
        String folder = tree == null ? "not chosen" : String.valueOf(Uri.parse(tree).getLastPathSegment());
        status.setText("Overlay permission: " + (Settings.canDrawOverlays(this) ? "allowed" : "NOT allowed")
                + "\nImage folder: " + folder);
    }

    private Button button(String text, View.OnClickListener l) {
        Button btn = new Button(this);
        btn.setText(text);
        btn.setAllCaps(false);
        btn.setOnClickListener(l);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        lp.topMargin = dp(8);
        btn.setLayoutParams(lp);
        return btn;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}
