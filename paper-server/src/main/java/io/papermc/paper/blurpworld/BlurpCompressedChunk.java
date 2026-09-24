package io.papermc.paper.blurpworld;

import com.github.luben.zstd.Zstd;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.NbtIo;

record BlurpCompressedChunk(byte[] data, int rawSize) {

    static BlurpCompressedChunk encode(CompoundTag tag, int compressionLevel) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        try (DataOutputStream dataOutput = new DataOutputStream(output)) {
            NbtIo.write(tag, dataOutput);
        }
        byte[] raw = output.toByteArray();
        return new BlurpCompressedChunk(Zstd.compress(raw, compressionLevel), raw.length);
    }

    CompoundTag decode() throws IOException {
        byte[] raw = Zstd.decompress(this.data, this.rawSize);
        try (DataInputStream input = new DataInputStream(new ByteArrayInputStream(raw))) {
            return NbtIo.read(input);
        }
    }

    BlurpCompressedChunk copy() {
        return new BlurpCompressedChunk(this.data.clone(), this.rawSize);
    }

    // Carries an immutable record through Moonrise's read pipeline so decoding runs on its parallel decompression workers.
    static final class Input extends DataInputStream {

        private final BlurpCompressedChunk chunk;

        Input(BlurpCompressedChunk chunk) {
            super(InputStream.nullInputStream());
            this.chunk = chunk;
        }

        BlurpCompressedChunk chunk() {
            return this.chunk;
        }
    }
}
