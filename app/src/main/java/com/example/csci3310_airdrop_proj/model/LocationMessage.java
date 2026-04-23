package com.example.csci3310_airdrop_proj.model;

import java.util.Locale;

/** A chat message that shares a GPS point. */
public final class LocationMessage extends ChatMessage {

    private final double latitude;
    private final double longitude;

    public LocationMessage(String senderName, long timestamp, boolean outgoing,
                           double latitude, double longitude) {
        super(senderName, timestamp, outgoing);
        this.latitude  = latitude;
        this.longitude = longitude;
    }

    public double getLatitude()  { return latitude;  }
    public double getLongitude() { return longitude; }

    @Override public Type   getType() { return Type.LOCATION; }
    @Override public String getText() {
        return String.format(Locale.US, "%.6f, %.6f", latitude, longitude);
    }
}
