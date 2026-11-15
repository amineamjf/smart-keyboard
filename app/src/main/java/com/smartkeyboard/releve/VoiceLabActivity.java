package com.smartkeyboard.releve;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.util.ArrayList;

/**
 * Laboratoire vocal (essai): dire un nombre en darija, voir ce que le telephone entend et les chiffres obtenus.
 * Ne touche ni au tableau ni au clavier: sert seulement a mesurer la precision avant toute integration.
 */
public class VoiceLabActivity extends Activity {

    private static final String[] LOCALES = {"ar-MA", "ar", "fr-FR"};
    private static final String[] LOCALE_NAMES = {"Arabe (Maroc)", "Arabe", "Français"};

    private SpeechRecognizer sr;
    private TextView status, big, out;
    private Button mic, lang, off;
    private int li = 0;
    private boolean preferOffline = true;
    private boolean listening = false;
    private final StringBuilder history = new StringBuilder();

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
        b.setBackground(shape(bg, 26));
        b.setStateListAnimator(null);
        b.setPadding(dp(14), dp(12), dp(14), dp(12));
        return b;
    }

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(0xFFF5F7FB);
        root.setPadding(dp(20), dp(28), dp(20), dp(16));

        TextView title = new TextView(this);
        title.setText("Laboratoire vocal");
        title.setTextSize(22);
        title.setTextColor(0xFF121521);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        root.addView(title);

        TextView hint = new TextView(this);
        hint.setText("Dites un nombre en darija, par exemple « طناش خمسة وربعين » (1245). "
                + "Les mots reliés par « و » forment un nombre; sans « و », un nouveau morceau commence.");
        hint.setTextSize(13);
        hint.setTextColor(0xFF667085);
        hint.setPadding(0, dp(6), 0, dp(14));
        root.addView(hint);

        LinearLayout opts = new LinearLayout(this);
        opts.setOrientation(LinearLayout.HORIZONTAL);
        lang = pill("Langue: " + LOCALE_NAMES[li], 0xFFE9EFFF, 0xFF3366FF);
        lang.setOnClickListener(v -> {
            li = (li + 1) % LOCALES.length;
            lang.setText("Langue: " + LOCALE_NAMES[li]);
        });
        off = pill("Hors ligne: oui", 0xFFE9EFFF, 0xFF3366FF);
        off.setOnClickListener(v -> {
            preferOffline = !preferOffline;
            off.setText("Hors ligne: " + (preferOffline ? "oui" : "non"));
        });
        LinearLayout.LayoutParams lp1 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp1.rightMargin = dp(6);
        LinearLayout.LayoutParams lp2 = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        lp2.leftMargin = dp(6);
        opts.addView(lang, lp1);
        opts.addView(off, lp2);
        root.addView(opts);

        mic = pill("🎤  Parler", 0xFF3366FF, 0xFFFFFFFF);
        mic.setOnClickListener(v -> toggle());
        LinearLayout.LayoutParams lpm = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        lpm.topMargin = dp(14);
        root.addView(mic, lpm);

        status = new TextView(this);
        status.setTextSize(14);
        status.setTextColor(0xFF667085);
        status.setGravity(Gravity.CENTER);
        status.setPadding(0, dp(10), 0, dp(4));
        status.setText("Appuyez sur Parler");
        root.addView(status);

        big = new TextView(this);
        big.setTextSize(44);
        big.setTypeface(Typeface.DEFAULT_BOLD);
        big.setTextColor(0xFF121521);
        big.setGravity(Gravity.CENTER);
        big.setBackground(shape(0xFFFFFFFF, 20));
        big.setPadding(dp(12), dp(18), dp(12), dp(18));
        big.setText("—");
        root.addView(big, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        out = new TextView(this);
        out.setTextSize(14);
        out.setTextColor(0xFF121521);
        out.setPadding(dp(4), dp(10), dp(4), dp(10));
        ScrollView sv = new ScrollView(this);
        sv.addView(out);
        LinearLayout.LayoutParams lps = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        lps.topMargin = dp(10);
        root.addView(sv, lps);

        setContentView(root);
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
        }
    }

    @Override
    public void onRequestPermissionsResult(int code, String[] perms, int[] res) {
        super.onRequestPermissionsResult(code, perms, res);
        if (res.length == 0 || res[0] != PackageManager.PERMISSION_GRANTED) {
            status.setText("Autorisation du microphone refusée");
        }
    }

    private void toggle() {
        if (listening) {
            stop();
            return;
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[]{Manifest.permission.RECORD_AUDIO}, 7);
            return;
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            status.setText("Reconnaissance vocale indisponible sur ce téléphone");
            return;
        }
        start();
    }

    private void start() {
        release();
        sr = SpeechRecognizer.createSpeechRecognizer(this);
        sr.setRecognitionListener(new RecognitionListener() {
            @Override
            public void onReadyForSpeech(Bundle p) {
                status.setText("Écoute... parlez");
            }

            @Override
            public void onBeginningOfSpeech() {
                status.setText("Je vous entends...");
            }

            @Override
            public void onRmsChanged(float rms) {
            }

            @Override
            public void onBufferReceived(byte[] buf) {
            }

            @Override
            public void onEndOfSpeech() {
                status.setText("Analyse...");
            }

            @Override
            public void onError(int e) {
                listening = false;
                mic.setText("🎤  Parler");
                status.setText(errorText(e));
            }

            @Override
            public void onResults(Bundle r) {
                listening = false;
                mic.setText("🎤  Parler");
                show(r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION), false);
            }

            @Override
            public void onPartialResults(Bundle r) {
                ArrayList<String> l = r.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
                if (l != null && !l.isEmpty()) status.setText("... " + l.get(0));
            }

            @Override
            public void onEvent(int t, Bundle p) {
            }
        });
        Intent i = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
        i.putExtra(RecognizerIntent.EXTRA_LANGUAGE, LOCALES[li]);
        i.putExtra(RecognizerIntent.EXTRA_PREFER_OFFLINE, preferOffline);
        i.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 5);
        i.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true);
        listening = true;
        mic.setText("⏹  Arrêter");
        status.setText("Démarrage...");
        sr.startListening(i);
    }

    private void stop() {
        listening = false;
        mic.setText("🎤  Parler");
        try {
            if (sr != null) sr.stopListening();
        } catch (Throwable ignored) {
        }
        status.setText("Arrêté");
    }

    private void release() {
        try {
            if (sr != null) {
                sr.cancel();
                sr.destroy();
            }
        } catch (Throwable ignored) {
        }
        sr = null;
    }

    private void show(ArrayList<String> alts, boolean partial) {
        if (alts == null || alts.isEmpty()) {
            status.setText("Rien entendu");
            return;
        }
        DarijaNumbers.Result best = DarijaNumbers.best(alts);
        if (best.ok()) {
            big.setText(best.digits);
            big.setTextColor(best.risky ? 0xFFB54708 : 0xFF12A150);
            status.setText(best.risky
                    ? "À vérifier: un « و » manque peut-être"
                    : "Nombre reconnu (variante " + (best.altIndex + 1) + ")");
        } else {
            big.setText("?");
            big.setTextColor(0xFFE5484D);
            status.setText("Non compris: " + best.error);
        }
        StringBuilder sb = new StringBuilder();
        sb.append("[").append(LOCALE_NAMES[li]).append(preferOffline ? ", hors ligne" : ", en ligne").append("]\n");
        for (int i = 0; i < alts.size(); i++) {
            DarijaNumbers.Result r = DarijaNumbers.parse(alts.get(i));
            sb.append(i + 1).append(". ").append(alts.get(i)).append("  →  ")
                    .append(r.ok() ? r.digits : "✕ " + r.error).append('\n');
        }
        sb.append('\n');
        history.insert(0, sb);
        out.setText(history);
    }

    private static String errorText(int e) {
        switch (e) {
            case SpeechRecognizer.ERROR_NO_MATCH:
                return "Rien compris (ERROR_NO_MATCH): réessayez plus près du micro";
            case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
                return "Aucune parole détectée";
            case SpeechRecognizer.ERROR_AUDIO:
                return "Erreur audio du microphone";
            case SpeechRecognizer.ERROR_NETWORK:
            case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
                return "Réseau indisponible: la langue demandée n'est pas installée hors ligne";
            case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
                return "Autorisation du microphone manquante";
            case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
                return "Reconnaissance occupée, réessayez";
            case 12: // ERROR_LANGUAGE_NOT_SUPPORTED
            case 13: // ERROR_LANGUAGE_UNAVAILABLE
                return "Langue non disponible hors ligne sur ce téléphone";
            default:
                return "Erreur de reconnaissance (" + e + ")";
        }
    }

    @Override
    protected void onDestroy() {
        release();
        super.onDestroy();
    }
}
