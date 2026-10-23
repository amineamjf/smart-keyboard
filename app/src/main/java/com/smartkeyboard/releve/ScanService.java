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
        ensureLoaded(this);
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
        autoRunning = false;
        aWaitSubmit = false;
        closureFlow = false;
        handler.removeCallbacks(autoStep);
        handler.removeCallbacks(submitStep);
        handler.removeCallbacks(searchStep);
        handler.removeCallbacks(photoStep);
        photoPhase = 0;
        busy = false;
        log("STOP demande");
    }

    public boolean submit(String value) {
        if (busy) {
            log("envoi deja en cours");
            return false;
        }
        if (value == null || value.length() == 0) {
            log("envoi: rien a envoyer");
            return false;
        }
        if (!afterPhoto) photoCount = 0;
        boolean wasAfterPhoto = afterPhoto;
        afterPhoto = false;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) {
            log("envoi: pas dans l'application");
            return false;
        }
        AccessibilityNodeInfo btn = findId(root, "btn-cloture-success", 0);
        AccessibilityNodeInfo field = findId(root, "modelInputIdx", 0);
        if (btn == null || field == null) {
            log("envoi: ce n'est pas l'ecran Releve");
            return false;
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
            return true;
        }
        if (!value.equals(cur)) {
            log("envoi refuse: champ=" + cur + " mais clavier=" + value);
            return false;
        }
        subValue = value;
        subMeter = meterLabel(root);
        t0 = SystemClock.uptimeMillis();
        if (!live) {
            log("SIMULATION ok: valeur " + value + ", " + subMeter);
            return false;
        }
        stopFlag = false;
        boolean t = tapNode(btn);
        if (!t) {
            log("envoi: position du bouton invalide");
            return false;
        }
        busy = true;
        subPhase = 1;
        subPhaseT = SystemClock.uptimeMillis();
        log("ENVOI REEL " + value + ", " + subMeter + ", J'ai fini 1 touche");
        handler.postDelayed(submitStep, 150);
        return true;
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
            if (photoMsg != null && autoRunning && photoMode != 0) {
                AccessibilityNodeInfo okq = findExact(root, "Ok", 0);
                if (okq != null) tapNode(okq);
                if (photoMode == 1) finish("PHOTO_SKIP");
                else finish("STOP: photo demandee (mode arret)");
                return;
            }
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
        closureFlow = false;
        log(msg);
        if (aWaitSubmit) autoSubmitDone(msg);
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
                if (inApp && (closureFlow || findText(root, "Photo obligatoire", 0) == null)) {
                    if (closureFlow) tapCameraClosure();
                    else tapCamera();
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
                if (closureFlow) {
                    if (inApp && findText(root, "Cl\u00f4turer l", 0) != null) {
                        photoPhase = 0;
                        closureFlow = false;
                        log("PHOTO: retour a la cloture");
                        autoClosureBack();
                        return;
                    }
                } else if (inApp && findId(root, "modelInputIdx", 0) != null) {
                    photoPhase = 0;
                    busy = false;
                    afterPhoto = true;
                    log("PHOTO: retour au releve, nouvelle validation");
                    if (!submit(subValue)) finish("STOP: nouvel envoi impossible apres la photo");
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

    // ---------- Automatisation (tableau Excel) ----------

    public static boolean autoRunning = false;
    public static int photoMode = 0;       // 0 = photo auto, 1 = sauter, 2 = arreter
    public static long maxJump = 1000;     // saut maximal accepte entre ancien et nouvel index
    private static boolean cfgLoaded = false;

    private static final int A_READ = 1, A_SW_WAIT = 2, A_CLEAR = 3,
            A_AN_OPEN = 10, A_AN_PICK = 11, A_AN_CHECK = 12, A_AN_BACK = 13,
            A_EN_TAP = 20, A_EN_TYPE = 21, A_EN_GO = 22, A_EN_WAIT = 23,
            A_ZL_ARROW = 30, A_ZL_BTN = 31, A_ZL_CLOSURE = 32, A_ZL_OPEN = 33,
            A_ZL_PICK = 34, A_ZL_CHECK = 35, A_ZL_PHOTO = 36, A_ZL_CLOSE = 37, A_ZL_NEXT = 38;

    private int aPhase = 0;
    private long aT = 0;
    private int aTries = 0;
    private int aScrolls = 0;
    private int aArrow = 0;
    private int aSwipeTries = 0;
    private int aSent = 0;
    private int aSkipped = 0;
    private Meters.Row aRow = null;
    private String aId = "";
    private String aValue = "";
    private String aPrevId = "";
    private String aOptKey = "";
    private boolean aWaitSubmit = false;
    private boolean closureFlow = false;
    private final java.util.HashSet<String> aSeen = new java.util.HashSet<>();
    private static final java.util.regex.Pattern OPTION_PAT =
            java.util.regex.Pattern.compile("^\\d{2}-.+");

    public static void ensureLoaded(android.content.Context c) {
        if (!cfgLoaded) {
            cfgLoaded = true;
            android.content.SharedPreferences sp =
                    c.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE);
            photoMode = sp.getInt("photoMode", 0);
            maxJump = sp.getLong("maxJump", 1000);
        }
        if (Meters.ROWS.isEmpty()) {
            Meters.load(new java.io.File(c.getFilesDir(), "table.tsv"));
        }
    }

    public static void saveCfg(android.content.Context c) {
        c.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).edit()
                .putInt("photoMode", photoMode).putLong("maxJump", maxJump).apply();
    }

    public static void saveTable(android.content.Context c) {
        Meters.save(new java.io.File(c.getFilesDir(), "table.tsv"));
    }

    private String modeName() {
        return photoMode == 0 ? "photo auto" : (photoMode == 1 ? "photo: sauter" : "photo: arreter");
    }

    public void startAuto() {
        if (autoRunning) {
            log("auto deja en cours");
            return;
        }
        if (busy) {
            log("auto: un envoi est en cours");
            return;
        }
        if (Meters.ROWS.isEmpty()) {
            log("auto: tableau vide, importez un fichier");
            return;
        }
        stopFlag = false;
        aSeen.clear();
        aSent = 0;
        aSkipped = 0;
        aWaitSubmit = false;
        closureFlow = false;
        t0 = SystemClock.uptimeMillis();
        autoRunning = true;
        log("AUTO demarre: " + (live ? "REEL" : "SIMULATION") + ", " + modeName());
        aGo(A_READ, 100);
    }

    public void autoStop(String msg) {
        boolean was = autoRunning;
        autoRunning = false;
        aWaitSubmit = false;
        handler.removeCallbacks(autoStep);
        saveTable(this);
        log(msg);
        if (was) log("AUTO termine: envoyes=" + aSent + ", sautes=" + aSkipped);
    }

    private void aGo(int phase, long ms) {
        aPhase = phase;
        aT = SystemClock.uptimeMillis();
        aTries = 0;
        aPost(ms);
    }

    private void aPost(long ms) {
        handler.removeCallbacks(autoStep);
        handler.postDelayed(autoStep, ms);
    }

    private void aMark(int status, String note) {
        if (aRow != null) {
            aRow.status = status;
            aRow.note = note;
        }
        saveTable(this);
        log("[" + aId + "] " + note);
    }

    private void aFail(int status, String msg) {
        aMark(status, msg);
        autoStop(msg);
    }

    private void aSwipe() {
        aSkipped++;
        aPrevId = aId;
        aSwipeTries = 0;
        boolean ok = swipe(true);
        log("[" + aId + "] swipe vers le compteur suivant (" + ok + ")");
        aGo(A_SW_WAIT, 700);
    }

    private final Runnable autoStep = new Runnable() {
        @Override
        public void run() {
            if (!autoRunning) return;
            if (stopFlag) {
                autoStop("AUTO arrete");
                return;
            }
            long now = SystemClock.uptimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inApp = root != null && APP.equals(String.valueOf(root.getPackageName()));
            switch (aPhase) {
                case A_READ: aRead(root, inApp, now); break;
                case A_SW_WAIT: aSwWait(root, inApp, now); break;
                case A_CLEAR: aClear(root, inApp); break;
                case A_AN_OPEN: aAnOpen(root, inApp, now); break;
                case A_AN_PICK: aPick(root, inApp, now, true); break;
                case A_AN_CHECK: aCheck(root, inApp, now, true); break;
                case A_AN_BACK: aSwipeAfterBack(); break;
                case A_EN_TAP: aEnTap(root, inApp, now); break;
                case A_EN_TYPE: aEnType(root, inApp, now); break;
                case A_EN_GO: aEnGo(root, inApp, now); break;
                case A_ZL_ARROW: aZlArrow(root, inApp, now); break;
                case A_ZL_BTN: aZlBtn(root, inApp, now); break;
                case A_ZL_CLOSURE: aZlClosure(root, inApp, now); break;
                case A_ZL_OPEN: aZlOpen(root, inApp, now); break;
                case A_ZL_PICK: aPick(root, inApp, now, false); break;
                case A_ZL_CHECK: aCheck(root, inApp, now, false); break;
                case A_ZL_CLOSE: aZlClose(root, inApp, now); break;
                case A_ZL_NEXT: aZlNext(root, inApp, now); break;
                default: break; // A_EN_WAIT / A_ZL_PHOTO: on attend la fin de l'envoi
            }
        }
    };

    // --- Lecture du compteur affiche et decision ---

    private void aRead(AccessibilityNodeInfo root, boolean inApp, long now) {
        String id = "";
        if (inApp && findId(root, "modelInputIdx", 0) != null) id = readMeterId(root);
        if (id.isEmpty()) {
            if (now - aT > 8000) {
                autoStop("STOP: ecran Releve ou numero de compteur illisible");
            } else {
                aPost(POLL);
            }
            return;
        }
        if (aSeen.contains(id)) {
            autoStop("STOP: compteur deja traite dans cette passe (" + id + ")");
            return;
        }
        aSeen.add(id);
        aId = id;
        Meters.Row r = Meters.find(id);
        if (r == null) {
            String label = meterLabel(root);
            int i = label.indexOf(':');
            String shown = i >= 0 ? label.substring(i + 1).trim() : label;
            r = Meters.addExtra(shown.isEmpty() ? id : shown, id);
            aRow = r;
            aMark(Meters.EXTRA, "absent du tableau, saute");
            aSwipe();
            return;
        }
        aRow = r;
        if (r.status == Meters.SENT) {
            log("[" + id + "] deja envoye, saute");
            aSwipe();
            return;
        }
        boolean hasAnom = !Meters.noAnomaly(r.anomaly);
        boolean hasAnnot = !Meters.noAnnotation(r.annotation);

        if (hasAnom) {
            if (photoMode == 1) {
                aMark(Meters.PHOTO, "photo requise (anomalie " + r.anomaly + "), saute");
                aSwipe();
                return;
            }
            if (photoMode == 2) {
                autoStop("STOP: photo requise pour " + id + " (mode arret)");
                return;
            }
            if (!live) {
                aMark(Meters.SIM, "SIM: anomalie " + r.anomaly + " + photo + cloture");
                aSwipe();
                return;
            }
            aArrow = 0;
            log("[" + id + "] anomalie: " + r.anomaly);
            aGo(A_ZL_ARROW, 100);
            return;
        }

        String val = r.newIdx;
        if (val.isEmpty() && hasAnnot && Meters.isLocalInhabite(r.annotation)
                && !r.oldIdx.isEmpty()) {
            val = r.oldIdx;
        }
        if (val.isEmpty()) {
            aMark(Meters.NODATA, "pas de nouvel index, saute");
            aSwipe();
            return;
        }
        if (!val.matches("\\d{1,9}")) {
            aMark(Meters.SUSPECT, "index invalide: " + val + ", non envoye");
            aSwipe();
            return;
        }
        if (r.oldIdx.matches("\\d{1,9}")) {
            long o = Long.parseLong(r.oldIdx);
            long n = Long.parseLong(val);
            if (n < o) {
                aMark(Meters.SUSPECT, "nouvel index " + n + " < ancien " + o + ", non envoye");
                aSwipe();
                return;
            }
            if (n - o > maxJump) {
                aMark(Meters.SUSPECT, "saut " + (n - o) + " > " + maxJump + ", non envoye");
                aSwipe();
                return;
            }
        }
        aValue = val;
        if (!live) {
            aMark(Meters.SIM, "SIM: enverrait " + val + (hasAnnot ? " + " + r.annotation : ""));
            aSwipe();
            return;
        }
        if (hasAnnot) aGo(A_AN_OPEN, 100);
        else aGo(A_EN_TAP, 100);
    }

    private void aSwWait(AccessibilityNodeInfo root, boolean inApp, long now) {
        if (inApp) {
            String id = readMeterId(root);
            if (!id.isEmpty() && !id.equals(aPrevId)) {
                aGo(A_READ, 450);
                return;
            }
        }
        if (now - aT > 2500) {
            if (aSwipeTries < 1) {
                aSwipeTries++;
                swipe(true);
                aT = now;
            } else {
                autoStop("FIN: le compteur ne change plus apres le swipe (" + aPrevId + ")");
                return;
            }
        }
        aPost(POLL);
    }

    private void aClear(AccessibilityNodeInfo root, boolean inApp) {
        if (inApp) {
            AccessibilityNodeInfo f = findId(root, "modelInputIdx", 0);
            if (f != null && !isEmptyField(f)) clearField(f);
        }
        aSwipe();
    }

    private void aSwipeAfterBack() {
        aSwipe();
    }

    private void clearField(AccessibilityNodeInfo f) {
        Bundle args = new Bundle();
        args.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
        f.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
    }

    // --- Annotation (ecran Releve) ---

    private void aAnOpen(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo lab = inApp ? findExact(root, "Annotation", 0) : null;
        if (lab == null) {
            if (now - aT > 3000) aFail(Meters.ERROR, "STOP: ligne Annotation introuvable");
            else aPost(POLL);
            return;
        }
        Rect r = new Rect();
        lab.getBoundsInScreen(r);
        boolean ok = tapAbs(dm().widthPixels * 0.65f, r.centerY());
        log("annotation: ouverture de la liste (" + ok + ")");
        aScrolls = 0;
        aGo(A_AN_PICK, 600);
    }

    /** Choix dans une liste ouverte (annotation si ann=true, sinon code releve). */
    private void aPick(AccessibilityNodeInfo root, boolean inApp, long now, boolean ann) {
        String wanted = ann ? aRow.annotation : aRow.anomaly;
        java.util.ArrayList<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        if (inApp) collectOptions(root, nodes, texts);
        if (texts.size() >= 3) {
            int idx = Meters.bestMatch(texts, wanted);
            if (idx >= 0) {
                aOptKey = Meters.stripCode(Meters.norm(texts.get(idx)));
                boolean ok = tapNode(nodes.get(idx));
                if (!ok) ok = clickUp(nodes.get(idx));
                log((ann ? "annotation" : "anomalie") + ": choix " + texts.get(idx) + " (" + ok + ")");
                aGo(ann ? A_AN_CHECK : A_ZL_CHECK, 700);
                return;
            }
            if (aScrolls < 10) {
                scrollForward(root);
                aScrolls++;
                aPost(500);
                return;
            }
            performGlobalAction(GLOBAL_ACTION_BACK);
            if (ann) {
                aMark(Meters.UNKNOWN, "annotation inconnue: " + wanted + ", saute");
                aGo(A_AN_BACK, 700);
            } else {
                aFail(Meters.UNKNOWN, "STOP: anomalie inconnue: " + wanted
                        + " (ecran de cloture ouvert, a terminer a la main)");
            }
            return;
        }
        if (now - aT > 4000) {
            aFail(Meters.ERROR, "STOP: la liste " + (ann ? "Annotation" : "Code releve")
                    + " ne s'ouvre pas");
            return;
        }
        aPost(POLL);
    }

    private void aCheck(AccessibilityNodeInfo root, boolean inApp, long now, boolean ann) {
        boolean closed = inApp && (ann ? findId(root, "modelInputIdx", 0) != null
                : findText(root, "Clôturer l", 0) != null);
        if (closed && screenHasKey(root, aOptKey)) {
            if (ann) {
                log("annotation confirmee: " + aOptKey);
                aGo(A_EN_TAP, 200);
            } else {
                log("anomalie confirmee: " + aOptKey);
                startPhotoClosure();
                aPhase = A_ZL_PHOTO;
            }
            return;
        }
        if (now - aT > 3000) {
            aFail(Meters.ERROR, "STOP: choix non confirme (" + aOptKey + ")");
            return;
        }
        aPost(POLL);
    }

    // --- Saisie de l'index (ecran Releve) ---

    private void aEnTap(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo f = inApp ? findId(root, "modelInputIdx", 0) : null;
        if (f == null) {
            if (now - aT > 4000) aFail(Meters.ERROR, "STOP: champ index introuvable");
            else aPost(POLL);
            return;
        }
        if (!isEmptyField(f)) {
            if (aTries < 3) {
                aTries++;
                clearField(f);
                aPost(300);
            } else {
                aFail(Meters.ERROR, "STOP: le champ index n'est pas vide");
            }
            return;
        }
        boolean t = tapNode(f);
        log("index: champ touche (" + t + ")");
        aGo(A_EN_TYPE, 300);
    }

    private void aEnType(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo f = inApp ? findId(root, "modelInputIdx", 0) : null;
        KeyboardService k = KeyboardService.instance;
        if (f != null && isEmptyField(f) && k != null && k.typeText(aValue)) {
            log("index: saisie de " + aValue);
            aGo(A_EN_GO, 300);
            return;
        }
        if (aTries == 0 && now - aT > 1200 && f != null) {
            aTries = 1;
            tapNode(f);
        }
        if (now - aT > 3500) {
            aFail(Meters.ERROR, "STOP: clavier pas pret pour la saisie");
            return;
        }
        aPost(POLL);
    }

    private void aEnGo(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo f = inApp ? findId(root, "modelInputIdx", 0) : null;
        if (f != null && aValue.equals(textOf(f))) {
            aPhase = A_EN_WAIT;
            aWaitSubmit = true;
            if (!submit(aValue)) {
                aWaitSubmit = false;
                aFail(Meters.ERROR, "STOP: envoi refuse");
            }
            return;
        }
        if (now - aT > 1500) {
            String c = f == null ? "?" : textOf(f);
            aFail(Meters.ERROR, "STOP: champ=" + c + " au lieu de " + aValue);
            return;
        }
        aPost(POLL);
    }

    /** Appele par finish() quand un envoi (ou la photo d'une cloture) se termine. */
    private void autoSubmitDone(String msg) {
        aWaitSubmit = false;
        if (!autoRunning) return;
        if (aPhase == A_EN_WAIT && msg.startsWith("FIN OK")) {
            aSent++;
            aMark(Meters.SENT, msg);
            aGo(A_READ, 500);
            return;
        }
        if (aPhase == A_EN_WAIT && msg.equals("PHOTO_SKIP")) {
            aMark(Meters.PHOTO, "photo requise, saute");
            aGo(A_CLEAR, 700);
            return;
        }
        if (msg.contains("photo demandee")) {
            aFail(Meters.PHOTO, msg);
            return;
        }
        aFail(Meters.ERROR, msg);
    }

    // --- Anomalie: Localisation, Je ne peux pas localiser, Code releve, photo, cloture ---

    private void aZlArrow(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo loc = inApp ? findText(root, "Localisation", 0) : null;
        if (loc == null) {
            if (now - aT > 4000) aFail(Meters.ERROR, "STOP: barre Localisation introuvable");
            else aPost(POLL);
            return;
        }
        Rect r = new Rect();
        loc.getBoundsInScreen(r);
        float fx = aArrow == 0 ? 0.915f : (aArrow == 1 ? 0.88f : 0.95f);
        aArrow++;
        boolean ok = tapAbs(dm().widthPixels * fx, r.centerY());
        log("anomalie: fleche Localisation (" + ok + ")");
        aPhase = A_ZL_BTN;
        aT = now;
        aPost(800);
    }

    private void aZlBtn(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo b = inApp ? findText(root, "ne peux pas localiser", 0) : null;
        if (b != null) {
            boolean ok = tapNode(b);
            if (!ok) ok = clickUp(b);
            log("anomalie: 'Je ne peux pas localiser' (" + ok + ")");
            aGo(A_ZL_CLOSURE, 800);
            return;
        }
        if (now - aT > 3500) {
            if (aArrow < 3) aGo(A_ZL_ARROW, 100);
            else aFail(Meters.ERROR, "STOP: ecran Localisation non ouvert");
            return;
        }
        aPost(POLL);
    }

    private void aZlClosure(AccessibilityNodeInfo root, boolean inApp, long now) {
        if (inApp && findText(root, "Clôture de l", 0) != null) {
            aGo(A_ZL_OPEN, 300);
            return;
        }
        if (now - aT > 5000) {
            aFail(Meters.ERROR, "STOP: ecran Cloture de l'intervention non ouvert");
            return;
        }
        aPost(POLL);
    }

    private void aZlOpen(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo lab = inApp ? findText(root, "Code rel", 0) : null;
        if (lab == null) {
            if (now - aT > 3000) aFail(Meters.ERROR, "STOP: ligne Code releve introuvable");
            else aPost(POLL);
            return;
        }
        Rect r = new Rect();
        lab.getBoundsInScreen(r);
        boolean ok = tapAbs(dm().widthPixels * 0.88f, r.centerY());
        log("anomalie: ouverture de la liste Code releve (" + ok + ")");
        aScrolls = 0;
        aGo(A_ZL_PICK, 600);
    }

    private void startPhotoClosure() {
        busy = true;
        closureFlow = true;
        aWaitSubmit = true;
        photoPhase = 1;
        photoTaps = 0;
        photoT = SystemClock.uptimeMillis();
        log("PHOTO (cloture): ouverture de l'appareil photo");
        handler.postDelayed(photoStep, 400);
    }

    private void autoClosureBack() {
        aWaitSubmit = false;
        busy = false;
        if (autoRunning) aGo(A_ZL_CLOSE, 500);
    }

    private void aZlClose(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo b = inApp ? findText(root, "Clôturer l", 0) : null;
        if (b != null) {
            boolean ok = tapNode(b);
            if (!ok) ok = clickUp(b);
            log("anomalie: Cloturer l'intervention (" + ok + ")");
            aGo(A_ZL_NEXT, 900);
            return;
        }
        if (now - aT > 4000) {
            aFail(Meters.ERROR, "STOP: bouton Cloturer l'intervention introuvable");
            return;
        }
        aPost(POLL);
    }

    private void aZlNext(AccessibilityNodeInfo root, boolean inApp, long now) {
        if (inApp && findId(root, "modelInputIdx", 0) != null) {
            String id = readMeterId(root);
            if (!id.isEmpty() && !id.equals(aId)) {
                aSent++;
                aMark(Meters.SENT, "FIN OK: anomalie " + aRow.anomaly);
                aGo(A_READ, 500);
                return;
            }
        }
        if (now - aT > 6000) {
            aFail(Meters.ERROR, "STOP: pas de compteur suivant apres la cloture");
            return;
        }
        aPost(POLL);
    }

    // --- Outils de lecture et de geste ---

    private DisplayMetrics dm() {
        DisplayMetrics d = new DisplayMetrics();
        WindowManager wm = (WindowManager) getSystemService(WINDOW_SERVICE);
        wm.getDefaultDisplay().getRealMetrics(d);
        return d;
    }

    private String readMeterId(AccessibilityNodeInfo root) {
        if (root == null) return "";
        String label = meterLabel(root);
        if (label.length() > 0) {
            int i = label.indexOf(':');
            String k = Meters.key(i >= 0 ? label.substring(i + 1) : label);
            if (!k.isEmpty()) return k;
        }
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        collectTexts(root, texts, 0);
        for (String t : texts) {
            String k = Meters.key(t);
            if (k.length() >= 6 && Meters.find(k) != null) return k;
        }
        return "";
    }

    private void collectTexts(AccessibilityNodeInfo n, java.util.ArrayList<String> out, int depth) {
        if (n == null || depth > 30) return;
        CharSequence t = n.getText();
        if (t != null && t.length() > 0 && n.isVisibleToUser()) out.add(t.toString());
        for (int i = 0; i < n.getChildCount(); i++) collectTexts(n.getChild(i), out, depth + 1);
    }

    private void collectOptions(AccessibilityNodeInfo n,
                                java.util.ArrayList<AccessibilityNodeInfo> nodes,
                                java.util.ArrayList<String> texts) {
        collectOptions(n, nodes, texts, 0);
    }

    private void collectOptions(AccessibilityNodeInfo n,
                                java.util.ArrayList<AccessibilityNodeInfo> nodes,
                                java.util.ArrayList<String> texts, int depth) {
        if (n == null || depth > 30) return;
        CharSequence t = n.getText();
        if (t != null && n.isVisibleToUser()) {
            String s = t.toString().trim();
            if (OPTION_PAT.matcher(s).matches()) {
                nodes.add(n);
                texts.add(s);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            collectOptions(n.getChild(i), nodes, texts, depth + 1);
        }
    }

    private boolean screenHasKey(AccessibilityNodeInfo root, String key) {
        if (key == null || key.isEmpty()) return false;
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        collectTexts(root, texts, 0);
        for (String t : texts) if (Meters.norm(t).contains(key)) return true;
        return false;
    }

    private boolean scrollForward(AccessibilityNodeInfo n) {
        return scrollForward(n, 0);
    }

    private boolean scrollForward(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return false;
        if (n.isScrollable()) {
            return n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (scrollForward(n.getChild(i), depth + 1)) return true;
        }
        return false;
    }

    /** Glisse horizontalement sur la banniere du compteur (suivant = de gauche a droite). */
    private boolean swipe(boolean next) {
        DisplayMetrics d = dm();
        float y = d.heightPixels * 0.22f;
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root != null) {
            AccessibilityNodeInfo m = findText(root, "compteur :", 0);
            if (m != null) {
                Rect r = new Rect();
                m.getBoundsInScreen(r);
                if (r.height() > 0) y = r.centerY();
            }
        }
        float x1 = d.widthPixels * (next ? 0.2f : 0.8f);
        float x2 = d.widthPixels * (next ? 0.8f : 0.2f);
        Path p = new Path();
        p.moveTo(x1, y);
        p.lineTo(x2, y);
        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 300);
        return dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
    }

    /** Test: une seule glissade, avec le compteur affiche avant et apres. */
    public void testSwipe(final boolean next) {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        String before = root == null ? "?" : meterLabel(root);
        t0 = SystemClock.uptimeMillis();
        boolean ok = swipe(next);
        log("TEST swipe " + (next ? "suivant" : "precedent") + " (" + ok + "), avant: " + before);
        handler.postDelayed(new Runnable() {
            @Override
            public void run() {
                AccessibilityNodeInfo r2 = getRootInActiveWindow();
                log("TEST swipe, apres: " + (r2 == null ? "?" : meterLabel(r2)));
            }
        }, 1500);
    }

    private boolean tapCameraClosure() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        AccessibilityNodeInfo lab = findText(root, "Prise de photo", 0);
        AccessibilityNodeInfo cam = null;
        if (lab != null) {
            Rect f = new Rect();
            lab.getBoundsInScreen(f);
            cam = findRightOf(root, f, 0);
        }
        if (cam != null) {
            boolean t = tapNode(cam);
            log("cloture: bouton appareil photo touche (" + t + ")");
            return t;
        }
        boolean t = tap(0.9f, 0.135f);
        log("cloture: appareil photo, position fixe (" + t + ")");
        return t;
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

    // ---------- Bandeau au-dessus du clavier (compteur + ancien index) ----------

    private boolean stripPending = false;
    private final Runnable stripUpdate = new Runnable() {
        @Override
        public void run() {
            stripPending = false;
            KeyboardService k = KeyboardService.instance;
            if (k == null || !KeyboardService.stripOn) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return;
            if (findId(root, "modelInputIdx", 0) == null) return;
            ensureLoaded(ScanService.this);
            String label = meterLabel(root);
            int i = label.indexOf(':');
            String shown = i >= 0 ? label.substring(i + 1).trim() : label;
            String id = readMeterId(root);
            Meters.Row r = id.isEmpty() ? null : Meters.find(id);
            String txt;
            if (r != null) {
                String name = shown.isEmpty() ? r.compteur : shown;
                txt = name + "   |   Ancien : " + (r.oldIdx.isEmpty() ? "-" : r.oldIdx);
            } else if (!shown.isEmpty()) {
                txt = shown + "   |   (absent du tableau)";
            } else {
                txt = "";
            }
            k.setStrip(txt);
        }
    };

    public void refreshStrip() {
        if (stripPending) return;
        stripPending = true;
        handler.postDelayed(stripUpdate, 120);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        if (p == null || !APP.equals(p.toString())) return;
        if (KeyboardService.stripOn && !stripPending) {
            stripPending = true;
            handler.postDelayed(stripUpdate, 300);
        }
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
