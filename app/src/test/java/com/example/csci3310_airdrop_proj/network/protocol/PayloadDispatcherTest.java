package com.example.csci3310_airdrop_proj.network.protocol;

import static org.junit.Assert.assertEquals;

import com.example.csci3310_airdrop_proj.model.FileMetadata;

import org.junit.Test;

public class PayloadDispatcherTest {

    private PayloadDispatcher newDispatcher(RecordingPayloadSink sink) {
        // Order matters: CHATFILE| must be tried before FILE| because the
        // FileMetadataCodec claims "FILE|" and would miss the "CHATFILE|"
        // prefix (it uses startsWith, which is strict, but we still prefer
        // explicit ordering for clarity).
        return new PayloadDispatcher(sink,
                new ChatTextCodec(),
                new LocationCodec(),
                new ChatFileCodec(),
                new FileMetadataCodec());
    }

    @Test
    public void routesChatToChatCodec() {
        RecordingPayloadSink sink = new RecordingPayloadSink();
        newDispatcher(sink).dispatch("ep1",
                ChatTextCodec.encode("Alice", 10L, "hello"));

        RecordingPayloadSink.Event ev = sink.only();
        assertEquals(RecordingPayloadSink.Kind.CHAT, ev.kind);
        assertEquals("ep1", ev.endpointId);
        assertEquals("hello", ev.text);
    }

    @Test
    public void routesLocation() {
        RecordingPayloadSink sink = new RecordingPayloadSink();
        newDispatcher(sink).dispatch("ep1",
                LocationCodec.encode("Bob", 5L, 1.5, 2.5));

        assertEquals(RecordingPayloadSink.Kind.LOCATION, sink.only().kind);
    }

    @Test
    public void routesChatFile() {
        RecordingPayloadSink sink = new RecordingPayloadSink();
        FileMetadata meta = new FileMetadata("a.jpg", "image/jpeg", 3);
        newDispatcher(sink).dispatch("ep1",
                ChatFileCodec.encode("Alice", meta, new byte[]{1, 2, 3}));

        assertEquals(RecordingPayloadSink.Kind.CHAT_FILE, sink.only().kind);
    }

    @Test
    public void routesFileMetadata() {
        RecordingPayloadSink sink = new RecordingPayloadSink();
        FileMetadata meta = new FileMetadata("doc.pdf", "application/pdf", 42L);
        newDispatcher(sink).dispatch("ep1", FileMetadataCodec.encode(meta));

        assertEquals(RecordingPayloadSink.Kind.FILE_METADATA, sink.only().kind);
    }

    @Test
    public void unknownBytesReachUnknownHandler() {
        RecordingPayloadSink sink = new RecordingPayloadSink();
        byte[] garbage = "HELLO_WORLD".getBytes();
        newDispatcher(sink).dispatch("ep1", garbage);

        RecordingPayloadSink.Event ev = sink.only();
        assertEquals(RecordingPayloadSink.Kind.UNKNOWN, ev.kind);
    }

    @Test
    public void emptyAndNullAreSilentlyIgnored() {
        RecordingPayloadSink sink = new RecordingPayloadSink();
        PayloadDispatcher d = newDispatcher(sink);
        d.dispatch("ep1", null);
        d.dispatch("ep1", new byte[0]);
        assertEquals(0, sink.events.size());
    }
}
