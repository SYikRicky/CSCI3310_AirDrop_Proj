package com.example.csci3310_airdrop_proj.network.protocol;

import com.example.csci3310_airdrop_proj.model.FileMetadata;

import java.util.ArrayList;
import java.util.List;

/**
 * Test helper: a {@link PayloadSink} that records every callback into a list.
 * Tests drive a codec with a byte array, then assert on what the sink saw.
 */
final class RecordingPayloadSink implements PayloadSink {

    enum Kind { CHAT, LOCATION, CHAT_FILE, FILE_METADATA, UNKNOWN }

    static final class Event {
        final Kind kind;
        final String endpointId;
        final String senderName;
        final String text;
        final double latitude;
        final double longitude;
        final long timestamp;
        final FileMetadata meta;
        final byte[] fileData;
        final byte[] unknownBytes;

        private Event(Kind kind, String endpointId, String senderName, String text,
                      double lat, double lng, long timestamp,
                      FileMetadata meta, byte[] fileData, byte[] unknownBytes) {
            this.kind = kind;
            this.endpointId = endpointId;
            this.senderName = senderName;
            this.text = text;
            this.latitude = lat;
            this.longitude = lng;
            this.timestamp = timestamp;
            this.meta = meta;
            this.fileData = fileData;
            this.unknownBytes = unknownBytes;
        }
    }

    final List<Event> events = new ArrayList<>();

    @Override
    public void onChatText(String endpointId, String senderName, String text, long timestamp) {
        events.add(new Event(Kind.CHAT, endpointId, senderName, text,
                0, 0, timestamp, null, null, null));
    }

    @Override
    public void onLocation(String endpointId, String senderName,
                           double latitude, double longitude, long timestamp) {
        events.add(new Event(Kind.LOCATION, endpointId, senderName, null,
                latitude, longitude, timestamp, null, null, null));
    }

    @Override
    public void onChatFile(String endpointId, String senderName,
                           FileMetadata meta, byte[] fileData) {
        events.add(new Event(Kind.CHAT_FILE, endpointId, senderName, null,
                0, 0, 0, meta, fileData, null));
    }

    @Override
    public void onFileMetadata(String endpointId, FileMetadata meta) {
        events.add(new Event(Kind.FILE_METADATA, endpointId, null, null,
                0, 0, 0, meta, null, null));
    }

    @Override
    public void onUnknownPayload(String endpointId, byte[] bytes) {
        events.add(new Event(Kind.UNKNOWN, endpointId, null, null,
                0, 0, 0, null, null, bytes));
    }

    Event only() {
        if (events.size() != 1) {
            throw new AssertionError("expected exactly one event, got " + events.size());
        }
        return events.get(0);
    }
}
