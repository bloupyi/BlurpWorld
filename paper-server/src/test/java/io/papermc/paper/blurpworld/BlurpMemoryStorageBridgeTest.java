package io.papermc.paper.blurpworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.UUID;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.StringTag;
import net.minecraft.core.registries.Registries;
import net.minecraft.resources.Identifier;
import net.minecraft.resources.ResourceKey;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.chunk.storage.RegionStorageInfo;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

class BlurpMemoryStorageBridgeTest {

    @BeforeAll
    static void detectVersion() {
        SharedConstants.tryDetectVersion();
    }

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
            ListTag content = new ListTag();
            content.add(section("minecraft:stone"));
            tag.put("sections", content);
            store.write(3, -2, tag);

            assertEquals(tag, BlurpMemoryRegionStore.finishRead(store.openRead(3, -2)));

            // Chunks that were never saved load as empty full chunks instead of being generated
            CompoundTag empty = BlurpMemoryRegionStore.finishRead(store.openRead(7, 8));
            assertEquals("minecraft:full", empty.getStringOr("Status", ""));
            assertEquals(7, empty.getIntOr("xPos", 0));
            assertEquals(8, empty.getIntOr("zPos", 0));
            assertEquals(empty, store.read(7, 8));

            // Untouched empty chunks are dropped on save instead of bloating the store
            CompoundTag saved = BlurpMemoryRegionStore.emptyChunk(7, 8);
            ListTag sections = new ListTag();
            sections.add(section("minecraft:air"));
            saved.put("sections", sections);
            store.write(7, 8, saved);
            assertFalse(store.contains(7, 8));
            sections.add(section("minecraft:stone"));
            store.write(7, 8, saved);
            assertTrue(store.contains(7, 8));

            BlurpMemoryRegionStore entities = BlurpMemoryStorageBridge.attach(new RegionStorageInfo(worldName, dimension, "entities"));
            assertNotNull(entities);
            assertNull(entities.openRead(7, 8));
            assertNull(entities.read(7, 8));
        } finally {
            BlurpMemoryStorageBridge.discard(worldName);
        }
    }

    private static CompoundTag section(String block) {
        CompoundTag state = new CompoundTag();
        state.putString("Name", block);
        ListTag blockPalette = new ListTag();
        blockPalette.add(state);
        CompoundTag blockStates = new CompoundTag();
        blockStates.put("palette", blockPalette);
        ListTag biomePalette = new ListTag();
        biomePalette.add(StringTag.valueOf("minecraft:plains"));
        CompoundTag biomes = new CompoundTag();
        biomes.put("palette", biomePalette);
        CompoundTag section = new CompoundTag();
        section.putByte("Y", (byte) 0);
        section.put("block_states", blockStates);
        section.put("biomes", biomes);
        return section;
    }
}
