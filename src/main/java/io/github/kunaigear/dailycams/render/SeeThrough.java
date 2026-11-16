package io.github.kunaigear.dailycams.render;

import it.unimi.dsi.fastutil.ints.Int2IntOpenHashMap;
import org.jetbrains.annotations.Nullable;

/**
 * Glass and water that Camera Obscura drew over empty background, so distant terrain can show through them. Camera
 * Obscura doesn't write depth for translucent surfaces, so those framebuffer samples still look empty afterwards.
 * Kept per sample: the colour behind the first such surface, and how much of it still shows through (0-255).
 * Only active on the render thread while {@link #ACTIVE} is set.
 */
public final class SeeThrough {
    private static final ThreadLocal<SeeThrough> ACTIVE = new ThreadLocal<>();

    private final Int2IntOpenHashMap behind = new Int2IntOpenHashMap(), transmit = new Int2IntOpenHashMap();

    public static @Nullable SeeThrough active() {
        return ACTIVE.get();
    }

    void activate() {
        ACTIVE.set(this);
    }

    static void deactivate() {
        ACTIVE.remove();
    }

    /** A translucent fragment with this alpha is about to be blended over {@code dst} at an empty sample. */
    public void record(int idx, int alpha, int dst) {
        int t = transmit.getOrDefault(idx, 255);
        if (!behind.containsKey(idx)) behind.put(idx, dst);
        transmit.put(idx, t * (255 - alpha) / 255);
    }

    /** How much of the background shows at a sample, 0-1. */
    double transmittance(int idx) {
        return transmit.getOrDefault(idx, 255) / 255.0;
    }

    /** The colour that was behind the translucent surfaces at a sample, or {@code fallback} if there are none. */
    int behind(int idx, int fallback) {
        return behind.getOrDefault(idx, fallback);
    }
}
