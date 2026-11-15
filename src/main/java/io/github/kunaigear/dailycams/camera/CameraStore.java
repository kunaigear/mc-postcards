package io.github.kunaigear.dailycams.camera;

import com.google.gson.reflect.TypeToken;
import io.github.kunaigear.dailycams.DailyCams;
import io.github.kunaigear.dailycams.util.Json;
import org.jetbrains.annotations.Nullable;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/** Camera list persisted to {@code cameras.json}. Only touched from the server thread. */
public final class CameraStore {
    public static final Pattern VALID_NAME = Pattern.compile("[A-Za-z0-9_-]{1,32}");

    private final Path file;
    private final Map<String, CameraSpec> cameras = new LinkedHashMap<>();

    public CameraStore(Path file) {
        this.file = file;
    }

    /**
     * Loads the cameras. If the file can't be read, it's backed up first (the next save would otherwise replace it
     * with only the cameras still in memory) and the cameras loaded before are kept. Problems go to {@code problems}.
     */
    public void load(List<String> problems) {
        if (!Files.exists(file)) {
            cameras.clear();
            return;
        }
        Map<String, CameraSpec> loaded = new LinkedHashMap<>();
        try {
            List<CameraSpec> list = Json.GSON.fromJson(Files.readString(file), new TypeToken<List<CameraSpec>>() {}.getType());
            if (list != null) {
                for (CameraSpec c : list) {
                    if (c != null && c.name != null && c.dimension != null) loaded.put(key(c.name), c);
                }
            }
        } catch (Exception e) {
            Path copy = Json.backupBroken(file);
            String problem = file.getFileName() + " has an error and wasn't loaded (" + e.getMessage() + "). "
                    + (cameras.isEmpty() ? "No cameras are loaded" : "Kept the " + cameras.size() + " camera(s) loaded before")
                    + (copy != null ? "; a copy of the file is in " + copy.getFileName() + "." : ".");
            DailyCams.LOGGER.error(problem);
            problems.add(problem);
            return;
        }
        cameras.clear();
        cameras.putAll(loaded);
    }

    public void save() {
        try {
            Json.writeAtomically(file, Json.GSON.toJson(new ArrayList<>(cameras.values())));
        } catch (IOException e) {
            DailyCams.LOGGER.error("Could not write {}", file, e);
        }
    }

    public @Nullable CameraSpec get(String name) {
        return cameras.get(key(name));
    }

    public Collection<CameraSpec> all() {
        return Collections.unmodifiableCollection(cameras.values());
    }

    public List<CameraSpec> ownedBy(@Nullable UUID owner) {
        return cameras.values().stream().filter(c -> Objects.equals(c.owner, owner)).toList();
    }

    public @Nullable CameraSpec personalOf(UUID owner) {
        return cameras.values().stream().filter(c -> c.personal && owner.equals(c.owner)).findFirst().orElse(null);
    }

    public long namedCount(UUID owner) {
        return cameras.values().stream().filter(c -> !c.personal && owner.equals(c.owner)).count();
    }

    /** True if some player's personal camera is called this. */
    public boolean isPersonalName(String name) {
        CameraSpec c = get(name);
        return c != null && c.personal;
    }

    public void rename(CameraSpec camera, String newName) {
        cameras.remove(key(camera.name));
        camera.name = newName;
        put(camera);
    }

    public void put(CameraSpec camera) {
        cameras.put(key(camera.name), camera);
        save();
    }

    public boolean remove(String name) {
        boolean removed = cameras.remove(key(name)) != null;
        if (removed) save();
        return removed;
    }

    private static String key(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
