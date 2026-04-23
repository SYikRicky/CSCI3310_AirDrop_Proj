package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatViewHolder;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Chain of Responsibility: walks an ordered list of {@link MessageRenderer}
 * implementations and lets the first matching one handle the message.
 */
public final class MessageRendererRegistry {

    private final List<MessageRenderer> renderers;

    public MessageRendererRegistry(MessageRenderer... renderers) {
        this.renderers = Collections.unmodifiableList(Arrays.asList(renderers.clone()));
    }

    /**
     * Render {@code msg} into {@code holder} using the first matching renderer.
     * If no renderer claims the message (a bug in wiring), the bubble falls
     * back to the message's {@link ChatMessage#getText() default label} so the
     * user at least sees something.
     */
    public void render(ChatViewHolder holder, ChatMessage msg) {
        for (MessageRenderer r : renderers) {
            if (r.canRender(msg)) {
                r.render(holder, msg);
                return;
            }
        }
        holder.hideAllExtras();
        holder.setBodyText(msg.getText());
    }
}
