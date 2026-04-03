package com.example.csci3310_airdrop_proj.network;

import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.TransferProgress;

import java.util.List;

/**
 * Defines the callback interfaces (event bus) between the Network layer (Person B)
 * and the UI layer (Person A).
 *
 * - ConnectionListener: device discovery and connection state events
 * - TransferListener:   file transfer progress and completion events
 *
 * MainActivity implements both interfaces and registers itself with
 * NearbyConnectionsManager so all callbacks arrive on the main thread
 * (NearbyConnectionsManager calls runOnUiThread where needed).
 */
public class TransferEventBus {

    /**
     * Implemented by MainActivity to receive Nearby connection state changes.
     */
    public interface ConnectionListener {
        /** Called whenever the set of discovered nearby devices changes. */
        void onDevicesUpdated(List<DeviceInfo> devices);

        /** Called when a P2P connection is fully established and ready to transfer files. */
        void onConnectionEstablished(DeviceInfo device);

        /** Called when a connection request fails. */
        void onConnectionFailed(String reason);

        /** Called when a connected endpoint disconnects. */
        void onDisconnected(String endpointId);

        /**
         * Called in chat mode when another device requests a connection to us.
         * The UI should prompt the user to accept or reject via
         * {@link com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager#acceptChatInvitation}
         * or {@link com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager#rejectChatInvitation}.
         */
        void onChatInvitationReceived(String endpointId, String deviceName);
    }

    /**
     * Implemented by MainActivity to receive file transfer events.
     */
    public interface TransferListener {
        /** Called on the receiver when incoming file metadata is received. */
        void onIncomingFile(FileMetadata meta, String fromDeviceName);

        /** Called periodically during a transfer with updated progress. */
        void onTransferProgressUpdated(TransferProgress progress);

        /** Called when a transfer finishes successfully (both send and receive). */
        void onTransferCompleted(TransferProgress finalProgress);

        /** Called when a transfer fails. */
        void onTransferFailed(String fileName, String error);
    }

    /**
     * Implemented by MainActivity to receive chat text messages from connected devices.
     */
    public interface ChatListener {
        /** Called when a text message is received from a connected device. */
        void onChatMessageReceived(String endpointId, String senderName, String text, long timestamp);
    }
}
