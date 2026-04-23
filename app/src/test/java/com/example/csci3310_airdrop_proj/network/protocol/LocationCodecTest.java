package com.example.csci3310_airdrop_proj.network.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class LocationCodecTest {

    private final LocationCodec codec = new LocationCodec();

    @Test
    public void roundTrip_hongKongCoords() {
        // CUHK approx.
        byte[] encoded = LocationCodec.encode("Alice", 1700000000000L,
                22.41967, 114.20629);
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("ep", encoded, sink);

        assertTrue(consumed);
        RecordingPayloadSink.Event ev = sink.only();
        assertEquals(RecordingPayloadSink.Kind.LOCATION, ev.kind);
        assertEquals("Alice", ev.senderName);
        assertEquals(22.41967, ev.latitude, 0.0);
        assertEquals(114.20629, ev.longitude, 0.0);
        assertEquals(1700000000000L, ev.timestamp);
    }

    @Test
    public void roundTrip_negativeCoords() {
        byte[] encoded = LocationCodec.encode("Bob", 1L, -34.6037, -58.3816); // Buenos Aires
        RecordingPayloadSink sink = new RecordingPayloadSink();

        codec.tryDecode("ep", encoded, sink);

        assertEquals(-34.6037, sink.only().latitude, 0.0);
        assertEquals(-58.3816, sink.only().longitude, 0.0);
    }

    @Test
    public void rejectsChatPrefix() {
        byte[] chat = "CHAT|Alice|1|hi".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();
        assertFalse(codec.tryDecode("ep", chat, sink));
    }

    @Test
    public void malformedCoordsAreConsumedButDropped() {
        byte[] bad = "LOCATION|Alice|1|not-a-number|also-not".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("ep", bad, sink);

        assertTrue(consumed);
        assertEquals(0, sink.events.size());
    }
}
