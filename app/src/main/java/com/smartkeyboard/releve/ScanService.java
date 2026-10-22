package com.smartkeyboard.releve;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.GestureDescription;
import android.graphics.Path;
import android.graphics.Rect;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.DisplayMetrics;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayList;

public class ScanService extends AccessibilityService {

    private static final String APP = "com.somei.smartmobility.srmrsk";
    private static final long POLL = 60;
    public static ScanService instance;
    public static boolean live = false;
    public static final ArrayList<String> DUMPS = new ArrayList<>();

    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean pending = false;
    private String last = "";
    private int steps = 0;
    private int phase = 0;
    private int backsDone = 0;
    private int contTries = 0;
    private long t0 = 0;
    private long lastTap = 0;
    private long backTime = 0;

    private boolean busy = false;
    private boolean stopFlag = false;
    private boolean subRetried = false;
    private String subValue = "";
    private String subMeter = "";
    private int subPhase = 0;
    private long subPhaseT = 0;

    private static final long PHOTO_DELAY_MS = 1000;
    private int photoPhase = 0;
    private long photoT = 0;
    private int photoCount = 0;
    private boolean afterPhoto = false;
    private int photoTaps = 0;
    private long photoMsgSeen = 0;

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        instance = this;
        live = false;
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
            int max = isLog ? 30 : 3;
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
        long ms = SystemClock.uptimeMillis() - t0;
        add("LOG " + s + " (+" + ms + "ms)\n", true);
    }

    // ---------- Recherche ----------

    public void goSearch() {
        steps = 0;
        phase = 0;
        backsDone = 0;
        contTries = 0;
        lastTap = 0;
        t0 = SystemClock.uptimeMillis();
        log("Recherche demandee");
        handler.removeCallbacks(searchStep);
        handler.post(searchStep);
    }

    private final Runnable searchStep = new Runnable() {
        @Override
        public void run() {
            steps++;
            if (steps > 160) {
                log("abandon, delai depasse");
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null) {
                handler.postDelayed(searchStep, POLL);
                return;
            }
            String pkg = String.valueOf(root.getPackageName());
            if (!APP.equals(pkg)) {
                log("autre appli " + pkg);
                return;
            }
            long now = SystemClock.uptimeMillis();
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
                if (contTries >= 1 && now - lastTap < 900) {
                    handler.postDelayed(searchStep, POLL);
                    return;
                }
                contTries++;
                lastTap = now;
                if (contTries == 1) {
                    Rect r = new Rect();
                    go.getBoundsInScreen(r);
                    boolean ok = tapAbs(r.centerX(), r.centerY());
                    log("Continuer: toucher " + r.toShortString() + " (" + ok + ")");
                } else if (contTries == 2) {
                    boolean ok = tap(0.649f, 0.560f);
                    log("Continuer: toucher position fixe (" + ok + ")");
                } else {
                    boolean ok = clickUp(go);
                    log("Continuer: clic direct (" + ok + ")");
                }
                handler.postDelayed(searchStep, 150);
                return;
            }
            if (phase == 0) {
                phase = 1;
                backsDone = 1;
                backTime = now;
                boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                log("retour demande (" + ok + ")");
                handler.postDelayed(searchStep, POLL);
                return;
            }
            if (phase == 1) {
                if (backsDone < 2 && now - backTime > 1100) {
                    backsDone = 2;
                    backTime = now;
                    boolean ok = performGlobalAction(GLOBAL_ACTION_BACK);
                    log("2e retour (" + ok + ")");
                    handler.postDelayed(searchStep, POLL);
                    return;
                }
                if (now - backTime > 2500) {
                    phase = 2;
                    boolean ok = tap(0.649f, 0.560f);
                    log("rien trouve, toucher position fixe (" + ok + ")");
                    handler.postDelayed(searchStep, 300);
                    return;
                }
            }
            handler.postDelayed(searchStep, POLL);
        }
    };

    // ---------- Envoi du releve ----------

    public void stopAll() {
        stopFlag = true;
        handler.removeCallbacks(submitStep);
        handler.removeCallbacks(searchStep);
        handler.removeCallbacks(photoStep);
        photoPhase = 0;
        busy = false;
        log("STOP demande");
    }

    public void submit(String value) {
        if (busy) {
            log("envoi deja en cours");
            return;
        }
        if (value == null || value.length() == 0) {
            log("envoi: rien a envoyer");
            return;
        }
        if (!afterPhoto) photoCount = 0;
        boolean wasAfterPhoto = afterPhoto;
        afterPhoto = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) {
            log("envoi: pas dans l'application");
            return;
        }
        AccessibilityNodeInfo btn = findId(root, "btn-cloture-success", 0);
        AccessibilityNodeInfo field = findId(root, "modelInputIdx", 0);
        if (btn == null || field == null) {
            log("envoi: ce n'est pas l'ecran Releve");
            return;
        }
        String cur = textOf(field);
        if (wasAfterPhoto && live && isEmptyField(field)) {
            subValue = value;
            subMeter = meterLabel(root);
            t0 = SystemClock.uptimeMillis();
            stopFlag = false;
            boolean tf = tapNode(field);
            busy = true;
            subPhase = 3;
            subPhaseT = SystemClock.uptimeMillis();
            subRetried = false;
            log("apres photo: champ vide, saisie de " + value + " (" + tf + ")");
            handler.postDelayed(submitStep, 250);
            return;
        }
        if (!value.equals(cur)) {
            log("envoi refuse: champ=" + cur + " mais clavier=" + value);
            return;
        }
        subValue = value;
        subMeter = meterLabel(root);
        t0 = SystemClock.uptimeMillis();
        if (!live) {
            log("SIMULATION ok: valeur " + value + ", " + subMeter);
            return;
        }
        stopFlag = false;
        boolean t = tapNode(btn);
        if (!t) {
            log("envoi: position du bouton invalide");
            return;
        }
        busy = true;
        subPhase = 1;
        subPhaseT = SystemClock.uptimeMillis();
        log("ENVOI REEL " + value + ", " + subMeter + ", J'ai fini 1 touche");
        handler.postDelayed(submitStep, 150);
    }

    private final Runnable submitStep = new Runnable() {
        @Override
        public void run() {
            if (stopFlag) {
                finish("arret demande");
                return;
            }
            long now = SystemClock.uptimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inApp = root != null
                    && APP.equals(String.valueOf(root.getPackageName()));
            if (!inApp) {
                if (now - subPhaseT > 3000) finish("STOP: ecran illisible");
                else handler.postDelayed(submitStep, POLL);
                return;
            }
            AccessibilityNodeInfo field = findId(root, "modelInputIdx", 0);
            AccessibilityNodeInfo dialog = findText(root, "jug", 0);
            String meter = meterLabel(root);
            AccessibilityNodeInfo photoMsg = findText(root, "Photo obligatoire", 0);
            if (photoMsg != null) {
                if (photoMsgSeen == 0) {
                    photoMsgSeen = now;
                    log("PHOTO: message vu, attente 1,5 s");
                }
                if (now - photoMsgSeen < 1500) {
                    handler.postDelayed(submitStep, POLL);
                    return;
                }
                photoMsgSeen = 0;
                if (photoCount >= 1) {
                    finish("STOP: photo encore demandee apres la prise");
                    return;
                }
                AccessibilityNodeInfo okp = findExact(root, "Ok", 0);
                if (okp != null) tapNode(okp);
                photoCount++;
                startPhoto();
                return;
            }
            if (subPhase == 1) {
                if (dialog != null) {
                    AccessibilityNodeInfo ok = findExact(root, "Ok", 0);
                    if (ok != null) {
                        boolean t = tapNode(ok);
                        log("message: Ok touche (" + t + ")");
                        subPhase = 2;
                        subPhaseT = now;
                        handler.postDelayed(submitStep, 250);
                        return;
                    }
                } else if (meter.length() > 0 && !meter.equals(subMeter)) {
                    finish("FIN OK: compteur suivant apres une seule validation");
                    return;
                }
                if (now - subPhaseT > 3500) {
                    finish("STOP: ni message ni changement apres 3,5 s");
                    return;
                }
            } else if (subPhase == 2) {
                if (dialog == null && field != null && isEmptyField(field)) {
                    boolean t = tapNode(field);
                    log("champ vide: toucher pour saisir (" + t + ")");
                    subPhase = 3;
                    subPhaseT = now;
                    subRetried = false;
                    handler.postDelayed(submitStep, 250);
                    return;
                }
                if (now - subPhaseT > 3000) {
                    finish("STOP: champ non vide ou message encore present");
                    return;
                }
            } else if (subPhase == 3) {
                KeyboardService k = KeyboardService.instance;
                if (field != null && isEmptyField(field) && k != null
                        && k.typeText(subValue)) {
                    log("saisie de " + subValue);
                    subPhase = 4;
                    subPhaseT = now;
                    handler.postDelayed(submitStep, 150);
                    return;
                }
                if (!subRetried && now - subPhaseT > 1200 && field != null) {
                    subRetried = true;
                    tapNode(field);
                }
                if (now - subPhaseT > 3000) {
                    finish("STOP: clavier pas pret pour la saisie");
                    return;
                }
            } else if (subPhase == 4) {
                if (field != null && subValue.equals(textOf(field))) {
                    AccessibilityNodeInfo btn = findId(root, "btn-cloture-success", 0);
                    if (btn == null) {
                        finish("STOP: bouton J'ai fini introuvable");
                        return;
                    }
                    boolean t = tapNode(btn);
                    log("J'ai fini 2: toucher (" + t + ")");
                    subPhase = 5;
                    subPhaseT = now;
                    handler.postDelayed(submitStep, 250);
                    return;
                }
                if (now - subPhaseT > 1500) {
                    String c = field == null ? "?" : textOf(field);
                    finish("STOP: champ=" + c + " au lieu de " + subValue);
                    return;
                }
            } else if (subPhase == 5) {
                if (dialog != null) {
                    finish("STOP: le message est revenu");
                    return;
                }
                if (meter.length() > 0 && !meter.equals(subMeter)) {
                    finish("FIN OK: compteur suivant");
                    return;
                }
                if (now - subPhaseT > 4000) {
                    finish("STOP: pas de changement apres la 2e validation");
                    return;
                }
            }
            handler.postDelayed(submitStep, POLL);
        }
    };

    private void finish(String msg) {
        busy = false;
        photoMsgSeen = 0;
        log(msg);
    }

    private String textOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        return t == null ? "" : t.toString().trim();
    }

    private boolean isEmptyField(AccessibilityNodeInfo n) {
        String t = textOf(n);
        return t.length() == 0 || t.equalsIgnoreCase("Index");
    }

    private String meterLabel(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo m = findText(root, "compteur :", 0);
        return m == null ? "" : textOf(m);
    }

    private boolean tapNode(AccessibilityNodeInfo n) {
        Rect r = new Rect();
        n.getBoundsInScreen(r);
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        if (r.width() <= 0 || r.height() <= 0) return false;
        if (r.centerX() < 0 || r.centerX() > dm.widthPixels) return false;
        if (r.centerY() < 0 || r.centerY() > dm.heightPixels) return false;
        return tapAbs(r.centerX(), r.centerY());
    }

    // ---------- Photo ----------

    private void startPhoto() {
        busy = true;
        photoPhase = 1;
        photoTaps = 0;
        photoT = SystemClock.uptimeMillis();
        log("PHOTO: OK touche, ouverture de l'appareil photo");
        handler.postDelayed(photoStep, 500);
    }

    private final Runnable photoStep = new Runnable() {
        @Override
        public void run() {
            if (stopFlag) {
                photoPhase = 0;
                finish("arret demande (photo)");
                return;
            }
            long now = SystemClock.uptimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String pkg = root == null ? "" : String.valueOf(root.getPackageName());
            boolean inApp = APP.equals(pkg);

            if (photoPhase == 1) {
                if (inApp && findText(root, "Photo obligatoire", 0) == null) {
                    tapCamera();
                    photoPhase = 2;
                    photoT = now;
                    handler.postDelayed(photoStep, 300);
                    return;
                }
                if (now - photoT > 3000) {
                    photoPhase = 0;
                    finish("STOP: fenetre photo non fermee");
                    return;
                }
            } else if (photoPhase == 2) {
                if (root != null && !inApp && pkg.length() > 0) {
                    photoPhase = 3;
                    photoT = now;
                    log("PHOTO: appareil ouvert (" + pkg + ")");
                    handler.postDelayed(photoStep, PHOTO_DELAY_MS);
                    return;
                }
                if (now - photoT > 5000) {
                    photoPhase = 0;
                    finish("STOP: l'appareil photo ne s'ouvre pas");
                    return;
                }
            } else if (photoPhase == 3) {
                boolean ok = tap(0.5f, 0.856f);
                log("PHOTO: declencheur touche (" + ok + ")");
                photoPhase = 4;
                photoT = now;
                handler.postDelayed(photoStep, 2500);
                return;
            } else if (photoPhase == 4) {
                boolean ok = tap(0.793f, 0.862f);
                log("PHOTO: valider touche (" + ok + ")");
                photoPhase = 5;
                photoT = now;
                handler.postDelayed(photoStep, 500);
                return;
            } else if (photoPhase == 5) {
                if (inApp && findId(root, "modelInputIdx", 0) != null) {
                    photoPhase = 0;
                    busy = false;
                    afterPhoto = true;
                    log("PHOTO: retour au releve, nouvelle validation");
                    submit(subValue);
                    return;
                }
                if (now - photoT > 1500 && photoTaps < 3) {
                    photoTaps++;
                    photoT = now;
                    boolean ok2 = tap(0.793f, 0.862f);
                    log("PHOTO: valider re-touche (" + ok2 + ")");
                    handler.postDelayed(photoStep, 500);
                    return;
                }
                if (now - photoT > 3000) {
                    photoPhase = 0;
                    finish("STOP: retour au releve impossible");
                    return;
                }
            }
            handler.postDelayed(photoStep, POLL);
        }
    };

    private void tapCamera() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return;
        AccessibilityNodeInfo field = findId(root, "modelInputIdx", 0);
        if (field == null) {
            log("appareil photo: champ introuvable");
            return;
        }
        Rect f = new Rect();
        field.getBoundsInScreen(f);
        AccessibilityNodeInfo cam = findRightOf(root, f, 0);
        if (cam == null) {
            log("appareil photo: bouton introuvable");
            return;
        }
        boolean t = tapNode(cam);
        log("appareil photo: bouton touche (" + t + ")");
    }

    private AccessibilityNodeInfo findRightOf(AccessibilityNodeInfo n, Rect f, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isClickable() && String.valueOf(n.getClassName()).endsWith("Button")) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.left >= f.right - 5 && r.bottom > f.top && r.top < f.bottom) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo x = findRightOf(n.getChild(i), f, depth + 1);
            if (x != null) return x;
        }
        return null;
    }

    // ---------- Outils ----------

    private boolean tap(float fx, float fy) {
        DisplayMetrics dm = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(dm);
        return tapAbs(dm.widthPixels * fx, dm.heightPixels * fy);
    }

    private boolean tapAbs(float x, float y) {
        Path p = new Path();
        p.moveTo(x, y);
        GestureDescription.StrokeDescription st =
                new GestureDescription.StrokeDescription(p, 0, 80);
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

    private AccessibilityNodeInfo findExact(AccessibilityNodeInfo n, String s, int depth) {
        if (n == null || depth > 30) return null;
        CharSequence t = n.getText();
        if (t != null && t.toString().trim().equalsIgnoreCase(s)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo r = findExact(n.getChild(i), s, depth + 1);
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
