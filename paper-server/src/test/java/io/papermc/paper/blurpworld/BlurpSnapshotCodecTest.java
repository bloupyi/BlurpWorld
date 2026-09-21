package io.papermc.paper.blurpworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;
import org.bukkit.support.environment.Normal;

@Normal
class BlurpSnapshotCodecTest {

    @Test
    void roundTripsCompressedChunkData() throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        tag.putInt("DataVersion", 4440);
        CompoundTag metadata = new CompoundTag();
        metadata.putLong("Time", 12000L);
        BlurpCompressedChunk chunk = BlurpCompressedChunk.encode(tag, 3);
        BlurpSnapshotData snapshot = BlurpSnapshotData.create(
            "arena", "checkpoint", Map.of("minecraft:overworld/chunk", Map.of(ChunkPos.pack(2, -3), chunk)),
            Map.of("minecraft:world_clocks", BlurpCompressedChunk.encode(metadata, 3))
        );

        byte[] encoded = BlurpSnapshotCodec.encode(snapshot);
        BlurpSnapshotData decoded = BlurpSnapshotCodec.decode(encoded);
        CompoundTag decodedTag = decoded.stores().get("minecraft:overworld/chunk").get(ChunkPos.pack(2, -3)).decode();

        assertEquals(snapshot.id(), decoded.id());
        assertEquals(snapshot.sha256(), decoded.sha256());
        assertEquals("minecraft:full", decodedTag.getStringOr("Status", ""));
        assertEquals(4440, decodedTag.getIntOr("DataVersion", 0));
        assertEquals(12000L, decoded.savedData().get("minecraft:world_clocks").decode().getLongOr("Time", 0L));
    }

    @Test
    void rejectsInvalidHeader() {
        assertThrows(IllegalArgumentException.class, () -> BlurpSnapshotCodec.decode(new byte[32]));
    }

    @Test
    void renamesSnapshotAndRecomputesChecksum() throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        BlurpSnapshotData original = BlurpSnapshotData.create(
            "arena", "before", Map.of("chunk", Map.of(ChunkPos.pack(0, 0), BlurpCompressedChunk.encode(tag, 3))), Map.of()
        );

        BlurpSnapshotData renamed = original.withLabel("after");
        BlurpSnapshotData decoded = BlurpSnapshotCodec.decode(BlurpSnapshotCodec.encode(renamed));

        assertEquals(original.id(), renamed.id());
        assertEquals("after", decoded.label());
        assertNotEquals(original.sha256(), renamed.sha256());
        assertEquals(renamed.sha256(), decoded.sha256());
    }
}
