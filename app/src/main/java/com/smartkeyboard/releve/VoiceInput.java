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
        run(cb, false, 8000);
    }

    /** Ecoute enchainee pendant au plus maxMs. */
    public void guard(Callback cb, int maxMs) {
        run(cb, true, maxMs);
    }

    private void run(final Callback cb, final boolean loop, final int maxMs) {
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
                long deadline = System.currentTimeMillis() + maxMs;
                double floor = -1;
                while (running && System.currentTimeMillis() < deadline) {
                    short[] all = new short[VoiceCore.RATE * 8];
                    int n = 0;
                    boolean spoke = false;
                    int quiet = 0, total = 0;
                    while (running && n + buf.length < all.length && System.currentTimeMillis() < deadline) {
                        int r = ar.read(buf, 0, buf.length);
                        if (r <= 0) continue;
                        double sum = 0;
                        for (int i = 0; i < r; i++) sum += (double) buf[i] * buf[i];
                        double db = 10 * Math.log10(sum / r + 1.0);
                        if (floor < 0) floor = db;
                        else if (!spoke) floor = Math.min(db, floor * 0.98 + db * 0.02 + 0.5);
                        boolean loud = db > floor + 10;
                        // on garde un peu de silence avant la parole
                        System.arraycopy(buf, 0, all, n, r);
                        n += r;
                        total += r;
                        if (loud) {
                            spoke = true;
                            quiet = 0;
                        } else if (spoke) {
                            quiet += r;
                        } else if (n > VoiceCore.RATE / 2) {
                            // pas encore de parole: on garde seulement la derniere demi-seconde
                            System.arraycopy(all, n - VoiceCore.RATE / 4, all, 0, VoiceCore.RATE / 4);
                            n = VoiceCore.RATE / 4;
                        }
                        if (spoke && quiet > VoiceCore.RATE * 7 / 10) break;
                        if (!loop && !spoke && total > VoiceCore.RATE * 6) break;
                    }
                    if (!running) break;
                    short[] out = spoke ? java.util.Arrays.copyOf(all, n) : new short[0];
                    if (!loop) {
                        running = false;
                        deliver(cb, out);
                        break;
                    }
                    if (spoke && !deliverWait(cb, out)) break;
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
