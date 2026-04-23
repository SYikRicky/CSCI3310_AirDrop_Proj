package com.example.csci3310_airdrop_proj.network.protocol;

import com.example.csci3310_airdrop_proj.model.FileMetadata;

/**
 * Destination for decoded Nearby Connections payloads.
 *
 * Each method corresponds to one kind of wire message. A {@link PayloadCodec}
 * invokes the matching method once it has parsed incoming bytes. The sink
 * implementation (typically in the network manager) then dispatches to higher
 * level listeners.
 *
 * Splitting "parse bytes" (codec) from "react to event" (sink) keeps protocol
 * parsing pure and unit-testable: a recording sink can verify that a codec
 * produced the expected event for a given byte array, with no Android
 * dependencies.
 */
public interface PayloadSink {

    /** "CHAT|senderName|timestamp|text" decoded. */
    void onChatText(String endpointId, String senderName, String text, long timestamp);

    /** "LOCATION|senderName|timestamp|lat|lng" decoded. */
    void onLocation(String endpointId, String senderName,
                    double latitude, double longitude, long timestamp);

    /**
     * "CHATFILE|senderName|fileName|mimeType|fileSize|" + raw bytes decoded.
     *
     * @param fileData the raw file contents (after the header).
     */
    void onChatFile(String endpointId, String senderName,
                    FileMetadata meta, byte[] fileData);

    /**
     * "FILE|fileName|mimeType|fileSize" — metadata preamble for a subsequent
     * FILE payload. The actual file bytes arrive later via Nearby's FILE
     * payload type (not a BYTES payload), so this sink only receives the
     * metadata.
     */
    void onFileMetadata(String endpointId, FileMetadata meta);

    /** Bytes received that no codec claimed. Default: ignore. */
    default void onUnknownPayload(String endpointId, byte[] bytes) {
        // default no-op; implementations may log
    }
}
