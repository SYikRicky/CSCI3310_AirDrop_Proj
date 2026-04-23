package com.example.csci3310_airdrop_proj.network.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Wire format: {@code "LOCATION|senderName|timestamp|lat|lng"}.
 *
 * Latitude and longitude are encoded using {@link Double#toString(double)},
 * which round-trips exactly through {@link Double#parseDouble(String)}.
 */
public final class LocationCodec implements PayloadCodec {

    static final String PREFIX = "LOCATION|";

    public static byte[] encode(String senderName, long timestamp,
                                double latitude, double longitude) {
        String wire = PREFIX + senderName + "|" + timestamp + "|"
                + latitude + "|" + longitude;
        return wire.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean tryDecode(String endpointId, byte[] bytes, PayloadSink sink) {
        if (!BytePrefix.startsWith(bytes, PREFIX)) return false;
        String raw = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = raw.split("\\|", 5);
        if (parts.length != 5) return true; // consumed but malformed
        String senderName = parts[1];
        long timestamp;
        try {
            timestamp = Long.parseLong(parts[2]);
        } catch (NumberFormatException e) {
            timestamp = System.currentTimeMillis();
        }
        try {
            double lat = Double.parseDouble(parts[3]);
            double lng = Double.parseDouble(parts[4]);
            sink.onLocation(endpointId, senderName, lat, lng, timestamp);
        } catch (NumberFormatException e) {
            // bad coords — still consumed, don't fall through
        }
        return true;
    }
}
