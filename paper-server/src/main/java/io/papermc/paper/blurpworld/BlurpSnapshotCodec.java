package io.papermc.paper.blurpworld;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

final class BlurpSnapshotCodec {

    private static final byte[] MAGIC = "BLURPWLD".getBytes(StandardCharsets.US_ASCII);
    private static final int VERSION = 2;

    private BlurpSnapshotCodec() {
    }

    static byte[] encode(BlurpSnapshotData snapshot) {
        try {
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            try (DataOutputStream data = new DataOutputStream(output)) {
                data.write(MAGIC);
                data.writeInt(VERSION);
                data.writeLong(snapshot.id().getMostSignificantBits());
                data.writeLong(snapshot.id().getLeastSignificantBits());
                data.writeUTF(snapshot.sourceWorld());
                data.writeUTF(snapshot.label());
                data.writeLong(snapshot.createdAt().toEpochMilli());
                data.writeInt(snapshot.stores().size());
                for (Map.Entry<String, Map<Long, BlurpCompressedChunk>> store : snapshot.stores().entrySet()) {
                    data.writeUTF(store.getKey());
                    data.writeInt(store.getValue().size());
                    for (Map.Entry<Long, BlurpCompressedChunk> chunk : store.getValue().entrySet()) {
                        data.writeLong(chunk.getKey());
                        data.writeInt(chunk.getValue().rawSize());
                        data.writeInt(chunk.getValue().data().length);
                        data.write(chunk.getValue().data());
                    }
                }
                data.writeInt(snapshot.savedData().size());
                for (Map.Entry<String, BlurpCompressedChunk> entry : snapshot.savedData().entrySet()) {
                    data.writeUTF(entry.getKey());
                    data.writeInt(entry.getValue().rawSize());
                    data.writeInt(entry.getValue().data().length);
                    data.write(entry.getValue().data());
                }
            }
            return output.toByteArray();
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    static BlurpSnapshotData decode(byte[] encoded) {
        try (DataInputStream data = new DataInputStream(new ByteArrayInputStream(encoded))) {
            byte[] magic = data.readNBytes(MAGIC.length);
            if (!MessageDigest.isEqual(MAGIC, magic)) {
                throw new IllegalArgumentException("Not a BlurpWorld snapshot");
            }
            int version = data.readInt();
            if (version < 1 || version > VERSION) {
                throw new IllegalArgumentException("Unsupported BlurpWorld snapshot version: " + version);
            }
            UUID id = new UUID(data.readLong(), data.readLong());
            String sourceWorld = data.readUTF();
            String label = data.readUTF();
            Instant createdAt = Instant.ofEpochMilli(data.readLong());
            int storeCount = checkedCount(data.readInt(), "store");
            Map<String, Map<Long, BlurpCompressedChunk>> stores = new HashMap<>();
            for (int storeIndex = 0; storeIndex < storeCount; storeIndex++) {
                String key = data.readUTF();
                int chunkCount = checkedCount(data.readInt(), "chunk");
                Map<Long, BlurpCompressedChunk> chunks = new HashMap<>();
                for (int chunkIndex = 0; chunkIndex < chunkCount; chunkIndex++) {
                    long chunkKey = data.readLong();
                    int rawSize = checkedSize(data.readInt(), "raw chunk");
                    int compressedSize = checkedSize(data.readInt(), "compressed chunk");
                    byte[] compressed = data.readNBytes(compressedSize);
                    if (compressed.length != compressedSize) {
                        throw new IllegalArgumentException("Truncated BlurpWorld snapshot");
                    }
                    chunks.put(chunkKey, new BlurpCompressedChunk(compressed, rawSize));
                }
                stores.put(key, Map.copyOf(chunks));
            }
            Map<String, BlurpCompressedChunk> savedData = new HashMap<>();
            if (version >= 2) {
                int savedDataCount = checkedCount(data.readInt(), "saved data");
                for (int dataIndex = 0; dataIndex < savedDataCount; dataIndex++) {
                    String key = data.readUTF();
                    int rawSize = checkedSize(data.readInt(), "raw saved data");
                    int compressedSize = checkedSize(data.readInt(), "compressed saved data");
                    byte[] compressed = data.readNBytes(compressedSize);
                    if (compressed.length != compressedSize) {
                        throw new IllegalArgumentException("Truncated BlurpWorld snapshot");
                    }
                    savedData.put(key, new BlurpCompressedChunk(compressed, rawSize));
                }
            }
            if (data.available() != 0) {
                throw new IllegalArgumentException("Trailing data in BlurpWorld snapshot");
            }
            int chunkCount = stores.values().stream().mapToInt(Map::size).sum();
            long compressedBytes = stores.values().stream().flatMap(store -> store.values().stream()).mapToLong(chunk -> chunk.data().length).sum()
                + savedData.values().stream().mapToLong(value -> value.data().length).sum();
            long uncompressedBytes = stores.values().stream().flatMap(store -> store.values().stream()).mapToLong(BlurpCompressedChunk::rawSize).sum()
                + savedData.values().stream().mapToLong(BlurpCompressedChunk::rawSize).sum();
            BlurpSnapshotData snapshot = new BlurpSnapshotData(
                id, sourceWorld, label, createdAt, Map.copyOf(stores), Map.copyOf(savedData),
                chunkCount, compressedBytes, uncompressedBytes, ""
            );
            return snapshot.withSha256(sha256(encode(snapshot)));
        } catch (IOException exception) {
            throw new IllegalArgumentException("Invalid BlurpWorld snapshot", exception);
        }
    }

    static String sha256(byte[] data) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(data));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static int checkedCount(int value, String type) {
        if (value < 0 || value > 10_000_000) {
            throw new IllegalArgumentException("Invalid " + type + " count: " + value);
        }
        return value;
    }

    private static int checkedSize(int value, String type) {
        if (value < 0 || value > 64 * 1024 * 1024) {
            throw new IllegalArgumentException("Invalid " + type + " size: " + value);
        }
        return value;
    }
}
