package net.kdt.pojavlaunch.performance;

import android.content.Context;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Process;

import net.kdt.pojavlaunch.utils.FpsCounter;

import java.util.Arrays;
import java.util.Locale;

/**
 * Measures what a session was actually doing, from the frame counter the launcher already keeps at the
 * GL swap boundary.
 *
 * <p>Runs in the game process while the session is in the foreground, at 1 Hz, reusing the performance
 * scheduler in boosted modes and using one background looper in Normal mode. A tick is one native {@code long} read plus arithmetic: no
 * file IO, no reflection, no allocation beyond an amortized ring buffer. The purpose is to turn "did
 * this mode help?" into a number the launcher can display and the user can re-measure; accurate
 * percentiles and jank counts remain the job of {@code dumpsys gfxinfo} on a real device, which is what
 * the benchmark harness collects.
 *
 * <p>Samples survive a background round-trip (a notification shade pull must not throw away ten minutes
 * of measurement), sessions shorter than 30 seconds are dropped, and every record is labelled with
 * renderer + profile + version so only like-for-like runs are ever compared.
 */
public final class FrameSampleCollector {

    private static final int WINDOW_SECONDS = 3600;
    /**
     * Tag for the per-second frame trace a benchmark harness reads. It exists so an old build and a new
     * build can be compared from the *same* measurement (frames counted at the swap boundary by the
     * native bridges) instead of from the in-game text, which is easy to misread and impossible to
     * machine-compare. Off by default: nothing is logged until somebody runs
     * {@code adb shell setprop log.tag.CSPerfFps D}, so a normal session pays nothing for it. It is not
     * a second frame counter — it prints the number this class already computed.
     */
    public static final String HARNESS_TAG = "CSPerfFps";

    private static final class Session {
        final long startedAt = System.currentTimeMillis();
        final String rendererId;
        final String modeKey;
        final String versionId;
        // Primitive circular buffer: avoids thousands of boxed Integer allocations during long sessions.
        final int[] samples = new int[WINDOW_SECONDS];
        int sampleCount;
        int nextSampleIndex;
        long lastPresents = -1L;
        long lastSampleAt;

        /** Opt-in adb hook: see {@link #HARNESS_TAG}. Off unless a developer turns it on. */
        final boolean traceForHarness = android.util.Log.isLoggable(HARNESS_TAG, android.util.Log.DEBUG);

        Session(String rendererId, String modeKey, String versionId) {
            this.rendererId = rendererId;
            this.modeKey = modeKey;
            this.versionId = versionId;
        }
    }

    private static volatile Session sSession;
    private static HandlerThread sThread;
    private static Handler sHandler;
    /** Boosted sessions reuse the scheduler heartbeat instead of starting another thread. */
    private static volatile boolean sUsingSharedHeartbeat;

    private FrameSampleCollector() {}

    /** Start (or restart after a background round-trip) sampling for this session. */
    public static synchronized void begin(Context ctx, String rendererId, String versionId) {
        if (ctx == null) return;
        PerformancePolicy policy = PerformancePolicy.current();
        String mode = policy != null ? policy.mode.key : PerformanceMode.NORMAL.key;
        if (sSession == null) sSession = new Session(rendererId, mode, versionId);
        if (SchedulingPolicy.isActive()) {
            // Performance/Maximum already own a 1 Hz background scheduler. Reuse it so
            // frame statistics do not create a second HandlerThread and another stack.
            sUsingSharedHeartbeat = true;
            SchedulingPolicy.addTickListener(tick);
            return;
        }

        sUsingSharedHeartbeat = false;
        if (sThread == null) {
            sThread = new HandlerThread("CS-FrameStats", Process.THREAD_PRIORITY_BACKGROUND);
            sThread.start();
            sHandler = new Handler(sThread.getLooper());
        }
        sHandler.removeCallbacks(tick);
        sHandler.post(tick);
    }

