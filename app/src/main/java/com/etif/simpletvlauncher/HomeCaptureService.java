package com.etif.simpletvlauncher;

import android.accessibilityservice.AccessibilityService;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.SystemClock;
import android.view.KeyEvent;
import android.view.accessibility.AccessibilityEvent;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/** Optional, user-enabled HOME redirection. Some TV firmware never forwards HOME. */
public final class HomeCaptureService extends AccessibilityService {
    private long lastRedirect;
    private final Set<String> otherHomePackages = new HashSet<>();

    @Override public void onServiceConnected() {
        super.onServiceConnected();
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        try {
            List<ResolveInfo> all = getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL);
            for (ResolveInfo info : all) {
                if (info.activityInfo != null && !getPackageName().equals(info.activityInfo.packageName))
                    otherHomePackages.add(info.activityInfo.packageName);
            }
        } catch (Exception ignored) { }
    }

    private boolean enabled() {
        SharedPreferences p = getSharedPreferences("launcher_prefs", MODE_PRIVATE);
        return p.getBoolean("home_capture_enabled", false);
    }

    private void redirect() {
        long now = SystemClock.elapsedRealtime();
        long suspendUntil = getSharedPreferences("launcher_prefs", MODE_PRIVATE)
                .getLong("external_launch_until", 0L);
        if (!enabled() || now < suspendUntil || now - lastRedirect < 1400) return;
        lastRedirect = now;
        Intent intent = new Intent(this, MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP);
        try { startActivity(intent); } catch (Exception ignored) { }
    }

    @Override protected boolean onKeyEvent(KeyEvent event) {
        if (event.getKeyCode() == KeyEvent.KEYCODE_HOME && enabled()) {
            if (event.getAction() == KeyEvent.ACTION_UP) redirect();
            return true;
        }
        return false;
    }

    @Override public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event.getEventType() != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED || !enabled()) return;
        CharSequence pkg = event.getPackageName();
        if (pkg != null && otherHomePackages.contains(pkg.toString())) redirect();
    }

    @Override public void onInterrupt() { }
}
