package io.papermc.paper.blurpworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.nbt.CompoundTag;
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

    @Test
    void readsHandOverCompressedRecordsForParallelDecoding() throws IOException {
        String worldName = "bridge_test_" + UUID.randomUUID().toString().replace("-", "");
        BlurpMemoryStorageBridge.prepare(worldName, null);
        try {
            ResourceKey<Level> dimension = ResourceKey.create(Registries.DIMENSION, Identifier.withDefaultNamespace(worldName));
            BlurpMemoryRegionStore store = BlurpMemoryStorageBridge.attach(new RegionStorageInfo(worldName, dimension, "chunk"));
            assertNotNull(store);
            CompoundTag tag = new CompoundTag();
            tag.putString("Status", "minecraft:full");
            tag.putInt("xPos", 3);
            store.write(3, -2, tag);

            assertNull(store.openRead(0, 0));
            assertEquals(tag, BlurpMemoryRegionStore.finishRead(store.openRead(3, -2)));
        } finally {
            BlurpMemoryStorageBridge.discard(worldName);
        }
    }
}
