package com.example.overlayref;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.media.ExifInterface;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Draws a semi-transparent reference image over every other app (touches pass through it)
 * plus a small draggable "assistive ball" that opens a control panel.
 */
public class OverlayService extends Service {
    static final String ACTION_STOP = "com.example.overlayref.STOP";
    private static final String CHANNEL = "overlay";
    private static final int BALL_DP = 52;
    private static final float MIN_ALPHA = 0.10f;
    // Android 12+ blocks touches that pass through an overlay window more opaque than 0.8
    private static final float MAX_ALPHA = 0.80f;

    private static final class Doc {
        final Uri uri;
        final String name;
        Doc(Uri u, String n) { uri = u; name = n; }
    }

    private interface IntCb { void on(int v); }

    private WindowManager wm;
    private Context ctx;
    private SharedPreferences prefs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Object cacheLock = new Object();

    private ImageView overlayView;
    private WindowManager.LayoutParams overlayLp;
    private TextView ball;
    private WindowManager.LayoutParams ballLp;
    private LinearLayout panel;
    private WindowManager.LayoutParams panelLp;
    private TextView title, opLabel, scLabel;
    private Button toggleBtn;
    private boolean overlayAttached, ballAttached, panelShown, destroyed;

    private List<Doc> docs = new ArrayList<>();
    private int index, dir = 1, loadToken, screenW, screenH, ballSize;
    private float opacity = 0.5f, scale = 1f;
    private boolean overlayOn = true;
    private Bitmap prefetchBmp;
    private int prefetchIdx = -1;