    /** Stop the heartbeat but keep what was measured. */
    public static synchronized void pause() {
        if (sUsingSharedHeartbeat) {
            SchedulingPolicy.removeTickListener(tick);
            sUsingSharedHeartbeat = false;
        } else {
            Handler handler = sHandler;
            if (handler != null) handler.removeCallbacks(tick);
        }
        // The next resume must not count the background gap as one very slow second.
        Session s = sSession;
        if (s != null) s.lastPresents = -1L;
    }

    /** Persist the session record and forget it. Called from {@code onDestroy}, never mid-session. */
    public static synchronized void finishAndClear(Context ctx) {
        pause();
        Session s = sSession;
        sSession = null;
        try {
            HandlerThread t = sThread;
            sThread = null;
            sHandler = null;
            if (t != null) t.quitSafely();
        } catch (Throwable ignored) {
        }
        if (s == null) return;
        persist(ctx, s);
    }

    /** Median fps over the session so far, or -1 when there is nothing trustworthy to show. */
    public static int liveMedianFps() {
        Session s = sSession;
        if (s == null) return -1;
        int[] copy;
        synchronized (s.samples) {
            if (s.sampleCount == 0) return -1;
            copy = Arrays.copyOf(s.samples, s.sampleCount);
        }
        Arrays.sort(copy);
        return copy[copy.length / 2];
    }

    private static final Runnable tick = new Runnable() {
        @Override public void run() {
            sample();
            Handler handler = sHandler;
            if (!sUsingSharedHeartbeat && handler != null) handler.postDelayed(this, 1000L);
        }
    };

    private static void sample() {
        Session s = sSession;
        if (s == null) return;
        long now = System.currentTimeMillis();
        long presents = FpsCounter.getTotalPresents();
        if (presents < 0L) return; // native glue not linked yet
        if (s.lastPresents >= 0L) {
            long delta = presents - s.lastPresents;
            long elapsed = Math.max(1L, now - s.lastSampleAt);
            // A zero delta is a real frozen second, not a missing sample. Preserve it so
            // median/worst-second statistics cannot hide stalls by silently dropping them.
            if (delta >= 0L) {
                int fps = delta == 0L ? 0
                        : (int) Math.min(1000L, (delta * 1000L) / elapsed);
                synchronized (s.samples) {
                    s.samples[s.nextSampleIndex] = fps;
                    s.nextSampleIndex = (s.nextSampleIndex + 1) % WINDOW_SECONDS;
                    if (s.sampleCount < WINDOW_SECONDS) s.sampleCount++;
                }
                if (s.traceForHarness) {
                    android.util.Log.d(HARNESS_TAG, "t=" + ((now - s.startedAt) / 1000L)
                            + "s fps=" + fps + " presents=" + presents
                            + " mode=" + s.modeKey + " renderer=" + s.rendererId);
                }
            }
        }
        s.lastPresents = presents;
        s.lastSampleAt = now;
    }

    private static void persist(Context ctx, Session s) {
        if (ctx == null) return;
        int[] copy;
        synchronized (s.samples) {
            if (s.sampleCount < 10) return;
            copy = Arrays.copyOf(s.samples, s.sampleCount);
        }
        Arrays.sort(copy);
        int median = copy[copy.length / 2];
        int worst = copy[0]; // lowest 1-second window: a stutter proxy, honestly labelled
        float medianFrameMs = median > 0 ? 1000f / median : 0f;
        long duration = System.currentTimeMillis() - s.startedAt;
        SessionStats.append(ctx, new SessionStats.Entry(s.startedAt, s.rendererId, s.modeKey, s.versionId,
                median, worst, medianFrameMs, duration, ThermalGovernor.peakLevelOrdinal()));
        android.util.Log.i("CSPerf", "session measured (" + s.modeKey + ", " + s.rendererId + ", "
                + s.versionId + "): median " + median + " fps, worst second " + worst + " fps, "
                + String.format(Locale.ROOT, "%.1f ms/frame, %d samples, %ds",
                medianFrameMs, copy.length, duration / 1000L));
    }
}
