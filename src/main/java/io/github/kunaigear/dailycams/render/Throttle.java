package io.github.kunaigear.dailycams.render;

import io.github.kunaigear.dailycams.DailyCams;

/**
 * Keeps the render thread from competing with the server thread for CPU.
 *
 * <p>The render thread works in short slices and sleeps in between so that it uses at most
 * {@code cpuShare} of one core. While the server is struggling (a slow or late tick in the last second)
 * it pauses entirely, so a busy server always wins. Sleeping also lowers the allocation rate, so garbage collections happen less often.
 */
public final class Throttle {
    private static final ThreadLocal<Throttle> CURRENT = new ThreadLocal<>();
    private static final long SLICE_NANOS = 4_000_000;       // work at most 4 ms at a time
    private static final long BUSY_BACKOFF_MS = 50;

    private final double cpuShare;
    private long sliceStart = System.nanoTime();
    private long sleptNanos;

    private Throttle(double cpuShare) {
        this.cpuShare = cpuShare;
    }

    static void begin(double cpuShare) {
        CURRENT.set(new Throttle(cpuShare));
    }

    static void end() {
        CURRENT.remove();
    }

    /** Total time this thread's current render has spent sleeping, in ms. */
    static long sleptMs() {
        Throttle t = CURRENT.get();
        return t == null ? 0 : t.sleptNanos / 1_000_000;
    }

    /** Called often from the render thread (between chunk sections and between draw commands). No-op elsewhere. */
    public static void checkpoint() {
        Throttle t = CURRENT.get();
        if (t != null) t.check();
    }

    private void check() {
        long worked = System.nanoTime() - sliceStart;
        boolean busy = DailyCams.serverBusy();
        if (worked < SLICE_NANOS && !busy) return;
        long sleepMs = cpuShare >= 1 ? 0 : (long) (worked * (1 - cpuShare) / cpuShare / 1_000_000);
        if (busy) sleepMs = Math.max(sleepMs, BUSY_BACKOFF_MS);
        if (sleepMs > 0) {
            long before = System.nanoTime();
            try {
                Thread.sleep(sleepMs);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("Render cancelled", e);
            }
            sleptNanos += System.nanoTime() - before;
        }
        sliceStart = System.nanoTime();
    }
}
