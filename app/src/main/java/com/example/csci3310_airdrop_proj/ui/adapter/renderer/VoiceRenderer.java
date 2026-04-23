package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.playback.VoicePlaybackController;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatViewHolder;

/**
 * Renders a {@link FileMessage} whose MIME type begins with {@code audio/}:
 * shows a "Voice message" label and a Play button that delegates to the
 * shared {@link VoicePlaybackController}.
 *
 * Must be registered before {@link FileRenderer}.
 */
public final class VoiceRenderer implements MessageRenderer {

    private final VoicePlaybackController playback;

    public VoiceRenderer(VoicePlaybackController playback) {
        this.playback = playback;
    }

    @Override
    public boolean canRender(ChatMessage msg) {
        if (!(msg instanceof FileMessage)) return false;
        FileMetadata meta = ((FileMessage) msg).getFileMetadata();
        if (meta == null || meta.getMimeType() == null) return false;
        return meta.getMimeType().startsWith("audio/");
    }

    @Override
    public void render(ChatViewHolder holder, ChatMessage msg) {
        FileMessage fm = (FileMessage) msg;

        holder.setBodyText(holder.getContext().getString(R.string.chat_voice_message));

        if (fm.getSavedUri() != null) {
            holder.showPlayButton(v -> playback.play(holder.getContext(), fm.getSavedUri()));
        } else {
            holder.hidePlayButton();
        }
        holder.hideImage();
        holder.hideOpenFileButton();
        holder.hideMapButton();
    }
}
