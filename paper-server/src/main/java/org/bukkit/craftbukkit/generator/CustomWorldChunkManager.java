package org.bukkit.craftbukkit.generator;

import com.google.common.base.Preconditions;
import com.mojang.serialization.MapCodec;
import java.util.stream.Stream;
import net.minecraft.core.Holder;
import net.minecraft.core.QuartPos;
import net.minecraft.world.level.biome.BiomeSource;
import net.minecraft.world.level.biome.Climate;
import org.bukkit.craftbukkit.block.CraftBiome;
import org.bukkit.generator.BiomeParameterPoint;
import org.bukkit.generator.BiomeProvider;
import org.bukkit.generator.WorldInfo;

public class CustomWorldChunkManager extends BiomeSource {

    private final WorldInfo worldInfo;
    private final BiomeProvider biomeProvider;
    public final BiomeSource vanillaBiomeSource;
    private final boolean usesParameterPoint; // BlurpWorld - skip climate sampling for providers that ignore it

    public CustomWorldChunkManager(WorldInfo worldInfo, BiomeProvider biomeProvider, BiomeSource vanillaBiomeSource) {
        this.worldInfo = worldInfo;
        this.biomeProvider = biomeProvider;
        this.vanillaBiomeSource = vanillaBiomeSource;
        this.usesParameterPoint = usesParameterPoint(biomeProvider); // BlurpWorld
    }

    // BlurpWorld start - skip climate sampling for providers that ignore it
    private static boolean usesParameterPoint(BiomeProvider biomeProvider) {
        try {
            return biomeProvider.getClass().getMethod("getBiome", WorldInfo.class, int.class, int.class, int.class, BiomeParameterPoint.class)
                .getDeclaringClass() != BiomeProvider.class;
        } catch (NoSuchMethodException exception) {
            return true;
        }
    }
    // BlurpWorld end - skip climate sampling for providers that ignore it

    @Override
    protected MapCodec<? extends BiomeSource> codec() {
        throw new UnsupportedOperationException("Cannot serialize CustomWorldChunkManager");
    }

    @Override
    public Holder<net.minecraft.world.level.biome.Biome> getNoiseBiome(int x, int y, int z, Climate.Sampler noise) {
        // BlurpWorld start - sampling the climate costs as much as vanilla biome generation, only do it when the provider reads it
        final int blockX = QuartPos.toBlock(x);
        final int blockY = QuartPos.toBlock(y);
        final int blockZ = QuartPos.toBlock(z);
        Holder<net.minecraft.world.level.biome.Biome> biome = CraftBiome.bukkitToMinecraftHolder(this.usesParameterPoint
            ? this.biomeProvider.getBiome(this.worldInfo, blockX, blockY, blockZ, CraftBiomeParameterPoint.createBiomeParameterPoint(noise, noise.sample(x, y, z)))
            : this.biomeProvider.getBiome(this.worldInfo, blockX, blockY, blockZ)
        );
        // BlurpWorld end - sampling the climate costs as much as vanilla biome generation, only do it when the provider reads it
        Preconditions.checkArgument(biome != null, "Cannot set the biome to %s", biome);

        return biome;
    }

    @Override
    protected Stream<Holder<net.minecraft.world.level.biome.Biome>> collectPossibleBiomes() {
        return this.biomeProvider.getBiomes(this.worldInfo).stream().map(biome -> {
            Holder<net.minecraft.world.level.biome.Biome> b = CraftBiome.bukkitToMinecraftHolder(biome);
            Preconditions.checkArgument(b != null, "Cannot use the biome %s", biome);
            return b;
        });
    }
}
