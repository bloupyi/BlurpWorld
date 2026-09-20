package io.papermc.paper.blurpworld;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

/**
 * Metadata describing a retained compressed world snapshot.
 *
 * @param id                snapshot identifier
 * @param sourceWorld       source world name
 * @param label             user-defined label
 * @param createdAt         creation time
 * @param chunkCount        stored record count
 * @param compressedBytes   compressed size
 * @param uncompressedBytes uncompressed size
 * @param sha256            archive content checksum
 */
@NullMarked
public record BlurpWorldSnapshot(
    UUID id,
    String sourceWorld,
    String label,
    Instant createdAt,
    int chunkCount,
    long compressedBytes,
    long uncompressedBytes,
    String sha256
) {
}
