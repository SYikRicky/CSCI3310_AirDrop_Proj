package com.example.csci3310_airdrop_proj.network.protocol;

import com.example.csci3310_airdrop_proj.model.FileMetadata;

import java.nio.charset.StandardCharsets;

/**
 * Wire format: {@code "CHATFILE|senderName|fileName|mimeType|fileSize|"}
 * immediately followed by the raw file bytes.
 *
 * This workaround exists because {@code Payload.fromFile()} is unreliable on
 * API 36 / play-services-nearby 18.7.0 — the receiver's
 * {@code asJavaFile()} returns null. Sending inline BYTES works because the
 * same mechanism carries every chat text message successfully.
 *
 * The header is located by scanning for the fifth {@code '|'} byte, so the
 * raw file data may contain arbitrary bytes including pipes and zero bytes.
 * Limitation: Nearby Connections caps a BYTES payload at ~32 KiB per transmit
 * (library chunks larger buffers internally, but the SDK has hard limits
 * above ~32 MiB). Image thumbnails and short voice clips fit comfortably.
 */
public final class ChatFileCodec implements PayloadCodec {

    static final String PREFIX = "CHATFILE|";
    private static final byte PIPE = (byte) '|';

    /** Build a combined header + file-bytes BYTES payload. */
    public static byte[] encode(String senderName, FileMetadata meta, byte[] fileData) {
        String header = PREFIX + senderName + "|"
                + meta.getFileName() + "|"
                + meta.getMimeType() + "|"
                + fileData.length + "|";
        byte[] headerBytes = header.getBytes(StandardCharsets.UTF_8);
        byte[] combined = new byte[headerBytes.length + fileData.length];
        System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
        System.arraycopy(fileData, 0, combined, headerBytes.length, fileData.length);
        return combined;
    }

    @Override
    public boolean tryDecode(String endpointId, byte[] bytes, PayloadSink sink) {
        if (!BytePrefix.startsWith(bytes, PREFIX)) return false;

        int fifthPipe = BytePrefix.indexOfNth(bytes, PIPE, 5);
        if (fifthPipe < 0) return true; // malformed, but consumed

        String header = new String(bytes, 0, fifthPipe, StandardCharsets.UTF_8);
        String[] parts = header.split("\\|", 5);
        if (parts.length < 5) return true; // malformed

        String senderName = parts[1];
        String fileName   = parts[2];
        String mimeType   = parts[3];
        long declaredSize;
        try {
            declaredSize = Long.parseLong(parts[4]);
        } catch (NumberFormatException e) {
            declaredSize = bytes.length - (fifthPipe + 1);
        }

        int dataOffset = fifthPipe + 1;
        int dataLength = bytes.length - dataOffset;
        byte[] fileData = new byte[dataLength];
        System.arraycopy(bytes, dataOffset, fileData, 0, dataLength);

        FileMetadata meta = new FileMetadata(fileName, mimeType, declaredSize);
        sink.onChatFile(endpointId, senderName, meta, fileData);
        return true;
    }
}
