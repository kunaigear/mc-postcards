package io.github.kunaigear.postcards.render;

import org.jetbrains.annotations.Nullable;

/**
 * Switches for Camera Obscura's drawing while one of our renders runs. They only apply on the thread running it.
 * Not thread-locals: some are read for every fragment drawn, and a thread-local lookup there costs more than the
 * rest of the fragment's bookkeeping.
 */
public final class RenderFlags {
    private static volatile @Nullable Thread renderThread;
    // Only written and read on the render thread.
    private static boolean noFog, bushyLeaves;

    private RenderFlags() {}

    static void rendering(boolean on) {
        noFog = false;
        bushyLeaves = false;
        renderThread = on ? Thread.currentThread() : null;
    }

    /** Whether one of our renders runs on this thread. */
    public static boolean rendering() {
        return Thread.currentThread() == renderThread;
    }

    /** Turns Camera Obscura's distance fog off (distant terrain continues where it would end the picture). */
    static void noFog(boolean on) {
        noFog = on;
    }

    public static boolean noFog() {
        return noFog && rendering();
    }

    /** Builds leaf blocks with leaves sticking out of them ({@link BushyLeaves}). */
    static void bushyLeaves(boolean on) {
        bushyLeaves = on;
    }

    static boolean bushyLeaves() {
        return bushyLeaves && rendering();
    }
}
