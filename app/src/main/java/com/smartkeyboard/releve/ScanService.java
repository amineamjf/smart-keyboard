package com.smartkeyboard.releve;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Rect;
import android.os.Handler;
import android.os.Looper;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;

public class ScanService extends AccessibilityService {

    private static final String APP = "com.somei.smartmobility.srmrsk";
    public static ScanService instance;
    public static final ArrayList<String> DUMPS = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pending = false;
    private String lastPkg = "";
    private String last = "";
    private int tries = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
    }

    @Override
    public boolean onUnbind(android.content.Intent intent) {
        instance = null;
        return super.onUnbind(intent);
    }

    public void goSearch() {
        tries = 0;
        handler.removeCallbacks(searchStep);
        handler.post(searchStep);
    }

    private final Runnable searchStep = new Runnable() {
        @Override
        public void run() {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inApp = root != null && APP.equals(String.valueOf(root.getPackageName()));
            if (inApp) {
                AccessibilityNodeInfo n = findId(root, "searchInput", 0);
                if (n != null) {
                    n.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                    return;
                }
            }
            if (tries >= 4 || !inApp) return;
            tries++;
            performGlobalAction(GLOBAL_ACTION_BACK);
            handler.postDelayed(searchStep, 700);
        }
    };

    private AccessibilityNodeInfo findId(AccessibilityNodeInfo n, String id, int depth) {
        if (n == null || depth > 30) return null;
        String vid = n.getViewIdResourceName();
        if (vid != null && vid.endsWith(id) && n.isVisibleToUser()) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findId(n.getChild(i), id, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            pending = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            String pkg = String.valueOf(root.getPackageName());
            if (skip(pkg)) return;
            StringBuilder sb = new StringBuilder();
            sb.append("PKG ").append(pkg).append("\n");
            dump(root, 0, sb);
            String s = sb.toString();
            if (s.equals(last)) return;
            last = s;
            synchronized (DUMPS) {
                DUMPS.add("=== ecran ===\n" + s);
                while (DUMPS.size() > 12) DUMPS.remove(0);
            }
        }
    };

    private boolean skip(String p) {
        return p.equals(getPackageName())
                || p.equals("com.android.systemui")
                || p.equals("com.anthropic.claude")
                || p.contains("launcher");
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
        boolean shown = t != null && t.length() > 0;
        if (shown || d != null || id != null || n.isEditable()) {
            sb.append(depth).append(" ").append(n.getClassName());
            if (shown) sb.append(" text=[").append(t).append("]");
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
