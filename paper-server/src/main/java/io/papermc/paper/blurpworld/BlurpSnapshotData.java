package io.papermc.paper.blurpworld;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

record BlurpSnapshotData(
    UUID id,
    String sourceWorld,
    String label,
    Instant createdAt,
    Map<String, Map<Long, BlurpCompressedChunk>> stores,
    Map<String, BlurpCompressedChunk> savedData,
    int chunkCount,
    long compressedBytes,
    long uncompressedBytes,
    String sha256
) {

    static BlurpSnapshotData create(
        String sourceWorld,
        String label,
        Map<String, Map<Long, BlurpCompressedChunk>> stores,
        Map<String, BlurpCompressedChunk> savedData
    ) {
        int chunkCount = stores.values().stream().mapToInt(Map::size).sum();
        long compressedBytes = stores.values().stream()
            .flatMap(store -> store.values().stream())
            .mapToLong(chunk -> chunk.data().length)
            .sum() + savedData.values().stream().mapToLong(data -> data.data().length).sum();
        long uncompressedBytes = stores.values().stream()
            .flatMap(store -> store.values().stream())
            .mapToLong(BlurpCompressedChunk::rawSize)
            .sum() + savedData.values().stream().mapToLong(BlurpCompressedChunk::rawSize).sum();
        BlurpSnapshotData snapshot = new BlurpSnapshotData(
            UUID.randomUUID(), sourceWorld, label, Instant.now(), Map.copyOf(stores), Map.copyOf(savedData),
            chunkCount, compressedBytes, uncompressedBytes, ""
        );
        return snapshot.withSha256(BlurpSnapshotCodec.sha256(BlurpSnapshotCodec.encode(snapshot)));
    }

    BlurpSnapshotData withSha256(String sha256) {
        return new BlurpSnapshotData(
            this.id, this.sourceWorld, this.label, this.createdAt, this.stores, this.savedData,
            this.chunkCount, this.compressedBytes, this.uncompressedBytes, sha256
        );
    }

    BlurpSnapshotData withLabel(String label) {
        BlurpSnapshotData renamed = new BlurpSnapshotData(
            this.id, this.sourceWorld, label, this.createdAt, this.stores, this.savedData,
            this.chunkCount, this.compressedBytes, this.uncompressedBytes, ""
        );
        return renamed.withSha256(BlurpSnapshotCodec.sha256(BlurpSnapshotCodec.encode(renamed)));
    }

    BlurpWorldSnapshot metadata() {
        return new BlurpWorldSnapshot(
            this.id, this.sourceWorld, this.label, this.createdAt, this.chunkCount,
            this.compressedBytes, this.uncompressedBytes, this.sha256
        );
    }
}
