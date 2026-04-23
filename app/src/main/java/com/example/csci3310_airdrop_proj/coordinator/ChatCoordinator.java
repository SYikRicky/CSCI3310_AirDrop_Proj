package com.example.csci3310_airdrop_proj.coordinator;

import android.net.Uri;
import android.os.Bundle;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.LocationMessage;
import com.example.csci3310_airdrop_proj.model.TextMessage;
import com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager;
import com.example.csci3310_airdrop_proj.network.TransferEventBus;
import com.example.csci3310_airdrop_proj.storage.ChatHistoryRepository;
import com.example.csci3310_airdrop_proj.ui.fragment.ChatDeviceListFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.ChatRoomFragment;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Owns all chat-related concerns previously crammed into MainActivity:
 *  - Persistent chat history (via {@link ChatHistoryRepository})
 *  - The {@link ChatRoomFragment} lifecycle (open, show connected/disconnected)
 *  - The {@link TransferEventBus.ChatListener} callbacks for incoming text and location
 *  - The {@link NearbyConnectionsManager.ChatFileReceivedCallback} for inline files
 *  - Sending text, files, and locations on behalf of the chat UI
 *
 * Holds two small pieces of cross-cutting state that other coordinators read:
 *  - {@link #getActiveChatEndpointId()} — which peer the user is actively chatting with
 *  - {@link #resolveDeviceName(String)} — endpointId → remembered device name
 *
 * Collaborators are injected via the constructor. The coordinator is
 * registered with the NearbyConnectionsManager as both a ChatListener and a
 * ChatFileReceivedCallback.
 */
public final class ChatCoordinator
        implements TransferEventBus.ChatListener,
                   NearbyConnectionsManager.ChatFileReceivedCallback {

    private final AppCompatActivity activity;
    private final NearbyConnectionsManager nearby;
    private final ChatHistoryRepository history;
    private final String localDeviceName;

    /** Endpoint the user is currently chatting with; null when no chatroom is open. */
    private String activeChatEndpointId;

    /** Ephemeral endpointId → remembered deviceName mapping for the current session. */
    private final Map<String, String> endpointToDeviceName = new HashMap<>();

    private ChatRoomFragment chatRoomFragment;
    private ChatDeviceListFragment chatDeviceListFragment;

    public ChatCoordinator(AppCompatActivity activity,
                           NearbyConnectionsManager nearby,
                           ChatHistoryRepository history,
                           String localDeviceName) {
        this.activity = activity;
        this.nearby = nearby;
        this.history = history;
        this.localDeviceName = localDeviceName;
    }

    // ── Cross-coordinator state access ───────────────────────────────────────

    public String getActiveChatEndpointId() { return activeChatEndpointId; }
    public void clearActiveChat() { activeChatEndpointId = null; chatRoomFragment = null; }

    public void rememberDeviceName(String endpointId, String deviceName) {
        endpointToDeviceName.put(endpointId, deviceName);
    }
    public String resolveDeviceName(String endpointId) {
        return endpointToDeviceName.getOrDefault(endpointId, endpointId);
    }

    public ChatRoomFragment getChatRoomFragment() { return chatRoomFragment; }
    public ChatDeviceListFragment getChatDeviceListFragment() { return chatDeviceListFragment; }
    public void setChatDeviceListFragment(ChatDeviceListFragment f) { chatDeviceListFragment = f; }

    public List<String> getPastConversationPeers() { return history.getAllPeers(); }

    // ── Opening the chat room ────────────────────────────────────────────────

    /** Open the chat room for {@code device}. Offline history mode shows past messages only. */
    public void openChatRoom(DeviceInfo device, boolean offlineHistoryOnly) {
        if (!offlineHistoryOnly) activeChatEndpointId = device.getEndpointId();
        chatRoomFragment = new ChatRoomFragment();

        Bundle args = new Bundle();
        args.putString(ChatRoomFragment.ARG_ENDPOINT_ID, device.getEndpointId());
        args.putString(ChatRoomFragment.ARG_DEVICE_NAME, device.getDeviceName());
        chatRoomFragment.setArguments(args);

        activity.getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, chatRoomFragment, ChatRoomFragment.TAG)
                .addToBackStack("chatroom")
                .commit();
        activity.getSupportFragmentManager().executePendingTransactions();

        if (offlineHistoryOnly && chatRoomFragment != null) {
            chatRoomFragment.showDisconnected();
        }

        List<ChatMessage> past = history.getHistory(device.getDeviceName());
        if (!past.isEmpty() && chatRoomFragment != null && chatRoomFragment.getView() != null) {
            chatRoomFragment.getView().post(() -> {
                for (ChatMessage m : past) chatRoomFragment.addMessage(m);
            });
        }
    }

    /** Notify the active chat room that its peer is connected. */
    public void notifyConnected(String endpointId) {
        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && endpointId.equals(activeChatEndpointId)) {
            chatRoomFragment.showConnected();
        }
    }

    /** Notify the active chat room that its peer disconnected. */
    public void notifyDisconnected(String endpointId) {
        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && endpointId.equals(activeChatEndpointId)) {
            chatRoomFragment.showDisconnected();
        }
    }

    /** Reset chat state when switching tabs. */
    public void resetForTabSwitch() {
        activeChatEndpointId = null;
        chatRoomFragment = null;
        activity.getSupportFragmentManager()
                .popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
    }

    // ── Sending ──────────────────────────────────────────────────────────────

    public void sendText(String endpointId, String text) {
        nearby.sendChatMessage(endpointId, localDeviceName, text);
        TextMessage msg = new TextMessage(localDeviceName, text,
                System.currentTimeMillis(), true);
        persistAndDisplay(endpointId, msg);
    }

    public void sendFile(String endpointId, Uri fileUri, FileMetadata metadata) {
        nearby.sendFileInChat(endpointId, fileUri, metadata);
        FileMessage msg = new FileMessage(localDeviceName,
                System.currentTimeMillis(), true, metadata);
        msg.setSavedUri(fileUri); // sender can immediately preview/play
        persistAndDisplay(endpointId, msg);
    }

    public void sendLocation(String endpointId, double latitude, double longitude) {
        nearby.sendLocationMessage(endpointId, localDeviceName, latitude, longitude);
        LocationMessage msg = new LocationMessage(
                localDeviceName, System.currentTimeMillis(), true, latitude, longitude);
        persistAndDisplay(endpointId, msg);
    }

    // ── Receiving: incoming file bubble created by FileTransferCoordinator ───

    public void addReceivedFileToChat(String endpointId, String fromDeviceName,
                                      FileMetadata meta) {
        if (chatRoomFragment == null || !chatRoomFragment.isAdded()
                || activeChatEndpointId == null) return;

        FileMessage msg = new FileMessage(fromDeviceName,
                System.currentTimeMillis(), false, meta);
        // endpointToDeviceName should already map activeChatEndpointId → fromDeviceName
        endpointToDeviceName.put(activeChatEndpointId, fromDeviceName);
        appendToHistory(activeChatEndpointId, msg);
        chatRoomFragment.addMessage(msg);
    }

    // ── TransferEventBus.ChatListener ────────────────────────────────────────

    @Override
    public void onChatMessageReceived(String endpointId, String senderName,
                                      String text, long timestamp) {
        endpointToDeviceName.put(endpointId, senderName);
        TextMessage msg = new TextMessage(senderName, text, timestamp, false);
        appendToHistory(endpointId, msg);

        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && endpointId.equals(activeChatEndpointId)) {
            chatRoomFragment.addMessage(msg);
        } else {
            Toast.makeText(activity, senderName + ": " + text, Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    public void onLocationMessageReceived(String endpointId, String senderName,
                                          double latitude, double longitude, long timestamp) {
        endpointToDeviceName.put(endpointId, senderName);
        LocationMessage msg = new LocationMessage(senderName, timestamp, false,
                latitude, longitude);
        appendToHistory(endpointId, msg);

        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && endpointId.equals(activeChatEndpointId)) {
            chatRoomFragment.addMessage(msg);
        } else {
            Toast.makeText(activity, senderName + " shared a location",
                    Toast.LENGTH_SHORT).show();
        }
    }

    // ── NearbyConnectionsManager.ChatFileReceivedCallback ────────────────────

    @Override
    public void onChatFileReceived(Uri uri, FileMetadata meta) {
        // Live chat bubble (if open) gets the URI
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.onFileSaved(uri, meta.getFileName(), meta.getMimeType());
        }
        // Persist URI so preview/play survives chat reopens
        if (activeChatEndpointId != null) {
            String deviceName = resolveDeviceName(activeChatEndpointId);
            history.updateFileUri(deviceName, meta.getFileName(), uri);
        }
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void persistAndDisplay(String endpointId, ChatMessage msg) {
        appendToHistory(endpointId, msg);
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.addMessage(msg);
        }
    }

    private void appendToHistory(String endpointId, ChatMessage msg) {
        String deviceName = resolveDeviceName(endpointId);
        history.saveMessage(deviceName, msg);
        if (chatDeviceListFragment != null && chatDeviceListFragment.isAdded()) {
            chatDeviceListFragment.updatePastConversations(history.getAllPeers());
        }
    }
}
