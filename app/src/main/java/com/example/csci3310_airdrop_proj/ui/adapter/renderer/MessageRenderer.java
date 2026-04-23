package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatViewHolder;

/**
 * Strategy: one implementation per kind of chat message.
 *
 * Each renderer is responsible for populating the extra widgets in a
 * {@link ChatViewHolder} — image thumbnail, voice play button, map button,
 * etc. — while the adapter handles common fields like the timestamp and
 * sender name.
 *
 * Renderers are consulted by {@link MessageRendererRegistry} in registration
 * order. Register more specific renderers before fallback ones — e.g.
 * {@code ImageRenderer} before {@code FileRenderer}, because every image is
 * also a file but only one should win.
 */
public interface MessageRenderer {

    /** True if this renderer knows how to display {@code msg}. */
    boolean canRender(ChatMessage msg);

    /** Populate {@code holder} for {@code msg}. */
    void render(ChatViewHolder holder, ChatMessage msg);
}
