package com.smartkeyboard.releve;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.os.Handler;
import android.os.Looper;

/**
 * Capture du micro hors ligne: un enonce a la fois (arret au silence). Deux modes:
 * une seule capture, ou ecoute "garde" qui enchaine les enonces jusqu'a l'annulation (pour entendre "stop").
 */
public final class VoiceInput {

    public interface Callback {
        /** Appele sur le thread principal; pcm vide = rien entendu. Renvoie true pour continuer (mode garde). */
        boolean onUtterance(short[] pcm);
    }

    private final Context ctx;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private volatile boolean running = false;
    private Thread thread;

    public VoiceInput(Context c) {
        ctx = c.getApplicationContext();
    }

    public boolean hasPermission() {
        return ctx.checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
    }

    public boolean isRunning() {
        return running;
    }

    public void cancel() {
        running = false;
    }

    /** Une seule capture. */
    public void once(Callback cb) {
        run(cb, false, 9000, null);
    }

    /** Une seule capture; ready est appele (thread principal) quand on peut vraiment parler. */
    public void once(Callback cb, Runnable ready) {
        run(cb, false, 9000, ready);
    }

    /** Ecoute enchainee pendant au plus maxMs. */
    public void guard(Callback cb, int maxMs) {
        run(cb, true, maxMs, null);
    }

    /** Duree ignoree au debut: vibration et bruit du bouton; sert aussi a mesurer le bruit de fond. */
    private static final int WARM_MS = 400;
    /** Silence qui termine un enonce. */
    private static final int END_QUIET_MS = 1000;
    /** Une parole doit durer au moins 3 pas de 50 ms pour commencer, et 6 au total pour etre gardee. */
    private static final int START_RUN = 3, KEEP_MIN = 6;

    private void run(final Callback cb, final boolean loop, final int maxMs, final Runnable ready) {
        if (running) return;
        running = true;
        thread = new Thread(() -> {
            AudioRecord ar = null;
            try {
                int min = AudioRecord.getMinBufferSize(VoiceCore.RATE, AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT);
                ar = new AudioRecord(MediaRecorder.AudioSource.VOICE_RECOGNITION, VoiceCore.RATE,
                        AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, Math.max(min, 8192));
                if (ar.getState() != AudioRecord.STATE_INITIALIZED) {
                    deliver(cb, new short[0]);
                    return;
                }
                ar.startRecording();
                short[] buf = new short[800];
                // 1) mise en route: on jette le debut (vibration du bouton) et on mesure le bruit de fond
                double floor = Double.MAX_VALUE;
                int warmN = 0;
                int warmChunks = WARM_MS / 50;
                while (running && warmN < warmChunks) {
                    int r = ar.read(buf, 0, buf.length);
                    if (r <= 0) continue;
                    double sum = 0;
                    for (int i = 0; i < r; i++) sum += (double) buf[i] * buf[i];
                    double db = 10 * Math.log10(sum / r + 1.0);
                    if (warmN >= 3) floor = Math.min(floor, db);
                    warmN++;
                }
                if (floor == Double.MAX_VALUE) floor = 30;
                if (ready != null && running) ui.post(ready);
                long deadline = System.currentTimeMillis() + maxMs;
                while (running && System.currentTimeMillis() < deadline) {
                    short[] all = new short[VoiceCore.RATE * 8];
                    int n = 0;
                    boolean spoke = false;
                    int run = 0, loudTotal = 0, quiet = 0, total = 0;
                    boolean done = false;
                    while (running && !done && n + buf.length < all.length && System.currentTimeMillis() < deadline) {
                        int r = ar.read(buf, 0, buf.length);
                        if (r <= 0) continue;
                        double sum = 0;
                        for (int i = 0; i < r; i++) sum += (double) buf[i] * buf[i];
                        double db = 10 * Math.log10(sum / r + 1.0);
                        boolean loud = db > floor + 11;
                        System.arraycopy(buf, 0, all, n, r);
                        n += r;
                        total += r;
                        if (!loud) {
                            // le bruit de fond suit doucement l'ambiance (sans jamais suivre la parole)
                            floor = db < floor ? 0.5 * floor + 0.5 * db : floor * 0.97 + db * 0.03;
                        }
                        if (!spoke) {
                            run = loud ? run + 1 : 0;
                            if (run >= START_RUN) {
                                spoke = true;
                                loudTotal = run;
                                quiet = 0;
                            } else if (n > VoiceCore.RATE * 3 / 10) {
                                // pas encore de parole: on ne garde que la derniere 0,25 s
                                System.arraycopy(all, n - VoiceCore.RATE / 4, all, 0, VoiceCore.RATE / 4);
                                n = VoiceCore.RATE / 4;
                            }
                        } else {
                            if (loud) {
                                loudTotal++;
                                quiet = 0;
                            } else {
                                quiet += r;
                            }
                            if (quiet > VoiceCore.RATE * END_QUIET_MS / 1000) {
                                if (loudTotal < KEEP_MIN) {
                                    // bruit bref (clic, toux...): on l'oublie et on continue d'attendre la vraie parole
                                    spoke = false;
                                    run = 0;
                                    loudTotal = 0;
                                    quiet = 0;
                                    n = 0;
                                } else {
                                    done = true;
                                }
                            }
                        }
                        if (!loop && !spoke && total > VoiceCore.RATE * 7) break;
                    }
                    if (!running) break;
                    short[] out = spoke && loudTotal >= KEEP_MIN ? java.util.Arrays.copyOf(all, n) : new short[0];
                    if (!loop) {
                        running = false;
                        deliver(cb, out);
                        break;
                    }
                    if (out.length > 0 && !deliverWait(cb, out)) break;
                }
            } catch (Throwable t) {
                deliver(cb, new short[0]);
            } finally {
                if (ar != null) {
                    try {
                        ar.stop();
                    } catch (Throwable ignore) {
                    }
                    ar.release();
                }
                running = false;
            }
        });
        thread.start();
    }

    private void deliver(final Callback cb, final short[] pcm) {
        ui.post(() -> cb.onUtterance(pcm));
    }

    /** Mode garde: livre l'enonce et attend la reponse (continuer ou non). */
    private boolean deliverWait(final Callback cb, final short[] pcm) {
        final boolean[] res = {true};
        final Object lock = new Object();
        final boolean[] done = {false};
        ui.post(() -> {
            try {
                res[0] = cb.onUtterance(pcm);
            } catch (Throwable t) {
                res[0] = true;
            }
            synchronized (lock) {
                done[0] = true;
                lock.notifyAll();
            }
        });
        synchronized (lock) {
            long end = System.currentTimeMillis() + 3000;
            while (!done[0] && System.currentTimeMillis() < end) {
                try {
                    lock.wait(200);
                } catch (InterruptedException e) {
                    return false;
                }
            }
        }
        return res[0];
    }
}
