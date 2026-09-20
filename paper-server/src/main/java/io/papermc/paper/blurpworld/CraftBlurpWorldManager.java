package io.papermc.paper.blurpworld;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.craftbukkit.CraftServer;

public final class CraftBlurpWorldManager implements BlurpWorldManager, AutoCloseable {

    private final CraftServer server;
    private final ExecutorService snapshotExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final LinkedHashMap<UUID, BlurpSnapshotData> snapshots = new LinkedHashMap<>(16, 0.75F, true);
    private long snapshotBytes;

    public CraftBlurpWorldManager(CraftServer server) {
        this.server = server;
    }

    @Override
    public BlurpWorldConfiguration configuration() {
        return BlurpMemoryStorageBridge.configuration();
    }

    @Override
    public synchronized void configure(BlurpWorldConfiguration configuration) {
        BlurpMemoryStorageBridge.configure(configuration);
        this.evictToLimit();
    }

    @Override
    public void prepare(String worldName) {
        validateWorldName(worldName);
        Preconditions.checkState(this.server.getWorld(worldName) == null, "World %s is already loaded", worldName);
        BlurpMemoryStorageBridge.prepare(worldName, null);
    }

    @Override
    public synchronized void prepare(String worldName, UUID snapshotId) {
        validateWorldName(worldName);
        Preconditions.checkState(this.server.getWorld(worldName) == null, "World %s is already loaded", worldName);
        BlurpSnapshotData snapshot = this.snapshots.get(snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown snapshot: " + snapshotId);
        }
        BlurpMemoryStorageBridge.prepare(worldName, snapshot);
    }

    @Override
    public boolean isPrepared(String worldName) {
        return BlurpMemoryStorageBridge.contains(worldName);
    }

    @Override
    public boolean isMemoryWorld(World world) {
        return this.isPrepared(world.getName());
    }

    @Override
    public boolean discard(String worldName) {
        Preconditions.checkState(this.server.getWorld(worldName) == null, "World %s must be unloaded before it is discarded", worldName);
        return BlurpMemoryStorageBridge.discard(worldName);
    }

    @Override
    public CompletableFuture<BlurpWorldSnapshot> snapshot(World world, String label) {
        Preconditions.checkArgument(this.isMemoryWorld(world), "World %s is not memory-backed", world.getName());
        CompletableFuture<Void> saved = new CompletableFuture<>();
        Runnable save = () -> {
            try {
                world.save(true);
                saved.complete(null);
            } catch (Throwable throwable) {
                saved.completeExceptionally(throwable);
            }
        };
        if (Bukkit.isPrimaryThread()) {
            save.run();
        } else {
            this.server.getServer().execute(save);
        }
        return saved.thenApplyAsync(ignored -> {
            BlurpMemoryWorldStorage storage = requireWorld(world.getName());
            BlurpSnapshotData snapshot = storage.snapshot(label);
            this.storeSnapshot(snapshot);
            return snapshot.metadata();
        }, this.snapshotExecutor);
    }

    @Override
    public synchronized Optional<BlurpWorldSnapshot> snapshot(UUID snapshotId) {
        BlurpSnapshotData snapshot = this.snapshots.get(snapshotId);
        return Optional.ofNullable(snapshot == null ? null : snapshot.metadata());
    }

    @Override
    public synchronized Collection<BlurpWorldSnapshot> snapshots() {
        return this.snapshots.values().stream().map(BlurpSnapshotData::metadata).toList();
    }

    @Override
    public synchronized boolean deleteSnapshot(UUID snapshotId) {
        BlurpSnapshotData removed = this.snapshots.remove(snapshotId);
        if (removed == null) {
            return false;
        }
        this.snapshotBytes -= removed.compressedBytes();
        return true;
    }

    @Override
    public synchronized byte[] exportSnapshot(UUID snapshotId) {
        BlurpSnapshotData snapshot = this.snapshots.get(snapshotId);
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown snapshot: " + snapshotId);
        }
        return BlurpSnapshotCodec.encode(snapshot);
    }

    @Override
    public BlurpWorldSnapshot importSnapshot(byte[] data) {
        Preconditions.checkArgument(data.length <= this.configuration().maxSnapshotBytes(), "Snapshot archive exceeds the configured memory limit");
        BlurpSnapshotData snapshot = BlurpSnapshotCodec.decode(data);
        this.storeSnapshot(snapshot);
        return snapshot.metadata();
    }

    @Override
    public BlurpWorldStatistics statistics(String worldName) {
        BlurpMemoryWorldStorage.BlurpWorldStatisticsSnapshot statistics = requireWorld(worldName).statistics();
        return new BlurpWorldStatistics(
            statistics.worldName(), statistics.storageCount(), statistics.chunkCount(),
            statistics.compressedBytes(), statistics.uncompressedBytes()
        );
    }

    @Override
    public void close() {
        this.snapshotExecutor.close();
    }

    private synchronized void storeSnapshot(BlurpSnapshotData snapshot) {
        if (snapshot.compressedBytes() > this.configuration().maxSnapshotBytes()) {
            throw new IllegalStateException("Snapshot exceeds the configured memory limit");
        }
        BlurpSnapshotData previous = this.snapshots.put(snapshot.id(), snapshot);
        if (previous != null) {
            this.snapshotBytes -= previous.compressedBytes();
        }
        this.snapshotBytes += snapshot.compressedBytes();
        this.evictToLimit();
    }

    private void evictToLimit() {
        long limit = this.configuration().maxSnapshotBytes();
        while (this.snapshotBytes > limit && !this.snapshots.isEmpty()) {
            Map.Entry<UUID, BlurpSnapshotData> eldest = this.snapshots.entrySet().iterator().next();
            this.snapshotBytes -= eldest.getValue().compressedBytes();
            this.snapshots.remove(eldest.getKey());
        }
    }

    private static BlurpMemoryWorldStorage requireWorld(String worldName) {
        BlurpMemoryWorldStorage storage = BlurpMemoryStorageBridge.world(worldName);
        if (storage == null) {
            throw new IllegalArgumentException("Unknown memory world: " + worldName);
        }
        return storage;
    }

    private static void validateWorldName(String worldName) {
        Preconditions.checkArgument(!worldName.isBlank(), "worldName cannot be blank");
        Preconditions.checkArgument(worldName.length() <= 64, "worldName cannot exceed 64 characters");
        Preconditions.checkArgument(worldName.matches("[A-Za-z0-9._-]+"), "worldName contains unsupported characters");
    }
}
