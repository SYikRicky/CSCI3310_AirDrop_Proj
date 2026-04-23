package com.example.csci3310_airdrop_proj.network.protocol;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.csci3310_airdrop_proj.model.FileMetadata;

import org.junit.Test;

public class FileMetadataCodecTest {

    private final FileMetadataCodec codec = new FileMetadataCodec();

    @Test
    public void roundTripMetadata() {
        FileMetadata meta = new FileMetadata("doc.pdf", "application/pdf", 987654L);
        byte[] encoded = FileMetadataCodec.encode(meta);
        RecordingPayloadSink sink = new RecordingPayloadSink();

        boolean consumed = codec.tryDecode("ep", encoded, sink);

        assertTrue(consumed);
        RecordingPayloadSink.Event ev = sink.only();
        assertEquals(RecordingPayloadSink.Kind.FILE_METADATA, ev.kind);
        assertEquals("doc.pdf", ev.meta.getFileName());
        assertEquals("application/pdf", ev.meta.getMimeType());
        assertEquals(987654L, ev.meta.getFileSize());
    }

    @Test
    public void rejectsCoincidentalPrefix() {
        // CHATFILE| starts differently from FILE| so should not be claimed here.
        byte[] chatFile = "CHATFILE|Alice|a.bin|application/octet-stream|0|".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();
        assertFalse(codec.tryDecode("ep", chatFile, sink));
    }

    @Test
    public void rejectsChatPrefix() {
        byte[] chat = "CHAT|Alice|1|hi".getBytes();
        RecordingPayloadSink sink = new RecordingPayloadSink();
        assertFalse(codec.tryDecode("ep", chat, sink));
    }
}
