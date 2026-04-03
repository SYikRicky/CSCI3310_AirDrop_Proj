package com.example.csci3310_airdrop_proj.model;

/**
 * Represents a single chat message — either text or a file transfer.
 * Held in-memory by MainActivity, keyed by endpoint ID.
 */
public class ChatMessage {

    public enum Type { TEXT, FILE }

    private final Type type;
    private final String senderName;
    private final String text;         // message text, or file name for FILE type
    private final long timestamp;
    private final boolean outgoing;    // true = sent by this device
    private FileMetadata fileMetadata; // non-null for FILE type
    private int transferProgress;      // 0–100, used during active file transfer
    private android.net.Uri savedUri;  // set after file is saved to device (received FILE only)

    public ChatMessage(Type type, String senderName, String text, long timestamp, boolean outgoing) {
        this.type = type;
        this.senderName = senderName;
        this.text = text;
        this.timestamp = timestamp;
        this.outgoing = outgoing;
    }

    public Type getType()               { return type; }
    public String getSenderName()       { return senderName; }
    public String getText()             { return text; }
    public long getTimestamp()          { return timestamp; }
    public boolean isOutgoing()        { return outgoing; }

    public FileMetadata getFileMetadata()                   { return fileMetadata; }
    public void setFileMetadata(FileMetadata fileMetadata)  { this.fileMetadata = fileMetadata; }

    public int getTransferProgress()                        { return transferProgress; }
    public void setTransferProgress(int transferProgress)   { this.transferProgress = transferProgress; }

    public android.net.Uri getSavedUri()                    { return savedUri; }
    public void setSavedUri(android.net.Uri uri)            { this.savedUri = uri; }
}
