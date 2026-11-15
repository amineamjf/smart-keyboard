package com.smartkeyboard.releve;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Voix hors ligne: l'utilisateur ecrit en arabe ce qu'il va dire, le prononce avec de courtes pauses entre les mots,
 * et l'application apprend chaque mot avec sa voix. En mode test, elle reconnait les mots et en tire les chiffres.
 * Tout reste dans le telephone: aucune connexion, aucun service Google.
 */
public class VoiceTrainActivity extends Activity {

    private static final String[] PHRASES = {
            "واحد جوج تلاتة ربعة خمسة ستة سبعة تمنية تسعود عشرة",
            "طناش تلطاش ربعطاش خمستاش ستاش سبعطاش تمنطاش تسعطاش",
            "عشرين تلاتين ربعين خمسين ستين سبعين تمانين تسعين",
            "ميا ميتين تلتمية ربعمية خمسمية ستمية سبعمية تمنمية تسعمية",
            "ألف ألفين تلاتة ألاف صفر",
            "وواحد وجوج وتلاتة وربعة وخمسة وستة وسبعة وتمنية وتسعود",
            "وعشرين وتلاتين وربعين وخمسين وستين وسبعين وتمانين وتسعين",
            "تلتمية وخمسين ، ربعمية وخمسة وخمسين",
            "واحد وعشرين ، جوج وتلاتين ، تلاتة وربعين"
    };

    private final Handler ui = new Handler(Looper.getMainLooper());
    private EditText phrase;
    private TextView status, result, info;
    private ProgressBar level;
    private Button rec, modeBtn, learnBtn;
    private boolean testMode = false;
    private boolean cmdMode = false;
    private int cmdIdx = 0;
    private Button cmdBtn;
    private LinearLayout row1;
    /** Ordres vocaux: identifiant (utilise par le clavier) et nom affiche. */
    static final String[][] COMMANDS = {
            {"local", "Local inhabit\u00E9"},
            {"bloque", "Compteur bloqu\u00E9"},
            {"inaccessible", "Compteur inaccessible"},
            {"photo", "Prendre la photo"},
            {"placer", "Placer compteur"},
            {"annuler", "Annuler (d\u00E9placement)"},
            {"ok", "OK (valider)"},
            {"efface", "Effacer"},
            {"rechercher", "Rechercher"},
            {"dernier", "Dernier"},
            {"options", "Options"},
            {"ajouter", "Ajouter un compteur"},
            {"apprendre", "Apprendre l'ordre"},
            {"analyser_conso", "Analyser conso"},
            {"a_realiser", "\u00C0 r\u00E9aliser"},
            {"journal", "Lire le journal"},
            {"stop", "Stop"}};
    private volatile boolean recording = false;
    private Thread recThread;
    private int phraseIdx = 0;

    private short[] lastPcm;
    private List<VoiceCore.Span> lastSpans = new ArrayList<>();

    private int dp(int v) {
        return (int) (v * getResources().getDisplayMetrics().density + 0.5f);
    }

