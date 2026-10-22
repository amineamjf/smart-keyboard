package com.smartkeyboard.releve;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;

public class ScanService extends AccessibilityService {

    private static final String APP = "com.somei.smartmobility.srmrsk";
    public static ScanService instance;
    public static final ArrayList<String> DUMPS = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pending = false;
    private String last = "";
    private int steps = 0;
    private int phase = 0;

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

    private void add(String s, boolean isLog) {
        synchronized (DUMPS) {
            DUMPS.add(s);
            int count = 0;
            int max = isLog ? 25 : 3;
            for (int i = DUMPS.size() - 1; i >= 0; i--) {
                boolean l = DUMPS.get(i).startsWith("LOG");
                if (l == isLog) {
                    count++;
                    if (count > max) DUMPS.remove(i);
                }
            }
        }
    }

    private void log(String s) {
        add("LOG " + s + "\n", true);
    }

    public void goSearch() {
        steps = 0;
        phase = 0;
        log("Recherche demandee");
        handler.removeCallbacks(searchStep);
        handler.post(searchStep);
    }

    private final Runnable searchStep = new Runnable() {
        @Override
        public void run() {
            steps++;
            if (steps > 14) {
                log("abandon apres 14 etapes");
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                log("etape " + steps + ": ecran illisible");
                handler.postDelayed(searchStep, 600);
                return;
            }
            String pkg = String.valueOf(root.getPackageName());
            if (!APP.equals(pkg)) {
                log("etape " + steps + ": autre appli " + pkg);
                return;
            }
            AccessibilityNodeInfo box = findId(root, "searchInput", 0);
            if (box != null) {
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                boolean cleared = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                log("champ recherche: clic=" + ok + ", vide=" + cleared);
                return;
            }
            AccessibilityNodeInfo go = findText(root, "Continuer", 0);
            if (go != null) {
                boolean ok = clickUp(go);
                log("Continuer trouve par texte, clic=" + ok);
                handler.postDelayed(searchStep, 900);
                return;
            }
            if (phase == 0) {
                phase = 1;
                boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                log("retour demande (" + ok + ")");
                handler.postDelayed(searchStep, 1000);
                return;
            }
            if (phase == 1) {
                phase = 2;
                boolean ok = tap(0.649f, 0.560f);
                log("clic par position sur Continuer (" + ok + ")");
                handler.postDelayed(searchStep, 1200);
                return;
            }
            handler.postDelayed(searchStep, 600);
        }
    };

    private boolean tap(float fx, float fy) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        Path p = new Path();
        p.moveTo(dm.widthPixels * fx, dm.heightPixels * fy);
        GestureDescription.StrokeDescription st =
                new GestureDescription.StrokeDescription(p, 0, 60);
        GestureDescription g = new GestureDescription.Builder().addStroke(st).build();
        return dispatchGesture(g, null, null);
    }

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

    private AccessibilityNodeInfo findText(AccessibilityNodeInfo n, String s, int depth) {
        if (n == null || depth > 30) return null;
        CharSequence t = n.getText();
        if (t != null && t.toString().contains(s)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findText(n.getChild(i), s, depth + 1);
            if (r != null) return r;
        }
        return null;
    }

    private boolean clickUp(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo c = n;
        for (int i = 0; i < 5 && c != null; i++) {
            if (c.isClickable()) {
                return c.performAction(AccessibilityNodeInfo.ACTION_CLICK);
            }
            c = c.getParent();
        }
        return n.performAction(AccessibilityNodeInfo.ACTION_CLICK);
    }

    private final Runnable capture = new Runnable() {
        @Override
        public void run() {
            pending = false;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) return;
            if (!APP.equals(String.valueOf(root.getPackageName()))) return;
            StringBuilder sb = new StringBuilder();
            dump(root, 0, sb);
            String s = sb.toString();
            if (s.equals(last)) return;
            last = s;
            add("=== ecran ===\n" + s, false);
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        if (p == null || !APP.equals(p.toString())) return;
        if (!pending) {
            pending = true;
            handler.postDelayed(capture, 800);
        }
    }

    @Override
    public void onInterrupt() {
    }

    private void dump(AccessibilityNodeInfo n, int depth, StringBuilder sb) {
        if (n == null || depth > 25 || sb.length() > 6000) return;
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
