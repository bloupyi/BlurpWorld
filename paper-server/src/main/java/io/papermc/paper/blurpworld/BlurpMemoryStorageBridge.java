package io.papermc.paper.blurpworld;

import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import com.mojang.datafixers.DataFixer;
import java.nio.file.Path;
import net.minecraft.core.HolderLookup;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.storage.SavedDataStorage;
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
            world = world(info.dimension().identifier().getPath());
        }
        if (world == null) {
            return null;
        }
        return world.store(info.type());
    }

    public static boolean isPreparedWorld(String worldName) {
        return world(worldName) != null;
    }

    public static SavedDataStorage createSavedDataStorage(
        String worldName,
        Path dataFolder,
        DataFixer fixerUpper,
        HolderLookup.Provider registries
    ) {
        BlurpMemoryWorldStorage world = world(worldName);
        if (world == null) {
            throw new IllegalStateException("No memory world is prepared with the name " + worldName);
        }
        return new SavedDataStorage(dataFolder, fixerUpper, registries, world.savedData());
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
