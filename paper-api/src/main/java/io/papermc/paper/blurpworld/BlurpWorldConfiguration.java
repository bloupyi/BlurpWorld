package io.papermc.paper.blurpworld;

import org.jspecify.annotations.NullMarked;

/**
 * Configuration shared by compressed memory worlds and their snapshots.
 *
 * @param maxSnapshotBytes total compressed snapshot budget
 * @param compressionLevel Zstandard compression level, from -7 to 22
 */
@NullMarked
public record BlurpWorldConfiguration(long maxSnapshotBytes, int compressionLevel) {

    /** Default total in-memory snapshot budget. */
    public static final long DEFAULT_MAX_SNAPSHOT_BYTES = 512L * 1024L * 1024L;
    /** Default Zstandard compression level. */
    public static final int DEFAULT_COMPRESSION_LEVEL = 3;

    /**
     * Creates a configuration.
     *
     * @param maxSnapshotBytes total compressed snapshot budget
     * @param compressionLevel Zstandard compression level, from -7 to 22
     */
    public BlurpWorldConfiguration {
        if (maxSnapshotBytes < 1L) {
            throw new IllegalArgumentException("maxSnapshotBytes must be positive");
        }
        if (compressionLevel < -7 || compressionLevel > 22) {
            throw new IllegalArgumentException("compressionLevel must be between -7 and 22");
        }
    }

    /**
     * Returns the default configuration.
     *
     * @return the default configuration
     */
    public static BlurpWorldConfiguration defaults() {
        return new BlurpWorldConfiguration(DEFAULT_MAX_SNAPSHOT_BYTES, DEFAULT_COMPRESSION_LEVEL);
    }
}
