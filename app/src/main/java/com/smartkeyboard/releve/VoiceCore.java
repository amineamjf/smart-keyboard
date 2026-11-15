package com.smartkeyboard.releve;

import java.util.ArrayList;
import java.util.List;

/**
 * Noyau de reconnaissance vocale hors ligne, en Java pur (aucune dependance Android):
 * caracteristiques MFCC, decoupage en mots par silence, comparaison DTW.
 * Entree attendue: PCM 16 bits mono a 16 000 Hz.
 */
public final class VoiceCore {

    public static final int RATE = 16000;
    static final int FRAME = 400;      // 25 ms
    static final int HOP = 160;        // 10 ms
    static final int NFFT = 512;
    static final int MELS = 26;
    static final int NCEP = 12;        // coefficients c1..c12

    private VoiceCore() {
    }

    // ------------------------------------------------------------------ MFCC

    private static double[][] melBank;
    private static double[][] dct;
    private static double[] window;

    private static synchronized void init() {
        if (melBank != null) return;
        window = new double[FRAME];
        for (int i = 0; i < FRAME; i++) window[i] = 0.54 - 0.46 * Math.cos(2 * Math.PI * i / (FRAME - 1));
        double lo = mel(60), hi = mel(RATE / 2.0 - 200);
        double[] pts = new double[MELS + 2];
        for (int i = 0; i < pts.length; i++) {
            double m = lo + (hi - lo) * i / (MELS + 1);
            pts[i] = inv(m) * NFFT / RATE;
        }
        melBank = new double[MELS][NFFT / 2 + 1];
        for (int m = 0; m < MELS; m++) {
            for (int k = 0; k <= NFFT / 2; k++) {
                double a = pts[m], b = pts[m + 1], c = pts[m + 2];
                double v = 0;
                if (k > a && k <= b) v = (k - a) / (b - a);
                else if (k > b && k < c) v = (c - k) / (c - b);
                melBank[m][k] = v;
            }
        }
        dct = new double[NCEP][MELS];
        for (int i = 0; i < NCEP; i++) {
            for (int j = 0; j < MELS; j++) {
                dct[i][j] = Math.cos(Math.PI * (i + 1) * (j + 0.5) / MELS);
            }
        }
    }

    private static double mel(double f) {
        return 2595 * Math.log10(1 + f / 700);
    }

    private static double inv(double m) {
        return 700 * (Math.pow(10, m / 2595) - 1);
    }

    private static void fft(double[] re, double[] im) {
        int n = re.length;
        for (int i = 1, j = 0; i < n; i++) {
            int bit = n >> 1;
            for (; (j & bit) != 0; bit >>= 1) j ^= bit;
            j ^= bit;
            if (i < j) {
                double t = re[i];
                re[i] = re[j];
                re[j] = t;
                t = im[i];
                im[i] = im[j];
                im[j] = t;
            }
        }
        for (int len = 2; len <= n; len <<= 1) {
            double ang = -2 * Math.PI / len;
            double wr = Math.cos(ang), wi = Math.sin(ang);
            for (int i = 0; i < n; i += len) {
                double cr = 1, ci = 0;
                for (int k = 0; k < len / 2; k++) {
                    int a = i + k, b = i + k + len / 2;
                    double xr = re[b] * cr - im[b] * ci;
                    double xi = re[b] * ci + im[b] * cr;
                    re[b] = re[a] - xr;
                    im[b] = im[a] - xi;
                    re[a] += xr;
                    im[a] += xi;
                    double t = cr * wr - ci * wi;
                    ci = cr * wi + ci * wr;
                    cr = t;
                }
            }
        }
    }

    /** Caracteristiques d'un morceau de signal: une ligne de NCEP coefficients par pas de 10 ms, moyenne retiree. */
    public static float[][] mfcc(short[] pcm, int from, int to) {
        init();
        int n = Math.max(0, (to - from - FRAME) / HOP + 1);
        float[][] out = new float[n][NCEP];
        double[] re = new double[NFFT], im = new double[NFFT];
        double[] e = new double[MELS];
        for (int f = 0; f < n; f++) {
            int s = from + f * HOP;
            java.util.Arrays.fill(re, 0);
            java.util.Arrays.fill(im, 0);
            double prev = s > 0 ? pcm[s - 1] : 0;
            for (int i = 0; i < FRAME; i++) {
                double x = pcm[s + i];
                double y = x - 0.97 * prev; // pre-emphase
                prev = x;
                re[i] = y * window[i];
            }
            fft(re, im);
            for (int m = 0; m < MELS; m++) {
                double sum = 0;
                for (int k = 0; k <= NFFT / 2; k++) {
                    if (melBank[m][k] != 0) sum += melBank[m][k] * (re[k] * re[k] + im[k] * im[k]);
                }
                e[m] = Math.log(sum + 1.0);
            }
            for (int i = 0; i < NCEP; i++) {
                double v = 0;
                for (int j = 0; j < MELS; j++) v += dct[i][j] * e[j];
                out[f][i] = (float) v;
            }
        }
        // soustraction de la moyenne (annule l'effet du micro et du volume)
        if (n > 0) {
            for (int i = 0; i < NCEP; i++) {
                double mean = 0;
                for (int f = 0; f < n; f++) mean += out[f][i];
                mean /= n;
                for (int f = 0; f < n; f++) out[f][i] -= (float) mean;
            }
        }
        return out;
    }

