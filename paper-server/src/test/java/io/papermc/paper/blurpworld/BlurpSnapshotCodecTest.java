package io.papermc.paper.blurpworld;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Map;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.junit.jupiter.api.Test;

class BlurpSnapshotCodecTest {

    @Test
    void roundTripsCompressedChunkData() throws Exception {
        CompoundTag tag = new CompoundTag();
        tag.putString("Status", "minecraft:full");
        tag.putInt("DataVersion", 4440);
        BlurpCompressedChunk chunk = BlurpCompressedChunk.encode(tag, 3);
        BlurpSnapshotData snapshot = BlurpSnapshotData.create(
            "arena", "checkpoint", Map.of("minecraft:overworld/chunk", Map.of(ChunkPos.asLong(2, -3), chunk))
        );

        byte[] encoded = BlurpSnapshotCodec.encode(snapshot);
        BlurpSnapshotData decoded = BlurpSnapshotCodec.decode(encoded);
        CompoundTag decodedTag = decoded.stores().get("minecraft:overworld/chunk").get(ChunkPos.asLong(2, -3)).decode();

        assertEquals(snapshot.id(), decoded.id());
        assertEquals(snapshot.sha256(), decoded.sha256());
        assertEquals("minecraft:full", decodedTag.getStringOr("Status", ""));
        assertEquals(4440, decodedTag.getIntOr("DataVersion", 0));
    }

    @Test
    void rejectsInvalidHeader() {
        assertThrows(IllegalArgumentException.class, () -> BlurpSnapshotCodec.decode(new byte[32]));
    }
}
