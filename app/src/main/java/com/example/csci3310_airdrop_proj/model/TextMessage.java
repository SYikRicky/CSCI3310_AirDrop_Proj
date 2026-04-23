package com.example.csci3310_airdrop_proj.model;

/** A plain-text chat message. */
public final class TextMessage extends ChatMessage {

    private final String text;

    public TextMessage(String senderName, String text, long timestamp, boolean outgoing) {
        super(senderName, timestamp, outgoing);
        this.text = text;
    }

    @Override public Type   getType() { return Type.TEXT; }
    @Override public String getText() { return text;     }
}
