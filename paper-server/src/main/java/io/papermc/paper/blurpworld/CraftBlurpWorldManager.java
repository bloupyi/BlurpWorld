package io.papermc.paper.blurpworld;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
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
    private final ExecutorService asyncExecutor = Executors.newVirtualThreadPerTaskExecutor();
    private final LinkedHashMap<UUID, BlurpSnapshotData> snapshots = new LinkedHashMap<>();
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
    public CompletableFuture<Void> prepareAsync(String worldName, UUID snapshotId) {
        validateWorldName(worldName);
        Preconditions.checkState(this.server.getWorld(worldName) == null, "World %s is already loaded", worldName);
        BlurpSnapshotData snapshot;
        synchronized (this) {
            snapshot = this.snapshots.get(snapshotId);
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown snapshot: " + snapshotId);
        }
        return CompletableFuture.runAsync(() -> BlurpMemoryStorageBridge.prepare(worldName, snapshot), this.asyncExecutor);
    }

    @Override
    public CompletableFuture<BlurpWorldSnapshot> prepareFromArchiveAsync(String worldName, byte[] archive) {
        validateWorldName(worldName);
        Preconditions.checkState(this.server.getWorld(worldName) == null, "World %s is already loaded", worldName);
        Preconditions.checkArgument(archive.length <= this.configuration().maxSnapshotBytes(), "Snapshot archive exceeds the configured memory limit");
        byte[] encoded = archive.clone();
        return CompletableFuture.supplyAsync(() -> {
            BlurpSnapshotData snapshot = BlurpSnapshotCodec.decode(encoded);
            BlurpMemoryStorageBridge.prepare(worldName, snapshot);
            return snapshot.metadata();
        }, this.asyncExecutor);
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
        validateLabel(label);
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
        }, this.asyncExecutor);
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
    public CompletableFuture<BlurpWorldSnapshot> renameSnapshot(UUID snapshotId, String label) {
        validateLabel(label);
        BlurpSnapshotData current;
        synchronized (this) {
            current = this.snapshots.get(snapshotId);
        }
        if (current == null) {
            throw new IllegalArgumentException("Unknown snapshot: " + snapshotId);
        }
        return CompletableFuture.supplyAsync(() -> {
            BlurpSnapshotData renamed = current.withLabel(label);
            synchronized (this) {
                Preconditions.checkState(this.snapshots.get(snapshotId) == current, "Snapshot changed while it was being renamed");
                this.snapshots.put(snapshotId, renamed);
            }
            return renamed.metadata();
        }, this.asyncExecutor);
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
    public CompletableFuture<byte[]> exportSnapshotAsync(UUID snapshotId) {
        BlurpSnapshotData snapshot;
        synchronized (this) {
            snapshot = this.snapshots.get(snapshotId);
        }
        if (snapshot == null) {
            throw new IllegalArgumentException("Unknown snapshot: " + snapshotId);
        }
        return CompletableFuture.supplyAsync(() -> BlurpSnapshotCodec.encode(snapshot), this.asyncExecutor);
    }

    @Override
    public CompletableFuture<byte[]> exportWorldAsync(World world, String label) {
        Preconditions.checkArgument(this.isMemoryWorld(world), "World %s is not memory-backed", world.getName());
        validateLabel(label);
        BlurpMemoryWorldStorage storage = requireWorld(world.getName());
        return CompletableFuture.supplyAsync(() -> BlurpSnapshotCodec.encode(storage.snapshot(label)), this.asyncExecutor);
    }

    @Override
    public BlurpWorldSnapshot importSnapshot(byte[] data) {
        Preconditions.checkArgument(data.length <= this.configuration().maxSnapshotBytes(), "Snapshot archive exceeds the configured memory limit");
        BlurpSnapshotData snapshot = BlurpSnapshotCodec.decode(data);
        this.storeSnapshot(snapshot);
        return snapshot.metadata();
    }

    @Override
    public CompletableFuture<BlurpWorldSnapshot> importSnapshotAsync(byte[] data) {
        Preconditions.checkArgument(data.length <= this.configuration().maxSnapshotBytes(), "Snapshot archive exceeds the configured memory limit");
        byte[] archive = data.clone();
        return CompletableFuture.supplyAsync(() -> {
            BlurpSnapshotData snapshot = BlurpSnapshotCodec.decode(archive);
            this.storeSnapshot(snapshot);
            return snapshot.metadata();
        }, this.asyncExecutor);
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
    public CompletableFuture<BlurpWorldStatistics> statisticsAsync(String worldName) {
        return CompletableFuture.supplyAsync(() -> this.statistics(worldName), this.asyncExecutor);
    }

    @Override
    public void close() {
        this.asyncExecutor.close();
    }

    private synchronized void storeSnapshot(BlurpSnapshotData snapshot) {
        long limit = this.configuration().maxSnapshotBytes();
        BlurpSnapshotData previous = this.snapshots.get(snapshot.id());
        long previousBytes = previous == null ? 0L : previous.compressedBytes();
        long projectedBytes = checkedProjectedSnapshotBytes(this.snapshotBytes, previousBytes, snapshot.compressedBytes(), limit);
        this.snapshots.put(snapshot.id(), snapshot);
        this.snapshotBytes = projectedBytes;
    }

    static long checkedProjectedSnapshotBytes(long currentBytes, long previousBytes, long newBytes, long limit) {
        long retainedBytes = Math.subtractExact(currentBytes, previousBytes);
        long projectedBytes = Math.addExact(retainedBytes, newBytes);
        if (projectedBytes > limit) {
            long availableBytes = Math.max(0L, limit - retainedBytes);
            throw new IllegalStateException(
                "Snapshot cancelled: " + newBytes + " compressed bytes required, but only " + availableBytes + " remain"
            );
        }
        return projectedBytes;
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

    private static void validateLabel(String label) {
        Preconditions.checkArgument(!label.isBlank(), "label cannot be blank");
        Preconditions.checkArgument(label.length() <= 128, "label cannot exceed 128 characters");
    }
}
