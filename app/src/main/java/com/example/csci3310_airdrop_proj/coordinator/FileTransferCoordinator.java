package com.example.csci3310_airdrop_proj.coordinator;

import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.TransferProgress;
import com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager;
import com.example.csci3310_airdrop_proj.network.TransferEventBus;
import com.example.csci3310_airdrop_proj.service.FileTransferService;
import com.example.csci3310_airdrop_proj.ui.fragment.ChatRoomFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.DeviceDiscoveryFragment;

/**
 * Owns the file-transfer concerns previously crammed into MainActivity:
 *  - The "send flow" state (pending file URI + metadata between file-pick and
 *    connection-established)
 *  - The {@link TransferEventBus.TransferListener} callbacks for incoming
 *    files and progress updates
 *  - The {@link FileTransferService.OnFileSavedCallback} (incoming files
 *    saved to Downloads)
 *
 * Collaborates with {@link ChatCoordinator} so that incoming file bubbles
 * appear in the active chat room when one is open.
 */
public final class FileTransferCoordinator
        implements TransferEventBus.TransferListener,
                   FileTransferService.OnFileSavedCallback {

    private final AppCompatActivity activity;
    private final NearbyConnectionsManager nearby;
    private final ChatCoordinator chat;

    /** URI of the file picked in SendModeFragment; held until a recipient is chosen. */
    private Uri          pendingFileUri;
    private FileMetadata pendingFileMeta;

    private DeviceDiscoveryFragment discoveryFragment;

    public FileTransferCoordinator(AppCompatActivity activity,
                                   NearbyConnectionsManager nearby,
                                   ChatCoordinator chat) {
        this.activity = activity;
        this.nearby = nearby;
        this.chat = chat;
    }

    // ── Send-flow API called by MainActivity ─────────────────────────────────

    public void setDiscoveryFragment(DeviceDiscoveryFragment f) { discoveryFragment = f; }
    public DeviceDiscoveryFragment getDiscoveryFragment() { return discoveryFragment; }

    public void setPendingFile(Uri fileUri, FileMetadata meta) {
        pendingFileUri = fileUri;
        pendingFileMeta = meta;
    }
    public void clearPendingFile() {
        pendingFileUri = null;
        pendingFileMeta = null;
    }
    public boolean hasPendingFile() { return pendingFileUri != null && pendingFileMeta != null; }

    /** True if the just-connected endpoint should trigger a pending file send. */
    public boolean onConnectionReadyForSend(String endpointId) {
        if (!hasPendingFile()) return false;
        nearby.sendFile(endpointId, pendingFileUri, pendingFileMeta);
        return true;
    }

    /** Called when the user taps a device in DeviceDiscoveryFragment. */
    public void onDeviceSelectedForSend(DeviceInfo device) {
        if (!hasPendingFile()) return;
        nearby.connectToDevice(device.getEndpointId());
    }

    // ── TransferEventBus.TransferListener ────────────────────────────────────

    @Override
    public void onIncomingFile(FileMetadata meta, String fromDeviceName) {
        Toast.makeText(activity,
                "Receiving \"" + meta.getFileName() + "\" from " + fromDeviceName,
                Toast.LENGTH_SHORT).show();

        // If a chat is open, put the incoming file bubble in it.
        String activeChat = chat.getActiveChatEndpointId();
        if (activeChat != null) {
            chat.addReceivedFileToChat(activeChat, fromDeviceName, meta);
        }
    }

    @Override
    public void onTransferProgressUpdated(TransferProgress progress) {
        if (!progress.isSending()) return;
        if (discoveryFragment == null || !discoveryFragment.isAdded()) return;
        String label = "Sending " + progress.getFileName()
                + " (" + progress.getProgressPercent() + "%)";
        discoveryFragment.showTransferProgress(progress.getProgressPercent(), label);
    }

    @Override
    public void onTransferCompleted(TransferProgress finalProgress) {
        if (finalProgress.isSending()) {
            Toast.makeText(activity, "Sent: " + finalProgress.getFileName(),
                    Toast.LENGTH_SHORT).show();
            if (discoveryFragment != null && discoveryFragment.isAdded()) {
                discoveryFragment.showTransferComplete(finalProgress.getFileName());
                activity.getSupportFragmentManager().popBackStack();
                clearPendingFile();
                nearby.stopDiscovery();
            }
            // In chat mode we stay in the chat room — nothing to do here.
        } else {
            Toast.makeText(activity, "File saved: " + finalProgress.getFileName(),
                    Toast.LENGTH_LONG).show();
        }
    }

    @Override
    public void onTransferFailed(String fileName, String error) {
        Toast.makeText(activity, "Transfer failed: " + error, Toast.LENGTH_SHORT).show();
        if (discoveryFragment != null && discoveryFragment.isAdded()) {
            discoveryFragment.showTransferFailed(error);
        }
    }

    // ── FileTransferService.OnFileSavedCallback ──────────────────────────────

    @Override
    public void onFileSaved(Uri savedUri, String fileName, String mimeType) {
        ChatRoomFragment room = chat.getChatRoomFragment();
        if (room != null && room.isAdded()) {
            room.onFileSaved(savedUri, fileName, mimeType);
        }
    }
}
