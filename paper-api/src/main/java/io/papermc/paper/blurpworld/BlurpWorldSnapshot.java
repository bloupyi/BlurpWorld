package io.papermc.paper.blurpworld;

import java.time.Instant;
import java.util.UUID;
import org.jspecify.annotations.NullMarked;

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
