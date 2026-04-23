package com.example.csci3310_airdrop_proj.model;

/**
 * A single chat message. Abstract base; see {@link TextMessage},
 * {@link FileMessage}, {@link LocationMessage} for concrete variants.
 *
 * Switching from a product-type-with-flags ({@code Type enum} + many nullable
 * fields) to a proper sum type lets consumers dispatch by class and keeps each
 * subclass honest about which fields it exposes. The {@link Type} enum is
 * retained as a persistent discriminator — {@link com.example.csci3310_airdrop_proj.storage.ChatHistoryManager}
 * uses it so that JSON written by older builds still deserialises.
 */
public abstract class ChatMessage {

    public enum Type { TEXT, FILE, LOCATION }

    private final String senderName;
    private final long   timestamp;
    private final boolean outgoing;

    protected ChatMessage(String senderName, long timestamp, boolean outgoing) {
        this.senderName = senderName;
        this.timestamp  = timestamp;
        this.outgoing   = outgoing;
    }

    public String getSenderName() { return senderName; }
    public long   getTimestamp()  { return timestamp;  }
    public boolean isOutgoing()   { return outgoing;   }

    /** Persistent discriminator; survives JSON serialisation. */
    public abstract Type getType();

    /**
     * Human-readable label for this message. For {@link TextMessage} this is
     * the body of the message; for {@link FileMessage} the file name; for
     * {@link LocationMessage} the formatted coordinates. Adapters use it as
     * the default bubble text when no richer rendering applies.
     */
    public abstract String getText();
}
