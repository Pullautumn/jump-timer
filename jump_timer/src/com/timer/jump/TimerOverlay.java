package com.timer.jump;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.TextView;

/**
 * 悬浮计时窗：显示"距上次跳转 N 秒"。
 *
 * 全部用匿名内部类，不用 lambda。
 * 原因：CI 用 JDK 17 + android.jar 做 bootclasspath，
 * lambda 依赖 LambdaMetafactory，android.jar 里没有，会编译失败。
 */
public final class TimerOverlay {

    private static final int TICK_MS = 100;

    private static TimerOverlay sInstance;

    private TextView view;
    private WindowManager wm;
    private WindowManager.LayoutParams lp;
    private final Handler handler = new Handler(Looper.getMainLooper());

    private volatile long startAt = System.currentTimeMillis();
    private volatile String event = "等待跳转";
    private volatile boolean running;

    private boolean added;

    private TimerOverlay() {
    }

    public static synchronized TimerOverlay get() {
        if (sInstance == null) {
            sInstance = new TimerOverlay();
        }
        return sInstance;
    }

    /** 跳转发生：归零，从 1 重新数 */
    public void reset(String desc) {
        this.startAt = System.currentTimeMillis();
        this.event = desc == null ? "" : desc;
        this.running = true;
        refresh();
    }

    public void show(final Context context) {
        if (context == null) {
            return;
        }
        final Context app = context.getApplicationContext();

        handler.post(new Runnable() {
            @Override
            public void run() {
                try {
                    if (!canDrawOverlays(app)) {
                        requestOverlayPermission(app);
                        return;
                    }
                    if (view == null) {
                        view = buildView(app);
                    }
                    if (wm == null) {
                        wm = (WindowManager) app.getSystemService(Context.WINDOW_SERVICE);
                    }
                    if (wm == null) {
                        return;
                    }
                    if (!added) {
                        lp = buildParams(app);
                        wm.addView(view, lp);
                        added = true;
                        bindDrag();
                    }
                    running = true;
                    handler.removeCallbacks(tick);
                    handler.post(tick);
                    refresh();
                } catch (Throwable ignored) {
                }
            }
        });
    }

    public void hide() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                running = false;
                handler.removeCallbacks(tick);
                try {
                    if (added && wm != null && view != null) {
                        wm.removeView(view);
                    }
                } catch (Throwable ignored) {
                }
                added = false;
            }
        });
    }

    // ==================== 内部实现 ====================

    private TextView buildView(Context ctx) {
        TextView tv = new TextView(ctx);
        tv.setTextColor(0xFFFFFFFF);
        tv.setTextSize(13f);
        int p = dp(ctx, 10);
        tv.setPadding(p, dp(ctx, 6), p, dp(ctx, 6));
        tv.setBackgroundColor(0xC8000000);
        tv.setGravity(Gravity.START);
        tv.setText("⏱ 1 s\n等待跳转");
        return tv;
    }

    private WindowManager.LayoutParams buildParams(Context ctx) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams();
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            p.type = WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY;
        } else {
            p.type = WindowManager.LayoutParams.TYPE_PHONE;
        }
        p.format = PixelFormat.TRANSLUCENT;
        p.flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS;
        p.gravity = Gravity.TOP | Gravity.START;
        p.x = dp(ctx, 12);
        p.y = dp(ctx, 180);
        p.width = WindowManager.LayoutParams.WRAP_CONTENT;
        p.height = WindowManager.LayoutParams.WRAP_CONTENT;
        return p;
    }

    /** 拖动：手指按下后跟随移动 */
    @SuppressLint("ClickableViewAccessibility")
    private void bindDrag() {
        if (view == null || lp == null || wm == null) {
            return;
        }
        view.setOnTouchListener(new View.OnTouchListener() {
            private final int[] down = new int[2];
            private final float[] touch = new float[2];

            @Override
            public boolean onTouch(View v, MotionEvent e) {
                switch (e.getAction()) {
                    case MotionEvent.ACTION_DOWN:
                        down[0] = lp.x;
                        down[1] = lp.y;
                        touch[0] = e.getRawX();
                        touch[1] = e.getRawY();
                        return true;
                    case MotionEvent.ACTION_MOVE:
                        lp.x = down[0] + (int) (e.getRawX() - touch[0]);
                        lp.y = down[1] + (int) (e.getRawY() - touch[1]);
                        try {
                            wm.updateViewLayout(view, lp);
                        } catch (Throwable ignored) {
                        }
                        return true;
                    case MotionEvent.ACTION_UP:
                        return true;
                }
                return false;
            }
        });
    }

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) {
                return;
            }
            refresh();
            handler.postDelayed(this, TICK_MS);
        }
    };

    @SuppressLint("SetTextI18n")
    private void refresh() {
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (view == null) {
                    return;
                }
                long ms = System.currentTimeMillis() - startAt;
                long sec = ms / 1000L + 1L;      // 从 1 开始
                String head = "⏱ " + sec + " s";
                String body = event;
                view.setText(body.isEmpty() ? head : head + "\n" + body);
            }
        });
    }

    // ==================== 权限 ====================

    private static boolean canDrawOverlays(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(ctx);
    }

    private static void requestOverlayPermission(Context ctx) {
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + ctx.getPackageName()));
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            ctx.startActivity(i);
        } catch (Throwable ignored) {
        }
    }

    private static int dp(Context ctx, int v) {
        return (int) (v * ctx.getResources().getDisplayMetrics().density + 0.5f);
    }
}