    // ------------------------------------------------------------- Decoupage

    /** Un mot repere dans l'enregistrement, en echantillons. */
    public static final class Span {
        public final int from, to;

        Span(int from, int to) {
            this.from = from;
            this.to = to;
        }
    }

    /** Energie (dB) de chaque pas de 10 ms. */
    public static double[] energies(short[] pcm) {
        int n = Math.max(0, (pcm.length - FRAME) / HOP + 1);
        double[] e = new double[n];
        for (int f = 0; f < n; f++) {
            double s = 0;
            int a = f * HOP;
            for (int i = 0; i < FRAME; i++) {
                double x = pcm[a + i];
                s += x * x;
            }
            e[f] = 10 * Math.log10(s / FRAME + 1.0);
        }
        return e;
    }

    /**
     * Repere les mots: les pas dont l'energie depasse le bruit de fond de plusieurs dB sont de la parole;
     * deux morceaux separes par moins de gapMs de calme restent un seul mot.
     */
    public static List<Span> split(short[] pcm, int gapMs, int minMs) {
        return split(pcm, gapMs, minMs, 0.25);
    }

    /**
     * Cherche un decoupage qui donne exactement le nombre de mots attendu (on essaie plusieurs durees de pause
     * et plusieurs seuils). Renvoie null si aucun reglage ne convient.
     */
    public static List<Span> splitTo(short[] pcm, int expected) {
        int[] gaps = {220, 160, 120, 90, 300, 400, 520, 700, 60};
        double[] fr = {0.25, 0.18, 0.35, 0.12, 0.5};
        for (double f : fr) {
            for (int g : gaps) {
                List<Span> r = split(pcm, g, g < 120 ? 60 : 90, f);
                if (r.size() == expected) return r;
            }
        }
        return null;
    }

    public static List<Span> split(short[] pcm, int gapMs, int minMs, double thrFrac) {
        double[] e = energies(pcm);
        List<Span> out = new ArrayList<>();
        if (e.length < 10) return out;
        double[] sorted = e.clone();
        java.util.Arrays.sort(sorted);
        double floor = sorted[(int) (sorted.length * 0.1)];
        double peak = sorted[(int) (sorted.length * 0.97)];
        if (peak - floor < 12) return out; // rien d'audible
        double thr = Math.max(floor + 8, floor + (peak - floor) * thrFrac);
        int gap = gapMs / 10, min = minMs / 10;
        int start = -1, last = -1;
        List<int[]> runs = new ArrayList<>();
        for (int f = 0; f < e.length; f++) {
            if (e[f] > thr) {
                if (start < 0) start = f;
                else if (f - last > gap) {
                    runs.add(new int[]{start, last});
                    start = f;
                }
                last = f;
            }
        }
        if (start >= 0) runs.add(new int[]{start, last});
        int pad = 5;
        for (int[] r : runs) {
            if (r[1] - r[0] + 1 < min) continue;
            int a = Math.max(0, r[0] - pad) * HOP;
            int b = Math.min(pcm.length, (r[1] + pad) * HOP + FRAME);
            out.add(new Span(a, b));
        }
        return out;
    }

    // ------------------------------------------------------------------- DTW

    /** Distance DTW normalisee entre deux sequences (bande de Sakoe-Chiba large, 30 %). */
    public static double dtw(float[][] a, float[][] b) {
        int n = a.length, m = b.length;
        if (n == 0 || m == 0) return Double.MAX_VALUE;
        if (n > 2.2 * m || m > 2.2 * n) return Double.MAX_VALUE; // durees trop differentes
        int band = Math.max(Math.abs(n - m) + 3, (int) (0.3 * Math.max(n, m)));
        double inf = Double.MAX_VALUE / 4;
        double[] prev = new double[m + 1], cur = new double[m + 1];
        java.util.Arrays.fill(prev, inf);
        prev[0] = 0;
        for (int i = 1; i <= n; i++) {
            java.util.Arrays.fill(cur, inf);
            int lo = Math.max(1, i - band), hi = Math.min(m, i + band);
            for (int j = lo; j <= hi; j++) {
                double d = 0;
                float[] x = a[i - 1], y = b[j - 1];
                for (int k = 0; k < x.length; k++) {
                    double t = x[k] - y[k];
                    d += t * t;
                }
                d = Math.sqrt(d);
                double best = Math.min(prev[j], Math.min(prev[j - 1], cur[j - 1]));
                cur[j] = best + d;
            }
            double[] t = prev;
            prev = cur;
            cur = t;
        }
        double r = prev[m];
        return r >= inf ? Double.MAX_VALUE : r / (n + m);
    }
}
