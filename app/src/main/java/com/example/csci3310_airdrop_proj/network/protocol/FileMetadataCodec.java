package com.example.csci3310_airdrop_proj.network.protocol;

import com.example.csci3310_airdrop_proj.model.FileMetadata;

/**
 * Wire format: {@code "FILE|fileName|mimeType|fileSize"}.
 *
 * This preamble is sent before a Nearby Connections FILE payload (the
 * traditional send path, used by the Shared Drive file transfer flow). It
 * tells the receiver the name/mime/size ahead of time so it can show a
 * progress bar and pre-populate UI. The file bytes themselves arrive via a
 * separate FILE payload, not through this codec.
 *
 * Delegates to {@link FileMetadata#toBytes()} / {@link FileMetadata#fromBytes(byte[])}
 * so there is a single source of truth for the metadata wire format.
 */
public final class FileMetadataCodec implements PayloadCodec {

    static final String PREFIX = "FILE|";

    public static byte[] encode(FileMetadata meta) {
        return meta.toBytes();
    }

    @Override
    public boolean tryDecode(String endpointId, byte[] bytes, PayloadSink sink) {
        if (!BytePrefix.startsWith(bytes, PREFIX)) return false;
        FileMetadata meta = FileMetadata.fromBytes(bytes);
        sink.onFileMetadata(endpointId, meta);
        return true;
    }
}
