package io.papermc.paper.blurpworld;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public final class BlurpMemoryRegionStore {

    private final BlurpMemoryWorldStorage world;
    private final ConcurrentHashMap<Long, BlurpCompressedChunk> chunks = new ConcurrentHashMap<>();

    BlurpMemoryRegionStore(BlurpMemoryWorldStorage world) {
        this.world = world;
    }

    public boolean contains(int chunkX, int chunkZ) {
        return this.chunks.containsKey(ChunkPos.pack(chunkX, chunkZ));
    }

    public void write(int chunkX, int chunkZ, @Nullable CompoundTag tag) throws IOException {
        long key = ChunkPos.pack(chunkX, chunkZ);
        this.world.writeLocked(() -> {
            if (tag == null) {
                this.chunks.remove(key);
            } else {
                this.chunks.put(key, BlurpCompressedChunk.encode(tag, BlurpMemoryStorageBridge.configuration().compressionLevel()));
            }
        });
    }

    public @Nullable CompoundTag read(int chunkX, int chunkZ) throws IOException {
        BlurpCompressedChunk chunk = this.chunks.get(ChunkPos.pack(chunkX, chunkZ));
        return chunk == null ? null : chunk.decode();
    }

    Map<Long, BlurpCompressedChunk> copyChunks() {
        Map<Long, BlurpCompressedChunk> copy = new HashMap<>();
        this.chunks.forEach((key, value) -> copy.put(key, value.copy()));
        return Map.copyOf(copy);
    }

    void replaceChunks(Map<Long, BlurpCompressedChunk> source) {
        this.chunks.clear();
        source.forEach((key, value) -> this.chunks.put(key, value.copy()));
    }

    int chunkCount() {
        return this.chunks.size();
    }

    long compressedBytes() {
        return this.chunks.values().stream().mapToLong(chunk -> chunk.data().length).sum();
    }

    long uncompressedBytes() {
        return this.chunks.values().stream().mapToLong(BlurpCompressedChunk::rawSize).sum();
    }
}
