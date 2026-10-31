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

    /** Vitesse: true = attentes reduites. Retombe sur la vitesse normale apres tout incident. */
    public static boolean fast = false;
    private int slowLeft = 0;

    private long d(long ms) {
        return (fast && slowLeft == 0) ? Math.max(80, ms * 45 / 100) : ms;
    }
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
    private int subKbTries = 0;
    private boolean subNudged = false;
    // --- ajouts: egalite avec l'ancien index, enregistrement, anomalie manuelle ---
    private Meters.Row subRowRef = null;
    private String subKey = "";
    private boolean subEqual = false;
    private boolean aSingle = false;
    private String aOvAnn = null;
    private String aOvAnom = null;
    private long holdT = 0;
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

    private boolean userSearch = false;
    private String camKey = "";
    private long camOpenT = 0;

    /** Note dans le tableau que ce compteur a une photo (pour s'en souvenir plus tard). */
    private void markPhoto(String key) {
        try {
            Meters.Row r = Meters.find(key);
            if (r == null) return;
            if (r.photos.isEmpty()) {
                r.photos = "PHOTO PRISE";
                saveTable(this);
                log("PHOTO notee pour " + key);
            }
        } catch (Exception ignored) {
        }
    }

    public void goSearch() {
        skipTrans = true;
        if (gotoKey == null) userSearch = true;   // recherche demandee par l'utilisateur
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
            if (box != null && !gotoTabText.isEmpty() && !closedTabDone) {
                AccessibilityNodeInfo tab = findText(root, gotoTabText, 0);
                if (tab == null && gotoTabText.startsWith("Cl")) tab = findText(root, "Cloturee", 0);
                if (tab != null) {
                    boolean sel = false;
                    AccessibilityNodeInfo q = tab;
                    for (int up = 0; up < 4 && q != null && !sel; up++) {
                        sel = q.isSelected();
                        q = q.getParent();
                    }
                    if (sel || closedTabTaps >= 3) {
                        closedTabDone = true;
                        log("DERNIER: onglet Cloturee(s) " + (sel ? "actif" : "suppose actif apres 3 essais"));
                    } else if (now - closedTabT > 900) {
                        boolean tt = tapNode(tab);
                        boolean tc = clickUp(tab);
                        closedTabTaps++;
                        closedTabT = now;
                        log("DERNIER: onglet Cloturee(s) touche " + closedTabTaps + " (" + tt + "/" + tc + ")");
                        handler.postDelayed(searchStep, d(900));
                        return;
                    } else {
                        handler.postDelayed(searchStep, POLL);
                        return;
                    }
                } else if (closedTabTaps >= 3 || now - t0 > 6000) {
                    log("DERNIER: onglet introuvable (" + gotoTabText + ")");
                    gotoTabText = "";
                }
            }
            if (box != null) {
                box.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                boolean ok = box.performAction(AccessibilityNodeInfo.ACTION_CLICK);
                Bundle args = new Bundle();
                args.putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                boolean cleared = box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args);
                log("champ recherche: clic=" + ok + ", vide=" + cleared);
                if (gotoKey != null) {
                    gotoPhase = 1;
                    gotoTries = 0;
                    gotoT = now;
                    handler.postDelayed(gotoStep, d(130));
                }
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
                if (backsDone < 2 && now - backTime > 800) {
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

    // ---------- Aller a un compteur du tableau (alerte du bandeau) ----------

    private String gotoKey = null;

    // ---------- Suivre l'ordre de la base / deplacer un compteur ----------

    public static boolean followDb = true;
    private boolean following = false;
    private int followTries = 0;
    private Meters.Row lastEntered = null;
    private Meters.Row undoRow = null;
    private int undoIndex = -1;

    /** Prochain compteur de la base apres `cur` (non traite, ni ajoute a la main). */
    private Meters.Row nextAfter(Meters.Row cur) {
        if (cur == null) return null;
        int i = Meters.ROWS.indexOf(cur);
        if (i < 0) return null;
        for (int j = i + 1; j < Meters.ROWS.size(); j++) {
            Meters.Row x = Meters.ROWS.get(j);
            if (x.extra || x.status == Meters.UNLISTED || x.status != Meters.NEW || !Meters.isActive(x)) continue;
            return x;
        }
        return null;
    }

    private final Runnable followCheck = new Runnable() {
        @Override
        public void run() {
            if (!following || !followDb || autoRunning || busy || gotoKey != null) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return;
            Meters.Row nxt = nextAfter(lastEntered);
            if (nxt == null) {
                following = false;
                log("SUIVI: fin de la liste");
                return;
            }
            String id = readMeterId(root);
            if (id.isEmpty() && followTries < 4) {
                followTries++;
                handler.postDelayed(this, 900);
                return;
            }
            if (id.equals(nxt.key)) {
                following = false;
                log("SUIVI: ordre realigne sur " + id);
                return;
            }
            log("SUIVI: attendu " + nxt.key + " / ecran " + id);
            gotoMeter(nxt.key);
        }
    };

    /** Place le compteur affiche juste apres le dernier compteur saisi. Retourne un message. */
    public String moveCurrentAfterLast() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) {
            return "Ouvrez d'abord le compteur dans l'application";
        }
        String id = readMeterId(root);
        Meters.Row r = id.isEmpty() ? null : Meters.find(id);
        if (r == null) return "Ce compteur n'est pas dans le tableau";
        if (lastEntered == null) return "Aucun compteur saisi depuis le demarrage";
        if (r == lastEntered) return "C'est deja le dernier compteur saisi";
        int from = Meters.ROWS.indexOf(r);
        Meters.ROWS.remove(r);
        int to = Meters.ROWS.indexOf(lastEntered);
        if (to < 0) {
            Meters.ROWS.add(Math.min(from, Meters.ROWS.size()), r);
            return "Dernier compteur introuvable dans le tableau";
        }
        Meters.ROWS.add(to + 1, r);
        undoRow = r;
        undoIndex = from;
        saveTable(this);
        log("DEPLACE: " + r.key + " place apres " + lastEntered.key);
        return "\u2713 Deplace: " + r.key + " place apres " + lastEntered.key;
    }

    public String undoMove() {
        if (undoRow == null || undoIndex < 0) return "Rien a annuler";
        Meters.ROWS.remove(undoRow);
        Meters.ROWS.add(Math.min(undoIndex, Meters.ROWS.size()), undoRow);
        String k = undoRow.key;
        undoRow = null;
        undoIndex = -1;
        saveTable(this);
        return "Deplacement annule: " + k;
    }
    private int gotoPhase = 0;
    private int gotoTries = 0;
    private long gotoT = 0;

    /** Recherche et ouvre un compteur dans l'application d'origine (numero sans zeros du debut). */
    public void gotoMeter(String key) {
        if (key == null || key.isEmpty()) return;
        if (autoRunning || busy) {
            toast("Une automatisation est deja en cours");
            return;
        }
        gotoKey = key;
        gotoPhase = 0;
        gotoQuery = suffixFor(key);
        log("ALLER AU COMPTEUR " + key + " (recherche: " + gotoQuery + ")");
        if (!gotoClosed && trySwipe(key)) return;
        goSearch();
    }

    // ---------- Recherche rapide: suffixe unique + ordre appris de l'application ----------

    private String gotoQuery = "";
    public static String lastSentKey = "";
    private boolean gotoClosed = false;
    private String gotoTabText = "";
    private String returnKey = "";
    private String fallbackKey = "";
    private boolean lastView = false;
    private boolean closedTabDone = false;
    private int closedTabTaps = 0;
    private long closedTabT = 0;

    /** Retrouve le dernier compteur envoye dans l'onglet "Cloturee(s)" de l'application d'origine. */
    public void gotoLastSent() {
        if (lastSentKey == null || lastSentKey.isEmpty()) {
            toast("Aucun compteur envoye depuis le demarrage");
            return;
        }
        if (autoRunning || busy) {
            toast("Une automatisation est deja en cours");
            return;
        }
        try {
            AccessibilityNodeInfo r0 = getRootInActiveWindow();
            if (r0 != null && APP.equals(String.valueOf(r0.getPackageName()))
                    && findId(r0, "modelInputIdx", 0) != null) {
                String cur = readMeterId(r0);
                if (!cur.isEmpty()) returnKey = cur;
            }
        } catch (Throwable ignored) {
        }
        gotoClosed = true;
        gotoTabText = "Cl\u00F4tur\u00E9e";
        closedTabDone = false;
        closedTabTaps = 0;
        closedTabT = 0;
        gotoMeter(lastSentKey);
    }

    /** Bouton DERNIER: 1re touche = va voir le dernier compteur envoye; 2e touche = revient a la saisie. */
    public void toggleLast() {
        if (lastView && !returnKey.isEmpty()) {
            lastView = false;
            gotoClosed = false;
            gotoTabText = "R\u00E9aliser";
            closedTabDone = false;
            closedTabTaps = 0;
            closedTabT = 0;
            fallbackKey = returnKey;
            toast("Ouverture du compteur " + lastSentKey + " pour correction");
            gotoMeter(lastSentKey);
        } else {
            gotoLastSent();
        }
    }
    private boolean skipTrans = false;
    private String swipeFrom = "";
    /** Ordre reel de l'application d'origine, appris pendant le travail: compteur -> suivant. */
    public static final java.util.HashMap<String, String> APP_NEXT = new java.util.HashMap<>();
    private int orderDirty = 0;

    /** Plus court suffixe (5 chiffres minimum) qui distingue ce compteur parmi ceux des tournees actives. */
    private String suffixFor(String key) {
        try {
            int n = key.length();
            for (int len = Math.min(5, n); len < n; len++) {
                String suf = key.substring(n - len);
                boolean unique = true;
                for (Meters.Row r : Meters.ROWS) {
                    if (r.extra || r.key == null || r.key.equals(key) || !Meters.isActive(r)) continue;
                    if (r.key.endsWith(suf)) {
                        unique = false;
                        break;
                    }
                }
                if (unique) return suf;
            }
        } catch (Throwable ignored) {
        }
        return key;
    }

    /** Nombre de glissades (1 a 6) pour aller de `cur` a `key` selon l'ordre appris, 0 si inconnu. */
    private int hopsTo(String cur, String key) {
        String x = cur;
        for (int i = 1; i <= 6; i++) {
            x = APP_NEXT.get(x);
            if (x == null) return 0;
            if (x.equals(key)) return i;
        }
        return 0;
    }

    private int swipeLeft = 0;

    private final Runnable swipeChain = new Runnable() {
        @Override
        public void run() {
            if (gotoKey == null) return;
            if (swipeLeft > 0) {
                swipeLeft--;
                swipe(true);
                handler.postDelayed(this, d(480));
            } else {
                handler.postDelayed(swipeCheck, d(500));
            }
        }
    };

    /** Si l'ordre appris place ce compteur a 1-6 glissades de l'actuel, on glisse au lieu de chercher. */
    private boolean trySwipe(String key) {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return false;
            if (findId(root, "modelInputIdx", 0) == null) return false;
            String cur = readMeterId(root);
            if (cur.isEmpty()) return false;
            int hops = hopsTo(cur, key);
            if (hops == 0) return false;
            swipeFrom = cur;
            boolean ok = swipe(true);
            log("ALLER: " + hops + " glissade(s) " + cur + " > " + key + " (" + ok + ")");
            if (!ok) return false;
            skipTrans = true;
            swipeLeft = hops - 1;
            handler.removeCallbacks(swipeCheck);
            handler.removeCallbacks(swipeChain);
            handler.postDelayed(swipeChain, d(480));
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    /**
     * Apres un index envoye: on decide tout de suite, sans attendre que l'application affiche son
     * prochain compteur. Ordre appris = meme que la base: rien a faire. Ordre connu a quelques
     * glissades: on glisse. Compteur "deplace" (hors ordre): on lance la recherche aussitot.
     */
    private void predictNext(Meters.Row done) {
        try {
            Meters.Row nxt = nextAfter(done);
            if (nxt == null) {
                handler.postDelayed(followCheck, d(850));
                return;
            }
            String appNext = APP_NEXT.get(done.key);
            if (appNext != null && appNext.equals(nxt.key)) {
                handler.postDelayed(followCheck, d(850));   // l'application suit deja la base
                return;
            }
            if (appNext == null) {
                handler.postDelayed(followCheck, d(850));   // ordre inconnu: on regarde ou on arrive
                return;
            }
            // l'application va ailleurs: on part directement vers le compteur de la base
            final String target = nxt.key;
            log("SUIVI: ordre appris " + done.key + " > " + appNext + ", base attend " + target);
            handler.postDelayed(() -> {
                if (!following || !followDb || autoRunning || busy || gotoKey != null) return;
                gotoMeter(target);
            }, d(350));
        } catch (Throwable t) {
            handler.postDelayed(followCheck, d(850));
        }
    }

    private final Runnable swipeCheck = new Runnable() {
        @Override
        public void run() {
            if (gotoKey == null) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String id = (root != null && APP.equals(String.valueOf(root.getPackageName())))
                    ? readMeterId(root) : "";
            if (id.equals(gotoKey)) {
                log("ALLER: arrive par glissade");
                following = followDb;
                followTries = 0;
                KeyboardService k = KeyboardService.instance;
                if (k != null) k.showNow();
                gotoKey = null;
                gotoPhase = 0;
                refreshStrip();
                return;
            }
            log("ALLER: glissade sans effet (ecran " + id + "), recherche");
            APP_NEXT.remove(swipeFrom);
            goSearch();
        }
    };

    private void noteTransition(String from, String to) {
        if (from.isEmpty() || to.isEmpty() || from.equals(to)) return;
        if (APP_NEXT.size() > 20000) return;
        if (to.equals(APP_NEXT.get(from))) return;
        APP_NEXT.put(from, to);
        if (++orderDirty >= 8) {
            orderDirty = 0;
            final java.io.File f = new java.io.File(getFilesDir(), "order.tsv");
            final java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            for (java.util.Map.Entry<String, String> e : APP_NEXT.entrySet()) {
                lines.add(e.getKey() + "\t" + e.getValue());
            }
            SAVER.execute(() -> {
                try (java.io.Writer w = new java.io.BufferedWriter(new java.io.OutputStreamWriter(
                        new java.io.FileOutputStream(f), "UTF-8"))) {
                    for (String l : lines) w.write(l + "\n");
                } catch (Exception ignored) {
                }
            });
        }
    }

    private static void loadOrder(java.io.File f) {
        if (!f.exists()) return;
        try (java.io.BufferedReader br = new java.io.BufferedReader(
                new java.io.InputStreamReader(new java.io.FileInputStream(f), "UTF-8"))) {
            String l;
            while ((l = br.readLine()) != null) {
                int i = l.indexOf('\t');
                if (i > 0) APP_NEXT.put(l.substring(0, i), l.substring(i + 1));
            }
        } catch (Exception ignored) {
        }
    }

    private void gotoFail(String why) {
        log("ALLER: " + why);
        if (!fallbackKey.isEmpty()) {
            String fb = fallbackKey;
            fallbackKey = "";
            gotoKey = null;
            gotoPhase = 0;
            gotoClosed = false;
            gotoTabText = "R\u00E9aliser";
            closedTabDone = false;
            closedTabTaps = 0;
            closedTabT = 0;
            toast("Pas retrouve parmi les non lus: retour au compteur " + fb);
            gotoMeter(fb);
            return;
        }
        toast("Compteur " + gotoKey + ": " + why);
        following = false;
        gotoKey = null;
        gotoPhase = 0;
        gotoClosed = false;
        gotoTabText = "";
    }

    private AccessibilityNodeInfo findMeterCard(AccessibilityNodeInfo n, String key, int depth) {
        if (n == null || depth > 30) return null;
        CharSequence t = n.getText();
        if (t != null && n.isVisibleToUser()) {
            CharSequence cn = n.getClassName();
            boolean edit = cn != null && cn.toString().contains("EditText");
            if (!edit && Meters.key(t.toString()).equals(key)) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo x = findMeterCard(n.getChild(i), key, depth + 1);
            if (x != null) return x;
        }
        return null;
    }

    private final Runnable gotoStep = new Runnable() {
        @Override
        public void run() {
            if (gotoKey == null) return;
            if (stopFlag) {
                gotoKey = null;
                return;
            }
            long now = SystemClock.uptimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inApp = root != null && APP.equals(String.valueOf(root.getPackageName()));
            KeyboardService k = KeyboardService.instance;
            if (gotoPhase == 1) {
                AccessibilityNodeInfo box = inApp ? findId(root, "searchInput", 0) : null;
                if (box != null && k != null && k.typeText(gotoQuery.isEmpty() ? gotoKey : gotoQuery)) {
                    log("ALLER: numero tape " + (gotoQuery.isEmpty() ? gotoKey : gotoQuery));
                    gotoPhase = 2;
                    gotoT = now;
                    handler.postDelayed(gotoStep, d(120));
                    return;
                }
                if (box != null && gotoTries < 10 && now - gotoT > 250L * (gotoTries + 1)) {
                    gotoTries++;
                    if (gotoTries % 2 == 1) tapNode(box);
                    if (k != null) k.showNow();
                }
                if (now - gotoT > 7000) {
                    gotoFail("clavier pas pret pour la recherche");
                    return;
                }
            } else if (gotoPhase == 2) {
                AccessibilityNodeInfo card = inApp ? findMeterCard(root, gotoKey, 0) : null;
                if (card != null && gotoClosed) {
                    log("DERNIER: compteur " + gotoKey + " trouve dans Cloturee(s) (carte non ouverte)");
                    toast("Dernier compteur: " + gotoKey + ". Touchez DERNIER pour revenir a la saisie");
                    gotoClosed = false;
                    gotoTabText = "";
                    gotoKey = null;
                    gotoPhase = 0;
                    lastView = true;
                    return;
                }
                if (card != null) {
                    boolean ok = tapNode(card);
                    log("ALLER: carte touchee (" + ok + ")");
                    gotoPhase = 3;
                    gotoT = now;
                    handler.postDelayed(gotoStep, d(150));
                    return;
                }
                if (!gotoQuery.equals(gotoKey) && now - gotoT > 1500 && inApp) {
                    // le suffixe ne donne rien: on retape le numero complet
                    AccessibilityNodeInfo box2 = findId(root, "searchInput", 0);
                    if (box2 != null && k != null) {
                        Bundle a2 = new Bundle();
                        a2.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, "");
                        box2.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, a2);
                        if (k.typeText(gotoKey)) log("ALLER: suffixe sans resultat, numero complet");
                        gotoQuery = gotoKey;
                        gotoT = now;
                    }
                }
                if (now - gotoT > 5000) {
                    gotoFail("introuvable dans l'application");
                    return;
                }
            } else if (gotoPhase == 3) {
                if (gotoClosed && now - gotoT > 1200) {
                    log("DERNIER: fiche ouverte depuis Cloturee(s)");
                    gotoClosed = false;
                    gotoKey = null;
                    gotoPhase = 0;
                    refreshStrip();
                    return;
                }
                if (inApp && findId(root, "modelInputIdx", 0) != null) {
                    log("ALLER: ecran du releve ouvert");
                    following = followDb;
                    followTries = 0;
                    if (k != null) k.showNow();
                    gotoKey = null;
                    gotoPhase = 0;
                    refreshStrip();
                    return;
                }
                if (now - gotoT > 4000) {
                    gotoFail("ecran du releve non ouvert");
                    return;
                }
            }
            handler.postDelayed(gotoStep, POLL);
        }
    };

    // ---------- Envoi du releve ----------

    public void stopAll() {
        stopFlag = true;
        autoRunning = false;
        aSingle = false;
        aOvAnn = null;
        aOvAnom = null;
        aWaitSubmit = false;
        closureFlow = false;
        handler.removeCallbacks(autoStep);
        handler.removeCallbacks(submitStep);
        handler.removeCallbacks(searchStep);
        handler.removeCallbacks(photoStep);
        handler.removeCallbacks(gotoStep);
        handler.removeCallbacks(followCheck);
        handler.removeCallbacks(syncStep);
        syncPhase = 0;
        following = false;
        gotoKey = null;
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
        subEqual = false;
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
        subKey = readMeterId(root);
        subRowRef = lookupRow(root, subKey);
        subMeter = meterLabel(root);
        preRecord(value);
        if (wasAfterPhoto && live && isEmptyField(field)) {
            subValue = value;
            subMeter = meterLabel(root);
            t0 = SystemClock.uptimeMillis();
            stopFlag = false;
            boolean tf = tapNode(field);
            if (KeyboardService.instance != null) KeyboardService.instance.showNow();
            busy = true;
            subPhase = 3;
            subPhaseT = SystemClock.uptimeMillis();
            subRetried = false;
            subKbTries = 0;
            subNudged = false;
            log("apres photo: champ vide, saisie de " + value + " (" + tf + ")");
            handler.postDelayed(submitStep, 250);
            return true;
        }
        if (!value.equals(cur)) {
            log("envoi refuse: champ=" + cur + " mais clavier=" + value);
            return false;
        }
        // Lecture egale a l'ancien index: anomalie / annotation du tableau, sinon attente 1 s
        if (live && !autoRunning && !wasAfterPhoto && !subKey.isEmpty()) {
            ensureLoaded(this);
            Meters.Row rr = lookupRow(root, subKey);
            subRowRef = rr;
            if (rr != null && sameNumber(rr.oldIdx, value)) {
                if (!Meters.noAnomaly(rr.anomaly)) {
                    clearField(field);
                    return startFromRow(rr, subKey, value, false);
                }
                if (!Meters.noAnnotation(rr.annotation)) {
                    return startFromRow(rr, subKey, value, true);
                }
                subEqual = true;
            }
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
        subTaps = 1;
        subTapT = subPhaseT;
        log("ENVOI REEL " + value + ", " + subMeter + ", J'ai fini 1 touche"
                + (subEqual ? " (egal a l'ancien: attente 1 s)" : ""));
        handler.postDelayed(submitStep, subEqual ? 1000 : 150);
        return true;
    }

    private boolean subDirect = false;
    private int sub2Taps = 0;
    private long sub2TapT = 0;
    private int subTaps = 0;
    private long subTapT = 0;

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
                if (now - subPhaseT > 5000) finish("STOP: ecran illisible");
                else handler.postDelayed(submitStep, POLL);
                return;
            }
            AccessibilityNodeInfo field = findId(root, "modelInputIdx", 0);
            AccessibilityNodeInfo dialog = findAlert(root);
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
                        handler.postDelayed(submitStep, d(250));
                        return;
                    }
                } else if (meter.length() > 0 && !meter.equals(subMeter)) {
                    finish("FIN OK: compteur suivant apres une seule validation");
                    return;
                } else if (meter.equals(subMeter) && field != null && subTaps < 4
                        && now - subTapT > 1800 && textOf(field).replace(" ", "").equals(subValue)) {
                    // le clic sur "J'ai fini" n'a rien declenche: meme ecran, meme valeur dans le champ
                    AccessibilityNodeInfo again = findId(root, "btn-cloture-success", 0);
                    if (again != null) {
                        subTaps++;
                        subTapT = now;
                        boolean t = tapNode(again);
                        if (!t) t = clickUp(again);
                        log("J'ai fini: nouvel essai " + subTaps + " (" + t + ")");
                    }
                }
                if (now - subPhaseT > 7500) {
                    finish("STOP: ni message ni changement apres 6 s");
                    return;
                }
            } else if (subPhase == 2) {
                if (dialog == null && field != null && isEmptyField(field)) {
                    boolean t = tapNode(field);
                    log("champ vide: toucher pour saisir (" + t + ")");
                    subPhase = 3;
                    subPhaseT = now;
                    subRetried = false;
                    subKbTries = 0;
                    subNudged = false;
                    subDirect = false;
                    handler.postDelayed(submitStep, d(250));
                    return;
                }
                if (now - subPhaseT > 5000) {
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
                // dernier recours: ecrire la valeur directement dans le champ
                if (field != null && isEmptyField(field) && subKbTries >= 3 && !subDirect) {
                    subDirect = true;
                    Bundle sa = new Bundle();
                    sa.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, subValue);
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    boolean sok = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, sa);
                    log("saisie directe de " + subValue + " (" + sok + ")");
                    subPhase = 4;
                    subPhaseT = now;
                    handler.postDelayed(submitStep, 200);
                    return;
                }
                // le clavier peut tarder: on le redemande et on retouche le champ plusieurs fois
                if (field != null && subKbTries < 10 && now - subPhaseT > 450L * (subKbTries + 1)) {
                    subKbTries++;
                    subRetried = true;
                    if (subKbTries % 2 == 1) tapNode(field);
                    if (k != null) k.showNow();
                }
                if (now - subPhaseT > 7000) {
                    finish("STOP: clavier pas pret pour la saisie");
                    return;
                }
            } else if (subPhase == 4) {
                // l'application web met parfois du temps a exposer le texte saisi
                if (field != null) {
                    try {
                        field.refresh();
                    } catch (Exception ignored) {
                    }
                }
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
                    sub2Taps = 1;
                    sub2TapT = now;
                    handler.postDelayed(submitStep, d(250));
                    return;
                }
                if (!subDirect && now - subPhaseT > 900 && field != null && !subValue.equals(textOf(field))) {
                    subDirect = true;
                    Bundle sb2 = new Bundle();
                    sb2.putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, subValue);
                    field.performAction(AccessibilityNodeInfo.ACTION_FOCUS);
                    boolean sok2 = field.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, sb2);
                    log("saisie directe (champ non rempli): " + subValue + " (" + sok2 + ")");
                }
                if (!subNudged && now - subPhaseT > 1200 && field != null) {
                    subNudged = true;
                    log("champ encore vide apres la saisie: nouvelle lecture");
                    tapNode(field);
                }
                if (now - subPhaseT > 4500) {
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
                // 2e clic sans effet: meme ecran, meme valeur, pas de message -> on reclique
                if (meter.equals(subMeter) && field != null && sub2Taps < 5 && now - sub2TapT > 1500
                        && textOf(field).replace(" ", "").equals(subValue)) {
                    AccessibilityNodeInfo again2 = findId(root, "btn-cloture-success", 0);
                    if (again2 != null) {
                        sub2Taps++;
                        sub2TapT = now;
                        boolean t2 = tapNode(again2);
                        if (!t2) t2 = clickUp(again2);
                        log("J'ai fini 2: nouvel essai " + sub2Taps + " (" + t2 + ")");
                    }
                }
                if (now - subPhaseT > 9000) {
                    finish("STOP: pas de changement apres la 2e validation");
                    return;
                }
            }
            handler.postDelayed(submitStep, POLL);
        }
    };

    /** Garde tout de suite l'index tape dans le tableau (meme si l'envoi echoue ensuite). */
    private void preRecord(String value) {
        try {
            if (subKey == null || subKey.isEmpty() || value == null || value.isEmpty()) return;
            Meters.Row r = subRowRef != null ? subRowRef : Meters.find(subKey);
            if (r == null) {
                String shown = subMeter == null ? "" : subMeter;
                int i = shown.indexOf(':');
                if (i >= 0) shown = shown.substring(i + 1).trim();
                r = Meters.addExtra(shown.isEmpty() ? subKey : shown, subKey);
            }
            r.newIdx = value;
            if (!r.extra && r.status != Meters.UNLISTED) lastEntered = r;
            if (r.note.isEmpty()) r.note = "saisi: " + value + " (envoi en cours)";
            saveTable(this);
        } catch (Exception e) {
            log("preRecord: " + e.getMessage());
        }
    }

    private void finish(String msg) {
        if (msg.startsWith("STOP") && !autoRunning && subKey != null && !subKey.isEmpty()) {
            try {
                Meters.Row r = Meters.find(subKey);
                if (r != null && !r.extra && r.status != Meters.SENT) {
                    r.status = Meters.ERROR;
                    r.note = "index " + r.newIdx + " non confirme: " + msg;
                    saveTable(this);
                }
            } catch (Exception ignored) {
            }
        }
        busy = false;
        photoMsgSeen = 0;
        closureFlow = false;
        log(msg);
        if (msg.startsWith("STOP")) slowLeft = 8;
        else if (msg.startsWith("FIN OK") && slowLeft > 0) slowLeft--;
        if (msg.startsWith("FIN OK")) recordIndex();
        if (aWaitSubmit) autoSubmitDone(msg);
    }

    /** Memorise dans la base du clavier l'index saisi dans l'application d'origine. */
    private void recordIndex() {
        try {
            if (subKey == null || subKey.isEmpty() || subValue == null || subValue.isEmpty()) return;
            Meters.Row r = subRowRef != null ? subRowRef : Meters.find(subKey);
            if (r == null) {
                String shown = subMeter == null ? "" : subMeter;
                int i = shown.indexOf(':');
                if (i >= 0) shown = shown.substring(i + 1).trim();
                r = Meters.addExtra(shown.isEmpty() ? subKey : shown, subKey);
            }
            final String prevIdx = r.newIdx;
            r.newIdx = subValue;
            lastSentKey = subKey;
            try {
                getSharedPreferences("cfg", MODE_PRIVATE).edit().putString("lastSent", subKey).apply();
            } catch (Exception ignored) {
            }
            if (!r.extra && r.status != Meters.UNLISTED) lastEntered = r;
            if (userSearch) {
                userSearch = false;
                if (!r.extra && !autoRunning) {
                    following = followDb;
                }
            }
            if (following && followDb && !autoRunning) {
                followTries = 0;
                handler.removeCallbacks(followCheck);
                predictNext(r);
            }
            if (!r.extra) {
                r.status = Meters.SENT;
                r.note = (r.warn.isEmpty() ? "" : "\u26A0 " + r.warn + " | ") + "saisi: " + subValue
                        + (prevIdx.isEmpty() || sameNumber(prevIdx, subValue) ? ""
                        : " (corrige, etait " + prevIdx + ")");
                registerReplacement(r, subKey, subMeter, subValue);
            } else {
                r.note = "absent du tableau, saisi: " + subValue;
            }
            saveTable(this);
        } catch (Exception e) {
            log("enregistrement de l'index: " + e.getMessage());
        }
    }

    /**
     * Compteur remplace sur le terrain: le numero affiche n'est pas dans le tableau mais le nom et
     * l'adresse correspondent a une ligne. On garde les deux numeros et on cree une ligne pour le
     * nouveau compteur, avec son index, pour qu'il apparaisse dans le rapport.
     */
    private void registerReplacement(Meters.Row old, String screenKey, String label, String value) {
        try {
            if (old == null || old.warn.isEmpty() || screenKey == null || screenKey.isEmpty()
                    || screenKey.equals(old.key) || old.extra) return;
            String shown = label == null ? "" : label;
            int i = shown.indexOf(':');
            if (i >= 0) shown = shown.substring(i + 1).trim();
            if (shown.isEmpty()) shown = screenKey;
            String oldNum = old.compteur;
            old.warn = "compteur remplace: ancien " + oldNum + " -> nouveau " + shown;
            if (!old.note.contains("REMPLACE par")) {
                old.note = "REMPLACE par " + shown + " | " + old.note;
            }
            Meters.Row nr = Meters.find(screenKey);
            if (nr == null) nr = Meters.addExtra(shown, screenKey);
            nr.newIdx = value;
            nr.note = "nouveau compteur (remplace " + oldNum + "), saisi: " + value;
            if (nr.tournee.isEmpty()) nr.tournee = Meters.tourneeOf(old);
            log("REMPLACEMENT note: " + oldNum + " -> " + shown + " = " + value);
        } catch (Throwable t) {
            log("remplacement: " + t.getMessage());
        }
    }

    /** L'utilisateur confirme: ce compteur remplace celui du tableau (meme ligne, nouveau numero). */
    public String confirmReplacement() {
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) {
                return "Ouvrez le compteur dans l'application";
            }
            String id = readMeterId(root);
            Meters.Row r = id.isEmpty() ? null : lookupRow(root, id);
            if (r == null || r.warn.isEmpty() || id.equals(r.key)) return "Aucun remplacement a confirmer";
            String shown = meterLabel(root);
            int i = shown.indexOf(':');
            if (i >= 0) shown = shown.substring(i + 1).trim();
            if (shown.isEmpty()) shown = id;
            String oldNum = r.compteur;
            Meters.rekey(r, id, shown);
            r.warn = "";
            r.note = "REMPLACE confirme: ancien " + oldNum + (r.note.isEmpty() ? "" : " | " + r.note);
            missId = "";
            missRow = null;
            saveTable(this);
            log("REMPLACEMENT confirme: " + oldNum + " -> " + shown);
            refreshStrip();
            return "\u2713 Remplace dans le tableau: " + oldNum + " > " + shown;
        } catch (Throwable t) {
            return "Remplacement impossible: " + t.getMessage();
        }
    }

    // ---------- Completer les index depuis l'onglet Cloturee(s) ----------

    private int syncPhase = 0;
    private long syncT = 0, syncBackT = 0, syncTapT = 0;
    private int syncTabTaps = 0, syncPages = 0, syncStuck = 0;
    private int syncCards = 0, syncFilled = 0, syncFixed = 0, syncMissing = 0;
    private String syncPrevSig = "", syncToday = "";
    private final java.util.HashSet<String> syncSeen = new java.util.HashSet<>();

    // ne JAMAIS ecrire dans le tableau depuis les cartes: elles ne contiennent pas le nouvel index
    private static final boolean SYNC_WRITES = false;
    private boolean syncAll = false;
    private int syncEmpty = 0;
    private boolean syncProbed = false;
    private String syncProbeText = "";
    private int syncRows = 0, syncEqNew = 0, syncEqOld = 0, syncEqCon = 0;

    public void syncClosed() {
        syncClosed(false);
    }

    /** all=false: seulement aujourd'hui; all=true: tout l'historique de la liste. */
    public void syncClosed(boolean all) {
        syncAll = all;
        if (autoRunning || busy || gotoKey != null || syncPhase != 0) {
            toast("Occupe: reessayez dans un instant");
            return;
        }
        ensureLoaded(this);
        syncPhase = 1;
        syncT = SystemClock.uptimeMillis();
        syncBackT = 0;
        syncTapT = 0;
        syncTabTaps = 0;
        syncPages = 0;
        syncStuck = 0;
        syncCards = syncFilled = syncFixed = syncMissing = 0;
        syncEmpty = 0;
        syncProbed = false;
        syncProbeText = "";
        syncRows = syncEqNew = syncEqOld = syncEqCon = 0;
        syncPrevSig = "";
        syncSeen.clear();
        syncToday = new java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.FRANCE)
                .format(new java.util.Date());
        stopFlag = false;
        toast("Completer depuis Cloturee(s): demarrage");
        log("SYNC v71: debut (date " + syncToday + ")");
        handler.removeCallbacks(syncStep);
        handler.post(syncStep);
    }

    private void syncEnd(String why) {
        syncPhase = 0;
        saveTable(this);
        final java.io.File cf = new java.io.File(getFilesDir(), "closed.tsv");
        SAVER.execute(() -> Meters.saveClosed(cf));
        String msg = "SYNC " + why + ": " + syncCards + " lus, " + syncFilled + " remplis, "
                + syncFixed + " corriges, " + syncMissing + " hors tableau";
        log("SYNC v70 diagnostic: 3e nombre de la carte = nouvel index du tableau pour " + syncEqNew
                + " / ancien index pour " + syncEqOld + " / contrat pour " + syncEqCon
                + " (sur " + syncRows + " cartes rattachees)");
        log(msg);
        toast(msg);
    }

    private boolean isSelectedUp(AccessibilityNodeInfo n) {
        AccessibilityNodeInfo q = n;
        for (int up = 0; up < 4 && q != null; up++) {
            if (q.isSelected()) return true;
            q = q.getParent();
        }
        return false;
    }

    private final Runnable syncStep = new Runnable() {
        @Override
        public void run() {
            if (syncPhase == 0) return;
            if (stopFlag) {
                syncEnd("arrete");
                return;
            }
            long now = SystemClock.uptimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            boolean inApp = root != null && APP.equals(String.valueOf(root.getPackageName()));
            if (syncPhase == 1) {
                if (!inApp) {
                    if (now - syncT > 8000) {
                        syncEnd("application non ouverte");
                        return;
                    }
                    handler.postDelayed(this, 200);
                    return;
                }
                AccessibilityNodeInfo tab = findText(root, "Cl\u00F4tur\u00E9e", 0);
                if (tab != null && findId(root, "searchInput", 0) != null) {
                    syncPhase = 2;
                    syncT = now;
                    handler.post(this);
                    return;
                }
                AccessibilityNodeInfo go = findText(root, "Continuer", 0);
                if (go != null && now - syncTapT > 900) {
                    syncTapT = now;
                    tapNode(go);
                } else if (go == null && now - syncBackT > 1100) {
                    syncBackT = now;
                    performGlobalAction(GLOBAL_ACTION_BACK);
                    log("SYNC: retour vers la liste");
                }
                if (now - syncT > 14000) {
                    syncEnd("liste Interventions introuvable");
                    return;
                }
                handler.postDelayed(this, 200);
                return;
            }
            if (syncPhase == 2) {
                AccessibilityNodeInfo tab = inApp ? findText(root, "Cl\u00F4tur\u00E9e", 0) : null;
                if (tab != null && !isSelectedUp(tab) && syncTabTaps < 3) {
                    if (now - syncTapT > 900) {
                        syncTapT = now;
                        syncTabTaps++;
                        boolean a = tapNode(tab);
                        boolean b = clickUp(tab);
                        log("SYNC: onglet Cloturee(s) touche " + syncTabTaps + " (" + a + "/" + b + ")");
                    }
                    handler.postDelayed(this, 300);
                    return;
                }
                syncPhase = 3;
                syncT = now;
                handler.postDelayed(this, d(700));
                return;
            }
            if (syncPhase == 4) {
                // sonde: texte de l'ecran de detail d'une carte (pour savoir ou se trouve le nouvel index)
                if (inApp) {
                    java.util.ArrayList<String> dt = new java.util.ArrayList<>();
                    collectTexts(root, dt, 0);
                    StringBuilder ds = new StringBuilder();
                    int shown = 0;
                    for (String x : dt) {
                        if (x == null || !x.matches("(?s).*[\\p{L}\\p{N}].*")) continue;
                        ds.append(x.length() > 40 ? x.substring(0, 40) : x).append(" | ");
                        if (++shown >= 120) break;
                    }
                    log("SYNC sonde carte (" + shown + " textes): " + ds);
                }
                performGlobalAction(GLOBAL_ACTION_BACK);
                syncPhase = 3;
                syncT = now;
                handler.postDelayed(this, d(1500));
                return;
            }
            if (syncPhase == 3) {
                if (!inApp) {
                    syncEnd("application quittee");
                    return;
                }
                java.util.ArrayList<String> t = new java.util.ArrayList<>();
                collectTexts(root, t, 0);
                // l'arbre contient des textes vides entre les lignes: on les retire pour que les positions soient fixes
                for (int q = t.size() - 1; q >= 0; q--) {
                    if (t.get(q) == null || !t.get(q).matches("(?s).*[\\p{L}\\p{N}].*")) t.remove(q);
                }
                boolean older = false;
                StringBuilder sig = new StringBuilder();
                for (int i = 0; i < t.size(); i++) {
                    if (!t.get(i).toLowerCase().contains("onsommation") || i < 6) continue;
                    String date = t.get(i - 1).trim();
                    String idx = t.get(i - 4).trim();
                    String meter = t.get(i - 5).trim();
                    if (idx.matches("[\\d\\s.,]{1,14}")) idx = idx.replaceAll("\\D", "");
                    if (!date.matches("\\d{4}-\\d{2}-\\d{2}.*") || !idx.matches("\\d{1,9}")) continue;
                    String key = Meters.key(meter);
                    sig.append(key).append(';');
                    if (!syncAll && date.substring(0, 10).compareTo(syncToday) < 0) {
                        older = true;
                        continue;
                    }
                    if (!syncSeen.add(key)) continue;
                    syncCards++;
                    if (syncProbeText.isEmpty()) syncProbeText = meter;
                    Meters.Closed cl = new Meters.Closed();
                    cl.key = key;
                    cl.meter = meter;
                    cl.ref = t.get(i - 6).trim();
                    cl.idx = ""; // le 3e nombre de la carte est le numero client, PAS le nouvel index
                    cl.name = t.get(i - 3).trim();
                    cl.address = t.get(i - 2).trim();
                    cl.date = date;
                    cl.conso = t.get(i).replaceAll("\\D", "");
                    cl.days = i + 1 < t.size() && t.get(i + 1).startsWith("Nombre")
                            ? t.get(i + 1).replaceAll("\\D", "") : "";
                    Meters.Row r = Meters.find(key);
                    if (r == null) {
                        syncMissing++;
                        cl.note = "hors tableau (client " + idx + ")";
                        continue;
                    }
                    cl.tournee = Meters.tourneeOf(r);
                    syncRows++;
                    if (sameNumber(r.newIdx, idx)) syncEqNew++;
                    if (sameNumber(r.oldIdx, idx)) syncEqOld++;
                    if (!Meters.key(Meters.contractOf(r)).isEmpty()
                            && Meters.key(Meters.contractOf(r)).equals(Meters.key(idx))) syncEqCon++;
                    cl.note = "client " + idx;
                    if (SYNC_WRITES && r.newIdx.isEmpty()) {
                        cl.note = "rempli dans le tableau";
                        r.newIdx = idx;
                        r.note = "complete depuis Cloturee(s): " + idx
                                + (r.note.isEmpty() ? "" : " | " + r.note);
                        if (r.status == Meters.NEW || r.status == Meters.ERROR) r.status = Meters.SENT;
                        syncFilled++;
                    } else if (SYNC_WRITES && !sameNumber(r.newIdx, idx)) {
                        cl.note = "corrige (le tableau avait " + r.newIdx + ")";
                        r.note = "corrige depuis l'application (etait " + r.newIdx + ")"
                                + (r.note.isEmpty() ? "" : " | " + r.note);
                        r.newIdx = idx;
                        syncFixed++;
                    }
                }
                if (sig.length() == 0) {
                    // aucune carte reconnue: la liste charge peut-etre encore; on attend et on journalise les textes
                    syncEmpty++;
                    if (syncEmpty == 1 || syncEmpty == 4) {
                        StringBuilder dump = new StringBuilder();
                        for (int q = 0; q < t.size() && q < 40; q++) {
                            String x = t.get(q);
                            dump.append(x.length() > 28 ? x.substring(0, 28) : x).append(" | ");
                        }
                        log("SYNC: aucune carte reconnue (" + t.size() + " textes): " + dump);
                    }
                    if (syncEmpty >= 8) {
                        syncEnd("aucune carte reconnue (voir le texte dans le journal)");
                        return;
                    }
                    handler.postDelayed(this, d(1000));
                    return;
                }
                syncPages++;
                if (syncPages % 20 == 0) {
                    toast("Sync: " + syncCards + " lus, " + syncFilled + " remplis");
                    saveTable(ScanService.this);
                }
                String s2 = sig.toString();
                if (s2.equals(syncPrevSig)) syncStuck++;
                else syncStuck = 0;
                syncPrevSig = s2;
                if (older) {
                    syncEnd("termine (cartes d'un jour precedent atteintes)");
                    return;
                }
                if (syncStuck >= 3) {
                    syncEnd("termine (fin de liste)");
                    return;
                }
                if (syncPages > (syncAll ? 3000 : 600)) {
                    syncEnd("limite de pages atteinte");
                    return;
                }
                if (!syncProbed && !syncProbeText.isEmpty()) {
                    syncProbed = true;
                    AccessibilityNodeInfo card = findText(root, syncProbeText, 0);
                    if (card != null) {
                        tapNode(card);
                        clickUp(card);
                        log("SYNC sonde: ouverture de la carte " + syncProbeText);
                        syncPhase = 4;
                        syncT = now;
                        handler.postDelayed(this, 1800);
                        return;
                    }
                    log("SYNC sonde: carte introuvable a l'ecran");
                }
                DisplayMetrics dd = dm();
                Path p = new Path();
                p.moveTo(dd.widthPixels * 0.5f, dd.heightPixels * 0.74f);
                p.lineTo(dd.widthPixels * 0.5f, dd.heightPixels * 0.36f);
                dispatchGesture(new GestureDescription.Builder().addStroke(
                        new GestureDescription.StrokeDescription(p, 0, 380)).build(), null, null);
                handler.postDelayed(this, d(900));
            }
        }
    };

    private static boolean sameNumber(String a, String b) {
        if (a == null || b == null) return false;
        if (!a.matches("\\d{1,9}") || !b.matches("\\d{1,9}")) return false;
        return Long.parseLong(a) == Long.parseLong(b);
    }

    /** Appele par le clavier: faut-il envoyer tout seul le nombre tape ? */
    public boolean autoOk(String v) {
        try {
            if (autoRunning || busy) return false;
            ensureLoaded(this);
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return true;
            String id = readMeterId(root);
            Meters.Row r = id.isEmpty() ? null : lookupRow(root, id);
            if (r == null || !r.oldIdx.matches("\\d{1,9}") || !v.matches("\\d{1,9}")) {
                holdT = 0;
                return true;
            }
            long o = Long.parseLong(r.oldIdx);
            long n = Long.parseLong(v);
            if (outOfRange(o, n)) {
                holdT = SystemClock.uptimeMillis();
                log("attente: " + v + (n < o ? " trop bas par rapport a l'ancien " : " trop haut par rapport a l'ancien ") + o
                        + " (limites +" + maxJump + " / -" + maxDrop + ", valider a la main)");
                refreshStrip();
                return false;
            }
            holdT = 0;
            return true;
        } catch (Exception e) {
            return true;
        }
    }

    private void toast(String m) {
        try {
            android.widget.Toast.makeText(this, m, android.widget.Toast.LENGTH_SHORT).show();
        } catch (Exception ignored) {
        }
    }

    /** Demarre le flux automatique pour un seul compteur, puis s'arrete au suivant. */
    private boolean startFromRow(Meters.Row rr, String key, String value, boolean annotationFlow) {
        aRow = rr;
        aId = key;
        aValue = value;
        aSingle = true;
        aOvAnn = null;
        aOvAnom = null;
        aSeen.clear();
        aSent = 0;
        aSkipped = 0;
        aWaitSubmit = false;
        closureFlow = false;
        stopFlag = false;
        t0 = SystemClock.uptimeMillis();
        autoRunning = true;
        log("egal a l'ancien index: " + (annotationFlow ? "annotation " + rr.annotation
                : "anomalie " + rr.anomaly) + " (tableau)");
        if (annotationFlow) {
            aGo(A_AN_OPEN, 100);
        } else {
            aArrow = 0;
            aGo(A_ZL_ARROW, 100);
        }
        return true;
    }

    /** Bouton "+ COMPTEUR" du clavier: ouvre la saisie d'un compteur non repertorie. */
    public void openAddMeter() {
        ensureLoaded(this);
        String tournee = "";
        try {
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root != null && APP.equals(String.valueOf(root.getPackageName()))) {
                String id = readMeterId(root);
                Meters.Row r = id.isEmpty() ? null : lookupRow(root, id);
                if (r != null) tournee = Meters.tourneeOf(r);
            }
        } catch (Exception ignored) {
        }
        if (tournee.isEmpty()) tournee = Meters.lastTournee;
        android.content.Intent i = new android.content.Intent(this, AddMeterActivity.class);
        i.putExtra("tournee", tournee);
        i.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(i);
    }

    /** Bouton ANOMALIE du clavier: 0 = local inhabite, 1 = compteur bloque, 2 = inaccessible. */
    public void startManualAnomaly(int kind) {
        ensureLoaded(this);
        if (autoRunning || busy) {
            toast("Une automatisation est deja en cours");
            return;
        }
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null || !APP.equals(String.valueOf(root.getPackageName()))
                || findId(root, "modelInputIdx", 0) == null) {
            toast("Ouvrez d'abord l'ecran Releve");
            return;
        }
        String id = readMeterId(root);
        if (id.isEmpty()) {
            toast("Numero de compteur illisible");
            return;
        }
        if (!live) {
            log("ANOMALIE: mode SIMULATION, rien n'est touche");
            toast("Mode SIMULATION: activez le mode REEL");
            return;
        }
        Meters.Row r = lookupRow(root, id);
        if (r == null) {
            String label = meterLabel(root);
            int i = label.indexOf(':');
            String shown = i >= 0 ? label.substring(i + 1).trim() : label;
            r = Meters.addExtra(shown.isEmpty() ? id : shown, id);
        }
        String val = "";
        if (kind == 0) {
            val = r.oldIdx;
            KeyboardService k = KeyboardService.instance;
            if (!val.matches("\\d{1,9}") && k != null) val = k.typedValue();
            if (!val.matches("\\d{1,9}")) {
                toast("Ancien index inconnu: tapez l'index puis reessayez");
                return;
            }
        }
        if (kind != 0) {
            AccessibilityNodeInfo fld = findId(root, "modelInputIdx", 0);
            if (fld != null && !isEmptyField(fld)) clearField(fld);
        }
        aRow = r;
        aId = id;
        aValue = val;
        if (kind == 0) r.annotation = "LOCAL INHABITE";
        else if (kind == 1) r.anomaly = "COMPTEUR BLOQUE";
        else r.anomaly = "COMPTEUR INACCESSIBLE";
        aSingle = true;
        aOvAnn = kind == 0 ? "LOCAL INHABITE" : null;
        aOvAnom = kind == 1 ? "COMPTEUR BLOQUE" : (kind == 2 ? "COMPTEUR INACCESSIBLE" : null);
        aSeen.clear();
        aSent = 0;
        aSkipped = 0;
        aWaitSubmit = false;
        closureFlow = false;
        stopFlag = false;
        t0 = SystemClock.uptimeMillis();
        autoRunning = true;
        log("ANOMALIE manuelle: " + (kind == 0 ? "local inhabite (index " + val + ")"
                : (kind == 1 ? "compteur bloque" : "compteur inaccessible")) + ", " + id);
        if (kind == 0) {
            aGo(A_AN_OPEN, 100);
        } else {
            aArrow = 0;
            aGo(A_ZL_ARROW, 100);
        }
    }

    private String textOf(AccessibilityNodeInfo n) {
        CharSequence t = n.getText();
        return t == null ? "" : t.toString().trim();
    }

    /** Message "Attention": index juge trop eleve OU index egal au precedent. */
    private AccessibilityNodeInfo findAlert(AccessibilityNodeInfo root) {
        AccessibilityNodeInfo a = findText(root, "jug", 0);
        if (a == null) a = findText(root, "gal au pr", 0);
        if (a == null) a = findText(root, "saisir \u00e0 nouveau", 0);
        if (a == null) a = findText(root, "saisir a nouveau", 0);
        return a;
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
        photoBackT = 0;
        photoTaps = 0;
        photoSnap = 0;
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
                photoDumped = false;
                handler.postDelayed(photoStep, 500);
                return;
            } else if (photoPhase == 4) {
                // On n'appuie sur "valider" que lorsque son indicateur est visible.
                if (inApp) {
                    // l'appareil photo s'est deja ferme tout seul
                    log("PHOTO: retour a l'application sans validation");
                    photoPhase = 5;
                    photoT = now;
                    handler.postDelayed(photoStep, 100);
                    return;
                }
                if (root != null && pkg.length() > 0) {
                    AccessibilityNodeInfo cf = findConfirm(root, 0);
                    if (cf != null) {
                        // copie de la photo (avec bandeau) AVANT de valider, sans retarder la suite
                        if (photoProof && photoSnap == 0) {
                            photoSnap = 1;
                            photoSnapT = now;
                            snapProof();
                            handler.postDelayed(photoStep, 40);
                            return;
                        }
                        if (photoSnap == 1 && now - photoSnapT < 900) {
                            handler.postDelayed(photoStep, 40);
                            return;
                        }
                        photoSnap = 0;
                        boolean ok = tapNode(cf);
                        log("PHOTO: indicateur de validation trouve, touche (" + ok + ")");
                        photoPhase = 5;
                        photoT = now;
                        handler.postDelayed(photoStep, 500);
                        return;
                    }
                    if (!photoDumped && now - photoT > 3000) {
                        photoDumped = true;
                        StringBuilder sb = new StringBuilder();
                        dump(root, 0, sb);
                        log("PHOTO: indicateur introuvable apres 3 s, ecran de l'appareil:\n" + sb);
                    }
                    if (now - photoT > 7000) {
                        boolean ok = tap(0.793f, 0.862f);
                        log("PHOTO: toujours rien apres 7 s, toucher de secours (" + ok + ")");
                        photoPhase = 5;
                        photoT = now;
                        handler.postDelayed(photoStep, 500);
                        return;
                    }
                }
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
                    // laisser l'ecran se stabiliser avant de reprendre
                    if (photoBackT == 0) {
                        photoBackT = now;
                        handler.postDelayed(photoStep, 450);
                        return;
                    }
                    if (now - photoBackT < 450) {
                        handler.postDelayed(photoStep, POLL);
                        return;
                    }
                    photoBackT = 0;
                    photoPhase = 0;
                    busy = false;
                    afterPhoto = true;
                    markPhoto(subKey);
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

    private boolean photoDumped = false;
    private long photoBackT = 0;

    // ---------- Copie de la photo avec bandeau (preuve) ----------

    /** Copie de chaque photo avec bandeau (compteur, contrat, client): activee par defaut. */
    public static boolean photoProof = false;
    private int photoSnap = 0;      // 0 = rien, 1 = capture en cours
    private long photoSnapT = 0;

    /** Photo prise par l'automatisation: compteur en cours de traitement. */
    private void snapProof() {
        final Meters.Row r = autoRunning && aRow != null ? aRow : subRowRef;
        final String id = autoRunning && aId != null && !aId.isEmpty() ? aId : subKey;
        snapProofFor(r, id);
    }

    private void snapProofFor(Meters.Row r, String id) {
        if (android.os.Build.VERSION.SDK_INT < 30) {
            log("PHOTO: copie impossible (Android 11 minimum)");
            photoSnap = 0;
            return;
        }
        final String compteur = r != null && !r.compteur.isEmpty() ? r.compteur : (id == null ? "" : id);
        final String contrat = r == null ? "" : Meters.contractOf(r);
        final String tournee = r == null ? "" : Meters.tourneeOf(r);
        // petit delai: laisse finir la transition de l'ecran de confirmation
        snap30(r, compteur, contrat, tournee);
    }

    @android.annotation.TargetApi(30)
    private void snap30(final Meters.Row row, final String compteur, final String contrat,
                        final String tournee) {
        try {
            takeScreenshot(android.view.Display.DEFAULT_DISPLAY, SHOT_EXEC,
                    new TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(ScreenshotResult res) {
                            android.graphics.Bitmap copy = null;
                            try {
                                android.graphics.Bitmap hw = android.graphics.Bitmap
                                        .wrapHardwareBuffer(res.getHardwareBuffer(), res.getColorSpace());
                                if (hw != null) {
                                    copy = hw.copy(android.graphics.Bitmap.Config.ARGB_8888, false);
                                    hw.recycle();
                                }
                                res.getHardwareBuffer().close();
                            } catch (Throwable t) {
                                log("PHOTO: copie echouee (" + t.getMessage() + ")");
                            }
                            photoSnap = 0;
                            if (copy == null) return;
                            final android.graphics.Bitmap shot = copy;
                            new Thread(() -> saveProof(shot, row, compteur, contrat, tournee)).start();
                        }

                        @Override
                        public void onFailure(int code) {
                            photoSnap = 0;
                            log("PHOTO: capture d'ecran refusee (code " + code + ")");
                        }
                    });
        } catch (Throwable t) {
            photoSnap = 0;
            log("PHOTO: capture impossible (" + t.getMessage() + ")");
        }
    }

    /** La capture est copiee hors du fil principal (sinon le clavier gele sur les telephones lents). */
    private static final java.util.concurrent.Executor SHOT_EXEC =
            java.util.concurrent.Executors.newSingleThreadExecutor();

    private float fitSize(android.graphics.Paint pt, String text, float max, float min, float avail) {
        float sz = max;
        while (sz > min) {
            pt.setTextSize(sz);
            if (pt.measureText(text) <= avail) break;
            sz -= 1f;
        }
        pt.setTextSize(sz);
        return sz;
    }

    /** Dessine le bandeau sous la capture et l'enregistre dans Pictures/Releve (galerie). */
    private void saveProof(android.graphics.Bitmap shot, Meters.Row row, String compteur,
                           String contrat, String tournee) {
        try {
            int w = shot.getWidth(), h = shot.getHeight();
            int pad = Math.max(14, w / 36);
            String when = new java.text.SimpleDateFormat("dd/MM/yyyy HH:mm", java.util.Locale.FRANCE)
                    .format(new java.util.Date());
            java.util.ArrayList<String> lines = new java.util.ArrayList<>();
            lines.add("N\u00B0 compteur : " + compteur);
            if (!contrat.isEmpty()) lines.add("Contrat : " + contrat);
            lines.add(when + (tournee.isEmpty() ? "" : "   |   Tourn\u00E9e " + tournee));

            android.graphics.Paint pt = new android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG);
            pt.setColor(0xFFFFFFFF);
            pt.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            float avail = w - 2f * pad;
            float[] size = new float[lines.size()];
            float total = pad * 1.4f;
            for (int i = 0; i < lines.size(); i++) {
                float max = i == 0 ? w / 15f : w / 22f;
                size[i] = fitSize(pt, lines.get(i), max, w / 40f, avail);
                total += size[i] * 1.3f;
            }
            int stripH = (int) total;
            android.graphics.Bitmap out = android.graphics.Bitmap.createBitmap(
                    w, h + stripH, android.graphics.Bitmap.Config.ARGB_8888);
            android.graphics.Canvas c = new android.graphics.Canvas(out);
            c.drawBitmap(shot, 0, 0, null);
            android.graphics.Paint bg = new android.graphics.Paint();
            bg.setColor(0xFF111827);
            c.drawRect(0, h, w, h + stripH, bg);
            float y = h + pad * 0.7f;
            for (int i = 0; i < lines.size(); i++) {
                pt.setTextSize(size[i]);
                pt.setColor(i == 0 ? 0xFFFFFFFF : 0xFFCBD5E1);
                y += size[i] * 1.05f;
                c.drawText(lines.get(i), pad, y, pt);
                y += size[i] * 0.25f;
            }
            shot.recycle();

            String safe = compteur.replaceAll("[^A-Za-z0-9_-]", "");
            if (safe.isEmpty()) safe = "compteur";
            String name = safe + "_" + new java.text.SimpleDateFormat("yyyyMMdd_HHmmss",
                    java.util.Locale.FRANCE).format(new java.util.Date()) + ".jpg";
            android.content.ContentValues cv = new android.content.ContentValues();
            cv.put(android.provider.MediaStore.Images.Media.DISPLAY_NAME, name);
            cv.put(android.provider.MediaStore.Images.Media.MIME_TYPE, "image/jpeg");
            cv.put(android.provider.MediaStore.Images.Media.RELATIVE_PATH, "Pictures/Releve");
            android.net.Uri uri = getContentResolver().insert(
                    android.provider.MediaStore.Images.Media.EXTERNAL_CONTENT_URI, cv);
            if (uri == null) {
                log("PHOTO: enregistrement de la copie impossible");
                out.recycle();
                return;
            }
            try (java.io.OutputStream os = getContentResolver().openOutputStream(uri)) {
                out.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, os);
            }
            out.recycle();
            if (row != null) {
                row.photos = row.photos.isEmpty() ? name : row.photos + "; " + name;
                saveTable(this);
            }
            log("PHOTO: copie enregistree (Pictures/Releve/" + name + ")");
        } catch (Throwable t) {
            log("PHOTO: copie echouee (" + t.getMessage() + ")");
        }
    }

    /** Cherche le bouton de validation de la photo (apparait apres la prise). */
    private AccessibilityNodeInfo findConfirm(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isVisibleToUser() && isConfirmNode(n)) return n;
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo x = findConfirm(n.getChild(i), depth + 1);
            if (x != null) return x;
        }
        return null;
    }

    private boolean isConfirmNode(AccessibilityNodeInfo n) {
        String t = n.getText() == null ? "" : n.getText().toString().trim().toLowerCase();
        String d = n.getContentDescription() == null ? ""
                : n.getContentDescription().toString().trim().toLowerCase();
        String id = n.getViewIdResourceName() == null ? ""
                : n.getViewIdResourceName().toLowerCase();
        String all = t + "|" + d + "|" + id;
        String[] bad = {"retake", "reprendre", "refaire", "annuler", "cancel", "retry",
                "shutter", "declench", "déclench", "capture", "back", "close", "discard",
                "إلغاء"};
        for (String b : bad) if (all.contains(b)) return false;
        String[] exact = {"ok", "ok.", "valider", "terminer", "terminé", "termine", "done",
                "save", "enregistrer", "accepter", "accept", "confirmer", "confirm", "apply",
                "appliquer", "utiliser la photo", "use photo", "تم",
                "موافق", "حفظ", "✓", "✔"};
        for (String e : exact) if (t.equals(e) || d.equals(e)) return true;
        String[] part = {"valid", "confirm", "enregistr", "save", "accept", "done", "termin",
                "تأكيد"};
        for (String p : part) if (d.contains(p) || t.contains(p)) return true;
        String[] ids = {"confirm", "done", "btn_ok", "btn_save", "save", "accept", "apply",
                "complete", "positive"};
        for (String s : ids) if (id.contains(s)) return true;
        return false;
    }

    /** Appele par le bandeau (toucher): ouvre l'appareil photo de l'application d'origine. */
    private int camPhase = 0;
    private long camT = 0;
    private String camMeter = "";
    private String camValue = "";

    /**
     * Touche de la bulle "HORS LIMITE": ouvre "Commentaire", touche l'icone de l'appareil photo,
     * prend la photo et la valide. N'envoie JAMAIS l'index.
     */
    public void openCameraNow() {
        handler.post(() -> {
            if (busy || autoRunning) {
                log("appareil photo: occupe (busy=" + busy + ", auto=" + autoRunning + ")");
                return;
            }
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) {
                log("appareil photo: fenetre active = "
                        + (root == null ? "aucune" : String.valueOf(root.getPackageName())));
                return;
            }
            camMeter = readMeterId(root);
            AccessibilityNodeInfo fv = findId(root, "modelInputIdx", 0);
            String tv = fv == null ? "" : textOf(fv).replace(" ", "");
            camValue = tv.matches("\\d{1,9}") ? tv : "";
            camT = SystemClock.uptimeMillis();
            handler.removeCallbacks(camStep);
            if (findId(root, "modale-cloture", 0) != null) {
                camPhase = 1;
                handler.post(camStep);
                return;
            }
            AccessibilityNodeInfo com = findId(root, "btn-cloture-error", 0);
            if (com == null) {
                log("appareil photo: bouton Commentaire introuvable");
                return;
            }
            boolean t = tapNode(com);
            log("appareil photo: Commentaire touche (" + t + ")");
            camPhase = 1;
            handler.postDelayed(camStep, 300);
        });
    }

    /** Icone de l'appareil photo dans la fenetre "Cloture de l'intervention" (Commentaire). */
    private AccessibilityNodeInfo findModalCam(AccessibilityNodeInfo n, boolean inModal, int depth) {
        if (n == null || depth > 30) return null;
        String vid = n.getViewIdResourceName();
        if (vid != null && vid.endsWith("modale-cloture")) inModal = true;
        if (inModal && n.isClickable() && String.valueOf(n.getClassName()).endsWith("Button")) {
            String t = textOf(n).toLowerCase();
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (!t.contains("annuler") && !t.contains("cl\u00F4turer") && !t.contains("cloturer")
                    && r.top > 100 && r.top < 600 && r.width() > 0) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo x = findModalCam(n.getChild(i), inModal, depth + 1);
            if (x != null) return x;
        }
        return null;
    }

    private void camEnd(String msg) {
        camPhase = 0;
        log("appareil photo: " + msg);
    }

    private final Runnable camStep = new Runnable() {
        @Override
        public void run() {
            if (camPhase == 0) return;
            if (stopFlag) {
                camEnd("arret demande");
                return;
            }
            long now = SystemClock.uptimeMillis();
            AccessibilityNodeInfo root = getRootInActiveWindow();
            String pkg = root == null ? "" : String.valueOf(root.getPackageName());
            boolean inApp = APP.equals(pkg);
            if (camPhase == 1) {
                AccessibilityNodeInfo cam = inApp ? findModalCam(root, false, 0) : null;
                if (cam != null) {
                    boolean t = tapNode(cam);
                    log("appareil photo: icone touchee (" + t + ")");
                    camPhase = 2;
                    camT = now;
                    handler.postDelayed(this, 300);
                    return;
                }
                if (now - camT > 4000) {
                    StringBuilder sb = new StringBuilder();
                    if (root != null) dump(root, 0, sb);
                    camEnd("icone introuvable, ecran:\n" + sb);
                    return;
                }
            } else if (camPhase == 2) {
                if (root != null && !inApp && pkg.length() > 0) {
                    log("appareil photo: ouvert (" + pkg + ")");
                    camPhase = 3;
                    camT = now;
                    handler.postDelayed(this, PHOTO_DELAY_MS);
                    return;
                }
                if (now - camT > 5000) {
                    camEnd("l'appareil photo ne s'ouvre pas");
                    return;
                }
            } else if (camPhase == 3) {
                boolean ok = tap(0.5f, 0.856f);
                log("appareil photo: declencheur touche (" + ok + ")");
                camPhase = 4;
                camT = now;
                handler.postDelayed(this, 500);
                return;
            } else if (camPhase == 4) {
                if (inApp) {
                    markPhoto(camMeter);
                    log("appareil photo: photo prise, retour a l'application");
                    camPhase = 6;
                    camT = now;
                    handler.postDelayed(this, 500);
                    return;
                }
                if (root != null && pkg.length() > 0) {
                    AccessibilityNodeInfo cf = findConfirm(root, 0);
                    if (cf != null) {
                        boolean ok = tapNode(cf);
                        log("appareil photo: validation touchee (" + ok + ")");
                        camPhase = 5;
                        camT = now;
                        handler.postDelayed(this, 500);
                        return;
                    }
                    if (now - camT > 7000) {
                        boolean ok = tap(0.793f, 0.862f);
                        log("appareil photo: toucher de secours (" + ok + ")");
                        camPhase = 5;
                        camT = now;
                        handler.postDelayed(this, 500);
                        return;
                    }
                }
            } else if (camPhase == 5) {
                if (inApp) {
                    markPhoto(camMeter);
                    log("appareil photo: photo validee");
                    camPhase = 6;
                    camT = now;
                    handler.postDelayed(this, 500);
                    return;
                }
                if (now - camT > 4000) {
                    camEnd("retour a l'application impossible");
                    return;
                }
            } else if (camPhase == 6) {
                // photo enregistree: on touche "Cloturer l'intervention" (bouton vert sous l'icone)
                AccessibilityNodeInfo cl = inApp ? findText(root, "Cl\u00F4turer l", 0) : null;
                if (cl != null) {
                    boolean ok = tapNode(cl);
                    if (!ok) ok = clickUp(cl);
                    log("appareil photo: Cloturer l'intervention touche (" + ok + ")");
                    try {
                        Meters.Row r = camMeter.isEmpty() ? null : Meters.find(camMeter);
                        if (r != null) {
                            r.note = "photo + cloture (Commentaire)" + (r.note.isEmpty() ? "" : " | " + r.note);
                            saveTable(ScanService.this);
                        }
                    } catch (Throwable ignored) {
                    }
                    camPhase = 7;
                    camT = now;
                    handler.postDelayed(this, 700);
                    return;
                }
                if (now - camT > 4000) {
                    camEnd("bouton Cloturer l'intervention introuvable");
                    return;
                }
            } else if (camPhase == 7) {
                // retour a l'ecran de saisie: on continue avec l'index qui etait hors limites
                if (inApp && findId(root, "modelInputIdx", 0) != null
                        && findId(root, "modale-cloture", 0) == null) {
                    String now0 = readMeterId(root);
                    if (camValue.isEmpty() || !now0.equals(camMeter)) {
                        camEnd("retour a la saisie, mais " + (camValue.isEmpty() ? "pas d'index memorise"
                                : "autre compteur (" + now0 + "), pas d'envoi"));
                        return;
                    }
                    camPhase = 0;
                    holdT = 0;
                    afterPhoto = true;
                    log("appareil photo: retour a la saisie, envoi de " + camValue);
                    if (!submit(camValue)) log("appareil photo: envoi impossible apres la photo");
                    return;
                }
                if (now - camT > 6000) {
                    camEnd("ecran de saisie non retrouve apres la cloture");
                    return;
                }
            }
            handler.postDelayed(this, POLL);
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
        if (cam == null) cam = findClickRightOf(root, f, 0);
        if (cam == null) {
            // dernier recours: toucher a droite du champ, a la hauteur du champ
            StringBuilder sbd = new StringBuilder();
            dump(root, 0, sbd);
            log("appareil photo: ecran:\n" + sbd);
            log("appareil photo: bouton introuvable (aucun envoi, aucune touche)");
            return;
        }
        boolean t = tapNode(cam);
        log("appareil photo: bouton touche (" + t + ")");
        if (!t) {
            Rect cr = new Rect();
            cam.getBoundsInScreen(cr);
            boolean g = tapAbs(cr.centerX(), cr.centerY());
            log("appareil photo: geste de secours (" + g + ")");
        }
    }

    private float dp40() {
        return 40f * getResources().getDisplayMetrics().density;
    }

    /** Variante large: n'importe quel element cliquable a droite du champ (icone, vue...). */
    private AccessibilityNodeInfo findClickRightOf(AccessibilityNodeInfo n, Rect f, int depth) {
        if (n == null || depth > 30) return null;
        if (n.isClickable()) {
            Rect r = new Rect();
            n.getBoundsInScreen(r);
            if (r.left >= f.right - 5 && r.bottom > f.top && r.top < f.bottom
                    && r.width() < f.height() * 3) return n;
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            AccessibilityNodeInfo x = findClickRightOf(n.getChild(i), f, depth + 1);
            if (x != null) return x;
        }
        return null;
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
    public static long maxJump = 50;       // hausse maximale acceptee (nouvel - ancien)
    public static long maxDrop = 10;       // baisse maximale acceptee (ancien - nouvel)

    /** Vrai si le nouvel index est hors de la plage [ancien - maxDrop, ancien + maxJump]. */
    public static boolean outOfRange(long o, long n) {
        return n > o + maxJump || n < o - maxDrop;
    }
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
    private int aRecover = 0;
    private int aRetries = 0;
    private int aScrollDir = -1;
    private int aStuck = 0;
    private String aLastOpts = "";
    private final java.util.HashSet<String> aBefore = new java.util.HashSet<>();
    // -1: suivant = glisser de la queue de la fleche (droite) vers sa tete (gauche), comme dans l app; +1: inverse (appris auto)
    private static int swipeSign = -1;

    private void flipSwipeDir() {
        swipeSign = -swipeSign;
        try {
            getSharedPreferences("cfg", MODE_PRIVATE).edit().putInt("swDir2", swipeSign).apply();
        } catch (Exception e) {
            // ignore
        }
        log("Sens du swipe inverse: " + (swipeSign > 0 ? "gauche->droite" : "droite->gauche") + " pour 'suivant'");
    }
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
            photoProof = sp.getBoolean("photoProof4", false);
            fast = sp.getBoolean("fast", false);
            followDb = sp.getBoolean("follow", true);
            Meters.ACTIVE_T.clear();
            for (String t : sp.getString("tournees", "").split("\u0001")) {
                if (!t.isEmpty()) Meters.ACTIVE_T.add(t);
            }
            swipeSign = sp.getInt("swDir2", -1) < 0 ? -1 : 1;
            lastSentKey = sp.getString("lastSent", "");
            loadOrder(new java.io.File(c.getFilesDir(), "order.tsv"));
            Meters.loadClosed(new java.io.File(c.getFilesDir(), "closed.tsv"));
            maxJump = sp.getLong("maxJump2", 50);
            maxDrop = sp.getLong("maxDrop", 10);
        }
        if (Meters.ROWS.isEmpty()) {
            Meters.load(new java.io.File(c.getFilesDir(), "table.tsv"));
            SAVER.execute(Meters::prewarm);
        }
    }

    public static void saveTournees(android.content.Context c) {
        StringBuilder sb = new StringBuilder();
        for (String t : Meters.ACTIVE_T) sb.append(t).append('\u0001');
        c.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).edit()
                .putString("tournees", sb.toString()).apply();
    }

    public static void saveCfg(android.content.Context c) {
        c.getSharedPreferences("cfg", android.content.Context.MODE_PRIVATE).edit()
                .putInt("photoMode", photoMode).putLong("maxJump2", maxJump)
                .putLong("maxDrop", maxDrop).apply();
    }

    private static final java.util.concurrent.ExecutorService SAVER =
            java.util.concurrent.Executors.newSingleThreadExecutor();
    private static final java.util.concurrent.atomic.AtomicBoolean SAVE_PENDING =
            new java.util.concurrent.atomic.AtomicBoolean(false);

    /** Ecriture en arriere-plan (plusieurs demandes rapprochees = une seule ecriture): ne bloque plus le clavier. */
    public static void saveTable(android.content.Context c) {
        final java.io.File f = new java.io.File(c.getApplicationContext().getFilesDir(), "table.tsv");
        if (!SAVE_PENDING.compareAndSet(false, true)) return;
        SAVER.execute(() -> {
            try {
                Thread.sleep(400);
            } catch (InterruptedException ignored) {
            }
            SAVE_PENDING.set(false);
            Meters.save(f);
        });
    }

    private String modeName() {
        return photoMode == 0 ? "photo auto" : (photoMode == 1 ? "photo: sauter" : "photo: arreter");
    }

    private boolean aForce = false;

    public void startAuto() {
        startAuto(false);
    }

    /** force=true: ne traite que les compteurs "index suspect", sans limite (confirmation en masse). */
    public void startAuto(boolean force) {
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
        if (force && Meters.count(Meters.SUSPECT) == 0) {
            toast("Aucun compteur suspect a confirmer");
            return;
        }
        aForce = force;
        aRetries = 0;
        stopFlag = false;
        aSeen.clear();
        aSent = 0;
        aSkipped = 0;
        aWaitSubmit = false;
        closureFlow = false;
        t0 = SystemClock.uptimeMillis();
        autoRunning = true;
        log((force ? "AUTO (suspects confirmes) demarre: " : "AUTO demarre: ") + (live ? "REEL" : "SIMULATION") + ", " + modeName());
        aGo(A_READ, 100);
    }

    public void autoStop(String msg) {
        boolean was = autoRunning;
        autoRunning = false;
        aSingle = false;
        aForce = false;
        aOvAnn = null;
        aOvAnom = null;
        aWaitSubmit = false;
        handler.removeCallbacks(autoStep);
        saveTable(this);
        log(msg);
        if (was) {
            int sus = Meters.count(Meters.SUSPECT);
            log("AUTO termine: envoyes=" + aSent + ", sautes=" + aSkipped + ", suspects=" + sus);
            toast("AUTO termine: " + aSent + " envoyes, " + sus + " suspects a controler");
        }
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

    /** Valeur affichee a droite d'un libelle de l'ecran (ex: Nom, Adresse). */
    private String screenValue(AccessibilityNodeInfo root, String label) {
        try {
            java.util.ArrayList<String> t = new java.util.ArrayList<>();
            collectTexts(root, t, 0);
            for (int i = 0; i + 1 < t.size(); i++) {
                if (Meters.norm(t.get(i)).equals(label)) {
                    for (int j = i + 1; j < t.size() && j <= i + 3; j++) {
                        String v = t.get(j).trim();
                        String k = Meters.norm(v);
                        if (!k.isEmpty() && !k.equals("ADRESSE") && !k.equals("NOM")
                                && !k.equals("ANNOTATION") && !k.equals("CODE RELEVE")) return v;
                    }
                }
            }
        } catch (Exception ignored) {
        }
        return "";
    }

    /** Ligne du tableau pour l'ecran courant: par numero, sinon par nom/adresse (compteur remplace). */
    private Meters.Row lookupRow(AccessibilityNodeInfo root, String id) {
        if (id == null || id.isEmpty()) return null;
        Meters.Row r = Meters.find(id);
        if (r != null) {
            r.warn = "";
            String tn = Meters.tourneeOf(r);
            if (!tn.isEmpty()) Meters.lastTournee = tn;
            return r;
        }
        // La recherche par nom parcourt tout le tableau (6000+ lignes): on garde le resultat
        // pour le meme compteur, sinon le clavier et le service ralentissent.
        long nowT = SystemClock.uptimeMillis();
        if (id.equals(missId) && nowT - missT < 900000 && missTableSize == Meters.ROWS.size()) {
            return missRow;
        }
        Meters.Row r2 = Meters.findByName(screenValue(root, "NOM"), screenValue(root, "ADRESSE"));
        if (r2 != null) {
            r2.warn = "compteur different: tableau " + r2.compteur + " / ecran " + id;
        }
        missId = id;
        missRow = r2;
        missT = nowT;
        missTableSize = Meters.ROWS.size();
        return r2;
    }

    private String missId = "";
    private Meters.Row missRow = null;
    private long missT = 0;
    private int missTableSize = -1;

    private void aMark(int status, String note) {
        if (aRow != null) {
            aRow.status = status;
            aRow.note = (aRow.warn.isEmpty() ? "" : "\u26A0 " + aRow.warn + " | ") + note;
        }
        saveTable(this);
        log("[" + aId + "] " + note);
    }

    /** Rejette tous les compteurs suspects: on efface leur nouvel index (rien ne sera envoye). */
    public int rejectSuspects() {
        int n = 0;
        for (Meters.Row r : Meters.ROWS) {
            if (r.status == Meters.SUSPECT) {
                r.newIdx = "";
                r.note = "rejete (index suspect)";
                r.status = Meters.NODATA;
                n++;
            }
        }
        saveTable(this);
        log("suspects rejetes: " + n);
        return n;
    }

    private String screenSummary() {
        try {
            AccessibilityNodeInfo r = getRootInActiveWindow();
            if (r == null) return "(ecran illisible)";
            java.util.ArrayList<String> t = new java.util.ArrayList<>();
            collectTexts(r, t, 0);
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < t.size() && i < 40; i++) {
                String x = t.get(i).replace('\n', ' ');
                if (x.length() > 40) x = x.substring(0, 40);
                sb.append(i > 0 ? " | " : "").append(x);
            }
            return sb.toString();
        } catch (Exception e) {
            return "(erreur lecture ecran)";
        }
    }

    private boolean retryable(String msg) {
        if (msg.contains("arret") || msg.contains("photo") || msg.contains("limite")) return false;
        return msg.contains("refuse") || msg.contains("STOP") || msg.contains("introuvable") || msg.contains("ne s'ouvre pas")
                || msg.contains("pas pret") || msg.contains("non confirme")
                || msg.contains("champ=") || msg.contains("pas vide")
                || msg.contains("illisible");
    }

    private void aFail(int status, String msg) {
        slowLeft = 8;
        log("ECHEC: " + msg + " | ecran: " + screenSummary());
        if (status == Meters.ERROR && autoRunning && !aSingle && aRetries < 3 && retryable(msg)) {
            aRetries++;
            log("[" + aId + "] nouvel essai " + aRetries + "/3");
            try {
                AccessibilityNodeInfo r = getRootInActiveWindow();
                if (r == null || findId(r, "modelInputIdx", 0) == null) {
                    performGlobalAction(GLOBAL_ACTION_BACK);
                }
            } catch (Exception ignored) {
            }
            aSeen.remove(aId);
            aGo(A_READ, 1000);
            return;
        }
        aMark(status, msg);
        autoStop(msg);
    }

    private void aSwipe() {
        if (aSingle) {
            autoStop("STOP: un seul compteur, pas de passage au suivant");
            return;
        }
        aSkipped++;
        aPrevId = aId;
        aSwipeTries = 0;
        aRecover = 0;
        aRetries = 0;
        boolean ok = swipe(true);
        log("[" + aId + "] swipe vers le compteur suivant (" + ok + ")");
        aGo(A_SW_WAIT, d(700));
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
        Meters.Row r = lookupRow(root, id);
        if (r != null && !r.warn.isEmpty()) log("[" + id + "] " + r.warn);
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
        if (!Meters.isActive(r)) {
            log("[" + id + "] hors des tournees choisies, saute");
            aSwipe();
            return;
        }
        if (r.status == Meters.SENT) {
            log("[" + id + "] deja envoye, saute");
            aSwipe();
            return;
        }
        if (aForce && r.status != Meters.SUSPECT) {
            log("[" + id + "] pas suspect, saute");
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
            if (!aForce && outOfRange(o, n)) {
                aMark(Meters.SUSPECT, "ecart " + (n >= o ? "+" : "") + (n - o) + " hors limites (+"
                        + maxJump + " / -" + maxDrop + "), a confirmer");
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
                if (aSeen.contains(id)) {
                    // Deja traite: on est alle dans le mauvais sens (ou on a boucle)
                    if (aRecover >= 2) {
                        autoStop("STOP: sens du swipe incertain, compteur deja traite (" + id + ")");
                        return;
                    }
                    if (aRecover == 0) flipSwipeDir();
                    aRecover++;
                    aPrevId = id;
                    swipe(true);
                    aT = now;
                    aPost(POLL);
                    return;
                }
                aRecover = 0;
                aGo(A_READ, d(450));
                return;
            }
        }
        if (now - aT > 2500) {
            if (aSwipeTries < 1) {
                aSwipeTries++;
                // Rien n'a bouge: on essaie l'autre sens
                flipSwipeDir();
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
        aBefore.clear();
        java.util.ArrayList<String> bg = new java.util.ArrayList<>();
        collectTexts(root, bg, 0);
        for (String x : bg) aBefore.add(Meters.norm(x));
        boolean ok = tapAbs(dm().widthPixels * 0.65f, r.centerY());
        log("annotation: ouverture de la liste (" + ok + ")");
        aScrolls = 0;
        aStuck = 0;
        aScrollDir = -1;
        aLastOpts = "";
        aGo(A_AN_PICK, 600);
    }

    /** Choix dans une liste ouverte (annotation si ann=true, sinon code releve). */
    private void aPick(AccessibilityNodeInfo root, boolean inApp, long now, boolean ann) {
        String wanted = ann ? (aOvAnn != null ? aOvAnn : aRow.annotation)
                : (aOvAnom != null ? aOvAnom : aRow.anomaly);
        java.util.ArrayList<AccessibilityNodeInfo> nodes = new java.util.ArrayList<>();
        java.util.ArrayList<String> texts = new java.util.ArrayList<>();
        if (inApp) collectOptions(root, nodes, texts);
        int minOpts = 3;
        if (ann && texts.size() < 3 && inApp) {
            // La liste Annotation n'est pas numerotee: on prend les textes apparus apres l'ouverture
            nodes.clear();
            texts.clear();
            collectNew(root, nodes, texts, 0);
            minOpts = 2;
        }
        if (texts.size() >= minOpts) {
            int idx = Meters.bestMatch(texts, wanted);
            if (idx >= 0) {
                aOptKey = Meters.stripCode(Meters.norm(texts.get(idx)));
                boolean ok = tapNode(nodes.get(idx));
                if (!ok) ok = clickUp(nodes.get(idx));
                log((ann ? "annotation" : "anomalie") + ": choix " + texts.get(idx) + " (" + ok + ")");
                aGo(ann ? A_AN_CHECK : A_ZL_CHECK, 700);
                return;
            }
            // On remonte d'abord vers le haut de la liste (elle peut s'ouvrir au milieu), puis on descend
            String sig = texts.toString();
            if (aScrolls > 0 && sig.equals(aLastOpts)) aStuck++;
            else aStuck = 0;
            if (aStuck >= 2 && aScrollDir < 0) {
                aScrollDir = 1;
                aStuck = 0;
                aLastOpts = "";
            }
            if (aStuck < 2 && aScrolls < 40) {
                aLastOpts = sig;
                if (aScrollDir < 0) scrollBackward(root);
                else scrollForward(root);
                aScrolls++;
                aPost(700);
                return;
            }
            log("options vues: " + texts);
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
                    + " ne s'ouvre pas (options vues: " + texts + ")");
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
        aGo(A_EN_TYPE, d(300));
    }

    private void aEnType(AccessibilityNodeInfo root, boolean inApp, long now) {
        AccessibilityNodeInfo f = inApp ? findId(root, "modelInputIdx", 0) : null;
        KeyboardService k = KeyboardService.instance;
        if (f != null && isEmptyField(f) && k != null && k.typeText(aValue)) {
            log("index: saisie de " + aValue);
            aGo(A_EN_GO, d(300));
            return;
        }
        // le clavier peut tarder (surtout apres le choix de l'annotation): on le redemande
        if (f != null && aTries < 10 && now - aT > 400L * (aTries + 1)) {
            aTries++;
            if (aTries % 2 == 1) tapNode(f);
            if (k != null) k.showNow();
        }
        if (now - aT > 7000) {
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
            if (aRow != null && aValue != null && !aValue.isEmpty()) aRow.newIdx = aValue;
            aMark(Meters.SENT, msg);
            if (aSingle) {
                autoStop("FIN: compteur suivant atteint");
                return;
            }
            aRetries = 0;
            aGo(A_READ, d(500));
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
        aStuck = 0;
        aScrollDir = -1;
        aLastOpts = "";
        aGo(A_ZL_PICK, 600);
    }

    private void startPhotoClosure() {
        busy = true;
        closureFlow = true;
        aWaitSubmit = true;
        photoPhase = 1;
        photoBackT = 0;
        photoTaps = 0;
        photoSnap = 0;
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
                aMark(Meters.SENT, "FIN OK: anomalie "
                        + (aOvAnom != null ? aOvAnom : aRow.anomaly));
                if (aSingle) {
                    autoStop("FIN: compteur suivant atteint");
                    return;
                }
                aRetries = 0;
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
            if (Meters.norm(t).replace(" ", "").length() >= 6 && Meters.find(k) != null) return k;
        }
        return "";
    }

    /** Textes visibles apparus depuis l'ouverture de la liste (absents de l'ecran de fond). */
    private void collectNew(AccessibilityNodeInfo n, java.util.ArrayList<AccessibilityNodeInfo> nodes,
                            java.util.ArrayList<String> texts, int depth) {
        if (n == null || depth > 30) return;
        CharSequence t = n.getText();
        if (t != null && n.isVisibleToUser()) {
            String s = t.toString().trim();
            String k = Meters.norm(s);
            if (!s.isEmpty() && s.length() < 60 && !k.isEmpty() && !aBefore.contains(k)) {
                nodes.add(n);
                texts.add(s);
            }
        }
        for (int i = 0; i < n.getChildCount(); i++) collectNew(n.getChild(i), nodes, texts, depth + 1);
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

    private boolean scrollBackward(AccessibilityNodeInfo n) {
        return scrollBackward(n, 0);
    }
    private boolean scrollBackward(AccessibilityNodeInfo n, int depth) {
        if (n == null || depth > 30) return false;
        if (n.isScrollable()) {
            return n.performAction(AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD);
        }
        for (int i = 0; i < n.getChildCount(); i++) {
            if (scrollBackward(n.getChild(i), depth + 1)) return true;
        }
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
        boolean ltr = (next == (swipeSign > 0));
        float x1 = d.widthPixels * (ltr ? 0.2f : 0.8f);
        float x2 = d.widthPixels * (ltr ? 0.8f : 0.2f);
        Path p = new Path();
        p.moveTo(x1, y);
        p.lineTo(x2, y);
        GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 300);
        return dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
    }

    /** Glissade faite sur le bandeau du clavier: meme sens du doigt dans l'application (gauche/droite). */
    public void swipeFinger(final boolean leftToRight) {
        handler.post(() -> {
            if (busy || autoRunning) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return;
            DisplayMetrics d = dm();
            float y = d.heightPixels * 0.22f;
            AccessibilityNodeInfo m = findText(root, "compteur :", 0);
            if (m != null) {
                Rect r = new Rect();
                m.getBoundsInScreen(r);
                if (r.height() > 0) y = r.centerY();
            }
            float x1 = d.widthPixels * (leftToRight ? 0.2f : 0.8f);
            float x2 = d.widthPixels * (leftToRight ? 0.8f : 0.2f);
            Path p = new Path();
            p.moveTo(x1, y);
            p.lineTo(x2, y);
            GestureDescription.StrokeDescription st = new GestureDescription.StrokeDescription(p, 0, 300);
            boolean ok = dispatchGesture(new GestureDescription.Builder().addStroke(st).build(), null, null);
            log("bandeau: glissade " + (leftToRight ? "gauche>droite" : "droite>gauche") + " (" + ok + ")");
            skipTrans = true;
            refreshStrip();
        });
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
    // Sur un telephone lent, on espace les mises a jour du bandeau (mesure de la duree reelle)
    private long stripDelay = 300;
    private final Runnable stripUpdateTimed = new Runnable() {
        @Override
        public void run() {
            long st = SystemClock.uptimeMillis();
            stripUpdate.run();
            long d = SystemClock.uptimeMillis() - st;
            stripDelay = Math.max(300, Math.min(1500, d * 4));
        }
    };
    private String curKey = "";
    private String prevKey = "";
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
            Meters.Row r = id.isEmpty() ? null : lookupRow(root, id);
            // alerte: un compteur du tableau, place juste apres le precedent, n'a pas ete propose
            if (!id.isEmpty()) lastView = false;
            if (!id.isEmpty() && !id.equals(curKey)) {
                if (skipTrans) skipTrans = false;
                else if (!busy && !autoRunning && gotoKey == null) noteTransition(curKey, id);
                prevKey = curKey;
                curKey = id;
            }
            String alertTxt = "", alertNum = "";
            try {
                if (r != null && !r.extra && !prevKey.isEmpty()) {
                    Meters.Row p = Meters.find(prevKey);
                    if (p != null && !p.extra) {
                        int ip = Meters.ROWS.indexOf(p), ic = Meters.ROWS.indexOf(r);
                        if (ip >= 0 && ic > ip + 1 && ic - ip <= 6) {
                            int n = 0;
                            Meters.Row first = null;
                            for (int j = ip + 1; j < ic; j++) {
                                Meters.Row x = Meters.ROWS.get(j);
                                if (x.extra || x.status != Meters.NEW || !Meters.isActive(x)) continue;
                                n++;
                                if (first == null) first = x;
                            }
                            if (first != null) {
                                alertNum = first.key;
                                alertTxt = first.key + (n > 1 ? "  +" + (n - 1) : "");
                            }
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
            k.setStripAlert(alertTxt, alertNum);
            boolean holdNow = holdT != 0 && SystemClock.uptimeMillis() - holdT < 30000;
            k.setCamHint(holdNow);
            if (r != null) {
                String name = shown.isEmpty() ? r.compteur : shown;
                String note = "";
                if (!r.warn.isEmpty()) note = "\u26A0 compteur different (tableau " + r.compteur + ") : toucher = confirmer";
                if (holdNow) note = note.isEmpty() ? "! valider a la main" : note + " | ! valider a la main";
                int oflag = 0;
                try {
                    String ds = r.oldIdx.replaceAll("\\D", ""), dn = r.newIdx.replaceAll("\\D", "");
                    if (!ds.isEmpty() && !dn.isEmpty() && ds.length() < 12 && dn.length() < 12) {
                        long ov = Long.parseLong(ds), nv = Long.parseLong(dn);
                        if (nv < ov) {
                            oflag = 2;
                            note = note.isEmpty() ? "\u26A0 ancien > nouveau : ancien douteux ?"
                                    : note + " | ancien > nouveau";
                        } else if (outOfRange(ov, nv)) {
                            oflag = 1;
                        }
                    }
                } catch (Throwable ignored) {
                }
                k.setOldFlag(oflag);
                k.setStripData(name, r.oldIdx, r.newIdx, r.status == Meters.SUSPECT, note);
            } else if (!shown.isEmpty()) {
                k.setOldFlag(0);
                String z = shown.trim();
                int zi = 0;
                while (zi < z.length() - 1 && z.charAt(zi) == '0') zi++;
                String txt = z.substring(zi) + "   |   (absent du tableau)";
                if (holdNow) txt = txt + "   |   ! valider a la main";
                k.setStrip(txt);
            } else {
                k.setStrip("");
            }
        }
    };

    public void refreshStrip() {
        if (stripPending) return;
        stripPending = true;
        handler.postDelayed(stripUpdate, 120);
    }

    private boolean awayFromApp = false;
    private final Runnable refocus = new Runnable() {
        @Override
        public void run() {
            if (busy || autoRunning) return;
            AccessibilityNodeInfo root = getRootInActiveWindow();
            if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return;
            AccessibilityNodeInfo field = findId(root, "modelInputIdx", 0);
            if (field == null) return;
            if (findText(root, "Photo obligatoire", 0) != null || findAlert(root) != null) return;
            KeyboardService k = KeyboardService.instance;
            if (!field.isFocused()) {
                boolean t = tapNode(field);
                log("retour dans l'application: champ index touche (" + t + ")");
            }
            if (k != null) k.showNow();
        }
    };

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        CharSequence p = event.getPackageName();
        if (p != null && !APP.equals(p.toString())) {
            String ps = p.toString();
            if (!ps.equals(getPackageName()) && !ps.equals("com.android.systemui")
                    && !ps.contains("inputmethod")) {
                awayFromApp = true;
            }
            if (ps.toLowerCase().contains("camera") && camKey.isEmpty() && !curKey.isEmpty()) {
                camKey = curKey;
                camOpenT = SystemClock.uptimeMillis();
            }
            // photo prise a la main avec l'appareil photo: copie des que l'ecran de validation apparait
            if (photoProof && photoPhase == 0 && !busy && !manualCheckPending
                    && ps.toLowerCase().contains("camera") && !lastMeterId.isEmpty()
                    && SystemClock.uptimeMillis() - lastMeterT < 180000) {
                manualCheckPending = true;
                handler.postDelayed(manualCheck, 250);
            }
            return;
        }
        if (p == null) return;
        if (!camKey.isEmpty()) {
            if (SystemClock.uptimeMillis() - camOpenT > 2500) markPhoto(camKey);
            camKey = "";
        }
        manualSnapped = false;
        if (photoProof && !trackPending) {
            trackPending = true;
            handler.postDelayed(trackMeter, 400);
        }
        if (awayFromApp) {
            awayFromApp = false;
            handler.removeCallbacks(refocus);
            handler.postDelayed(refocus, 900);
        }
        if (KeyboardService.stripOn && !stripPending) {
            stripPending = true;
            handler.postDelayed(stripUpdateTimed, stripDelay);
        }
        if (!shPending) {
            shPending = true;
            handler.postDelayed(shadowRec, shDelay);
        }
    }

    // ---------- Enregistreur de saisie: aucun index tape ne doit se perdre ----------

    private boolean shPending = false;
    private long shDelay = 150;
    private String shKey = "";
    private String shBase = "";
    private String shLast = "";

    /**
     * Surveille le champ "nouvel index" de l'application d'origine. Chaque valeur tapee (a la main,
     * avec ou sans notre bouton VALIDER) est copiee tout de suite dans le tableau; si l'utilisateur
     * efface et retape, la derniere valeur remplace la precedente.
     */
    private final Runnable shadowRec = new Runnable() {
        @Override
        public void run() {
            shPending = false;
            long st = SystemClock.uptimeMillis();
            try {
                if (busy || autoRunning || photoPhase != 0 || gotoKey != null || syncPhase != 0) return;
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return;
                AccessibilityNodeInfo field = findId(root, "modelInputIdx", 0);
                if (field == null) return;
                String txt = textOf(field).replace(" ", "");
                String id = readMeterId(root);
                if (id.isEmpty()) return;
                if (!id.equals(shKey)) {
                    shKey = id;
                    shBase = txt;
                    shLast = "";
                }
                if (txt.isEmpty() || !txt.matches("\\d{1,9}")) return;
                if (txt.equals(shLast)) return;
                if (shLast.isEmpty() && txt.equals(shBase)) return; // valeur deja la a l'arrivee
                shLast = txt;
                ensureLoaded(ScanService.this);
                Meters.Row r = lookupRow(root, id);
                if (r == null) {
                    String label = meterLabel(root);
                    int i = label.indexOf(':');
                    String shown = i >= 0 ? label.substring(i + 1).trim() : label;
                    r = Meters.addExtra(shown.isEmpty() ? id : shown, id);
                }
                if (txt.equals(r.newIdx)) return;
                r.newIdx = txt;
                if (!r.extra && r.status != Meters.UNLISTED) lastEntered = r;
                if (r.status != Meters.SENT) r.note = "saisi: " + txt + " (a confirmer)";
                saveTable(ScanService.this);
                log("SAISIE notee: " + id + " = " + txt);
            } catch (Throwable ignored) {
            } finally {
                long d = SystemClock.uptimeMillis() - st;
                shDelay = Math.max(150, Math.min(800, d * 3));
            }
        }
    };

    // ---------- Photos prises a la main ----------

    private String lastMeterId = "";
    private long lastMeterT = 0;
    private boolean trackPending = false;
    private boolean manualCheckPending = false;
    private boolean manualSnapped = false;

    /** Memorise le compteur affiche dans l'application d'origine (pour les photos prises a la main). */
    private final Runnable trackMeter = new Runnable() {
        @Override
        public void run() {
            trackPending = false;
            try {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null || !APP.equals(String.valueOf(root.getPackageName()))) return;
                if (findId(root, "modelInputIdx", 0) == null) return;
                String id = readMeterId(root);
                if (!id.isEmpty()) {
                    lastMeterId = id;
                    lastMeterT = SystemClock.uptimeMillis();
                    // infos du compteur lues a l'ecran: pour completer le rapport Excel
                    String[] inf = scrapeInfo(root);
                    Meters.Row row = Meters.find(id);
                    if (row != null) {
                        Meters.fillGaps(row, inf);
                    } else {
                        if (Meters.SEEN.size() > 3000) Meters.SEEN.clear();
                        Meters.SEEN.put(id, inf);
                        if (!infoLogged && Meters.SEEN.size() <= 3) {
                            infoLogged = true;
                            log("INFO ecran (compteur hors tableau): " + screenSummary());
                        }
                    }
                }
            } catch (Throwable ignored) {
            }
        }
    };

    private boolean infoLogged = false;

    private static final String[] L_NAME = {"NOM", "CLIENT", "NOM CLIENT", "NOM DU CLIENT", "TITULAIRE"};
    private static final String[] L_ADDR = {"ADRESSE", "ADR", "RUE"};
    private static final String[] L_CONTRAT = {"CONTRAT", "N CONTRAT", "NUM CONTRAT", "N CLIENT",
            "POLICE", "ABONNEMENT", "N ABONNEMENT"};
    private static final String[] L_OLD = {"ANCIEN INDEX", "INDEX PRECEDENT", "DERNIER INDEX",
            "ANCIEN", "INDEX ANTERIEUR"};

    private static boolean inList(String[] a, String x) {
        for (String s : a) if (s.equals(x)) return true;
        return false;
    }

    private static boolean isLabel(String k) {
        return inList(L_NAME, k) || inList(L_ADDR, k) || inList(L_CONTRAT, k) || inList(L_OLD, k)
                || k.equals("ANNOTATION") || k.equals("CODE RELEVE") || k.equals("LOCALISATION")
                || k.equals("INDEX") || k.equals("NOUVEL INDEX") || k.equals("COMPTEUR")
                || k.equals("GPS") || k.equals("ADRESSE");
    }

    /** Valeur affichee juste apres un des libelles (ex: NOM, ADRESSE), parmi les 2 textes suivants. */
    private String valueAfter(java.util.ArrayList<String> t, String[] labels) {
        for (int i = 0; i + 1 < t.size(); i++) {
            if (!inList(labels, Meters.norm(t.get(i)))) continue;
            for (int j = i + 1; j < t.size() && j <= i + 2; j++) {
                String v = t.get(j).trim();
                String k = Meters.norm(v);
                if (k.isEmpty() || isLabel(k)) continue;
                return v;
            }
        }
        return "";
    }

    /** {nom, adresse, contrat, ancien index} lus sur l'ecran du compteur (vide si introuvable). */
    private String[] scrapeInfo(AccessibilityNodeInfo root) {
        java.util.ArrayList<String> t = new java.util.ArrayList<>();
        collectTexts(root, t, 0);
        String old = Meters.cleanNumber(valueAfter(t, L_OLD));
        return new String[]{valueAfter(t, L_NAME), valueAfter(t, L_ADDR), valueAfter(t, L_CONTRAT),
                old.matches("\\d{1,9}") ? old : ""};
    }

    private final Runnable manualCheck = new Runnable() {
        @Override
        public void run() {
            manualCheckPending = false;
            try {
                if (!photoProof || photoPhase != 0 || busy) return;
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root == null) return;
                String pkg = String.valueOf(root.getPackageName());
                if (APP.equals(pkg) || !pkg.toLowerCase().contains("camera")) return;
                AccessibilityNodeInfo cf = findConfirm(root, 0);
                if (cf == null) {
                    manualSnapped = false; // reprise de la photo: on pourra recopier
                    return;
                }
                if (manualSnapped) return;
                manualSnapped = true;
                ensureLoaded(ScanService.this);
                snapProofFor(Meters.find(lastMeterId), lastMeterId);
            } catch (Throwable t) {
                log("PHOTO: copie manuelle echouee (" + t.getMessage() + ")");
            }
        }
    };

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
