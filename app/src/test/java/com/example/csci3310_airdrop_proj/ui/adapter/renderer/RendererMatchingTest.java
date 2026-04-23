package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.LocationMessage;
import com.example.csci3310_airdrop_proj.model.TextMessage;

import org.junit.Test;

/**
 * Pure tests on {@link MessageRenderer#canRender(ChatMessage)}.
 *
 * These verify each renderer claims the right message types. The
 * {@code render()} side-effect requires Android views and is exercised only
 * in the running app.
 */
public class RendererMatchingTest {

    private static ChatMessage text()     { return new TextMessage("A", "hi", 1L, true); }
    private static ChatMessage location() { return new LocationMessage("A", 1L, true, 0, 0); }
    private static ChatMessage file(String mime) {
        return new FileMessage("A", 1L, true, new FileMetadata("f", mime, 1L));
    }

    @Test
    public void textRendererClaimsOnlyText() {
        TextRenderer r = new TextRenderer();
        assertTrue(r.canRender(text()));
        assertFalse(r.canRender(location()));
        assertFalse(r.canRender(file("image/png")));
    }

    @Test
    public void imageRendererClaimsImageFiles() {
        ImageRenderer r = new ImageRenderer();
        assertTrue (r.canRender(file("image/jpeg")));
        assertTrue (r.canRender(file("image/png")));
        assertFalse(r.canRender(file("audio/mp4")));
        assertFalse(r.canRender(file("application/pdf")));
        assertFalse(r.canRender(text()));
        assertFalse(r.canRender(location()));
    }

    @Test
    public void voiceRendererClaimsAudioFiles() {
        VoiceRenderer r = new VoiceRenderer(null); // playback not invoked in canRender
        assertTrue (r.canRender(file("audio/mp4")));
        assertTrue (r.canRender(file("audio/mpeg")));
        assertFalse(r.canRender(file("image/jpeg")));
        assertFalse(r.canRender(file("application/pdf")));
        assertFalse(r.canRender(text()));
    }

    @Test
    public void fileRendererClaimsAnyFileMessage() {
        // Registered after Image and Voice, so its greedy matching is fine.
        FileRenderer r = new FileRenderer();
        assertTrue (r.canRender(file("application/pdf")));
        assertTrue (r.canRender(file("image/jpeg"))); // claimed by ImageRenderer first in chain
        assertTrue (r.canRender(file("audio/mp4")));  // claimed by VoiceRenderer first in chain
        assertFalse(r.canRender(text()));
        assertFalse(r.canRender(location()));
    }

    @Test
    public void locationRendererClaimsOnlyLocation() {
        LocationRenderer r = new LocationRenderer();
        assertTrue (r.canRender(location()));
        assertFalse(r.canRender(text()));
        assertFalse(r.canRender(file("image/jpeg")));
    }

    @Test
    public void fileMessageWithNoMimeIsNotImageOrVoice() {
        FileMessage fm = new FileMessage("A", 1L, true, new FileMetadata("f", null, 1L));
        assertFalse(new ImageRenderer().canRender(fm));
        assertFalse(new VoiceRenderer(null).canRender(fm));
        assertTrue (new FileRenderer().canRender(fm));
    }
}
