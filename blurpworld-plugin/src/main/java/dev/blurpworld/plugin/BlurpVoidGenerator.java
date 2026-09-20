package dev.blurpworld.plugin;

import java.util.Random;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.generator.ChunkGenerator;
import org.bukkit.generator.WorldInfo;
import org.jetbrains.annotations.NotNull;

final class BlurpVoidGenerator extends ChunkGenerator {

    @Override
    public void generateSurface(
        @NotNull WorldInfo worldInfo,
        @NotNull Random random,
        int chunkX,
        int chunkZ,
        @NotNull ChunkData chunkData
    ) {
        if (chunkX == 0 && chunkZ == 0) {
            chunkData.setRegion(0, 64, 0, 16, 65, 16, Material.BEDROCK);
        }
    }

    @Override
    public Location getFixedSpawnLocation(@NotNull World world, @NotNull Random random) {
        return new Location(world, 0.5D, 65.0D, 0.5D);
    }
}
