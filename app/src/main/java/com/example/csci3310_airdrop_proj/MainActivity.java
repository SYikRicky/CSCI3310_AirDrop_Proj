package com.example.csci3310_airdrop_proj;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.TransferProgress;
import com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager;
import com.example.csci3310_airdrop_proj.network.TransferEventBus;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.storage.ChatHistoryManager;
import com.example.csci3310_airdrop_proj.service.FileTransferService;
import com.example.csci3310_airdrop_proj.ui.fragment.ChatDeviceListFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.ChatRoomFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.DeviceDiscoveryFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.ReceiveModeFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.SendModeFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;

/**
 * MainActivity — the single Activity that hosts all Fragments.
 *
 * Responsibilities:
 *  1. Owns and initialises NearbyConnectionsManager.
 *  2. Implements TransferEventBus.ConnectionListener + TransferListener and
 *     forwards events to the currently visible Fragment.
 *  3. Hosts BottomNavigationView to switch between Send / Receive modes.
 *  4. Handles runtime permission requests.
 *  5. Orchestrates the send flow: SendModeFragment → DeviceDiscoveryFragment → transfer.
 *
 * Demonstrates: Activity lifecycle, Fragments, Intents, Permissions, Services.
 */
public class MainActivity extends AppCompatActivity
        implements TransferEventBus.ConnectionListener,
                   TransferEventBus.TransferListener,
                   TransferEventBus.ChatListener,
                   FileTransferService.OnFileSavedCallback {

    // ── Network ──────────────────────────────────────────────────────────────
    private NearbyConnectionsManager nearbyManager;

    // ── Device identity ───────────────────────────────────────────────────────
    private String localDeviceName;

    // ── Send-flow state ───────────────────────────────────────────────────────
    /** URI of the file the user picked in SendModeFragment — held until a device is chosen. */
    private Uri          pendingFileUri;
    private FileMetadata pendingFileMeta;

    // ── Fragment references ──────────────────────────────────────────────────
    private DeviceDiscoveryFragment discoveryFragment;
    private ReceiveModeFragment     receiveModeFragment;
    private ChatDeviceListFragment  chatDeviceListFragment;
    private ChatRoomFragment        chatRoomFragment;

    // ── Chat state ────────────────────────────────────────────────────────────
    /** The endpoint ID we're currently chatting with. */
    private String activeChatEndpointId;
    /** Maps endpointId → device name for the current session (endpointIds are ephemeral). */
    private final Map<String, String> endpointToDeviceName = new HashMap<>();
    /** Persistent storage for chat history across app restarts. */
    private ChatHistoryManager chatHistoryManager;

    // ── Permission launcher ───────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> {
                        boolean denied = results.containsValue(Boolean.FALSE);
                        if (denied) {
                            Toast.makeText(this,
                                    R.string.permission_required,
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        localDeviceName = initDeviceName();
        chatHistoryManager = new ChatHistoryManager(this);

        // Initialise NearbyConnectionsManager and register this Activity as listener
        nearbyManager = new NearbyConnectionsManager(this);
        nearbyManager.setLocalDeviceName(localDeviceName);
        nearbyManager.setConnectionListener(this);
        nearbyManager.setTransferListener(this);
        nearbyManager.setChatListener(this);

        setupBottomNavigation();
        requestRequiredPermissions();

        // Show Send mode by default on first launch
        if (savedInstanceState == null) {
            showSendMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FileTransferService.setCallback(this);
    }

    @Override
    protected void onPause() {
        super.onPause();
        FileTransferService.setCallback(null);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up all Nearby Connections state to avoid leaking resources
        nearbyManager.stopAll();
    }

    // ── FileTransferService.OnFileSavedCallback ───────────────────────────────

    @Override
    public void onFileSaved(android.net.Uri savedUri, String fileName, String mimeType) {
        if (receiveModeFragment != null && receiveModeFragment.isAdded()) {
            receiveModeFragment.showFileReceived(savedUri, fileName, mimeType);
        }
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.onFileSaved(savedUri, fileName, mimeType);
        }
    }

    // ── Bottom Navigation ─────────────────────────────────────────────────────

    private void setupBottomNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_send) {
                showSendMode();
                return true;
            } else if (id == R.id.nav_receive) {
                showReceiveMode();
                return true;
            } else if (id == R.id.nav_chat) {
                showChatMode();
                return true;
            }
            return false;
        });
    }

    private void showSendMode() {
        nearbyManager.setChatMode(false);
        // Stop any active discovery when switching away from send mode
        nearbyManager.stopDiscovery();
        pendingFileUri  = null;
        pendingFileMeta = null;
        // Clear back stack and show fresh SendModeFragment
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        replaceFragment(new SendModeFragment(), "send");
    }

    private void showReceiveMode() {
        nearbyManager.setChatMode(false);
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        receiveModeFragment = new ReceiveModeFragment();
        replaceFragment(receiveModeFragment, "receive");
    }

    private void replaceFragment(Fragment fragment, String tag) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    // ── Send flow (called by SendModeFragment) ────────────────────────────────

    /**
     * Called by SendModeFragment after the user picks a file.
     * Navigates to DeviceDiscoveryFragment and starts discovery.
     */
    public void onFilePicked(Uri uri, FileMetadata metadata) {
        pendingFileUri  = uri;
        pendingFileMeta = metadata;

        discoveryFragment = new DeviceDiscoveryFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, discoveryFragment, DeviceDiscoveryFragment.TAG)
                .addToBackStack("discovery") // allow Back to return to SendModeFragment
                .commit();

        // Start discovering nearby devices advertising DroidDrop
        nearbyManager.startDiscovery();
    }

    /**
     * Called by DeviceDiscoveryFragment when the user taps a device row.
     * Initiates a Nearby connection; file is sent in onConnectionEstablished().
     */
    public void onDeviceSelectedForSend(DeviceInfo device) {
        if (pendingFileUri == null || pendingFileMeta == null) return;
        nearbyManager.connectToDevice(device.getEndpointId());
    }

    // ── Receive flow (called by ReceiveModeFragment) ──────────────────────────

    /** Start advertising + discovering so senders can find and connect to us. */
    public void startReceiving() {
        nearbyManager.startAdvertising();
        nearbyManager.startDiscovery();
    }

    /** Stop all Nearby activity. */
    public void stopReceiving() {
        nearbyManager.stopAll();
    }

    // ── Chat flow ─────────────────────────────────────────────────────────────

    private void showChatMode() {
        nearbyManager.stopDiscovery();
        nearbyManager.stopAdvertising();
        activeChatEndpointId = null;
        chatRoomFragment = null;
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        chatDeviceListFragment = new ChatDeviceListFragment();
        replaceFragment(chatDeviceListFragment, ChatDeviceListFragment.TAG);
        // Enable invitation-dialog mode before starting advertising/discovery
        nearbyManager.setChatMode(true);
        // History is loaded by ChatDeviceListFragment.onViewCreated via getPastConversationPeers()
        // Both advertise and discover so either device can initiate
        nearbyManager.startAdvertising();
        nearbyManager.startDiscovery();
    }

    /** Called by ChatDeviceListFragment when a device is tapped. */
    public void onChatDeviceSelected(DeviceInfo device) {
        activeChatEndpointId = device.getEndpointId();
        endpointToDeviceName.put(device.getEndpointId(), device.getDeviceName());
        if (!device.isConnected()) {
            nearbyManager.connectToDevice(device.getEndpointId());
        }
        openChatRoom(device, false);
    }

    /**
     * Called by ChatDeviceListFragment when the user taps a past conversation (history-only, no live connection).
     * Opens the chatroom in offline/read-only mode showing stored messages.
     */
    public void onHistoryDeviceSelected(String peerDeviceName) {
        // Use device name as a pseudo endpoint ID for offline history viewing
        openChatRoom(new DeviceInfo(peerDeviceName, peerDeviceName), true);
    }

    private void openChatRoom(DeviceInfo device, boolean offlineHistoryOnly) {
        chatRoomFragment = new ChatRoomFragment();
        Bundle args = new Bundle();
        args.putString(ChatRoomFragment.ARG_ENDPOINT_ID, device.getEndpointId());
        args.putString(ChatRoomFragment.ARG_DEVICE_NAME, device.getDeviceName());
        chatRoomFragment.setArguments(args);

        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, chatRoomFragment, ChatRoomFragment.TAG)
                .addToBackStack("chatroom")
                .commit();

        // Load history from persistent storage (addToChatHistory always saves there)
        String deviceName = device.getDeviceName();
        List<ChatMessage> history = chatHistoryManager.getHistory(deviceName);

        getSupportFragmentManager().executePendingTransactions();

        if (offlineHistoryOnly && chatRoomFragment != null) {
            chatRoomFragment.showDisconnected();
        }

        if (!history.isEmpty() && chatRoomFragment != null) {
            chatRoomFragment.getView().post(() -> {
                for (ChatMessage msg : history) {
                    chatRoomFragment.addMessage(msg);
                }
            });
        }
    }

    /** Called by ChatRoomFragment to send a text message. */
    public void onChatSendText(String endpointId, String text) {
        nearbyManager.sendChatMessage(endpointId, localDeviceName, text);
        ChatMessage msg = new ChatMessage(
                ChatMessage.Type.TEXT, localDeviceName, text, System.currentTimeMillis(), true);
        addToChatHistory(endpointId, msg);
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.addMessage(msg);
        }
    }

    /** Called by ChatRoomFragment to send a file. */
    public void onChatSendFile(String endpointId, Uri fileUri, FileMetadata metadata) {
        nearbyManager.sendFileInChat(endpointId, fileUri, metadata);
        ChatMessage msg = new ChatMessage(
                ChatMessage.Type.FILE, localDeviceName, metadata.getFileName(),
                System.currentTimeMillis(), true);
        msg.setFileMetadata(metadata);
        addToChatHistory(endpointId, msg);
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.addMessage(msg);
        }
    }

    private void addToChatHistory(String endpointId, ChatMessage msg) {
        String deviceName = endpointToDeviceName.getOrDefault(endpointId, endpointId);
        chatHistoryManager.saveMessage(deviceName, msg);
        if (chatDeviceListFragment != null && chatDeviceListFragment.isAdded()) {
            chatDeviceListFragment.updatePastConversations(chatHistoryManager.getAllPeers());
        }
    }

    // ── Public accessors used by fragments ────────────────────────────────────

    /** Returns this device's persistent display name. */
    public String getLocalDeviceName() { return localDeviceName; }

    /** Returns all peers that have stored chat history (for the history section). */
    public List<String> getPastConversationPeers() {
        return chatHistoryManager.getAllPeers();
    }

    // ── Device name ───────────────────────────────────────────────────────────

    /**
     * Returns the persistent device name, generating one on first launch.
     * Stored in SharedPreferences so it survives app restarts.
     */
    private String initDeviceName() {
        SharedPreferences prefs = getSharedPreferences("app_prefs", MODE_PRIVATE);
        String name = prefs.getString("device_name", null);
        if (name == null) {
            name = generateRandomName();
            prefs.edit().putString("device_name", name).apply();
        }
        return name;
    }

    private String generateRandomName() {
        String[] adjectives = {"Swift", "Brave", "Bright", "Cool", "Quick", "Bold", "Sharp", "Calm",
                               "Witty", "Wise", "Noble", "Fierce", "Gentle", "Lucky", "Nimble"};
        String[] nouns = {"Fox", "Lion", "Bear", "Wolf", "Hawk", "Owl", "Deer", "Lynx",
                          "Panda", "Eagle", "Tiger", "Rabbit", "Falcon", "Otter", "Raven"};
        Random random = new Random();
        return adjectives[random.nextInt(adjectives.length)]
                + nouns[random.nextInt(nouns.length)];
    }

    // ── Permission handling ───────────────────────────────────────────────────

    /**
     * Build and request the permission set appropriate for this API level.
     * Only requests permissions that haven't been granted yet.
     */
    private void requestRequiredPermissions() {
        List<String> needed = new ArrayList<>();
        int sdk = Build.VERSION.SDK_INT;

        if (sdk >= Build.VERSION_CODES.TIRAMISU) {           // API 33+
            addIfNeeded(needed, Manifest.permission.NEARBY_WIFI_DEVICES);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_SCAN);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_ADVERTISE);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_CONNECT);
            addIfNeeded(needed, Manifest.permission.READ_MEDIA_IMAGES);
            addIfNeeded(needed, Manifest.permission.READ_MEDIA_VIDEO);
            addIfNeeded(needed, Manifest.permission.POST_NOTIFICATIONS);
        } else if (sdk >= Build.VERSION_CODES.S) {           // API 31–32
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_SCAN);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_ADVERTISE);
            addIfNeeded(needed, Manifest.permission.BLUETOOTH_CONNECT);
            addIfNeeded(needed, Manifest.permission.ACCESS_FINE_LOCATION);
        } else {                                              // API 24–30
            addIfNeeded(needed, Manifest.permission.ACCESS_FINE_LOCATION);
            addIfNeeded(needed, Manifest.permission.READ_EXTERNAL_STORAGE);
            if (sdk <= Build.VERSION_CODES.P) {               // API 24–28: write to Downloads
                addIfNeeded(needed, Manifest.permission.WRITE_EXTERNAL_STORAGE);
            }
        }

        if (!needed.isEmpty()) {
            permissionLauncher.launch(needed.toArray(new String[0]));
        }
    }

    private void addIfNeeded(List<String> list, String permission) {
        if (ContextCompat.checkSelfPermission(this, permission)
                != PackageManager.PERMISSION_GRANTED) {
            list.add(permission);
        }
    }

    // ── TransferEventBus.ConnectionListener ──────────────────────────────────

    @Override
    public void onDevicesUpdated(List<DeviceInfo> devices) {
        if (discoveryFragment != null && discoveryFragment.isAdded()) {
            discoveryFragment.updateDeviceList(devices);
        }
        if (chatDeviceListFragment != null && chatDeviceListFragment.isAdded()) {
            chatDeviceListFragment.updateDeviceList(devices);
        }
    }

    @Override
    public void onConnectionEstablished(DeviceInfo device) {
        endpointToDeviceName.put(device.getEndpointId(), device.getDeviceName());
        Toast.makeText(this, "Connected to " + device.getDeviceName(), Toast.LENGTH_SHORT).show();

        if (activeChatEndpointId != null && activeChatEndpointId.equals(device.getEndpointId())) {
            // Chat mode — notify chatroom of connection
            if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
                chatRoomFragment.showConnected();
            }
        } else if (pendingFileUri != null && pendingFileMeta != null) {
            // Send mode — send the pending file
            nearbyManager.sendFile(device.getEndpointId(), pendingFileUri, pendingFileMeta);
        }
        // Note: invitation-accepted side opens chatroom in onChatInvitationReceived,
        // so we don't need to handle that case here.
    }

    @Override
    public void onConnectionFailed(String reason) {
        Toast.makeText(this, "Connection failed: " + reason, Toast.LENGTH_SHORT).show();
        if (discoveryFragment != null && discoveryFragment.isAdded()) {
            discoveryFragment.showTransferFailed(reason);
        }
    }

    @Override
    public void onChatInvitationReceived(String endpointId, String deviceName) {
        new AlertDialog.Builder(this)
                .setTitle(R.string.chat_invitation_title)
                .setMessage(getString(R.string.chat_invitation_message, deviceName))
                .setCancelable(false)
                .setPositiveButton(R.string.chat_invitation_accept, (dialog, which) -> {
                    activeChatEndpointId = endpointId;
                    endpointToDeviceName.put(endpointId, deviceName);
                    nearbyManager.acceptChatInvitation(endpointId);
                    openChatRoom(new DeviceInfo(endpointId, deviceName), false);
                })
                .setNegativeButton(R.string.chat_invitation_decline, (dialog, which) ->
                        nearbyManager.rejectChatInvitation(endpointId))
                .show();
    }

    @Override
    public void onDisconnected(String endpointId) {
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && endpointId.equals(activeChatEndpointId)) {
            chatRoomFragment.showDisconnected();
        }
    }

    // ── TransferEventBus.TransferListener ────────────────────────────────────

    @Override
    public void onIncomingFile(FileMetadata meta, String fromDeviceName) {
        String text = "Receiving \"" + meta.getFileName() + "\" from " + fromDeviceName;
        Toast.makeText(this, text, Toast.LENGTH_SHORT).show();
        if (receiveModeFragment != null && receiveModeFragment.isAdded()) {
            receiveModeFragment.setStatusText("Receiving: " + meta.getFileName());
        }
        // Show file message in chat if active
        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && activeChatEndpointId != null) {
            ChatMessage msg = new ChatMessage(ChatMessage.Type.FILE, fromDeviceName,
                    meta.getFileName(), System.currentTimeMillis(), false);
            msg.setFileMetadata(meta);
            // endpointToDeviceName should already map activeChatEndpointId → fromDeviceName
            endpointToDeviceName.put(activeChatEndpointId, fromDeviceName);
            addToChatHistory(activeChatEndpointId, msg);
            chatRoomFragment.addMessage(msg);
        }
    }

    @Override
    public void onTransferProgressUpdated(TransferProgress progress) {
        String label = (progress.isSending() ? "Sending" : "Receiving")
                + " " + progress.getFileName()
                + " (" + progress.getProgressPercent() + "%)";

        if (progress.isSending()) {
            if (discoveryFragment != null && discoveryFragment.isAdded()) {
                discoveryFragment.showTransferProgress(progress.getProgressPercent(), label);
            }
        } else {
            if (receiveModeFragment != null && receiveModeFragment.isAdded()) {
                receiveModeFragment.setStatusText(label);
            }
        }
    }

    @Override
    public void onTransferCompleted(TransferProgress finalProgress) {
        if (finalProgress.isSending()) {
            Toast.makeText(this, "Sent: " + finalProgress.getFileName(), Toast.LENGTH_SHORT).show();
            if (discoveryFragment != null && discoveryFragment.isAdded()) {
                discoveryFragment.showTransferComplete(finalProgress.getFileName());
                // Return to Send mode only when in Send flow (not chat)
                getSupportFragmentManager().popBackStack();
                pendingFileUri  = null;
                pendingFileMeta = null;
                nearbyManager.stopDiscovery();
            }
            // In chat mode, don't pop — stay in the chatroom
        } else {
            Toast.makeText(this, "File saved: " + finalProgress.getFileName(), Toast.LENGTH_LONG).show();
            if (receiveModeFragment != null && receiveModeFragment.isAdded()) {
                receiveModeFragment.setStatusText("Saved: " + finalProgress.getFileName());
            }
        }
    }

    @Override
    public void onTransferFailed(String fileName, String error) {
        Toast.makeText(this, "Transfer failed: " + error, Toast.LENGTH_SHORT).show();
        if (discoveryFragment != null && discoveryFragment.isAdded()) {
            discoveryFragment.showTransferFailed(error);
        }
    }

    // ── TransferEventBus.ChatListener ─────────────────────────────────────────

    @Override
    public void onChatMessageReceived(String endpointId, String senderName, String text, long timestamp) {
        // Keep device name mapping up-to-date (senderName comes from the wire message)
        endpointToDeviceName.put(endpointId, senderName);
        ChatMessage msg = new ChatMessage(ChatMessage.Type.TEXT, senderName, text, timestamp, false);
        addToChatHistory(endpointId, msg);

        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && endpointId.equals(activeChatEndpointId)) {
            chatRoomFragment.addMessage(msg);
        } else {
            Toast.makeText(this, senderName + ": " + text, Toast.LENGTH_SHORT).show();
        }
    }
}
