package io.papermc.paper.blurpworld;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;

/** Manages compressed memory worlds and their in-memory snapshots. */
@NullMarked
public interface BlurpWorldManager {

    /**
     * Returns the active configuration.
     *
     * @return the active configuration
     */
    BlurpWorldConfiguration configuration();

    /**
     * Applies a configuration.
     *
     * @param configuration the configuration to apply
     */
    void configure(BlurpWorldConfiguration configuration);

    /**
     * Prepares an unloaded empty world.
     *
     * @param worldName the unloaded world to prepare
     */
    void prepare(String worldName);

    /**
     * Prepares an unloaded world from a snapshot.
     *
     * @param worldName  destination world name
     * @param snapshotId source snapshot
     */
    void prepare(String worldName, UUID snapshotId);

    /**
     * Asynchronously prepares an unloaded world from a snapshot.
     *
     * @param worldName  destination world name
     * @param snapshotId source snapshot
     * @return completion of the preparation
     */
    CompletableFuture<Void> prepareAsync(String worldName, UUID snapshotId);

    /**
     * @param worldName world name
     * @return whether compressed memory storage exists for the name
     */
    boolean isPrepared(String worldName);

    /**
     * @param world world to inspect
     * @return whether the world uses compressed memory storage
     */
    boolean isMemoryWorld(World world);

    /**
     * Discards storage for an unloaded memory world.
     *
     * @param worldName world name
     * @return whether storage was discarded
     */
    boolean discard(String worldName);

    /**
     * Saves the world and captures a snapshot.
     *
     * @param world memory world to capture
     * @param label snapshot label
     * @return the captured snapshot metadata
     */
    CompletableFuture<BlurpWorldSnapshot> snapshot(World world, String label);

    /**
     * @param snapshotId snapshot identifier
     * @return snapshot metadata, if present
     */
    Optional<BlurpWorldSnapshot> snapshot(UUID snapshotId);

    /**
     * Returns all retained snapshots.
     *
     * @return all retained snapshot metadata
     */
    Collection<BlurpWorldSnapshot> snapshots();

    /**
     * @param snapshotId snapshot identifier
     * @return whether a snapshot was deleted
     */
    boolean deleteSnapshot(UUID snapshotId);

    /**
     * Renames a snapshot and recomputes its archive checksum.
     *
     * @param snapshotId snapshot identifier
     * @param label      new label
     * @return the renamed snapshot metadata
     */
    CompletableFuture<BlurpWorldSnapshot> renameSnapshot(UUID snapshotId, String label);

    /**
     * @param snapshotId snapshot identifier
     * @return encoded snapshot archive
     */
    byte[] exportSnapshot(UUID snapshotId);

    /**
     * @param snapshotId snapshot identifier
     * @return encoded snapshot archive
     */
    CompletableFuture<byte[]> exportSnapshotAsync(UUID snapshotId);

    /**
     * @param data encoded snapshot archive
     * @return imported snapshot metadata
     */
    BlurpWorldSnapshot importSnapshot(byte[] data);

    /**
     * @param data encoded snapshot archive
     * @return imported snapshot metadata
     */
    CompletableFuture<BlurpWorldSnapshot> importSnapshotAsync(byte[] data);

    /**
     * @param worldName memory world name
     * @return current storage statistics
     */
    BlurpWorldStatistics statistics(String worldName);

    /**
     * @param worldName memory world name
     * @return current storage statistics
     */
    CompletableFuture<BlurpWorldStatistics> statisticsAsync(String worldName);
}
