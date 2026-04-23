package com.example.csci3310_airdrop_proj.network.protocol;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.csci3310_airdrop_proj.model.FileMetadata;

import org.junit.Test;

public class ChatFileCodecTest {

    private final ChatFileCodec codec = new ChatFileCodec();

    @Test
    public void roundTrip_smallImage() {
        byte[] fileBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0,
                0x00, 0x10, 'J', 'F', 'I', 'F'};
        FileMetadata meta = new FileMetadata("photo.jpg", "image/jpeg", fileBytes.length);

        byte[] encoded = ChatFileCodec.encode("Alice", meta, fileBytes);
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("ep", encoded, sink);

        assertTrue(consumed);
        RecordingPayloadSink.Event ev = sink.only();
        assertEquals(RecordingPayloadSink.Kind.CHAT_FILE, ev.kind);
        assertEquals("Alice", ev.senderName);
        assertEquals("photo.jpg", ev.meta.getFileName());
        assertEquals("image/jpeg", ev.meta.getMimeType());
        assertEquals(fileBytes.length, ev.meta.getFileSize());
        assertArrayEquals(fileBytes, ev.fileData);
    }

    @Test
    public void fileBytesContainingPipesAndNullsAreExact() {
        // Raw file data may contain any byte including '|' (0x7C) and 0x00.
        // The decoder must split only on the first five header pipes.
        byte[] fileBytes = new byte[]{0, '|', '|', '|', '|', '|', 1, 2, 0, (byte) 0xFF};
        FileMetadata meta = new FileMetadata("raw.bin", "application/octet-stream",
                fileBytes.length);

        byte[] encoded = ChatFileCodec.encode("Bob", meta, fileBytes);
        RecordingPayloadSink sink = new RecordingPayloadSink();

        codec.tryDecode("ep", encoded, sink);

        assertArrayEquals(fileBytes, sink.only().fileData);
    }

    @Test
    public void emptyFilePayloadProducesEmptyData() {
        FileMetadata meta = new FileMetadata("empty.txt", "text/plain", 0);

        byte[] encoded = ChatFileCodec.encode("Alice", meta, new byte[0]);
        RecordingPayloadSink sink = new RecordingPayloadSink();

        codec.tryDecode("ep", encoded, sink);

        assertEquals(0, sink.only().fileData.length);
    }

    @Test
    public void rejectsNonChatFilePrefix() {
        byte[] chat = "CHAT|Alice|1|hi".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();
        assertFalse(codec.tryDecode("ep", chat, sink));
    }

    @Test
    public void rejectsPlainFilePrefix() {
        // Important: CHATFILE| starts with "FILE|" after the "CHAT" prefix
        // has been chomped. We need to be sure the codec keys on the full
        // "CHATFILE|" prefix, not the substring "FILE|".
        byte[] filePrefix = "FILE|name.bin|application/octet-stream|42".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();
        assertFalse(codec.tryDecode("ep", filePrefix, sink));
    }

    @Test
    public void malformedHeaderIsConsumedButDropped() {
        // Only two pipes after the prefix.
        byte[] bad = "CHATFILE|Alice|file".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("ep", bad, sink);

        assertTrue("codec with matching prefix must report consumed", consumed);
        assertEquals(0, sink.events.size());
    }
}
