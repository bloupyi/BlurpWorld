package io.papermc.paper.blurpworld;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantReadWriteLock;

final class BlurpMemoryWorldStorage {

    @FunctionalInterface
    interface IoRunnable {
        void run() throws IOException;
    }

    private final String worldName;
    private final ConcurrentHashMap<String, BlurpMemoryRegionStore> stores = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    BlurpMemoryWorldStorage(String worldName) {
        this.worldName = worldName;
    }

    BlurpMemoryRegionStore store(String key) {
        return this.stores.computeIfAbsent(key, ignored -> new BlurpMemoryRegionStore(this));
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
            return BlurpSnapshotData.create(this.worldName, label, copiedStores);
        } finally {
            this.lock.writeLock().unlock();
        }
    }

    void restore(BlurpSnapshotData snapshot) {
        this.lock.writeLock().lock();
        try {
            this.stores.clear();
            snapshot.stores().forEach((key, chunks) -> this.store(key).replaceChunks(chunks));
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
