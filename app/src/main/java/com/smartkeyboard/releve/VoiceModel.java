package com.smartkeyboard.releve;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Mots appris par l'utilisateur (modeles d'enregistrements) et reconnaissance par comparaison DTW. */
public final class VoiceModel {

    /** Resultat de la comparaison d'un mot avec tous les modeles. */
    public static final class Match {
        public String label = "";
        public double dist = Double.MAX_VALUE;
        public String second = "";
        public double secondDist = Double.MAX_VALUE;
        /** Distance maximale acceptable pour ce mot/ordre (variation normale de ses propres exemples). */
        public double gate = Double.MAX_VALUE;
        /** Vrai pour un ordre dont on ne connait pas encore la variation (moins de 2 exemples). */
        public boolean unknownGate = false;

        /** Distance rapportee a la limite acceptable: <= 1 = dans la variation normale. */
        public double score() {
            if (gate <= 0 || gate == Double.MAX_VALUE || dist == Double.MAX_VALUE) return 0;
            return dist / gate;
        }

        /** Rapport entre le 2e et le 1er: plus il est grand, plus le choix est net. */
        public double ratio() {
            if (dist <= 0 || dist == Double.MAX_VALUE) return 0;
            return secondDist == Double.MAX_VALUE ? 99 : secondDist / dist;
        }

        public boolean sure() {
            return !label.isEmpty() && !unknownGate && ratio() >= 1.12 && dist <= gate;
        }
    }

    private static final int MAX_PER_LABEL = 12;
    /** Prefixe des enregistrements d'ordres entiers (ex. "cmd:bloque"); les autres sont des mots (chiffres). */
    public static final String CMD = "cmd:";

    private final LinkedHashMap<String, List<float[][]>> words = new LinkedHashMap<>();
    private final HashMap<String, Double> intra = new HashMap<>();

    /** Distance moyenne entre les exemples d'un meme mot (0 si moins de 2 exemples). */
    private double intraMean(String label) {
        Double c = intra.get(label);
        if (c != null) return c;
        List<float[][]> l = words.get(label);
        double sum = 0;
        int n = 0;
        if (l != null) {
            int k = Math.min(l.size(), 8);
            for (int i = 0; i < k; i++) {
                for (int j = i + 1; j < k; j++) {
                    double d = VoiceCore.dtw(l.get(i), l.get(j));
                    if (d != Double.MAX_VALUE) {
                        sum += d;
                        n++;
                    }
                }
            }
        }
        double v = n == 0 ? 0 : sum / n;
        intra.put(label, v);
        return v;
    }

    /** Limite acceptable pour un mot: 1,5 fois sa variation; sinon la moyenne generale du meme genre (mot/ordre). */
    private double gateFor(String label, boolean cmd) {
        double m = intraMean(label);
        if (m > 0) return 1.5 * m;
        double sum = 0;
        int n = 0;
        for (String k : words.keySet()) {
            if (k.startsWith(CMD) != cmd) continue;
            double x = intraMean(k);
            if (x > 0) {
                sum += x;
                n++;
            }
        }
        return n == 0 ? Double.MAX_VALUE : 1.5 * sum / n;
    }

    public synchronized void add(String label, float[][] feat) {
        if (label == null || label.isEmpty() || feat == null || feat.length < 8) return;
        List<float[][]> l = words.get(label);
        if (l == null) {
            l = new ArrayList<>();
            words.put(label, l);
        }
        l.add(feat);
        intra.remove(label);
        while (l.size() > MAX_PER_LABEL) l.remove(0); // on garde les plus recents
    }

    public synchronized int count(String label) {
        List<float[][]> l = words.get(label);
        return l == null ? 0 : l.size();
    }

    public synchronized int total() {
        int t = 0;
        for (List<float[][]> l : words.values()) t += l.size();
        return t;
    }

    public synchronized Map<String, Integer> counts() {
        LinkedHashMap<String, Integer> m = new LinkedHashMap<>();
        for (Map.Entry<String, List<float[][]>> e : words.entrySet()) m.put(e.getKey(), e.getValue().size());
        return m;
    }

    public synchronized void remove(String label) {
        words.remove(label);
        intra.remove(label);
    }

    public synchronized void clear() {
        words.clear();
        intra.clear();
    }

