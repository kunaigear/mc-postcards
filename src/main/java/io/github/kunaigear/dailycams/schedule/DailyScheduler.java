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
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.SplittableRandom;

/**
 * Decides when the daily run is due. The last started slot and the cameras of that run that haven't finished yet
 * are persisted in {@code state.json}, so restarts neither skip nor repeat a run, and a run interrupted by a
 * restart or crash can be finished afterwards.
 */
public final class DailyScheduler {
    private static final class State {
        String lastSlot;
        List<String> pending;
    }

    /** One Minecraft day. */
    private static final long MAX_OFFSET_SECONDS = 20 * 60;

    private final Path stateFile;
    private @Nullable ZonedDateTime lastSlot;
    /** Names of cameras from the last run that haven't finished (rendered and delivered, or failed) yet. */
    private final Set<String> pending = new LinkedHashSet<>();

    public DailyScheduler(Path stateFile) {
        this.stateFile = stateFile;
    }

    public void load(DailyCamsConfig cfg) {
        lastSlot = null;
        pending.clear();
        try {
            if (Files.exists(stateFile)) {
                State s = Json.GSON.fromJson(Files.readString(stateFile), State.class);
                if (s != null && s.lastSlot != null) lastSlot = ZonedDateTime.parse(s.lastSlot);
                if (s != null && s.pending != null) pending.addAll(s.pending);
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
        if (slot == null || done(cfg, slot)) return false;

        markDone(slot);
        boolean missed = now.isAfter(start(cfg, slot).plusMinutes(5));
        if (missed && !cfg.catchUpMissedRuns) {
            DailyCams.LOGGER.info("Skipping missed daily run scheduled for {}", slot);
            return false;
        }
        return true;
    }

    /** When the next run starts. */
    public @Nullable ZonedDateTime nextSlot(DailyCamsConfig cfg) {
        ZonedDateTime now = ZonedDateTime.now(cfg.zone());
        ZonedDateTime next = null;
        for (int day = -1; day <= 1; day++) {
            LocalDate date = now.toLocalDate().plusDays(day);
            for (LocalTime t : cfg.times()) {
                ZonedDateTime slot = date.atTime(t).atZone(cfg.zone());
                if (done(cfg, slot)) continue;
                ZonedDateTime start = start(cfg, slot);
                if (start.isAfter(now) && (next == null || start.isBefore(next))) next = start;
            }
        }
        return next;
    }

    /** The slot whose run started most recently by {@code now}. */
    private static @Nullable ZonedDateTime mostRecentSlot(DailyCamsConfig cfg, ZonedDateTime now) {
        ZonedDateTime recent = null;
        for (int day = 0; day <= 2; day++) {
            LocalDate date = now.toLocalDate().minusDays(day);
            for (LocalTime t : cfg.times()) {
                ZonedDateTime slot = date.atTime(t).atZone(cfg.zone());
                ZonedDateTime start = start(cfg, slot);
                if (!start.isAfter(now) && (recent == null || start.isAfter(start(cfg, recent)))) recent = slot;
            }
        }
        return recent;
    }

    /** Offsets can swap two slots less than 20 minutes apart, so runs are ordered by when they start. */
    private boolean done(DailyCamsConfig cfg, ZonedDateTime slot) {
        return lastSlot != null && !start(cfg, slot).toInstant().isAfter(start(cfg, lastSlot).toInstant());
    }

    /**
     * The slot plus its random offset, if that's on. The offset is derived from the slot, so it survives restarts
     * and reloads. SplittableRandom mixes its seed; java.util.Random gives similar first values for nearby seeds.
     */
    private static ZonedDateTime start(DailyCamsConfig cfg, ZonedDateTime slot) {
        if (!cfg.randomScheduleOffset) return slot;
        return slot.plusSeconds(new SplittableRandom(slot.toEpochSecond()).nextLong(MAX_OFFSET_SECONDS));
    }

    /** Records which cameras the run that just started includes. */
    public void startRun(Collection<String> cameraNames) {
        pending.clear();
        pending.addAll(cameraNames);
        save();
    }

    /** A camera of the current run has finished, successfully or not. */
    public void finished(String cameraName) {
        if (pending.remove(cameraName)) save();
    }

    /** Cameras of the last run that hadn't finished when the server stopped. */
    public List<String> unfinished() {
        return List.copyOf(pending);
    }

    public void clearUnfinished() {
        if (!pending.isEmpty()) {
            pending.clear();
            save();
        }
    }

    private void markDone(ZonedDateTime slot) {
        lastSlot = slot;
        pending.clear(); // a new run replaces whatever was left of the previous one
        save();
    }

    private void save() {
        State s = new State();
        s.lastSlot = lastSlot != null ? lastSlot.toString() : null;
        s.pending = pending.isEmpty() ? null : List.copyOf(pending);
        try {
            Json.writeAtomically(stateFile, Json.GSON.toJson(s));
        } catch (Exception e) {
            DailyCams.LOGGER.warn("Could not write {}: {}", stateFile, e.getMessage());
        }
    }
}
