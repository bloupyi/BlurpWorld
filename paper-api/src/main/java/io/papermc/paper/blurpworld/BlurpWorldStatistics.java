package io.papermc.paper.blurpworld;

import org.jspecify.annotations.NullMarked;

/**
 * Current storage statistics for a compressed memory world.
 *
 * @param worldName         world name
 * @param storageCount      region storage count
 * @param chunkCount        stored record count
 * @param compressedBytes   compressed size
 * @param uncompressedBytes uncompressed size
 */
@NullMarked
public record BlurpWorldStatistics(
    String worldName,
    int storageCount,
    int chunkCount,
    long compressedBytes,
    long uncompressedBytes
) {
}
