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
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationServices;
import com.google.android.gms.location.Priority;

import com.example.csci3310_airdrop_proj.coordinator.ChatCoordinator;
import com.example.csci3310_airdrop_proj.coordinator.ConnectionCoordinator;
import com.example.csci3310_airdrop_proj.coordinator.FileTransferCoordinator;
import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.SharedFile;
import com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager;
import com.example.csci3310_airdrop_proj.repository.FirebaseSharedDriveRepository;
import com.example.csci3310_airdrop_proj.repository.SharedDriveRepository;
import com.example.csci3310_airdrop_proj.service.FileTransferService;
import com.example.csci3310_airdrop_proj.storage.ChatHistoryRepository;
import com.example.csci3310_airdrop_proj.storage.SharedPrefsChatHistoryRepository;
import com.example.csci3310_airdrop_proj.ui.MapActivity;
import com.example.csci3310_airdrop_proj.ui.fragment.ChatDeviceListFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.DeviceDiscoveryFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.FileListFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.SendModeFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/**
 * The single Activity hosting all fragments.
 *
 * After the SOLID refactor, this Activity is a thin host. It is responsible for:
 *  - Creating and wiring the Nearby, repositories, and three coordinators
 *  - Owning Android-platform concerns it cannot delegate:
 *      permissions, activity-result launchers, location client, fragment navigation,
 *      device-name bootstrap, the Shared Drive (Firebase) CRUD flow
 *  - Exposing small shim methods the fragments call
 *
 * The three coordinators own the behavioural concerns:
 *  - {@link ConnectionCoordinator}     — TransferEventBus.ConnectionListener
 *  - {@link ChatCoordinator}           — ChatListener + ChatFileReceivedCallback
 *  - {@link FileTransferCoordinator}   — TransferListener + OnFileSavedCallback
 */
public class MainActivity extends AppCompatActivity {

    private static final String TAG = "MainActivity";

    // ── Infrastructure ───────────────────────────────────────────────────────
    private NearbyConnectionsManager    nearbyManager;
    private FusedLocationProviderClient fusedLocationClient;
    private SharedDriveRepository       repository;
    private ChatHistoryRepository       chatHistoryManager;
    private String                      localDeviceName;

    // ── Coordinators ─────────────────────────────────────────────────────────
    private ChatCoordinator         chatCoordinator;
    private FileTransferCoordinator fileTransferCoordinator;
    private ConnectionCoordinator   connectionCoordinator;

    // ── Shared Drive UI state ────────────────────────────────────────────────
    private SendModeFragment sendModeFragment;

