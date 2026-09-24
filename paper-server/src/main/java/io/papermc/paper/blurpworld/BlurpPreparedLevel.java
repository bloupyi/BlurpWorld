package io.papermc.paper.blurpworld;

import io.papermc.paper.configuration.Configurations;
import io.papermc.paper.configuration.PaperConfigurations;
import io.papermc.paper.configuration.WorldConfiguration;
import io.papermc.paper.world.saveddata.PaperLevelOverrides;
import io.papermc.paper.world.saveddata.PaperWorldMetadata;
import io.papermc.paper.world.saveddata.PaperWorldPDC;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.server.MinecraftServer;
import net.minecraft.world.clock.ServerClockManager;
import net.minecraft.world.entity.raid.Raids;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.TicketStorage;
import net.minecraft.world.level.border.WorldBorder;
import net.minecraft.world.level.gamerules.GameRuleMap;
import net.minecraft.world.level.gamerules.GameRules;
import net.minecraft.world.level.levelgen.NoiseGeneratorSettings;
import net.minecraft.world.level.levelgen.RandomState;
import net.minecraft.world.level.levelgen.WorldGenSettings;
import net.minecraft.world.level.saveddata.SavedDataType;
import net.minecraft.world.level.saveddata.WanderingTraderData;
import net.minecraft.world.level.saveddata.WeatherData;
import net.minecraft.world.level.storage.LevelResource;
import net.minecraft.world.level.storage.SavedDataStorage;
import net.minecraft.world.level.timers.TimerQueue;
import org.bukkit.craftbukkit.util.CraftNamespacedKey;
import org.jspecify.annotations.Nullable;
import org.spigotmc.SpigotWorldConfig;

/**
 * The parts of a memory world's {@code ServerLevel} that only decode configuration and saved data. They are built on
 * the preparation thread so that {@code Bukkit#createWorld} only assembles them on the server thread.
 */
record BlurpPreparedLevel(
    ResourceKey<Level> dimension,
    Path dataPath,
    SavedDataStorage savedData,
    SpigotWorldConfig spigotConfig,
    WorldConfiguration paperConfig,
    @Nullable RandomState randomState,
    long seed,
    UUID uuid
) {

    // Saved data the level reads while it is constructed
    private static final List<SavedDataType<?>> LEVEL_SAVED_DATA = List.of(
        WorldGenSettings.TYPE, PaperWorldMetadata.TYPE, PaperWorldPDC.TYPE, PaperLevelOverrides.TYPE,
        WeatherData.TYPE, TimerQueue.TYPE, ServerClockManager.TYPE, Raids.TYPE, TicketStorage.TYPE,
        WanderingTraderData.TYPE, WorldBorder.TYPE
    );

    static BlurpPreparedLevel build(String worldName, BlurpMemoryWorldStorage storage) {
        MinecraftServer server = MinecraftServer.getServer();
        // Same key as the default one of WorldCreator; createWorld ignores this preparation for any other key
        ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(worldName.toLowerCase(Locale.ENGLISH)));
        Path dimensionPath = server.storageSource.getDimensionPath(dimension);
        Path dataPath = dimensionPath.resolve(LevelResource.DATA.id());

        SavedDataStorage savedData = new SavedDataStorage(dataPath, server.getFixerUpper(), server.registryAccess(), storage.savedData());
        for (SavedDataType<?> type : LEVEL_SAVED_DATA) {
            savedData.get(type);
        }
        GameRules gameRules = new GameRules(server.getWorldData().enabledFeatures(), savedData.computeIfAbsent(GameRuleMap.TYPE));

        SpigotWorldConfig spigotConfig = new SpigotWorldConfig(worldName, CraftNamespacedKey.fromMinecraft(dimension.identifier()));
        Configurations.ContextMap contextMap = PaperConfigurations.createWorldContextMap(
            dimensionPath, dimension.identifier(), spigotConfig, server.registryAccess(), gameRules
        );
        WorldConfiguration paperConfig = server.paperConfigurations.createMemoryWorldConfig(contextMap);

        // Memory worlds never generate terrain, so their chunk map only needs the dummy noise router
        WorldGenSettings genSettings = savedData.get(WorldGenSettings.TYPE);
        long seed = genSettings == null ? 0L : genSettings.options().seed();
        RandomState randomState = genSettings == null
            ? null
            : RandomState.create(NoiseGeneratorSettings.dummy(), server.registryAccess().lookupOrThrow(Registries.NOISE), seed);
        return new BlurpPreparedLevel(dimension, dataPath, savedData, spigotConfig, paperConfig, randomState, seed, UUID.randomUUID());
    }
}
