package com.example.csci3310_airdrop_proj.coordinator;

import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager;
import com.example.csci3310_airdrop_proj.network.TransferEventBus;
import com.example.csci3310_airdrop_proj.ui.fragment.ChatDeviceListFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.DeviceDiscoveryFragment;

import java.util.List;

/**
 * Owns connection-lifecycle concerns previously crammed into MainActivity:
 *  - {@link TransferEventBus.ConnectionListener} callbacks for discovery,
 *    connection result, chat invitation, and disconnection events
 *  - Routing those events to the correct fragment (discovery vs. chat list)
 *    and forwarding chat-related connection state to {@link ChatCoordinator}
 *    and pending-send state to {@link FileTransferCoordinator}
 */
public final class ConnectionCoordinator
        implements TransferEventBus.ConnectionListener {

    private final AppCompatActivity activity;
    private final NearbyConnectionsManager nearby;
    private final ChatCoordinator chat;
    private final FileTransferCoordinator transfer;

    public ConnectionCoordinator(AppCompatActivity activity,
                                 NearbyConnectionsManager nearby,
                                 ChatCoordinator chat,
                                 FileTransferCoordinator transfer) {
        this.activity = activity;
        this.nearby = nearby;
        this.chat = chat;
        this.transfer = transfer;
    }

    // ── TransferEventBus.ConnectionListener ──────────────────────────────────

    @Override
    public void onDevicesUpdated(List<DeviceInfo> devices) {
        DeviceDiscoveryFragment disc = transfer.getDiscoveryFragment();
        if (disc != null && disc.isAdded()) disc.updateDeviceList(devices);

        ChatDeviceListFragment chatList = chat.getChatDeviceListFragment();
        if (chatList != null && chatList.isAdded()) chatList.updateDeviceList(devices);
    }

    @Override
    public void onConnectionEstablished(DeviceInfo device) {
        chat.rememberDeviceName(device.getEndpointId(), device.getDeviceName());
        Toast.makeText(activity,
                "Connected to " + device.getDeviceName(),
                Toast.LENGTH_SHORT).show();

        String activeChat = chat.getActiveChatEndpointId();
        if (activeChat != null && activeChat.equals(device.getEndpointId())) {
            chat.notifyConnected(device.getEndpointId());
        } else {
            // Possibly a pending send — FileTransferCoordinator decides.
            transfer.onConnectionReadyForSend(device.getEndpointId());
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        Toast.makeText(activity, "Connection failed: " + reason,
                Toast.LENGTH_SHORT).show();
        DeviceDiscoveryFragment disc = transfer.getDiscoveryFragment();
        if (disc != null && disc.isAdded()) disc.showTransferFailed(reason);
    }

    @Override
    public void onChatInvitationReceived(String endpointId, String deviceName) {
        new AlertDialog.Builder(activity)
                .setTitle(R.string.chat_invitation_title)
                .setMessage(activity.getString(R.string.chat_invitation_message, deviceName))
                .setCancelable(false)
                .setPositiveButton(R.string.chat_invitation_accept, (dialog, which) -> {
                    chat.rememberDeviceName(endpointId, deviceName);
                    nearby.acceptChatInvitation(endpointId);
                    chat.openChatRoom(new DeviceInfo(endpointId, deviceName), false);
                })
                .setNegativeButton(R.string.chat_invitation_decline, (dialog, which) ->
                        nearby.rejectChatInvitation(endpointId))
                .show();
    }

    @Override
    public void onDisconnected(String endpointId) {
        Toast.makeText(activity, "Disconnected", Toast.LENGTH_SHORT).show();
        chat.notifyDisconnected(endpointId);
    }
}
