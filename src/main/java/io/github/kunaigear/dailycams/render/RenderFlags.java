package io.github.kunaigear.dailycams.render;

/** Switches for Camera Obscura's drawing while one of our renders runs on this thread. */
public final class RenderFlags {
    private static final ThreadLocal<Boolean> NO_FOG = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> BUSHY_LEAVES = ThreadLocal.withInitial(() -> false);
    private static final ThreadLocal<Boolean> RENDERING = ThreadLocal.withInitial(() -> false);

    private RenderFlags() {}

    /** Turns Camera Obscura's distance fog off (distant terrain continues where it would end the picture). */
    static void noFog(boolean on) {
        if (on) NO_FOG.set(true);
        else NO_FOG.remove();
    }

    public static boolean noFog() {
        return NO_FOG.get();
    }

    /** Builds leaf blocks with leaves sticking out of them ({@link BushyLeaves}). */
    static void bushyLeaves(boolean on) {
        if (on) BUSHY_LEAVES.set(true);
        else BUSHY_LEAVES.remove();
    }

    static boolean bushyLeaves() {
        return BUSHY_LEAVES.get();
    }

    static void rendering(boolean on) {
        if (on) RENDERING.set(true);
        else RENDERING.remove();
    }

    /** Whether one of our renders runs on this thread. */
    public static boolean rendering() {
        return RENDERING.get();
    }
}
