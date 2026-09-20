package io.papermc.paper.blurpworld;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import org.bukkit.World;
import org.jspecify.annotations.NullMarked;

@NullMarked
public interface BlurpWorldManager {

    BlurpWorldConfiguration configuration();

    void configure(BlurpWorldConfiguration configuration);

    void prepare(String worldName);

    void prepare(String worldName, UUID snapshotId);

    boolean isPrepared(String worldName);

    boolean isMemoryWorld(World world);

    boolean discard(String worldName);

    CompletableFuture<BlurpWorldSnapshot> snapshot(World world, String label);

    Optional<BlurpWorldSnapshot> snapshot(UUID snapshotId);

    Collection<BlurpWorldSnapshot> snapshots();

    boolean deleteSnapshot(UUID snapshotId);

    byte[] exportSnapshot(UUID snapshotId);

    BlurpWorldSnapshot importSnapshot(byte[] data);

    BlurpWorldStatistics statistics(String worldName);
}
