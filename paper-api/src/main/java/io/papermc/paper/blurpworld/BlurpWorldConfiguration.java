package io.papermc.paper.blurpworld;

import org.jspecify.annotations.NullMarked;

@NullMarked
public record BlurpWorldConfiguration(long maxSnapshotBytes, int compressionLevel) {

    public static final long DEFAULT_MAX_SNAPSHOT_BYTES = 512L * 1024L * 1024L;
    public static final int DEFAULT_COMPRESSION_LEVEL = 3;

    public BlurpWorldConfiguration {
        if (maxSnapshotBytes < 1L) {
            throw new IllegalArgumentException("maxSnapshotBytes must be positive");
        }
        if (compressionLevel < -7 || compressionLevel > 22) {
            throw new IllegalArgumentException("compressionLevel must be between -7 and 22");
        }
    }

    public static BlurpWorldConfiguration defaults() {
        return new BlurpWorldConfiguration(DEFAULT_MAX_SNAPSHOT_BYTES, DEFAULT_COMPRESSION_LEVEL);
    }
}
