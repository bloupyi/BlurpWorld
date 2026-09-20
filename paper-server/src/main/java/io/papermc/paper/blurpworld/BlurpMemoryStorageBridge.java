package io.papermc.paper.blurpworld;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.jspecify.annotations.Nullable;

public final class BlurpMemoryStorageBridge {

    private static final ConcurrentHashMap<String, BlurpMemoryWorldStorage> WORLDS = new ConcurrentHashMap<>();
    private static volatile BlurpWorldConfiguration configuration = BlurpWorldConfiguration.defaults();

    private BlurpMemoryStorageBridge() {
    }

    static BlurpWorldConfiguration configuration() {
        return configuration;
    }

    static void configure(BlurpWorldConfiguration value) {
        configuration = value;
    }

    static void prepare(String worldName, @Nullable BlurpSnapshotData snapshot) {
        String key = normalize(worldName);
        BlurpMemoryWorldStorage storage = new BlurpMemoryWorldStorage(worldName);
        if (snapshot != null) {
            storage.restore(snapshot);
        }
        if (WORLDS.putIfAbsent(key, storage) != null) {
            throw new IllegalStateException("A memory world is already prepared with the name " + worldName);
        }
    }

    static @Nullable BlurpMemoryWorldStorage world(String worldName) {
        return WORLDS.get(normalize(worldName));
    }

    public static @Nullable BlurpMemoryRegionStore attach(RegionStorageInfo info) {
        BlurpMemoryWorldStorage world = world(info.level());
        if (world == null) {
            return null;
        }
        return world.store(info.type());
    }

    static boolean discard(String worldName) {
        return WORLDS.remove(normalize(worldName)) != null;
    }

    static boolean contains(String worldName) {
        return WORLDS.containsKey(normalize(worldName));
    }

    static Map<String, BlurpMemoryWorldStorage> worlds() {
        return Map.copyOf(WORLDS);
    }

    private static String normalize(String worldName) {
        return worldName.toLowerCase(Locale.ROOT);
    }
}
