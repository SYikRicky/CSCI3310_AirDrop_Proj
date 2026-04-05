package com.example.csci3310_airdrop_proj;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

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
import com.example.csci3310_airdrop_proj.ui.MapActivity;
import com.example.csci3310_airdrop_proj.ui.fragment.SendModeFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

// Shared Drive (Firebase)
import com.example.csci3310_airdrop_proj.model.SharedFile;
import com.example.csci3310_airdrop_proj.repository.SharedDriveRepository;
import com.example.csci3310_airdrop_proj.ui.fragment.FileListFragment;

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
                   FileTransferService.OnFileSavedCallback,
                   NearbyConnectionsManager.ChatFileReceivedCallback {

    private static final String TAG = "MainActivity";

    // ── Network ──────────────────────────────────────────────────────────────
    private NearbyConnectionsManager nearbyManager;

    // ── Location ──────────────────────────────────────────────────────────────
    private FusedLocationProviderClient fusedLocationClient;

    // ── Device identity ───────────────────────────────────────────────────────
    private String localDeviceName;

    // ── Send-flow state ───────────────────────────────────────────────────────
    /** URI of the file the user picked in SendModeFragment — held until a device is chosen. */
    private Uri          pendingFileUri;
    private FileMetadata pendingFileMeta;

    // ── Fragment references ──────────────────────────────────────────────────
    private DeviceDiscoveryFragment discoveryFragment;
    private ChatDeviceListFragment  chatDeviceListFragment;
    private ChatRoomFragment        chatRoomFragment;

    // ── Chat state ────────────────────────────────────────────────────────────
    /** The endpoint ID we're currently chatting with. */
    private String activeChatEndpointId;
    /** Maps endpointId → device name for the current session (endpointIds are ephemeral). */
    private final Map<String, String> endpointToDeviceName = new HashMap<>();
    /** Persistent storage for chat history across app restarts. */
    private ChatHistoryManager chatHistoryManager;

    // ── Shared Drive (Firebase) ───────────────────────────────────────────────
    private SharedDriveRepository repository;
    private SendModeFragment      sendModeFragment;
    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                getContentResolver().takePersistableUriPermission(
                                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                navigateToDriveUpload(uri, resolveFileName(uri),
                                        resolveMimeType(uri), resolveFileSize(uri));
                            }
                        }
                    });

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
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);

        // Initialise Firebase shared drive repository
        repository = new SharedDriveRepository();

        // Initialise NearbyConnectionsManager and register this Activity as listener
        nearbyManager = new NearbyConnectionsManager(this);
        nearbyManager.setLocalDeviceName(localDeviceName);
        nearbyManager.setConnectionListener(this);
        nearbyManager.setTransferListener(this);
        nearbyManager.setChatListener(this);
        NearbyConnectionsManager.setChatFileCallback(this);

        setupBottomNavigation();
        requestRequiredPermissions();

        // Show Drive mode by default on first launch
        if (savedInstanceState == null) {
            showDriveMode();
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
        NearbyConnectionsManager.setChatFileCallback(null);
        // Clean up all Nearby Connections state to avoid leaking resources
        nearbyManager.stopAll();
        super.onDestroy();
    }

    // ── FileTransferService.OnFileSavedCallback ───────────────────────────────

    @Override
    public void onFileSaved(android.net.Uri savedUri, String fileName, String mimeType) {
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.onFileSaved(savedUri, fileName, mimeType);
        }
    }

    // ── NearbyConnectionsManager.ChatFileReceivedCallback ────────────────────

    /** Called on the main thread when a received chat file has been copied to cache. */
    @Override
    public void onChatFileReceived(android.net.Uri uri, FileMetadata meta) {
        // Update the live chat bubble (if open)
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.onFileSaved(uri, meta.getFileName(), meta.getMimeType());
        }
        // Persist the URI so the thumbnail / Play button survives reopening the chat
        if (activeChatEndpointId != null) {
            String deviceName = endpointToDeviceName.getOrDefault(
                    activeChatEndpointId, activeChatEndpointId);
            chatHistoryManager.updateFileUri(deviceName, meta.getFileName(), uri);
        }
    }

    // ── Bottom Navigation ─────────────────────────────────────────────────────

    private void setupBottomNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_drive) {
                showDriveMode();
                return true;
            } else if (id == R.id.nav_chat) {
                showChatMode();
                return true;
            } else if (id == R.id.nav_map) {
                openMap();
                return true;
            }
            return false;
        });
    }

    private void showDriveMode() {
        nearbyManager.setChatMode(false);
        nearbyManager.stopDiscovery();
        pendingFileUri  = null;
        pendingFileMeta = null;
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        replaceFragment(new FileListFragment(), FileListFragment.TAG);
    }

    private void replaceFragment(Fragment fragment, String tag) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    // ── Map ───────────────────────────────────────────────────────────────────

    private void openMap() {
        nearbyManager.setChatMode(false);
        nearbyManager.stopDiscovery();
        startActivity(new Intent(this, MapActivity.class));
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

    /** Called by ChatRoomFragment to send a file or voice message. */
    public void onChatSendFile(String endpointId, Uri fileUri, FileMetadata metadata) {
        nearbyManager.sendFileInChat(endpointId, fileUri, metadata);
        ChatMessage msg = new ChatMessage(
                ChatMessage.Type.FILE, localDeviceName, metadata.getFileName(),
                System.currentTimeMillis(), true);
        msg.setFileMetadata(metadata);
        // Store the URI so the sender can immediately play back voice messages
        msg.setSavedUri(fileUri);
        addToChatHistory(endpointId, msg);
        if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
            chatRoomFragment.addMessage(msg);
        }
    }

    /** Called by ChatRoomFragment when the user taps the location button. */
    @SuppressWarnings("MissingPermission")
    public void onChatSendLocation(String endpointId) {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, R.string.location_permission_needed, Toast.LENGTH_SHORT).show();
            permissionLauncher.launch(new String[]{Manifest.permission.ACCESS_FINE_LOCATION});
            return;
        }

        fusedLocationClient.getCurrentLocation(Priority.PRIORITY_HIGH_ACCURACY, null)
                .addOnSuccessListener(this, location -> {
                    if (location == null) {
                        Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    double lat = location.getLatitude();
                    double lng = location.getLongitude();

                    nearbyManager.sendLocationMessage(endpointId, localDeviceName, lat, lng);

                    ChatMessage msg = ChatMessage.createLocation(
                            localDeviceName, lat, lng, System.currentTimeMillis(), true);
                    addToChatHistory(endpointId, msg);
                    if (chatRoomFragment != null && chatRoomFragment.isAdded()) {
                        chatRoomFragment.addMessage(msg);
                    }
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Location fetch failed", e);
                    Toast.makeText(this, R.string.location_unavailable, Toast.LENGTH_SHORT).show();
                });
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
            addIfNeeded(needed, Manifest.permission.ACCESS_FINE_LOCATION);
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

    @Override
    public void onLocationMessageReceived(String endpointId, String senderName,
                                          double latitude, double longitude, long timestamp) {
        endpointToDeviceName.put(endpointId, senderName);
        ChatMessage msg = ChatMessage.createLocation(senderName, latitude, longitude, timestamp, false);
        addToChatHistory(endpointId, msg);

        if (chatRoomFragment != null && chatRoomFragment.isAdded()
                && endpointId.equals(activeChatEndpointId)) {
            chatRoomFragment.addMessage(msg);
        } else {
            Toast.makeText(this, senderName + " shared a location", Toast.LENGTH_SHORT).show();
        }
    }

    // ── Shared Drive public API (called by FileListFragment / SendModeFragment) ──

    public SharedDriveRepository getRepository() { return repository; }

    public void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void navigateToDriveUpload(Uri uri, String fileName, String mimeType, long size) {
        sendModeFragment = new SendModeFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, sendModeFragment, "upload")
                .addToBackStack("upload")
                .commit();
        getSupportFragmentManager().executePendingTransactions();
        if (sendModeFragment.getView() != null) {
            sendModeFragment.setPreselectedFile(uri, fileName, mimeType, size);
        } else {
            new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                    sendModeFragment.setPreselectedFile(uri, fileName, mimeType, size));
        }
    }

    public void uploadFile(Uri uri, String fileName, String mimeType, long fileSize) {
        repository.uploadFile(uri, fileName, mimeType, fileSize,
                getContentResolver(),
                new SharedDriveRepository.UploadCallback() {
                    @Override public void onProgress(int percent) {
                        runOnUiThread(() -> {
                            if (sendModeFragment != null && sendModeFragment.isAdded())
                                sendModeFragment.onUploadProgress(percent);
                        });
                    }
                    @Override public void onSuccess(SharedFile file) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Uploaded: " + file.getFileName(), Toast.LENGTH_SHORT).show();
                            sendModeFragment = null;
                            getSupportFragmentManager()
                                    .beginTransaction()
                                    .replace(R.id.fragment_container,
                                            new FileListFragment(), FileListFragment.TAG)
                                    .commitAllowingStateLoss();
                        });
                    }
                    @Override public void onFailure(Exception e) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            if (sendModeFragment != null && sendModeFragment.isAdded())
                                sendModeFragment.onUploadFailure(e.getMessage());
                        });
                    }
                });
    }

    public void previewFile(SharedFile file) {
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(Uri.parse(file.getDownloadUrl()),
                file.getMimeType() != null ? file.getMimeType() : "*/*");
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            startActivity(intent);
        } catch (android.content.ActivityNotFoundException e) {
            Toast.makeText(this, "No app found to open this file type", Toast.LENGTH_SHORT).show();
        }
    }

    public void downloadFile(SharedFile file) {
        try {
            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(Uri.parse(file.getDownloadUrl()))
                            .setTitle(file.getFileName())
                            .setDescription("Downloading from DroidDrive")
                            .setNotificationVisibility(
                                    android.app.DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(
                                    android.os.Environment.DIRECTORY_DOWNLOADS, file.getFileName())
                            .setMimeType(file.getMimeType() != null ? file.getMimeType() : "*/*");
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(android.content.Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, "Downloading: " + file.getFileName(), Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    public void deleteFile(SharedFile file) {
        repository.deleteFile(file, new SharedDriveRepository.DeleteCallback() {
            @Override public void onSuccess() {
                Toast.makeText(MainActivity.this,
                        "Deleted: " + file.getFileName(), Toast.LENGTH_SHORT).show();
            }
            @Override public void onFailure(Exception e) {
                Toast.makeText(MainActivity.this,
                        "Delete failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    private String resolveFileName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "file";
    }

    private String resolveMimeType(Uri uri) {
        String type = getContentResolver().getType(uri);
        return type != null ? type : "*/*";
    }

    private long resolveFileSize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {}
        return 0;
    }
}
