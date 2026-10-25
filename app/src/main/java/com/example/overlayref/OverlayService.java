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
import android.content.res.ColorStateList;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Matrix;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.Icon;
import android.graphics.drawable.RippleDrawable;
import android.media.ExifInterface;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.text.Editable;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.DisplayMetrics;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ListView;
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
 * plus a draggable "assistive ball" that opens a control panel with a searchable image picker.
 * Reference images are bundled in the APK (app/src/main/assets/references).
 */
public class OverlayService extends Service {
    static final String ACTION_STOP = "com.example.overlayref.STOP";
    private static final String CHANNEL = "overlay";
    private static final int BALL_DP = 56;
    private static final int OP_MIN = 5;      // percent
    private static final int OP_MAX = 100;    // percent
    private static final int PANEL_DP = 300;

    private static final int GREEN = 0xFF2E7D32;
    private static final int RED = 0xFFB71C1C;
    private static final int GREY = 0xFF3A3F47;

    private static final class Doc {
        final String path;   // path inside assets
        final String name;   // file name
        final String label;  // file name without extension
        Doc(String p, String n) { path = p; name = n; label = stripExt(n); }
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
    private TextView opLabel, scLabel;
    private Button selectorBtn, toggleBtn;
    private LinearLayout pickerRoot;
    private WindowManager.LayoutParams pickerLp;
    private EditText search;
    private ListView pickerList;
    private PickAdapter pickAdapter;
    private final List<Integer> filtered = new ArrayList<>();
    private boolean overlayAttached, ballAttached, panelShown, pickerShown, destroyed;

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

        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, "Allow display over other apps first", Toast.LENGTH_LONG).show();
            stopSelf();
            return;
        }

        wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        ctx = new ContextThemeWrapper(this, android.R.style.Theme_DeviceDefault);
        ballSize = dp(BALL_DP);
        readScreen();

        opacity = clampF(prefs.getFloat("opacity", 0.5f), OP_MIN / 100f, OP_MAX / 100f);
        scale = clampF(prefs.getFloat("scale", 1f), 0.5f, 2f);
        overlayOn = prefs.getBoolean("on", true);
        index = prefs.getInt("index", 0);

        addOverlay();
        buildPanel();
        buildPicker();
        addBall();

        io.execute(() -> {
            final List<Doc> list = listImages();
            main.post(() -> {
                if (destroyed) return;
                docs = list;
                if (docs.isEmpty()) {
                    Toast.makeText(this, "No images found. Put them in app/src/main/assets/references",
                            Toast.LENGTH_LONG).show();
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
        hidePicker();
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
            hidePicker();
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

    /**
     * Full-screen reference image. NOT_TOUCHABLE lets every tap fall through to the app below.
     * Opacity is set on the VIEW (not the window), because Android 12+ only lets touches pass through
     * windows whose window-alpha is 0.8 or less. The window itself stays at alpha 1.0.
     */
    private void addOverlay() {
        overlayView = new ImageView(ctx);
        overlayView.setScaleType(ImageView.ScaleType.FIT_CENTER);
        overlayView.setAlpha(opacity);
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
        ball.setTextSize(24);
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
        panel.setPadding(dp(16), dp(14), dp(16), dp(16));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xF2171A1F);
        bg.setCornerRadius(dp(18));
        panel.setBackground(bg);

        panel.addView(label("Reference image", 0));
        selectorBtn = btn("", GREY, v -> showPicker());
        selectorBtn.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        selectorBtn.setSingleLine(true);
        selectorBtn.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(selectorBtn, lp(ViewGroup.LayoutParams.MATCH_PARENT, 6, 0, 0));

        opLabel = label("", 18);
        panel.addView(opLabel);
        panel.addView(seek(OP_MAX - OP_MIN, Math.round(opacity * 100) - OP_MIN, v -> {
            opacity = (v + OP_MIN) / 100f;
            applyOpacity();
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, 4, 0, 0));

        scLabel = label("", 14);
        panel.addView(scLabel);
        panel.addView(seek(150, Math.round(scale * 100) - 50, v -> {
            scale = (v + 50) / 100f;
            applyScale();
        }), lp(ViewGroup.LayoutParams.MATCH_PARENT, 4, 0, 0));

        LinearLayout nav = row();
        nav.addView(half(btn("◀  Prev", GREY, v -> show(index - 1)), true));
        nav.addView(half(btn("Next  ▶", GREY, v -> show(index + 1)), false));
        panel.addView(nav, lp(ViewGroup.LayoutParams.MATCH_PARENT, 18, 0, 0));

        LinearLayout act = row();
        toggleBtn = btn("", GREEN, v -> toggleOverlay());
        act.addView(half(toggleBtn, true));
        act.addView(half(btn("Stop app", RED, v -> stopSelf()), false));
        panel.addView(act, lp(ViewGroup.LayoutParams.MATCH_PARENT, 12, 0, 0));

        panelLp = new WindowManager.LayoutParams(
                Math.min(dp(PANEL_DP), screenW - dp(16)), WindowManager.LayoutParams.WRAP_CONTENT,
                windowType(),
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
        int w = Math.min(dp(PANEL_DP), screenW - dp(16));
        panelLp.width = w;
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

    // ------------------------------------------------------------ searchable image picker
    /** Like Streamlit's selectbox: a list of all images with a search box on top. */
    private void buildPicker() {
        pickerRoot = new LinearLayout(ctx) {
            @Override
            public boolean dispatchKeyEvent(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.KEYCODE_BACK) {
                    if (e.getAction() == KeyEvent.ACTION_UP) hidePicker();
                    return true;
                }
                return super.dispatchKeyEvent(e);
            }
        };
        pickerRoot.setOrientation(LinearLayout.VERTICAL);
        pickerRoot.setPadding(dp(14), dp(14), dp(14), dp(14));
        GradientDrawable bg = new GradientDrawable();
        bg.setColor(0xFA171A1F);
        bg.setCornerRadius(dp(18));
        pickerRoot.setBackground(bg);

        LinearLayout head = row();
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView t = new TextView(ctx);
        t.setText("Choose reference image");
        t.setTextColor(0xFFFFFFFF);
        t.setTextSize(16);
        t.setTypeface(null, Typeface.BOLD);
        head.addView(t, new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        Button close = btn("✕", GREY, v -> hidePicker());
        head.addView(close, new LinearLayout.LayoutParams(dp(48), dp(44)));
        pickerRoot.addView(head);

        search = new EditText(ctx);
        search.setHint("Search...");
        search.setHintTextColor(0xFF8A8F98);
        search.setTextColor(0xFFFFFFFF);
        search.setTextSize(15);
        search.setSingleLine(true);
        search.setPadding(dp(14), dp(10), dp(14), dp(10));
        GradientDrawable sbg = new GradientDrawable();
        sbg.setColor(GREY);
        sbg.setCornerRadius(dp(12));
        search.setBackground(sbg);
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) { }
            @Override public void afterTextChanged(Editable s) { applyFilter(s.toString()); }
        });
        pickerRoot.addView(search, lp(ViewGroup.LayoutParams.MATCH_PARENT, 12, 0, 0));

        pickerList = new ListView(ctx);
        pickerList.setDividerHeight(0);
        pickAdapter = new PickAdapter();
        pickerList.setAdapter(pickAdapter);
        pickerList.setOnItemClickListener((p, v, pos, id) -> {
            int idx = filtered.get(pos);
            hidePicker();
            show(idx);
        });
        pickerRoot.addView(pickerList, lp(ViewGroup.LayoutParams.MATCH_PARENT, 10, 0, 0));
        ((LinearLayout.LayoutParams) pickerList.getLayoutParams()).height = 0;
        ((LinearLayout.LayoutParams) pickerList.getLayoutParams()).weight = 1f;

        pickerLp = new WindowManager.LayoutParams(
                0, 0, windowType(),
                WindowManager.LayoutParams.FLAG_DIM_BEHIND
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        pickerLp.dimAmount = 0.45f;
        pickerLp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        pickerLp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE;
    }

    private void showPicker() {
        if (pickerShown || destroyed || docs.isEmpty()) return;
        hidePanel();
        pickerLp.width = screenW - dp(24);
        pickerLp.height = Math.min(Math.round(screenH * 0.62f), dp(560));
        pickerLp.y = dp(48);
        search.setText("");
        applyFilter("");
        wm.addView(pickerRoot, pickerLp);
        pickerShown = true;
        pickerList.setSelectionFromTop(Math.max(0, index - 2), 0);
    }

    private void hidePicker() {
        if (!pickerShown) return;
        try {
            ((android.view.inputmethod.InputMethodManager) getSystemService(INPUT_METHOD_SERVICE))
                    .hideSoftInputFromWindow(search.getWindowToken(), 0);
        } catch (Exception ignored) {
        }
        wm.removeViewImmediate(pickerRoot);
        pickerShown = false;
    }

    private void applyFilter(String q) {
        String needle = q.trim().toLowerCase(Locale.ROOT);
        filtered.clear();
        for (int i = 0; i < docs.size(); i++) {
            if (needle.isEmpty() || docs.get(i).label.toLowerCase(Locale.ROOT).contains(needle)) {
                filtered.add(i);
            }
        }
        pickAdapter.notifyDataSetChanged();
    }

    private final class PickAdapter extends BaseAdapter {
        @Override public int getCount() { return filtered.size(); }
        @Override public Object getItem(int p) { return filtered.get(p); }
        @Override public long getItemId(int p) { return p; }

        @Override
        public View getView(int pos, View convert, ViewGroup parent) {
            TextView t = convert instanceof TextView ? (TextView) convert : new TextView(ctx);
            int idx = filtered.get(pos);
            boolean cur = idx == index;
            t.setText((idx + 1) + ".   " + docs.get(idx).label);
            t.setTextSize(15);
            t.setTextColor(0xFFFFFFFF);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.MIDDLE);
            t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(dp(14), dp(13), dp(14), dp(13));
            GradientDrawable g = new GradientDrawable();
            g.setColor(cur ? GREEN : 0x00000000);
            g.setCornerRadius(dp(10));
            t.setBackground(g);
            return t;
        }
    }

    // ------------------------------------------------------------ actions
    private void applyOpacity() {
        overlayView.setAlpha(opacity);
        opLabel.setText("Opacity   " + Math.round(opacity * 100) + "%");
    }

    private void applyScale() {
        overlayView.setScaleX(scale);
        overlayView.setScaleY(scale);
        scLabel.setText("Size   " + Math.round(scale * 100) + "%");
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
        if (toggleBtn != null) {
            toggleBtn.setText(overlayOn ? "Overlay: ON" : "Overlay: OFF");
            setBtnColor(toggleBtn, overlayOn ? GREEN : RED);
        }
    }

    private void updateTitle() {
        if (selectorBtn == null) return;
        selectorBtn.setText(docs.isEmpty() ? "No images found"
                : "▾  " + (index + 1) + " / " + docs.size() + "   " + docs.get(index).label);
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
        final String path = docs.get(idx).path;
        final int preIdx = idx + dir;
        final String prePath = (preIdx >= 0 && preIdx < docs.size()) ? docs.get(preIdx).path : null;

        io.execute(() -> {
            Bitmap b = null;
            synchronized (cacheLock) {
                if (prefetchIdx == idx && prefetchBmp != null) {
                    b = prefetchBmp;
                    prefetchBmp = null;
                    prefetchIdx = -1;
                }
            }
            if (b == null) b = decode(path);
            final Bitmap shown = b;
            main.post(() -> {
                if (!destroyed && token == loadToken) overlayView.setImageBitmap(shown);
            });
            if (prePath != null) {
                Bitmap pb = decode(prePath);
                synchronized (cacheLock) {
                    prefetchBmp = pb;
                    prefetchIdx = preIdx;
                }
            }
        });
    }

    /** Decodes at (about) screen size so memory stays small, and fixes EXIF rotation. */
    private Bitmap decode(String path) {
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            try (InputStream in = getAssets().open(path)) {
                BitmapFactory.decodeStream(in, null, o);
            }
            int sample = 1;
            while (o.outWidth / (sample * 2) >= screenW && o.outHeight / (sample * 2) >= screenH) {
                sample *= 2;
            }
            o.inJustDecodeBounds = false;
            o.inSampleSize = sample;
            Bitmap b;
            try (InputStream in = getAssets().open(path)) {
                b = BitmapFactory.decodeStream(in, null, o);
            }
            int rot = exifRotation(path);
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

    private int exifRotation(String path) {
        try (InputStream in = getAssets().open(path)) {
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

    private List<Doc> listImages() {
        List<Doc> out = new ArrayList<>();
        try {
            String[] files = getAssets().list(MainActivity.ASSET_DIR);
            if (files != null) {
                for (String f : files) {
                    if (isImage(f)) out.add(new Doc(MainActivity.ASSET_DIR + "/" + f, f));
                }
            }
        } catch (Exception ignored) {
        }
        Collections.sort(out, (a, b) -> naturalCompare(a.name, b.name));
        return out;
    }

    static boolean isImage(String name) {
        String n = name.toLowerCase(Locale.ROOT);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png") || n.endsWith(".webp");
    }

    static String stripExt(String n) {
        int d = n.lastIndexOf('.');
        return d > 0 ? n.substring(0, d) : n;
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

    // ------------------------------------------------------------ ui helpers
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

    private TextView label(String text, int topDp) {
        TextView t = new TextView(ctx);
        t.setText(text);
        t.setTextColor(0xFFB8BDC6);
        t.setTextSize(13);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.topMargin = dp(topDp);
        t.setLayoutParams(p);
        return t;
    }

    private SeekBar seek(int max, int progress, IntCb cb) {
        SeekBar s = new SeekBar(ctx);
        s.setMax(max);
        s.setProgress(progress);
        s.setPadding(dp(8), dp(10), dp(8), dp(10));
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

    /** Makes a button share a row equally, with a gap between neighbours. */
    private Button half(Button b, boolean first) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(46), 1f);
        if (first) p.rightMargin = dp(6); else p.leftMargin = dp(6);
        b.setLayoutParams(p);
        return b;
    }

    private Button btn(String text, int color, View.OnClickListener l) {
        Button b = new Button(ctx);
        b.setText(text);
        b.setAllCaps(false);
        b.setTextSize(14);
        b.setTextColor(0xFFFFFFFF);
        b.setStateListAnimator(null);
        b.setMinHeight(0);
        b.setMinimumHeight(dp(44));
        b.setPadding(dp(14), dp(8), dp(14), dp(8));
        setBtnColor(b, color);
        b.setOnClickListener(l);
        return b;
    }

    private void setBtnColor(Button b, int color) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(12));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x44FFFFFF), g, null));
    }

    private LinearLayout.LayoutParams lp(int w, int top, int left, int right) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(w, ViewGroup.LayoutParams.WRAP_CONTENT);
        p.setMargins(dp(left), dp(top), dp(right), 0);
        return p;
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
