package io.github.kunaigear.dailycams.render;

/** Switches for Camera Obscura's drawing while one of our renders runs on this thread. */
public final class RenderFlags {
    private static final ThreadLocal<Boolean> NO_FOG = ThreadLocal.withInitial(() -> false);

    private RenderFlags() {}

    /** Turns Camera Obscura's distance fog off (distant terrain continues where it would end the picture). */
    static void noFog(boolean on) {
        if (on) NO_FOG.set(true);
        else NO_FOG.remove();
    }

    public static boolean noFog() {
        return NO_FOG.get();
    }
}
