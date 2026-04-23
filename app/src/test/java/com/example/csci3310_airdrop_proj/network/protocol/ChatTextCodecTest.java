package com.example.csci3310_airdrop_proj.network.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class ChatTextCodecTest {

    private final ChatTextCodec codec = new ChatTextCodec();

    @Test
    public void roundTrip_simpleMessage() {
        byte[] encoded = ChatTextCodec.encode("Alice", 1234567890L, "hello world");
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("endpointA", encoded, sink);

        assertTrue("codec should claim CHAT| payloads", consumed);
        RecordingPayloadSink.Event ev = sink.only();
        assertEquals(RecordingPayloadSink.Kind.CHAT, ev.kind);
        assertEquals("endpointA", ev.endpointId);
        assertEquals("Alice", ev.senderName);
        assertEquals("hello world", ev.text);
        assertEquals(1234567890L, ev.timestamp);
    }

    @Test
    public void textContainingPipesIsPreserved() {
        // The wire format reserves the first three | as separators; everything
        // after the third | is the text. Pipes inside the message body must
        // round-trip unchanged.
        byte[] encoded = ChatTextCodec.encode("Bob", 42L, "a|b|c||d");
        RecordingPayloadSink sink = new RecordingPayloadSink();

        codec.tryDecode("ep", encoded, sink);

        assertEquals("a|b|c||d", sink.only().text);
    }

    @Test
    public void utf8SenderAndTextRoundTrip() {
        byte[] encoded = ChatTextCodec.encode("アリス", 1L, "こんにちは 🌸");
        RecordingPayloadSink sink = new RecordingPayloadSink();

        codec.tryDecode("ep", encoded, sink);

        RecordingPayloadSink.Event ev = sink.only();
        assertEquals("アリス", ev.senderName);
        assertEquals("こんにちは 🌸", ev.text);
    }

    @Test
    public void rejectsNonChatPrefix() {
        byte[] other = "LOCATION|Bob|1|2|3".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("ep", other, sink);

        assertFalse("LOCATION| should not match CHAT|", consumed);
        assertEquals(0, sink.events.size());
    }

    @Test
    public void rejectsEmptyBytes() {
        RecordingPayloadSink sink = new RecordingPayloadSink();
        assertFalse(codec.tryDecode("ep", new byte[0], sink));
    }

    @Test
    public void malformedPayloadIsConsumedButDropped() {
        // Only two pipes instead of three — doesn't match the 4-field format.
        byte[] bad = "CHAT|Alice|no-timestamp".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("ep", bad, sink);

        assertTrue("codec claimed the prefix, so it must report consumed", consumed);
        assertEquals("malformed payload must not reach the sink", 0, sink.events.size());
    }
}
