package com.example.csci3310_airdrop_proj.network.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Wire format: {@code "CHAT|senderName|timestamp|text"}.
 *
 * {@code text} is everything after the third pipe, so it may itself contain
 * {@code '|'} characters. {@code senderName} must not contain {@code '|'}.
 * Timestamp is milliseconds since the Unix epoch.
 */
public final class ChatTextCodec implements PayloadCodec {

    static final String PREFIX = "CHAT|";

    /** Build a BYTES payload. */
    public static byte[] encode(String senderName, long timestamp, String text) {
        String wire = PREFIX + senderName + "|" + timestamp + "|" + text;
        return wire.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean tryDecode(String endpointId, byte[] bytes, PayloadSink sink) {
        if (!BytePrefix.startsWith(bytes, PREFIX)) return false;
        String raw = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", 4);
        if (parts.length != 4) return true; // consumed but malformed — drop silently
        String senderName = parts[1];
        long timestamp;
        try {
            timestamp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            timestamp = System.currentTimeMillis();
        }
        sink.onChatText(endpointId, senderName, parts[3], timestamp);
        return true;
    }
}
