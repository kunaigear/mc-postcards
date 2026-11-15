package io.github.kunaigear.dailycams.schedule;

import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.config.DailyCamsConfig;
import io.github.kunaigear.dailycams.util.Json;
import org.jetbrains.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZonedDateTime;

/**
 * Decides when the daily run is due. The last executed slot is persisted in {@code state.json},
 * so restarts neither skip nor repeat a run.
 */
public final class DailyScheduler {
    private static final class State {
        String lastSlot;
    }

    private final Path stateFile;
    private @Nullable ZonedDateTime lastSlot;

    public DailyScheduler(Path stateFile) {
        this.stateFile = stateFile;
    }

    public void load(DailyCamsConfig cfg) {
        lastSlot = null;
        try {
            if (Files.exists(stateFile)) {
                State s = Json.GSON.fromJson(Files.readString(stateFile), State.class);
                if (s != null && s.lastSlot != null) lastSlot = ZonedDateTime.parse(s.lastSlot);
            }
        } catch (Exception e) {
            DailyCams.LOGGER.warn("Could not read {}: {}", stateFile, e.getMessage());
        }
        if (lastSlot == null) {
            // First start: don't fire for a slot that passed before the mod was installed.
            ZonedDateTime recent = mostRecentSlot(cfg, ZonedDateTime.now(cfg.zone()));
            if (recent != null) markDone(recent);
        }
    }

    /** True exactly once per due slot. */
    public boolean poll(DailyCamsConfig cfg) {
        ZonedDateTime now = ZonedDateTime.now(cfg.zone());
        ZonedDateTime slot = mostRecentSlot(cfg, now);
        if (slot == null) return false;
        if (lastSlot != null && !slot.toInstant().isAfter(lastSlot.toInstant())) return false;

        markDone(slot);
        boolean missed = now.isAfter(slot.plusMinutes(5));
        if (missed && !cfg.catchUpMissedRuns) {
            DailyCams.LOGGER.info("Skipping missed daily run scheduled for {}", slot);
            return false;
        }
        return true;
    }

    public @Nullable ZonedDateTime nextSlot(DailyCamsConfig cfg) {
        ZonedDateTime now = ZonedDateTime.now(cfg.zone());
        for (int day = 0; day <= 1; day++) {
            LocalDate date = now.toLocalDate().plusDays(day);
            for (LocalTime t : cfg.times()) {
                ZonedDateTime slot = date.atTime(t).atZone(cfg.zone());
                if (slot.isAfter(now)) return slot;
            }
        }
        return null;
    }

    private static @Nullable ZonedDateTime mostRecentSlot(DailyCamsConfig cfg, ZonedDateTime now) {
        for (int day = 0; day <= 1; day++) {
            LocalDate date = now.toLocalDate().minusDays(day);
            for (LocalTime t : cfg.times().reversed()) {
                ZonedDateTime slot = date.atTime(t).atZone(cfg.zone());
                if (!slot.isAfter(now)) return slot;
            }
        }
        return null;
    }

    private void markDone(ZonedDateTime slot) {
        lastSlot = slot;
        State s = new State();
        s.lastSlot = slot.toString();
        try {
            Json.writeAtomically(stateFile, Json.GSON.toJson(s));
        } catch (Exception e) {
            DailyCams.LOGGER.warn("Could not write {}: {}", stateFile, e.getMessage());
        }
    }
}
