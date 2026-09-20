package io.papermc.paper.blurpworld;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.UUID;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.Test;

class BlurpMemoryStorageBridgeTest {

    @Test
    void resolvesPreparedWorldByCustomDimensionPath() {
        String worldName = "bridge_test_" + UUID.randomUUID().toString().replace("-", "");
        BlurpMemoryStorageBridge.prepare(worldName, null);
        try {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(worldName));
            RegionStorageInfo info = new RegionStorageInfo("world", dimension, "chunk");

            assertNotNull(BlurpMemoryStorageBridge.attach(info));
        } finally {
            BlurpMemoryStorageBridge.discard(worldName);
        }
    }
}