    // ------------------------------------------------------------ lifecycle
    @Override
    public void onCreate() {
        super.onCreate();
        prefs = getSharedPreferences(MainActivity.PREFS, MODE_PRIVATE);
        startForeground(1, buildNotification());

        String tree = prefs.getString(MainActivity.KEY_TREE, null);
        if (!Settings.canDrawOverlays(this) || tree == null) {
            Toast.makeText(this, "Allow overlay permission and choose a folder first", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        ctx = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        ballSize = dp(BALL_DP);
        readScreen();

        opacity = clampF(prefs.getFloat("opacity", 0.5f), MIN_ALPHA, MAX_ALPHA);
        scale = prefs.getFloat("scale", 1f);
        overlayOn = prefs.getBoolean("on", true);
        index = prefs.getInt("index", 0);

        addOverlay();
        buildPanel();
        addBall();

        final Uri treeUri = Uri.parse(tree);
        io.execute(() -> {
            final List<Doc> list = listImages(treeUri);
            main.post(() -> {
                if (destroyed) return;
                docs = list;
                if (docs.isEmpty()) {
                    Toast.makeText(this, "No images found in that folder", Toast.LENGTH_LONG).show();
                }
                show(index);
            });
        });
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        if (intent != null && ACTION_STOP.equals(intent.getAction())) stopSelf();
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (wm == null) return;
        readScreen();
        hidePanel();
        if (ballAttached) {
            ballLp.x = clampI(ballLp.x, 0, screenW - ballSize);
            ballLp.y = clampI(ballLp.y, 0, screenH - ballSize);
            wm.updateViewLayout(ball, ballLp);
        }
    }

    @Override
    public void onDestroy() {
        destroyed = true;
        main.removeCallbacksAndMessages(null);
        io.shutdownNow();
        if (wm != null) {
            savePrefs();
            hidePanel();
            if (ballAttached) { wm.removeViewImmediate(ball); ballAttached = false; }
            if (overlayAttached) { wm.removeViewImmediate(overlayView); overlayAttached = false; }
        }
        super.onDestroy();
    }

    // ------------------------------------------------------------ windows
    private int windowType() {
        return Build.VERSION.SDK_INT >= 26
                ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                : WindowManager.LayoutParams.TYPE_PHONE;
    }

    /** Full-screen reference image. NOT_TOUCHABLE lets every tap fall through to the app below. */
    private void addOverlay() {
        overlayView = new ImageView(ctx);
        overlayView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        overlayView.setScaleX(scale);
        overlayView.setScaleY(scale);
        overlayView.setVisibility(overlayOn ? View.VISIBLE : View.GONE);

        overlayLp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        overlayLp.gravity = Gravity.TOP | Gravity.START;
        overlayLp.alpha = opacity;
        if (Build.VERSION.SDK_INT >= 28) {
            overlayLp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        wm.addView(overlayView, overlayLp);
        overlayAttached = true;
    }

    /** The assistive ball: tap = panel, long-press = overlay on/off, drag = move (snaps to edge). */
    private void addBall() {
        ball = new TextView(ctx);
        ball.setGravity(Gravity.CENTER);
        ball.setTextColor(0xFFFFFFFF);
        ball.setTextSize(22);
        GradientDrawable bg = new GradientDrawable();
        bg.setShape(GradientDrawable.OVAL);
        bg.setStroke(dp(2), 0xFFFFFFFF);
        ball.setBackground(bg);
        refreshBall();

        ballLp = new WindowManager.LayoutParams(
                ballSize, ballSize, windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        ballLp.gravity = Gravity.TOP | Gravity.START;
        ballLp.x = clampI(prefs.getInt("bx", screenW - ballSize), 0, screenW - ballSize);
        ballLp.y = clampI(prefs.getInt("by", screenH / 3), 0, screenH - ballSize);

        final int slop = ViewConfiguration.get(this).getScaledTouchSlop();
        ball.setOnTouchListener(new View.OnTouchListener() {
            float downX, downY;
            int startX, startY;
            boolean moved, longDone;
            final Runnable longPress = () -> {
                if (moved) return;
                longDone = true;
                toggleOverlay();
                ball.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
            };

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getRawX();
                        downY = e.getRawY();
                        startX = ballLp.x;
                        startY = ballLp.y;
                        moved = false;
                        longDone = false;
                        main.postDelayed(longPress, 600);
                        return true;
                    case MotionEvent.ACTION_MOVE: {
                        float mx = e.getRawX() - downX, my = e.getRawY() - downY;
                        if (!moved && Math.hypot(mx, my) > slop) {
                            moved = true;
                            main.removeCallbacks(longPress);
                            hidePanel();
                        }
                        if (moved) {
                            ballLp.x = clampI(Math.round(startX + mx), 0, screenW - ballSize);
                            ballLp.y = clampI(Math.round(startY + my), 0, screenH - ballSize);
                            wm.updateViewLayout(ball, ballLp);
                        }
                        return true;
                    }
                    case MotionEvent.ACTION_UP:
                        main.removeCallbacks(longPress);
                        if (moved) snapToEdge();
                        else if (!longDone) togglePanel();
                        return true;
                    case MotionEvent.ACTION_CANCEL:
                        main.removeCallbacks(longPress);
                        return true;
                    default:
                        return false;
                }
            }
        });

        wm.addView(ball, ballLp);
        ballAttached = true;
    }

    private void snapToEdge() {
        int target = (ballLp.x + ballSize / 2 < screenW / 2) ? 0 : screenW - ballSize;
        ValueAnimator a = ValueAnimator.ofInt(ballLp.x, target);
        a.setDuration(150);
        a.addUpdateListener(va -> {
            if (!ballAttached) return;
            ballLp.x = (int) va.getAnimatedValue();
            wm.updateViewLayout(ball, ballLp);
        });
        a.addListener(new AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(Animator animation) { savePrefs(); }
        });
        a.start();
    }

    // ------------------------------------------------------------ control panel
    private void buildPanel() {
        panel = new LinearLayout(ctx);
        panel.setOrientation(LinearLayout.VERTICAL);
        int p = dp(12);
        panel.setPadding(p, p, p, p);
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xE6141414);
        bg.setCornerRadius(dp(14));
        panel.setBackground(bg);

        title = label("-");
        title.setSingleLine(true);
        title.setEllipsize(TextUtils.TruncateAt.MIDDLE);
        panel.addView(title);

        opLabel = label("");
        panel.addView(opLabel);
        int opMax = Math.round((MAX_ALPHA - MIN_ALPHA) * 100);
        panel.addView(seek(opMax, Math.round((opacity - MIN_ALPHA) * 100), v -> {
            opacity = MIN_ALPHA + v / 100f;
            applyOpacity();
        }));

        scLabel = label("");
        panel.addView(scLabel);
        panel.addView(seek(150, Math.round(scale * 100) - 50, v -> {
            scale = (v + 50) / 100f;
            applyScale();
        }));

        LinearLayout nav = row();
        nav.addView(btn("◀ Prev", v -> show(index - 1)));
        nav.addView(btn("Next ▶", v -> show(index + 1)));
        panel.addView(nav);

        LinearLayout act = row();
        toggleBtn = btn("", v -> toggleOverlay());
        act.addView(toggleBtn);
        act.addView(btn("Stop", v -> stopSelf()));
        panel.addView(act);

        panelLp = new WindowManager.LayoutParams(
                dp(260), WindowManager.LayoutParams.WRAP_CONTENT, windowType(),
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        panelLp.gravity = Gravity.TOP | Gravity.START;

        applyOpacity();
        applyScale();
        refreshBall();
    }

    private void togglePanel() {
        if (panelShown) hidePanel(); else showPanel();
    }

    private void showPanel() {
        if (panelShown || destroyed) return;
        int w = dp(260);
        panel.measure(View.MeasureSpec.makeMeasureSpec(w, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int h = panel.getMeasuredHeight();
        int x = (ballLp.x + ballSize / 2 > screenW / 2) ? ballLp.x - w - dp(8) : ballLp.x + ballSize + dp(8);
        panelLp.x = clampI(x, 0, screenW - w);
        panelLp.y = clampI(ballLp.y, 0, screenH - h);
        wm.addView(panel, panelLp);
        panelShown = true;
    }

    private void hidePanel() {
        if (!panelShown) return;
        wm.removeViewImmediate(panel);
        panelShown = false;
    }

    // ------------------------------------------------------------ actions
    private void applyOpacity() {
        overlayLp.alpha = opacity;
        if (overlayAttached) wm.updateViewLayout(overlayView, overlayLp);
        opLabel.setText("Opacity " + Math.round(opacity * 100) + "%");
    }

    private void applyScale() {
        overlayView.setScaleX(scale);
        overlayView.setScaleY(scale);
        scLabel.setText("Size " + Math.round(scale * 100) + "%");
    }

    private void toggleOverlay() {
        overlayOn = !overlayOn;
        overlayView.setVisibility(overlayOn ? View.VISIBLE : View.GONE);
        refreshBall();
        savePrefs();
    }

    private void refreshBall() {
        if (ball != null) {
            ball.setText(overlayOn ? "◐" : "○");
            ((GradientDrawable) ball.getBackground()).setColor(overlayOn ? 0xD92E7D32 : 0xD9B71C1C);
        }
        if (toggleBtn != null) toggleBtn.setText(overlayOn ? "Overlay: ON" : "Overlay: OFF");
    }

    private void updateTitle() {
        title.setText(docs.isEmpty() ? "No images in folder"
                : (index + 1) + " / " + docs.size() + "  " + docs.get(index).name);
    }

    private void savePrefs() {
        prefs.edit()
                .putFloat("opacity", opacity)
                .putFloat("scale", scale)
                .putBoolean("on", overlayOn)
                .putInt("index", index)
                .putInt("bx", ballLp != null ? ballLp.x : 0)
                .putInt("by", ballLp != null ? ballLp.y : 0)
                .apply();
    }

    // ------------------------------------------------------------ image loading
    /** Shows image i. Decodes off the main thread and prefetches the neighbour in travel direction. */
    private void show(int i) {
        if (destroyed) return;
        if (docs.isEmpty()) { updateTitle(); return; }
        final int idx = clampI(i, 0, docs.size() - 1);
        dir = idx >= index ? 1 : -1;
        index = idx;
        updateTitle();
        prefs.edit().putInt("index", index).apply();

        final int token = ++loadToken;
        final Uri uri = docs.get(idx).uri;
        final int preIdx = idx + dir;
        final Uri preUri = (preIdx >= 0 && preIdx < docs.size()) ? docs.get(preIdx).uri : null;

        io.execute(() -> {
            Bitmap b = null;
            synchronized (cacheLock) {
                if (prefetchIdx == idx && prefetchBmp != null) {
                    b = prefetchBmp;
                    prefetchBmp = null;
                    prefetchIdx = -1;
                }
            }
            if (b == null) b = decode(uri);
            final Bitmap shown = b;
            main.post(() -> {
                if (!destroyed && token == loadToken) overlayView.setImageBitmap(shown);
            });
            if (preUri != null) {
                Bitmap pb = decode(preUri);
                synchronized (cacheLock) {
                    prefetchBmp = pb;
                    prefetchIdx = preIdx;
                }
            }
        });
    }

    /** Decodes at (about) screen size so memory stays small, and fixes EXIF rotation. */
    private Bitmap decode(Uri uri) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getContentResolver().openInputStream(uri)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            int rot = exifRotation(uri);
            if (b != null && rot != 0) {
                Matrix m = new Matrix();
                m.postRotate(rot);
                b = Bitmap.createBitmap(b, 0, 0, b.getWidth(), b.getHeight(), m, true);
            }
            return b;
        } catch (Exception | OutOfMemoryError e) {
            return null;
        }
    }

    private int exifRotation(Uri uri) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ExifInterface ex = new ExifInterface(in);
            switch (ex.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)) {
                case ExifInterface.ORIENTATION_ROTATE_90: return 90;
                case ExifInterface.ORIENTATION_ROTATE_180: return 180;
                case ExifInterface.ORIENTATION_ROTATE_270: return 270;
                default: return 0;
            }
        } catch (Exception e) {
            return 0;
        }
    }

    private List<Doc> listImages(Uri tree) {
        List<Doc> out = new ArrayList<>();
        try {
            Uri children = DocumentsContract.buildChildDocumentsUriUsingTree(
                    tree, DocumentsContract.getTreeDocumentId(tree));
            String[] proj = {
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE};
            try (Cursor c = getContentResolver().query(children, proj, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    String mime = c.getString(2);
                    if (mime != null && mime.startsWith("image/")) {
                        out.add(new Doc(DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                                c.getString(1)));
                    }
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> naturalCompare(a.name, b.name));
        return out;
    }

    /** "img2" sorts before "img10", like natural_key() in the Python version. */
    static int naturalCompare(String a, String b) {
        a = a.toLowerCase(Locale.ROOT);
        b = b.toLowerCase(Locale.ROOT);
        int i = 0, j = 0;
        while (i < a.length() && j < b.length()) {
            char ca = a.charAt(i), cb = b.charAt(j);
            if (Character.isDigit(ca) && Character.isDigit(cb)) {
                int si = i, sj = j;
                while (i < a.length() && Character.isDigit(a.charAt(i))) i++;
                while (j < b.length() && Character.isDigit(b.charAt(j))) j++;
                String na = a.substring(si, i).replaceFirst("^0+(?!$)", "");
                String nb = b.substring(sj, j).replaceFirst("^0+(?!$)", "");
                if (na.length() != nb.length()) return na.length() - nb.length();
                int c = na.compareTo(nb);
                if (c != 0) return c;
            } else {
                if (ca != cb) return ca - cb;
                i++;
                j++;
            }
        }
        return (a.length() - i) - (b.length() - j);
    }

    // ------------------------------------------------------------ small helpers
    private Notification buildNotification() {
        Notification.Builder b;
        if (Build.VERSION.SDK_INT >= 26) {
            getSystemService(NotificationManager.class).createNotificationChannel(
                    new NotificationChannel(CHANNEL, "Overlay", NotificationManager.IMPORTANCE_LOW));
            b = new Notification.Builder(this, CHANNEL);
        } else {
            b = new Notification.Builder(this);
        }
        PendingIntent stop = PendingIntent.getService(this, 0,
                new Intent(this, OverlayService.class).setAction(ACTION_STOP),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        return b.setSmallIcon(android.R.drawable.ic_menu_gallery)
                .setContentTitle("Reference overlay is on")
                .setContentText("Use the floating ball, or tap Stop")
                .setOngoing(true)
                .addAction(new Notification.Action.Builder(
                        Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel),
                        "Stop", stop).build())
                .build();
    }

    private TextView label(String text) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(13);
        t.setPadding(0, dp(6), 0, 0);
        return t;
    }

    private SeekBar seek(int max, int progress, IntCb cb) {
        SeekBar s = new SeekBar(ctx);
        s.setMax(max);
        s.setProgress(progress);
        s.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int v, boolean fromUser) {
                if (fromUser) cb.on(v);
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { savePrefs(); }
        });
        return s;
    }

    private LinearLayout row() {
        LinearLayout r = new LinearLayout(ctx);
        r.setOrientation(LinearLayout.HORIZONTAL);
        return r;
    }

    private Button btn(String text, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(12);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void readScreen() {
        DisplayMetrics dm = new DisplayMetrics();
        //noinspection deprecation
        wm.getDefaultDisplay().getRealMetrics(dm);
        screenW = dm.widthPixels;
        screenH = dm.heightPixels;
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static int clampI(int v, int lo, int hi) {
        return Math.max(lo, Math.min(Math.max(lo, hi), v));
    }

    private static float clampF(float v, float lo, float hi) {
        return Math.max(lo, Math.min(hi, v));
    }
}
