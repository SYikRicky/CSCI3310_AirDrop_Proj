package com.example.csci3310_airdrop_proj.model;

/**
 * Represents a single chat message — text, file transfer, or GPS location share.
 * Held in-memory by MainActivity, keyed by endpoint ID.
 */
public class ChatMessage {

    public enum Type { TEXT, FILE, LOCATION }

    private final Type type;
    private final String senderName;
    private final String text;         // message text, file name for FILE, or label for LOCATION
    private final long timestamp;
    private final boolean outgoing;    // true = sent by this device
    private FileMetadata fileMetadata; // non-null for FILE type
    private int transferProgress;      // 0–100, used during active file transfer
    private android.net.Uri savedUri;  // set after file is saved to device (received FILE only)
    private double latitude;           // valid when type == LOCATION
    private double longitude;          // valid when type == LOCATION

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

    public double getLatitude()                             { return latitude; }
    public void setLatitude(double latitude)                { this.latitude = latitude; }

    public double getLongitude()                            { return longitude; }
    public void setLongitude(double longitude)              { this.longitude = longitude; }

    /** Convenience factory for creating a LOCATION message. */
    public static ChatMessage createLocation(String senderName, double lat, double lng,
                                             long timestamp, boolean outgoing) {
        ChatMessage msg = new ChatMessage(Type.LOCATION, senderName,
                String.format(java.util.Locale.US, "%.6f, %.6f", lat, lng),
                timestamp, outgoing);
        msg.latitude = lat;
        msg.longitude = lng;
        return msg;
    }
}
