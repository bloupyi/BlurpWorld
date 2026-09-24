package io.papermc.paper.blurpworld;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.resources.Identifier;
import org.jspecify.annotations.Nullable;

final class BlurpMemoryWorldStorage {

    @FunctionalInterface
    interface IoRunnable {
        void run() throws IOException;
    }

    private final String worldName;
    private final ConcurrentHashMap<String, BlurpMemoryRegionStore> stores = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Identifier, CompoundTag> savedData = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    BlurpMemoryWorldStorage(String worldName) {
        this.worldName = worldName;
    }

    BlurpMemoryRegionStore store(String key) {
        return this.stores.computeIfAbsent(key, ignored -> new BlurpMemoryRegionStore(this, BlurpMemoryRegionStore.CHUNK_STORE.equals(key)));
    }

    volatile @Nullable BlurpPreparedLevel preparedLevel;

    Map<Identifier, CompoundTag> savedData() {
        return this.savedData;
    }

    void writeLocked(IoRunnable operation) throws IOException {
        this.lock.readLock().lock();
        try {
            operation.run();
        } finally {
            this.lock.readLock().unlock();
        }
    }

    BlurpSnapshotData snapshot(String label) {
        this.lock.writeLock().lock();
        try {
            Map<String, Map<Long, BlurpCompressedChunk>> copiedStores = new HashMap<>();
            this.stores.forEach((key, store) -> copiedStores.put(key, store.copyChunks()));
            Map<String, BlurpCompressedChunk> copiedSavedData = new HashMap<>();
            this.savedData.forEach((key, data) -> {
                try {
                    copiedSavedData.put(key.toString(), BlurpCompressedChunk.encode(data, BlurpMemoryStorageBridge.configuration().compressionLevel()));
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
            return BlurpSnapshotData.create(this.worldName, label, copiedStores, copiedSavedData);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    void restore(BlurpSnapshotData snapshot) {
        this.lock.writeLock().lock();
        try {
            this.stores.clear();
            snapshot.stores().forEach((key, chunks) -> this.store(key).replaceChunks(chunks));
            this.savedData.clear();
            snapshot.savedData().forEach((key, data) -> {
                try {
                    this.savedData.put(Identifier.parse(key), data.decode());
                } catch (IOException exception) {
                    throw new UncheckedIOException(exception);
                }
            });
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    BlurpWorldStatisticsSnapshot statistics() {
        return new BlurpWorldStatisticsSnapshot(
            this.worldName,
            this.stores.size(),
            this.stores.values().stream().mapToInt(BlurpMemoryRegionStore::chunkCount).sum(),
            this.stores.values().stream().mapToLong(BlurpMemoryRegionStore::compressedBytes).sum(),
            this.stores.values().stream().mapToLong(BlurpMemoryRegionStore::uncompressedBytes).sum()
        );
    }

    record BlurpWorldStatisticsSnapshot(String worldName, int storageCount, int chunkCount, long compressedBytes, long uncompressedBytes) {
    }
}
