package io.papermc.paper.blurpworld;

import com.mojang.datafixers.DataFixer;
import com.mojang.logging.LogUtils;
import io.papermc.paper.configuration.WorldConfiguration;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.core.HolderLookup;
import net.minecraft.core.registries.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.storage.SavedDataStorage;
import org.bukkit.NamespacedKey;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.spigotmc.SpigotWorldConfig;

public final class BlurpMemoryStorageBridge {

    private static final Logger LOGGER = LogUtils.getClassLogger();
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
        BlurpPreparedLevel prepared = world.preparedLevel;
        if (prepared != null && prepared.dataPath().equals(dataFolder)) {
            return prepared.savedData();
        }
        world.preparedLevel = null; // created under another key, the prepared configs do not apply
        return new SavedDataStorage(dataFolder, fixerUpper, registries, world.savedData());
    }

    // Builds the configuration and saved data of the level off the server thread; createWorld falls back to building
    // them itself when this was not run or failed.
    static void prepareLevel(String worldName) {
        BlurpMemoryWorldStorage world = world(worldName);
        if (world == null) {
            return;
        }
        try {
            world.preparedLevel = BlurpPreparedLevel.build(worldName, world);
        } catch (RuntimeException exception) {
            LOGGER.warn("Could not prepare level data for memory world {} ahead of its creation", worldName, exception);
        }
    }

    public static SpigotWorldConfig spigotConfig(String worldName, NamespacedKey worldKey) {
        BlurpPreparedLevel prepared = preparedLevel(worldName);
        if (prepared != null && CraftNamespacedKey.fromMinecraft(prepared.dimension().identifier()).equals(worldKey)) {
            return prepared.spigotConfig();
        }
        return new SpigotWorldConfig(worldName, worldKey);
    }

    public static @Nullable WorldConfiguration paperConfig(String worldName, SpigotWorldConfig spigotConfig) {
        BlurpPreparedLevel prepared = preparedLevel(worldName);
        return prepared != null && prepared.spigotConfig() == spigotConfig ? prepared.paperConfig() : null;
    }

    public static RandomState randomState(String worldName, long seed) {
        BlurpPreparedLevel prepared = preparedLevel(worldName);
        if (prepared != null && prepared.randomState() != null && prepared.seed() == seed) {
            return prepared.randomState();
        }
        return RandomState.create(NoiseGeneratorSettings.dummy(), MinecraftServer.getServer().registryAccess().lookupOrThrow(Registries.NOISE), seed);
    }

    public static UUID levelUuid(String worldName) {
        BlurpPreparedLevel prepared = preparedLevel(worldName);
        return prepared != null ? prepared.uuid() : UUID.randomUUID();
    }

    public static void levelCreated(String worldName) {
        BlurpMemoryWorldStorage world = world(worldName);
        if (world != null) {
            world.preparedLevel = null;
        }
    }

    private static @Nullable BlurpPreparedLevel preparedLevel(String worldName) {
        BlurpMemoryWorldStorage world = world(worldName);
        return world == null ? null : world.preparedLevel;
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