    /** Compare un mot a tous les modeles: meilleure distance par mot appris. */
    public synchronized Match classify(float[][] feat) {
        return classify(feat, null, -1, false);
    }

    /**
     * Compare un enonce entier aux ordres appris (cmd:...). Un ordre n'est retenu que si la distance reste proche
     * de la variation normale de ses propres enregistrements (au moins 2 exemples), pour ne pas confondre avec des chiffres.
     */
    public synchronized Match classifyCommand(float[][] feat) {
        Match m = classify(feat, null, -1, true);
        if (m.label.isEmpty()) return m;
        List<float[][]> l = words.get(m.label);
        m.unknownGate = l == null || l.size() < 2; // un seul exemple: jamais "sur"
        return m;
    }

    private Match classify(float[][] feat, String skipLabel, int skipIndex, boolean cmd) {
        Match m = new Match();
        for (Map.Entry<String, List<float[][]>> e : words.entrySet()) {
            if (e.getKey().startsWith(CMD) != cmd) continue;
            double best = Double.MAX_VALUE;
            List<float[][]> l = e.getValue();
            for (int i = 0; i < l.size(); i++) {
                if (e.getKey().equals(skipLabel) && i == skipIndex) continue;
                double d = VoiceCore.dtw(feat, l.get(i));
                if (d < best) best = d;
            }
            if (best == Double.MAX_VALUE) continue;
            if (best < m.dist) {
                m.second = m.label;
                m.secondDist = m.dist;
                m.label = e.getKey();
                m.dist = best;
            } else if (best < m.secondDist) {
                m.second = e.getKey();
                m.secondDist = best;
            }
        }
        if (!m.label.isEmpty()) m.gate = gateFor(m.label, cmd);
        return m;
    }

    /** Auto-evaluation: chaque enregistrement est reconnu avec tous les autres (le sien exclu). Renvoie [bons, testes]. */
    public synchronized int[] selfTest(List<String> errors) {
        int ok = 0, n = 0;
        for (Map.Entry<String, List<float[][]>> e : words.entrySet()) {
            List<float[][]> l = e.getValue();
            if (l.size() < 2) continue; // un seul exemple: on ne peut pas tester ce mot
            for (int i = 0; i < l.size(); i++) {
                Match m = classify(l.get(i), e.getKey(), i, e.getKey().startsWith(CMD));
                n++;
                if (e.getKey().equals(m.label)) ok++;
                else if (errors != null) errors.add(e.getKey() + " → " + m.label);
            }
        }
        return new int[]{ok, n};
    }

    public synchronized void save(File f) throws IOException {
        File tmp = new File(f.getPath() + ".tmp");
        try (DataOutputStream o = new DataOutputStream(new java.io.BufferedOutputStream(new FileOutputStream(tmp)))) {
            o.writeInt(1);
            o.writeInt(total());
            for (Map.Entry<String, List<float[][]>> e : words.entrySet()) {
                for (float[][] t : e.getValue()) {
                    o.writeUTF(e.getKey());
                    o.writeInt(t.length);
                    o.writeInt(t.length == 0 ? 0 : t[0].length);
                    for (float[] r : t) for (float v : r) o.writeFloat(v);
                }
            }
        }
        if (!tmp.renameTo(f)) {
            f.delete();
            if (!tmp.renameTo(f)) throw new IOException("ecriture impossible");
        }
    }

    public synchronized void load(File f) throws IOException {
        words.clear();
        if (!f.exists()) return;
        try (DataInputStream in = new DataInputStream(new java.io.BufferedInputStream(new FileInputStream(f)))) {
            if (in.readInt() != 1) return;
            int n = in.readInt();
            for (int k = 0; k < n; k++) {
                String label = in.readUTF();
                int rows = in.readInt(), cols = in.readInt();
                float[][] t = new float[rows][cols];
                for (int i = 0; i < rows; i++) for (int j = 0; j < cols; j++) t[i][j] = in.readFloat();
                add(label, t);
            }
        }
    }

    /** Mots appris, tries par ordre alphabetique, pour l'affichage. */
    public synchronized List<String> labels() {
        List<String> l = new ArrayList<>(words.keySet());
        Collections.sort(l);
        return l;
    }
}