    private GradientDrawable shape(int color, int r) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(color);
        g.setCornerRadius(dp(r));
        return g;
    }

    private Button pill(String label, int bg, int fg) {
        Button b = new Button(this);
        b.setText(label);
        b.setAllCaps(false);
        b.setTextColor(fg);
        b.setTextSize(15);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setBackground(shape(bg, 16));
        b.setStateListAnimator(null);
        b.setPadding(dp(12), dp(10), dp(12), dp(10));
        return b;
    }

    private TextView text(String s, int sp, int color, boolean bold) {
        TextView t = new TextView(this);
        t.setText(s);
        t.setTextSize(sp);
        t.setTextColor(color);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        return t;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF4F6FB);
        root.setPadding(dp(18), dp(28), dp(18), dp(12));

        root.addView(text("Voix hors ligne", 24, 0xFF0F172A, true));
        info = text("", 13, 0xFF64748B, false);
        root.addView(info);

        modeBtn = pill("", 0xFFEAF0FF, 0xFF2563EB);
        modeBtn.setOnClickListener(v -> {
            if (!testMode && !cmdMode) {
                testMode = true;
            } else if (testMode) {
                testMode = false;
                cmdMode = true;
            } else {
                cmdMode = false;
            }
            status.setText("");
            result.setText("");
            refresh();
        });
        LinearLayout.LayoutParams mp = new LinearLayout.LayoutParams(-1, -2);
        mp.topMargin = dp(10);
        root.addView(modeBtn, mp);

        phrase = new EditText(this);
        phrase.setHint("Écrivez en arabe ce que vous allez dire");
        phrase.setTextSize(20);
        phrase.setGravity(Gravity.CENTER);
        phrase.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        phrase.setBackground(shape(0xFFFFFFFF, 14));
        phrase.setPadding(dp(12), dp(12), dp(12), dp(12));
        phrase.setText(PHRASES[0]);
        LinearLayout.LayoutParams pp = new LinearLayout.LayoutParams(-1, -2);
        pp.topMargin = dp(8);
        root.addView(phrase, pp);

        row1 = new LinearLayout(this);
        Button next = pill("Phrase suivante", 0xFFFFFFFF, 0xFF0F172A);
        next.setOnClickListener(v -> {
            phraseIdx = (phraseIdx + 1) % PHRASES.length;
            phrase.setText(PHRASES[phraseIdx]);
        });
        Button clear = pill("Effacer le texte", 0xFFFFFFFF, 0xFF0F172A);
        clear.setOnClickListener(v -> phrase.setText(""));
        LinearLayout.LayoutParams r1 = new LinearLayout.LayoutParams(0, -2, 1f);
        r1.rightMargin = dp(6);
        row1.addView(next, r1);
        row1.addView(clear, new LinearLayout.LayoutParams(0, -2, 1f));
        LinearLayout.LayoutParams rl1 = new LinearLayout.LayoutParams(-1, -2);
        rl1.topMargin = dp(6);
        root.addView(row1, rl1);

        cmdBtn = pill("", 0xFFFFFFFF, 0xFF0F172A);
        cmdBtn.setOnClickListener(v -> {
            cmdIdx = (cmdIdx + 1) % COMMANDS.length;
            refresh();
        });
        LinearLayout.LayoutParams cbp = new LinearLayout.LayoutParams(-1, -2);
        cbp.topMargin = dp(8);
        root.addView(cmdBtn, cbp);

        rec = pill("🎤  Enregistrer", 0xFF2563EB, 0xFFFFFFFF);
        rec.setOnClickListener(v -> toggleRecord());
        LinearLayout.LayoutParams rp = new LinearLayout.LayoutParams(-1, dp(56));
        rp.topMargin = dp(10);
        root.addView(rec, rp);

        level = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        level.setMax(100);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(-1, dp(8));
        lp.topMargin = dp(8);
        root.addView(level, lp);

        status = text("", 14, 0xFF334155, false);
        root.addView(status);

        learnBtn = pill("Apprendre ceci (corrigé)", 0xFF0F172A, 0xFFFFFFFF);
        learnBtn.setOnClickListener(v -> learnLast());
        LinearLayout.LayoutParams lbp = new LinearLayout.LayoutParams(-1, -2);
        lbp.topMargin = dp(8);
        root.addView(learnBtn, lbp);

        ScrollView sv = new ScrollView(this);
        result = text("", 17, 0xFF0F172A, false);
        result.setPadding(dp(4), dp(10), dp(4), dp(10));
        sv.addView(result);
        root.addView(sv, new LinearLayout.LayoutParams(-1, 0, 1f));

        LinearLayout row2 = new LinearLayout(this);
        Button eval = pill("Évaluer", 0xFFEAF0FF, 0xFF2563EB);
        eval.setOnClickListener(v -> evaluate());
        Button words = pill("Mots appris", 0xFFEAF0FF, 0xFF2563EB);
        words.setOnClickListener(v -> showWords());
        Button wipe = pill("Tout effacer", 0xFFFAC9C9, 0xFF8E1B1B);
        wipe.setOnClickListener(v -> confirmWipe());
        for (Button x : new Button[]{eval, words, wipe}) {
            LinearLayout.LayoutParams q = new LinearLayout.LayoutParams(0, -2, 1f);
            q.rightMargin = dp(4);
            row2.addView(x, q);
        }
        root.addView(row2, new LinearLayout.LayoutParams(-1, -2));
        setContentView(root);
        refresh();
    }

    private void refresh() {
        VoiceModel m = VoiceStore.get(this);
        info.setText(m.counts().size() + " mots appris · " + m.total() + " enregistrements · 100 % hors ligne");
        modeBtn.setText(cmdMode ? "Mode : ORDRES  (toucher pour apprendre)"
                : testMode ? "Mode : TEST  (toucher pour les ordres)" : "Mode : APPRENTISSAGE  (toucher pour tester)");
        phrase.setVisibility(cmdMode ? View.GONE : View.VISIBLE);
        row1.setVisibility(cmdMode ? View.GONE : View.VISIBLE);
        cmdBtn.setVisibility(cmdMode ? View.VISIBLE : View.GONE);
        cmdBtn.setText("Ordre : " + COMMANDS[cmdIdx][1] + "  (\u00D7" + m.count(VoiceModel.CMD + COMMANDS[cmdIdx][0])
                + ")  \u2014 toucher pour changer");
        learnBtn.setVisibility(testMode && lastPcm != null ? View.VISIBLE : View.GONE);
        if (status.getText().length() == 0) {
            status.setText(cmdMode
                    ? "Choisissez l'ordre, appuyez sur Enregistrer et dites-le comme vous le direz sur le terrain (3 fois au moins)."
                    : testMode
                    ? "Dites des mots avec une courte pause entre chacun, l'application les reconnaît."
                    : "Dites exactement les mots écrits, avec une courte pause entre chacun.");
        }
    }

    // ------------------------------------------------------------ enregistrement

    private void toggleRecord() {
        if (recording) {
            recording = false;
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        startRecording();
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        if (code == 7 && res.length > 0 && res[0] == PackageManager.PERMISSION_GRANTED) startRecording();
        else status.setText("Autorisez le micro pour continuer.");
    }

    private void startRecording() {
        recording = true;
        rec.setText("⏹  Arrêter");
        status.setText("Parlez… (arrêt automatique après un silence)");
        result.setText("");
        recThread = new Thread(this::recordLoop);
        recThread.start();
    }

    private void recordLoop() {
        int min = AudioRecord.getMinBufferSize(VoiceCore.RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(min, 8192);
        AudioRecord ar = null;
        short[] all = new short[VoiceCore.RATE * 20];
        int n = 0;
        try {
            ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceCore.RATE,
                    AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, bufSize);
            if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                post("Micro indisponible.");
                recording = false;
                return;
            }
            ar.startRecording();
            short[] buf = new short[1600];
            double floor = -1;
            boolean spoke = false;
            int quiet = 0, tick = 0;
            while (recording && n + buf.length < all.length) {
                int r = ar.read(buf, 0, buf.length);
                if (r <= 0) continue;
                double s = 0;
                for (int i = 0; i < r; i++) {
                    all[n + i] = buf[i];
                    s += (double) buf[i] * buf[i];
                }
                n += r;
                double db = 10 * Math.log10(s / r + 1.0);
                if (floor < 0) floor = db;
                else if (n < VoiceCore.RATE / 2) floor = Math.min(floor, db);
                boolean loud = db > floor + 10;
                if (loud) {
                    spoke = true;
                    quiet = 0;
                } else if (spoke) {
                    quiet += r;
                }
                if (spoke && quiet > VoiceCore.RATE * 3 / 2) break;
                final int lv = (int) Math.max(0, Math.min(100, (db - floor) * 3));
                if (++tick % 2 == 0) ui.post(() -> level.setProgress(lv));
            }
        } catch (Throwable t) {
            post("Erreur micro : " + t.getMessage());
        } finally {
            if (ar != null) {
                try {
                    ar.stop();
                } catch (Throwable ignore) {
                }
                ar.release();
            }
        }
        recording = false;
        final short[] pcm = java.util.Arrays.copyOf(all, n);
        ui.post(() -> finished(pcm));
    }

    private void post(String s) {
        ui.post(() -> status.setText(s));
    }

    private void finished(short[] pcm) {
        rec.setText("🎤  Enregistrer");
        level.setProgress(0);
        List<VoiceCore.Span> spans = VoiceCore.split(pcm, 220, 90);
        lastPcm = pcm;
        lastSpans = spans;
        if (spans.isEmpty()) {
            status.setText("Rien d'audible détecté. Rapprochez le micro et réessayez.");
            refresh();
            return;
        }
        if (cmdMode) learnCommand();
        else if (testMode) recognize();
        else learnFrom(phrase.getText().toString());
        refresh();
    }

    // ------------------------------------------------------------------ logique

    private static List<String> tokens(String t) {
        List<String> out = new ArrayList<>();
        boolean waw = false;
        for (String w : t.replace('\u060C', ' ').replace(',', ' ').trim().split("\\s+")) {
            if (w.isEmpty()) continue;
            String n = DarijaNumbers.norm(w);
            if (n == null || n.isEmpty()) n = w;
            if (n.equals("\u0648")) { // "و" seul: se prononce colle au mot suivant
                waw = true;
                continue;
            }
            out.add(waw ? "\u0648" + n : n);
            waw = false;
        }
        if (waw) out.add("\u0648");
        return out;
    }

    private void learnFrom(String typed) {
        List<String> tk = tokens(typed);
        if (tk.isEmpty()) {
            status.setText("Écrivez d'abord les mots que vous allez dire.");
            return;
        }
        if (tk.size() != lastSpans.size()) {
            List<VoiceCore.Span> alt = VoiceCore.splitTo(lastPcm, tk.size());
            if (alt != null) lastSpans = alt;
        }
        if (tk.size() != lastSpans.size()) {
            status.setText("⚠ J'ai entendu " + lastSpans.size() + " mots mais vous en avez écrit " + tk.size()
                    + ". Faites une courte pause entre les mots, puis recommencez.");
            result.setText("");
            return;
        }
        VoiceModel m = VoiceStore.get(this);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < tk.size(); i++) {
            VoiceCore.Span s = lastSpans.get(i);
            m.add(tk.get(i), VoiceCore.mfcc(lastPcm, s.from, s.to));
            sb.append(tk.get(i)).append("  (").append(m.count(tk.get(i))).append(")\n");
        }
        VoiceStore.save(this);
        status.setText("✓ Appris : " + tk.size() + " mots");
        result.setText(sb.toString());
    }

    private void learnCommand() {
        List<VoiceCore.Span> one = VoiceCore.split(lastPcm, 700, 90);
        if (one.isEmpty()) {
            status.setText("Rien d'audible d\u00E9tect\u00E9.");
            return;
        }
        VoiceModel m = VoiceStore.get(this);
        String id = VoiceModel.CMD + COMMANDS[cmdIdx][0];
        m.add(id, VoiceCore.mfcc(lastPcm, one.get(0).from, one.get(one.size() - 1).to));
        VoiceStore.save(this);
        status.setText("\u2713 Ordre appris : " + COMMANDS[cmdIdx][1] + " (" + m.count(id) + " enregistrements)");
        result.setText("");
    }

    private void learnLast() {
        if (lastPcm == null) return;
        learnFrom(phrase.getText().toString());
        refresh();
    }

    private void recognize() {
        VoiceModel m = VoiceStore.get(this);
        if (m.total() == 0) {
            status.setText("Rien appris pour l'instant : passez en mode apprentissage.");
            return;
        }
        if (lastSpans.size() <= 4) {
            List<VoiceCore.Span> one = VoiceCore.split(lastPcm, 700, 90);
            if (!one.isEmpty()) {
                VoiceModel.Match cm = m.classifyCommand(
                        VoiceCore.mfcc(lastPcm, one.get(0).from, one.get(one.size() - 1).to));
                if (cm.sure()) {
                    String nm = cm.label;
                    for (String[] c : COMMANDS) if ((VoiceModel.CMD + c[0]).equals(cm.label)) nm = c[1];
                    result.setText("Ordre reconnu : " + nm);
                    status.setText("(dans le clavier, cet ordre serait ex\u00E9cut\u00E9)");
                    return;
                }
            }
        }
        StringBuilder sb = new StringBuilder();
        StringBuilder joined = new StringBuilder();
        boolean allSure = true;
        for (VoiceCore.Span s : lastSpans) {
            VoiceModel.Match r = m.classify(VoiceCore.mfcc(lastPcm, s.from, s.to));
            if (r.label.isEmpty()) {
                sb.append("? ");
                allSure = false;
                continue;
            }
            joined.append(r.label).append(' ');
            sb.append(r.label).append(r.sure() ? " " : "? ");
            if (!r.sure()) allSure = false;
        }
        phrase.setText(joined.toString().trim());
        StringBuilder out = new StringBuilder();
        out.append("Mots : ").append(sb).append("\n\n");
        try {
            DarijaNumbers.Result nr = DarijaNumbers.parse(joined.toString().trim());
            if (nr != null && nr.ok()) {
                out.append("Chiffres : ").append(nr.digits);
                if (nr.risky) out.append("   ⚠ à vérifier");
            } else {
                out.append("Chiffres : —  ").append(nr == null ? "" : nr.error);
            }
        } catch (Throwable t) {
            out.append("Chiffres : —");
        }
        result.setText(out.toString());
        status.setText(allSure
                ? "Reconnu. Si un mot est faux, corrigez le texte en haut puis appuyez sur « Apprendre ceci »."
                : "Un point d'interrogation = hésitation. Corrigez le texte puis « Apprendre ceci ».");
    }

    private void evaluate() {
        VoiceModel m = VoiceStore.get(this);
        List<String> errs = new ArrayList<>();
        int[] r = m.selfTest(errs);
        StringBuilder sb = new StringBuilder();
        if (r[1] == 0) {
            sb.append("Pas assez d'exemples : dites chaque mot au moins 2 fois.");
        } else {
            sb.append("Précision sur vos enregistrements : ").append(r[0] * 100 / r[1]).append(" % (")
                    .append(r[0]).append("/").append(r[1]).append(")\n");
            if (!errs.isEmpty()) {
                sb.append("\nConfusions :\n");
                for (int i = 0; i < errs.size() && i < 25; i++) sb.append(errs.get(i)).append('\n');
                sb.append("\nPour les corriger : répétez ces mots-là davantage.");
            }
            List<String> few = new ArrayList<>();
            for (Map.Entry<String, Integer> e : m.counts().entrySet()) if (e.getValue() < 2) few.add(e.getKey());
            if (!few.isEmpty()) sb.append("\n\nUn seul exemple (non testés) : ").append(join(few));
        }
        result.setText(sb.toString());
        status.setText("");
    }

    private static String join(List<String> l) {
        StringBuilder sb = new StringBuilder();
        for (String s : l) sb.append(s).append("  ");
        return sb.toString().trim();
    }

    private void showWords() {
        VoiceModel m = VoiceStore.get(this);
        StringBuilder sb = new StringBuilder();
        for (Map.Entry<String, Integer> e : m.counts().entrySet()) {
            sb.append(e.getKey()).append("  ×").append(e.getValue()).append('\n');
        }
        result.setText(sb.length() == 0 ? "Aucun mot appris." : sb.toString());
        status.setText("");
    }

    private void confirmWipe() {
        new AlertDialog.Builder(this)
                .setTitle("Tout effacer ?")
                .setMessage("Tous les mots appris seront supprimés.")
                .setPositiveButton("Effacer", (d, w) -> {
                    VoiceStore.get(this).clear();
                    VoiceStore.save(this);
                    result.setText("");
                    refresh();
                })
                .setNegativeButton("Annuler", null)
                .show();
    }

    @Override
    protected void onPause() {
        recording = false;
        super.onPause();
    }
}
