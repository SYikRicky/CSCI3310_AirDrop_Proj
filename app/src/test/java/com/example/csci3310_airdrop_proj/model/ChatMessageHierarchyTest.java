package com.example.csci3310_airdrop_proj.model;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/**
 * Behavioural tests for the polymorphic ChatMessage hierarchy.
 *
 * These tests only touch pure-Java classes — no Android Uri or Context — so
 * they run as plain JVM unit tests.
 */
public class ChatMessageHierarchyTest {

    @Test
    public void textMessage_exposesTextAndDiscriminator() {
        TextMessage msg = new TextMessage("Alice", "hello world", 10L, true);

        assertEquals(ChatMessage.Type.TEXT, msg.getType());
        assertEquals("hello world", msg.getText());
        assertEquals("Alice", msg.getSenderName());
        assertEquals(10L, msg.getTimestamp());
        assertTrue(msg.isOutgoing());
    }

    @Test
    public void fileMessage_getTextReturnsFileName() {
        FileMetadata meta = new FileMetadata("photo.jpg", "image/jpeg", 1024);
        FileMessage msg = new FileMessage("Bob", 5L, false, meta);

        assertEquals(ChatMessage.Type.FILE, msg.getType());
        assertEquals("photo.jpg", msg.getText());
        assertSame(meta, msg.getFileMetadata());
        assertNull(msg.getSavedUri());
        assertEquals(0, msg.getTransferProgress());
        assertFalse(msg.isOutgoing());
    }

    @Test
    public void fileMessage_transferProgressRoundTrips() {
        FileMetadata meta = new FileMetadata("big.bin", "application/octet-stream", 9000);
        FileMessage msg = new FileMessage("Bob", 0L, true, meta);

        msg.setTransferProgress(73);

        assertEquals(73, msg.getTransferProgress());
    }

    @Test
    public void locationMessage_getTextFormatsCoords() {
        LocationMessage msg = new LocationMessage("Alice", 1L, true,
                22.41967, 114.20629);

        assertEquals(ChatMessage.Type.LOCATION, msg.getType());
        assertEquals(22.41967, msg.getLatitude(), 0.0);
        assertEquals(114.20629, msg.getLongitude(), 0.0);
        assertEquals("22.419670, 114.206290", msg.getText());
    }

    @Test
    public void locationMessage_usesDotDecimalIndependentOfLocale() {
        // Ensures we use Locale.US, not the JVM default which might be
        // e.g. fr_FR and render "22,419670". The format separates the two
        // numbers with ", ", so check the decimal separator on the lat
        // token specifically.
        LocationMessage msg = new LocationMessage("A", 0L, false, 1.5, 2.5);
        String lat = msg.getText().split(", ")[0];
        assertTrue("lat token should use dot decimal, got " + lat,
                lat.contains("."));
        assertFalse("lat token should not use comma decimal, got " + lat,
                lat.contains(","));
    }
}
