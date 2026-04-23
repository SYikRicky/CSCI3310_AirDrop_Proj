package com.example.csci3310_airdrop_proj.ui.adapter;

import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMessage;
import com.example.csci3310_airdrop_proj.playback.VoicePlaybackController;
import com.example.csci3310_airdrop_proj.ui.adapter.renderer.FileRenderer;
import com.example.csci3310_airdrop_proj.ui.adapter.renderer.ImageRenderer;
import com.example.csci3310_airdrop_proj.ui.adapter.renderer.LocationRenderer;
import com.example.csci3310_airdrop_proj.ui.adapter.renderer.MessageRendererRegistry;
import com.example.csci3310_airdrop_proj.ui.adapter.renderer.TextRenderer;
import com.example.csci3310_airdrop_proj.ui.adapter.renderer.VoiceRenderer;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for chat messages.
 *
 * The adapter handles only what is common across every bubble — choosing the
 * sent/received layout, timestamp, sender name, and feeding messages into the
 * renderer chain. Everything else (image thumbnails, voice playback,
 * location buttons, file open intents) lives in
 * {@link com.example.csci3310_airdrop_proj.ui.adapter.renderer.MessageRenderer}
 * implementations.
 *
 * The owning fragment supplies a {@link VoicePlaybackController} so only one
 * {@link android.media.MediaPlayer} is alive at a time and the fragment can
 * release it in {@code onDestroyView()}.
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatViewHolder> {

    private static final int VIEW_TYPE_SENT     = 0;
    private static final int VIEW_TYPE_RECEIVED = 1;

    private final List<ChatMessage> messages = new ArrayList<>();
    private final MessageRendererRegistry renderers;

    public ChatAdapter(VoicePlaybackController playback) {
        // Order matters: specific renderers before fallbacks.
        this.renderers = new MessageRendererRegistry(
                new ImageRenderer(),
                new VoiceRenderer(playback),
                new FileRenderer(),
                new LocationRenderer(),
                new TextRenderer());
    }

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    /**
     * Attach {@code uri} to the most recent received {@link FileMessage}
     * with the matching filename that has no URI yet, then rebind its row so
     * the preview / play button appears.
     */
    public void updateMessageUri(String fileName, Uri uri) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg instanceof FileMessage && !msg.isOutgoing()
                    && fileName.equals(msg.getText())) {
                FileMessage fm = (FileMessage) msg;
                if (fm.getSavedUri() == null) {
                    fm.setSavedUri(uri);
                    notifyItemChanged(i);
                    return;
                }
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isOutgoing() ? VIEW_TYPE_SENT : VIEW_TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId = viewType == VIEW_TYPE_SENT
                ? R.layout.item_chat_message_sent
                : R.layout.item_chat_message_received;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ChatViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        ChatMessage msg = messages.get(position);
        holder.setSenderName(msg.getSenderName());
        holder.setTimestamp(msg.getTimestamp());
        renderers.render(holder, msg);
    }

    @Override
    public int getItemCount() { return messages.size(); }
}
