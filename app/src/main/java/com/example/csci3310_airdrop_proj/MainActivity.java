package com.example.csci3310_airdrop_proj;

import android.Manifest;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;
import androidx.fragment.app.FragmentManager;

import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.TransferProgress;
import com.example.csci3310_airdrop_proj.network.NearbyConnectionsManager;
import com.example.csci3310_airdrop_proj.network.TransferEventBus;
import com.example.csci3310_airdrop_proj.ui.fragment.DeviceDiscoveryFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.ReceiveModeFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.SendModeFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.WalkieTalkieFragment;
import com.google.android.material.bottomnavigation.BottomNavigationView;

import java.util.ArrayList;
import java.util.List;

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
                   TransferEventBus.TransferListener {

    // ── Network ──────────────────────────────────────────────────────────────
    private NearbyConnectionsManager nearbyManager;

    // ── Send-flow state ───────────────────────────────────────────────────────
    /** URI of the file the user picked in SendModeFragment — held until a device is chosen. */
    private Uri          pendingFileUri;
    private FileMetadata pendingFileMeta;

    // ── Fragment references ──────────────────────────────────────────────────
    private DeviceDiscoveryFragment discoveryFragment;
    private ReceiveModeFragment     receiveModeFragment;

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

        // Initialise NearbyConnectionsManager and register this Activity as listener
        nearbyManager = new NearbyConnectionsManager(this);
        nearbyManager.setConnectionListener(this);
        nearbyManager.setTransferListener(this);

        setupBottomNavigation();
        requestRequiredPermissions();

        // Show Send mode by default on first launch
        if (savedInstanceState == null) {
            showSendMode();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        // Clean up all Nearby Connections state to avoid leaking resources
        nearbyManager.stopAll();
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
            } else if (id == R.id.nav_walkie_talkie) {
                showWalkieTalkie();
                return true;
            }
            return false;
        });
    }

    private void showSendMode() {
        // Stop any active discovery when switching away from send mode
        nearbyManager.stopDiscovery();
        pendingFileUri  = null;
        pendingFileMeta = null;
        // Clear back stack and show fresh SendModeFragment
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        replaceFragment(new SendModeFragment(), "send");
    }

    private void showReceiveMode() {
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        receiveModeFragment = new ReceiveModeFragment();
        replaceFragment(receiveModeFragment, "receive");
    }

    private void showWalkieTalkie() {
        nearbyManager.stopDiscovery();
        nearbyManager.stopAdvertising();
        pendingFileUri  = null;
        pendingFileMeta = null;
        getSupportFragmentManager().popBackStack(null, FragmentManager.POP_BACK_STACK_INCLUSIVE);
        replaceFragment(new WalkieTalkieFragment(), WalkieTalkieFragment.TAG);
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
        // Callbacks from NearbyConnectionsManager already arrive on main thread
        if (discoveryFragment != null && discoveryFragment.isAdded()) {
            discoveryFragment.updateDeviceList(devices);
        }
    }

    @Override
    public void onConnectionEstablished(DeviceInfo device) {
        Toast.makeText(this, "Connected to " + device.getDeviceName(), Toast.LENGTH_SHORT).show();
        // Connection is ready — send the pending file
        if (pendingFileUri != null && pendingFileMeta != null) {
            nearbyManager.sendFile(device.getEndpointId(), pendingFileUri, pendingFileMeta);
        }
    }

    @Override
    public void onConnectionFailed(String reason) {
        Toast.makeText(this, "Connection failed: " + reason, Toast.LENGTH_SHORT).show();
        if (discoveryFragment != null && discoveryFragment.isAdded()) {
            discoveryFragment.showTransferFailed(reason);
        }
    }

    @Override
    public void onDisconnected(String endpointId) {
        Toast.makeText(this, "Disconnected", Toast.LENGTH_SHORT).show();
    }

    // ── TransferEventBus.TransferListener ────────────────────────────────────

    @Override
    public void onIncomingFile(FileMetadata meta, String fromDeviceName) {
        String msg = "Receiving \"" + meta.getFileName() + "\" from " + fromDeviceName;
        Toast.makeText(this, msg, Toast.LENGTH_SHORT).show();
        if (receiveModeFragment != null && receiveModeFragment.isAdded()) {
            receiveModeFragment.setStatusText("Receiving: " + meta.getFileName());
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
            }
            // Return to Send mode after a brief moment
            getSupportFragmentManager().popBackStack();
            pendingFileUri  = null;
            pendingFileMeta = null;
            nearbyManager.stopDiscovery();
        } else {
            // Receiver side: FileTransferService will show a notification when saved
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
}