    // ── Activity-result launchers ────────────────────────────────────────────

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

    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> {
                        if (results.containsValue(Boolean.FALSE)) {
                            Toast.makeText(this,
                                    R.string.permission_required, Toast.LENGTH_LONG).show();
                        }
                    });

    // ── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        localDeviceName     = initDeviceName();
        chatHistoryManager  = new SharedPrefsChatHistoryRepository(this);
        fusedLocationClient = LocationServices.getFusedLocationProviderClient(this);
        repository          = new FirebaseSharedDriveRepository();

        nearbyManager = new NearbyConnectionsManager(this);
        nearbyManager.setLocalDeviceName(localDeviceName);

        // Coordinators: Chat first (no other coordinator deps), FileTransfer (needs Chat),
        // then Connection (needs both).
        chatCoordinator         = new ChatCoordinator(this, nearbyManager, chatHistoryManager,
                                                      localDeviceName);
        fileTransferCoordinator = new FileTransferCoordinator(this, nearbyManager, chatCoordinator);
        connectionCoordinator   = new ConnectionCoordinator(this, nearbyManager,
                                                            chatCoordinator, fileTransferCoordinator);

        // Wire coordinators as the Nearby listeners.
        nearbyManager.setConnectionListener(connectionCoordinator);
        nearbyManager.setTransferListener(fileTransferCoordinator);
        nearbyManager.setChatListener(chatCoordinator);
        NearbyConnectionsManager.setChatFileCallback(chatCoordinator);

        setupBottomNavigation();
        requestRequiredPermissions();

        if (savedInstanceState == null) {
            showDriveMode();
        }
    }

    @Override
    protected void onResume() {
        super.onResume();
        FileTransferService.setCallback(fileTransferCoordinator);
    }

    @Override
    protected void onPause() {
        super.onPause();
        FileTransferService.setCallback(null);
    }

    @Override
    protected void onDestroy() {
        NearbyConnectionsManager.setChatFileCallback(null);
        nearbyManager.stopAll();
        super.onDestroy();
    }

    // ── Bottom Navigation ────────────────────────────────────────────────────

    private void setupBottomNavigation() {
        BottomNavigationView bottomNav = findViewById(R.id.bottom_nav);
        bottomNav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            if (id == R.id.nav_drive) { showDriveMode(); return true; }
            if (id == R.id.nav_chat)  { showChatMode();  return true; }
            if (id == R.id.nav_map)   { openMap();       return true; }
            return false;
        });
    }

    private void showDriveMode() {
        nearbyManager.setChatMode(false);
        nearbyManager.stopDiscovery();
        fileTransferCoordinator.clearPendingFile();
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        replaceFragment(new FileListFragment(), FileListFragment.TAG);
    }

    private void showChatMode() {
        nearbyManager.stopDiscovery();
        nearbyManager.stopAdvertising();
        chatCoordinator.resetForTabSwitch();

        ChatDeviceListFragment chatList = new ChatDeviceListFragment();
        chatCoordinator.setChatDeviceListFragment(chatList);
        replaceFragment(chatList, ChatDeviceListFragment.TAG);

        nearbyManager.setChatMode(true);
        nearbyManager.startAdvertising();
        nearbyManager.startDiscovery();
    }

    private void openMap() {
        nearbyManager.setChatMode(false);
        nearbyManager.stopDiscovery();
        startActivity(new Intent(this, MapActivity.class));
    }

    private void replaceFragment(Fragment fragment, String tag) {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, fragment, tag)
                .commit();
    }

    // ── Fragment-facing shims ────────────────────────────────────────────────

    /** Called by SendModeFragment after the user picks a file for nearby send. */
    public void onFilePicked(Uri uri, FileMetadata metadata) {
        fileTransferCoordinator.setPendingFile(uri, metadata);

        DeviceDiscoveryFragment discovery = new DeviceDiscoveryFragment();
        fileTransferCoordinator.setDiscoveryFragment(discovery);
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, discovery, DeviceDiscoveryFragment.TAG)
                .addToBackStack("discovery")
                .commit();
        nearbyManager.startDiscovery();
    }

    /** Called by DeviceDiscoveryFragment when the user taps a device row. */
    public void onDeviceSelectedForSend(DeviceInfo device) {
        fileTransferCoordinator.onDeviceSelectedForSend(device);
    }

    /** Called by ChatDeviceListFragment when a device is tapped. */
    public void onChatDeviceSelected(DeviceInfo device) {
        chatCoordinator.rememberDeviceName(device.getEndpointId(), device.getDeviceName());
        if (!device.isConnected()) {
            nearbyManager.connectToDevice(device.getEndpointId());
        }
        chatCoordinator.openChatRoom(device, false);
    }

    /** Called by ChatDeviceListFragment when a past conversation is tapped (offline). */
    public void onHistoryDeviceSelected(String peerDeviceName) {
        chatCoordinator.openChatRoom(new DeviceInfo(peerDeviceName, peerDeviceName), true);
    }

    /** Called by ChatRoomFragment to send a text message. */
    public void onChatSendText(String endpointId, String text) {
        chatCoordinator.sendText(endpointId, text);
    }

    /** Called by ChatRoomFragment to send a file or voice message. */
    public void onChatSendFile(String endpointId, Uri fileUri, FileMetadata metadata) {
        chatCoordinator.sendFile(endpointId, fileUri, metadata);
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
                        Toast.makeText(this, R.string.location_unavailable,
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    chatCoordinator.sendLocation(endpointId,
                            location.getLatitude(), location.getLongitude());
                })
                .addOnFailureListener(e -> {
                    Log.w(TAG, "Location fetch failed", e);
                    Toast.makeText(this, R.string.location_unavailable,
                            Toast.LENGTH_SHORT).show();
                });
    }

    // ── Public accessors used by fragments ───────────────────────────────────

    public String getLocalDeviceName() { return localDeviceName; }

    public List<String> getPastConversationPeers() {
        return chatCoordinator.getPastConversationPeers();
    }

    // ── Device name bootstrap ────────────────────────────────────────────────

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

    // ── Runtime permissions ──────────────────────────────────────────────────

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
            if (sdk <= Build.VERSION_CODES.P) {
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

    // ── Shared Drive (Firebase) flow ─────────────────────────────────────────

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
                                    "Uploaded: " + file.getFileName(),
                                    Toast.LENGTH_SHORT).show();
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
                                    "Upload failed: " + e.getMessage(),
                                    Toast.LENGTH_SHORT).show();
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
            Toast.makeText(this, "No app found to open this file type",
                    Toast.LENGTH_SHORT).show();
        }
    }

    public void downloadFile(SharedFile file) {
        try {
            android.app.DownloadManager.Request request =
                    new android.app.DownloadManager.Request(Uri.parse(file.getDownloadUrl()))
                            .setTitle(file.getFileName())
                            .setDescription("Downloading from DroidDrive")
                            .setNotificationVisibility(
                                    android.app.DownloadManager.Request
                                            .VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                            .setDestinationInExternalPublicDir(
                                    android.os.Environment.DIRECTORY_DOWNLOADS,
                                    file.getFileName())
                            .setMimeType(file.getMimeType() != null ? file.getMimeType() : "*/*");
            android.app.DownloadManager dm =
                    (android.app.DownloadManager) getSystemService(
                            android.content.Context.DOWNLOAD_SERVICE);
            if (dm != null) {
                dm.enqueue(request);
                Toast.makeText(this, "Downloading: " + file.getFileName(),
                        Toast.LENGTH_SHORT).show();
            }
        } catch (Exception e) {
            Toast.makeText(this, "Download failed: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
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

    // ── URI resolution helpers ───────────────────────────────────────────────

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
