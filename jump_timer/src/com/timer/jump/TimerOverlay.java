package com.timer.jump;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/**
 * 主界面：申请两个权限，显示使用说明。
 *
 * 注意：这里全部用匿名内部类而不是 lambda。
 * 原因：CI 用 JDK 17 编译、-bootclasspath 指向 android.jar，
 * lambda 需要 LambdaMetafactory，而 android.jar 里没有，
 * 会导致 "Unable to find method metafactory" 编译错误。
 */
public class MainActivity extends Activity {

    private TextView status;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(20);
        root.setPadding(pad, pad, pad, pad);
        root.setBackgroundColor(0xFFF5F5F7);

        TextView title = new TextView(this);
        title.setText("跳转计时器");
        title.setTextSize(22);
        title.setTextColor(0xFF111111);
        root.addView(title);

        TextView sub = new TextView(this);
        sub.setText("页面 / App 跳转时，悬浮窗从 1 开始计时");
        sub.setTextSize(13);
        sub.setTextColor(0xFF666666);
        sub.setPadding(0, dp(6), 0, dp(20));
        root.addView(sub);

        Button btnOverlay = new Button(this);
        btnOverlay.setText("① 授予「悬浮窗」权限");
        btnOverlay.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestOverlay();
            }
        });
        root.addView(btnOverlay);

        Button btnAccess = new Button(this);
        btnAccess.setText("② 开启「无障碍服务」");
        btnAccess.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View v) {
                requestAccessibility();
            }
        });
        root.addView(btnAccess);

        status = new TextView(this);
        status.setTextSize(12);
        status.setPadding(0, dp(16), 0, 0);
        root.addView(status);

        ScrollView scroll = new ScrollView(this);
        TextView help = new TextView(this);
        help.setTextSize(12);
        help.setTextColor(0xFF444444);
        help.setPadding(0, dp(20), 0, 0);
        help.setText(
                "使用说明\n\n"
                        + "1. 点上面两个按钮，分别授予权限\n\n"
                        + "2. 无障碍服务在「已下载的应用」或「已安装的服务」里找到本应用并开启\n\n"
                        + "3. 开启后悬浮窗出现，显示「⏱ 1 s 等待跳转」\n\n"
                        + "4. 切换到任何 App 或切换页面，秒数立即重置为 1\n\n"
                        + "5. 悬浮窗可按住拖动到任意位置\n\n\n"
                        + "检测范围\n\n"
                        + "· App 之间切换（包名变化）\n"
                        + "· Activity 页面切换（类名变化）\n"
                        + "· 弹窗、对话框等新窗口出现\n\n\n"
                        + "不检测的情况\n\n"
                        + "· 同一页面内的内容滚动、列表刷新\n"
                        + "· 输入法、系统权限弹窗（已过滤）\n"
                        + "· Flutter / Compose 应用内部跳转（它们不产生新窗口）\n\n\n"
                        + "常见问题\n\n"
                        + "Q: 秒数不动？\n"
                        + "A: 检查无障碍服务是否已开启，部分手机需要「锁定后台」防止被杀。\n\n"
                        + "Q: 小米/华为上重启后失效？\n"
                        + "A: 到「电池优化」里把本应用设为「不限制」。");
        scroll.addView(help);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        root.addView(scroll, lp);

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        StringBuilder sb = new StringBuilder();
        sb.append("悬浮窗权限：").append(hasOverlay() ? "✅ 已授予" : "❌ 未授予").append("\n");
        sb.append("无障碍服务：").append(hasAccessibility() ? "✅ 已开启" : "❌ 未开启");
        status.setText(sb.toString());
    }

    private void requestOverlay() {
        if (hasOverlay()) {
            Toast.makeText(this, "已授予", Toast.LENGTH_SHORT).show();
            return;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        }
    }

    private void requestAccessibility() {
        Intent i = new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        startActivity(i);
        Toast.makeText(this, "找到「跳转计时器」并开启", Toast.LENGTH_LONG).show();
    }

    private boolean hasOverlay() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return true;
        }
        return Settings.canDrawOverlays(this);
    }

    private boolean hasAccessibility() {
        try {
            String enabled = Settings.Secure.getString(
                    getContentResolver(),
                    Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            if (enabled == null) {
                return false;
            }
            String me = getPackageName() + "/" + JumpDetectService.class.getName();
            return enabled.contains(me) || enabled.contains(getPackageName());
        } catch (Throwable t) {
            return false;
        }
    }

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }
}
