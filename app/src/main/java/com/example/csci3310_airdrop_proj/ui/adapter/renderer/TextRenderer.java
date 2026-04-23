package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.TextMessage;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatViewHolder;

/** Renders a plain text bubble — no image, no buttons. */
public final class TextRenderer implements MessageRenderer {

    @Override
    public boolean canRender(ChatMessage msg) {
        return msg instanceof TextMessage;
    }

    @Override
    public void render(ChatViewHolder holder, ChatMessage msg) {
        holder.hideAllExtras();
        holder.setBodyText(msg.getText());
    }
}
