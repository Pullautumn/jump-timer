package com.timer.jump;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.Intent;
import android.os.Build;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * 页面 / App 跳转监听服务。
 *
 * 靠 AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED 实现：
 *   系统在「窗口切换」时广播这个事件，我们据此判断发生了跳转。
 *
 * 能捕获的三类跳转：
 *   ① App 之间切换      → event.getPackageName() 变了
 *   ② Activity 切换     → event.getClassName() 变了
 *   ③ 弹窗/对话框出现    → 新窗口成为前台（也算一次跳转）
 *
 * 每触发一次 → TimerOverlay.reset() → 悬浮窗从 1 开始重新计时
 */
public class JumpDetectService extends AccessibilityService {

    private static final String TAG = "JumpTimer";

    /** 上一次事件的包名，用来判断是否跨 App */
    private String lastPackage = "";

    /** 上一次事件的窗口类名 */
    private String lastClassName = "";

    /** 同一次跳转可能连发多个事件，用时间戳去抖 */
    private static final long DEBOUNCE_MS = 200L;
    private long lastTriggerAt = 0L;

    @Override
    public void onServiceConnected() {
        super.onServiceConnected();

        AccessibilityServiceInfo info = new AccessibilityServiceInfo();
        // 只关心窗口状态变化，避免收无用事件耗电
        info.eventTypes = AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                | AccessibilityEvent.TYPE_WINDOWS_CHANGED;

        // 反馈类型：不朗读、不震动，静默运行
        info.feedbackType = AccessibilityServiceInfo.FEEDBACK_GENERIC;

        // 监听所有包
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            info.flags = AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS
                    | AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS;
        }

        // 0 = 不限间隔，实时回调
        info.notificationTimeout = 0;

        setServiceInfo(info);

        Log.i(TAG, "无障碍服务已连接");

        // 服务起来就显示悬浮窗，初始状态"等待跳转"
        TimerOverlay.get().show(this);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null) {
            return;
        }

        int type = event.getEventType();

        // 窗口改变也要处理：有些系统只发 WINDOWS_CHANGED 不发 STATE_CHANGED
        if (type != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                && type != AccessibilityEvent.TYPE_WINDOWS_CHANGED) {
            return;
        }

        String pkg = safeStr(event.getPackageName());
        String cls = safeStr(event.getClassName());

        // 过滤自己，避免自身界面触发
        if (pkg.equals(getPackageName())) {
            return;
        }

        // 过滤输入法、系统 UI 这类高频弹窗，否则会被狂刷
        if (isNoise(pkg, cls)) {
            return;
        }

        // 没变化就不算跳转（同一窗口的内容刷新不算）
        if (pkg.equals(lastPackage) && cls.equals(lastClassName)) {
            return;
        }

        // 去抖
        long now = System.currentTimeMillis();
        if (now - lastTriggerAt < DEBOUNCE_MS) {
            return;
        }
        lastTriggerAt = now;

        boolean crossApp = !pkg.equals(lastPackage) && !lastPackage.isEmpty();
        lastPackage = pkg;
        lastClassName = cls;

        // 构造显示文字
        String appLabel = getAppLabel(pkg);
        String pageName = shortClass(cls);
        String desc;
        if (crossApp) {
            desc = "切换到 " + appLabel;
        } else {
            desc = appLabel + " · " + pageName;
        }

        // 尝试拿到页面标题，比类名更直白
        String title = extractTitle(event);
        if (title != null && !title.isEmpty()) {
            desc = appLabel + " · " + title;
        }

        TimerOverlay.get().reset(desc);
        Log.d(TAG, "跳转检测：" + desc + "  (" + pkg + " / " + cls + ")");
    }

    @Override
    public void onInterrupt() {
        Log.i(TAG, "服务被中断");
    }

    @Override
    public boolean onUnbind(Intent intent) {
        TimerOverlay.get().hide();
        return super.onUnbind(intent);
    }

    @Override
    public void onDestroy() {
        TimerOverlay.get().hide();
        super.onDestroy();
    }

    // ==================== 辅助方法 ====================

    private static String safeStr(CharSequence cs) {
        return cs == null ? "" : cs.toString();
    }

    /**
     * 过滤噪音：输入法、系统弹窗、通知栏展开等
     * 这些不是用户感知的"页面跳转"
     */
    private boolean isNoise(String pkg, String cls) {
        if (pkg.isEmpty()) {
            return true;
        }
        // 输入法（会频繁弹窗）
        if (pkg.contains("inputmethod") || pkg.contains("ime")
                || cls.contains("InputMethod")) {
            return true;
        }
        // 系统 UI（状态栏、最近任务）
        if (pkg.equals("com.android.systemui")) {
            return true;
        }
        // 权限弹窗
        if (pkg.equals("com.android.permissioncontroller")
                || pkg.equals("com.google.android.permissioncontroller")) {
            return true;
        }
        // Toast 容器
        if (cls.contains("Toast")) {
            return true;
        }
        return false;
    }

    /** 从事件源节点里取标题文字 */
    private String extractTitle(AccessibilityEvent event) {
        try {
            AccessibilityNodeInfo node = event.getSource();
            if (node == null) {
                return null;
            }
            CharSequence text = node.getText();
            if (text != null && text.length() > 0 && text.length() < 24) {
                return text.toString();
            }
            // 有些页面标题在 contentDescription 里
            CharSequence cd = node.getContentDescription();
            if (cd != null && cd.length() > 0 && cd.length() < 24) {
                return cd.toString();
            }
        } catch (Throwable ignored) {
        }
        return null;
    }

    /** com.tencent.mm.ui.LauncherUI → LauncherUI */
    private static String shortClass(String cls) {
        if (cls == null || cls.isEmpty()) {
            return "未知页面";
        }
        int i = cls.lastIndexOf('.');
        return i >= 0 ? cls.substring(i + 1) : cls;
    }

    /** 包名 → App 名称 */
    private String getAppLabel(String pkg) {
        try {
            android.content.pm.PackageManager pm = getPackageManager();
            android.content.pm.ApplicationInfo ai = pm.getApplicationInfo(pkg, 0);
            CharSequence label = pm.getApplicationLabel(ai);
            if (label != null) {
                return label.toString();
            }
        } catch (Throwable ignored) {
        }
        return pkg;
    }
}
