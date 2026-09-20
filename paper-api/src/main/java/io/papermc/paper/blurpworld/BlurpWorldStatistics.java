package io.papermc.paper.blurpworld;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record BlurpWorldStatistics(
    String worldName,
    int storageCount,
    int chunkCount,
    long compressedBytes,
    long uncompressedBytes
) {
}
