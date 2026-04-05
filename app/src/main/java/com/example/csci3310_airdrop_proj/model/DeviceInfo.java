package com.example.csci3310_airdrop_proj.model;

/**
 * Represents a nearby device discovered via Nearby Connections API.
 */
public class DeviceInfo {

    private final String endpointId;
    private final String deviceName;
    private boolean connected;

    public DeviceInfo(String endpointId, String deviceName) {
        this.endpointId = endpointId;
        this.deviceName = deviceName;
        this.connected = false;
    }

    public String getEndpointId() { return endpointId; }
    public String getDeviceName() { return deviceName; }
    public boolean isConnected() { return connected; }
    public void setConnected(boolean connected) { this.connected = connected; }

    @Override
    public String toString() {
        return "DeviceInfo{endpointId='" + endpointId + "', name='" + deviceName + "'}";
    }
}
