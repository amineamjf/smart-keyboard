package com.smartkeyboard.releve;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;

public class ScanService extends AccessibilityService {

    public static final ArrayList<String> DUMPS = new ArrayList<>();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pending = false;
    private String lastPkg = "";
    private String last = "";

    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            pending = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                add("ROOT NULL pkg=" + lastPkg);
                return;
            }
            String pkg = String.valueOf(root.getPackageName());
            if (skip(pkg)) return;
            StringBuilder sb = new StringBuilder();
            sb.append("PKG ").append(pkg).append("\n");
            dump(root, 0, sb);
            add(sb.toString());
        }
    };

    private boolean skip(String p) {
        return p.equals(getPackageName())
                || p.equals("com.android.systemui")
                || p.equals("com.anthropic.claude")
                || p.contains("launcher");
    }

    private void add(String s) {
        if (s.equals(last)) return;
        last = s;
        synchronized (DUMPS) {
            DUMPS.add("=== ecran ===\n" + s);
            while (DUMPS.size() > 12) DUMPS.remove(0);
        }
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        if (p != null) lastPkg = p.toString();
        if (skip(lastPkg)) return;
        if (!pending) {
            pending = true;
            handler.postDelayed(capture, 800);
        }
    }

    @Override
    public void onInterrupt() {
    }

    private void dump(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 25) return;
        CharSequence t = n.getText();
        CharSequence d = n.getContentDescription();
        String id = n.getViewIdResourceName();
        if (t != null || d != null || id != null || n.isClickable() || n.isEditable()) {
            sb.append(depth).append(" ").append(n.getClassName());
            if (t != null) sb.append(" text=[").append(t).append("]");
            if (d != null) sb.append(" desc=[").append(d).append("]");
            if (id != null) sb.append(" id=").append(id);
            if (n.isClickable()) sb.append(" CLICK");
            if (n.isEditable()) sb.append(" EDIT");
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            sb.append(" ").append(r.toShortString()).append("\n");
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            dump(n.getChild(i), depth + 1, sb);
        }
    }
}
