package io.papermc.paper.blurpworld;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import ca.spottedleaf.moonrise.patches.starlight.util.SaveUtil;
import net.minecraft.SharedConstants;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;
import net.minecraft.world.level.ChunkPos;
import org.jspecify.annotations.Nullable;

public final class BlurpMemoryRegionStore {

    static final String CHUNK_STORE = "chunk";

    private final BlurpMemoryWorldStorage world;
    private final ConcurrentHashMap<Long, BlurpCompressedChunk> chunks = new ConcurrentHashMap<>();
    private final boolean emptyWhenMissing;

    BlurpMemoryRegionStore(BlurpMemoryWorldStorage world, boolean emptyWhenMissing) {
        this.world = world;
        this.emptyWhenMissing = emptyWhenMissing;
    }

    public boolean contains(int chunkX, int chunkZ) {
        return this.chunks.containsKey(ChunkPos.pack(chunkX, chunkZ));
    }

    public void write(int chunkX, int chunkZ, @Nullable CompoundTag tag) throws IOException {
        long key = ChunkPos.pack(chunkX, chunkZ);
        this.world.writeLocked(() -> {
            if (tag == null || (this.emptyWhenMissing && isEmptyChunk(tag))) {
                // A missing chunk already loads as an empty one, so empty chunks are never stored
                this.chunks.remove(key);
            } else {
                this.chunks.put(key, BlurpCompressedChunk.encode(tag, BlurpMemoryStorageBridge.configuration().compressionLevel()));
            }
        });
    }

    public @Nullable CompoundTag read(int chunkX, int chunkZ) throws IOException {
        BlurpCompressedChunk chunk = this.chunks.get(ChunkPos.pack(chunkX, chunkZ));
        if (chunk != null) {
            return chunk.decode();
        }
        return this.emptyWhenMissing ? emptyChunk(chunkX, chunkZ) : null;
    }

    public @Nullable DataInputStream openRead(int chunkX, int chunkZ) {
        BlurpCompressedChunk chunk = this.chunks.get(ChunkPos.pack(chunkX, chunkZ));
        return chunk == null && !this.emptyWhenMissing ? null : new PendingRead(chunk, chunkX, chunkZ);
    }

    public static CompoundTag finishRead(DataInputStream input) throws IOException {
        if (!(input instanceof PendingRead read)) {
            throw new IOException("Unexpected memory chunk input: " + input);
        }
        return read.chunk == null ? emptyChunk(read.chunkX, read.chunkZ) : read.chunk.decode();
    }

    // Memory worlds never run terrain generation: a chunk that was never saved loads directly as an empty full chunk,
    // in the same form as a saved void chunk, so no generator, biome noise or lighting pass runs for it.
    static CompoundTag emptyChunk(int chunkX, int chunkZ) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("DataVersion", SharedConstants.getCurrentVersion().dataVersion().version());
        tag.putInt("xPos", chunkX);
        tag.putInt("zPos", chunkZ);
        tag.putString("Status", "minecraft:full");
        tag.putBoolean("isLightOn", false); // Starlight only checks for the tag's presence
        tag.putInt(SaveUtil.STARLIGHT_VERSION_TAG, SaveUtil.STARLIGHT_LIGHT_VERSION);
        return tag;
    }

    static boolean isEmptyChunk(CompoundTag tag) {
        if (!"minecraft:full".equals(tag.getStringOr("Status", ""))
            || tag.contains("ChunkBukkitValues")
            || !tag.getListOrEmpty("block_entities").isEmpty()
            || !tag.getListOrEmpty("block_ticks").isEmpty()
            || !tag.getListOrEmpty("fluid_ticks").isEmpty()
            || !tag.getListOrEmpty("entities").isEmpty()) {
            return false;
        }
        for (Tag postProcessing : tag.getListOrEmpty("PostProcessing")) {
            if (!(postProcessing instanceof ListTag list) || !list.isEmpty()) {
                return false;
            }
        }
        CompoundTag structures = tag.getCompoundOrEmpty("structures");
        if (!structures.getCompoundOrEmpty("starts").isEmpty() || !structures.getCompoundOrEmpty("References").isEmpty()) {
            return false;
        }
        for (Tag sectionTag : tag.getListOrEmpty("sections")) {
            if (!(sectionTag instanceof CompoundTag section)
                || section.contains("BlockLight")
                || section.contains("SkyLight")
                || !isSingleValue(section.getCompoundOrEmpty("block_states"), "minecraft:air", true)
                || !isSingleValue(section.getCompoundOrEmpty("biomes"), "minecraft:plains", false)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isSingleValue(CompoundTag container, String expected, boolean blockState) {
        if (container.isEmpty()) {
            return true;
        }
        ListTag palette = container.getListOrEmpty("palette");
        if (palette.size() != 1) {
            return false;
        }
        String value = blockState
            ? palette.getCompound(0).map(state -> state.getStringOr("Name", "")).orElse("")
            : palette.getString(0).orElse("");
        return expected.equals(value) && (!blockState || palette.getCompound(0).map(state -> !state.contains("Properties")).orElse(false));
    }

    // Carries a record through Moonrise's read pipeline so decoding runs on its parallel decompression workers.
    private static final class PendingRead extends DataInputStream {

        private final @Nullable BlurpCompressedChunk chunk;
        private final int chunkX;
        private final int chunkZ;

        private PendingRead(@Nullable BlurpCompressedChunk chunk, int chunkX, int chunkZ) {
            super(InputStream.nullInputStream());
            this.chunk = chunk;
            this.chunkX = chunkX;
            this.chunkZ = chunkZ;
        }
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
