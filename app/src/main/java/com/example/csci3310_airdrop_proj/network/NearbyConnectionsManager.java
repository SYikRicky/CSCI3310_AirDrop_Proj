package com.example.csci3310_airdrop_proj.network;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import androidx.core.content.ContextCompat;

import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.TransferProgress;
import com.example.csci3310_airdrop_proj.service.FileTransferService;
import com.google.android.gms.nearby.Nearby;
import com.google.android.gms.nearby.connection.AdvertisingOptions;
import com.google.android.gms.nearby.connection.ConnectionInfo;
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback;
import com.google.android.gms.nearby.connection.ConnectionResolution;
import com.google.android.gms.nearby.connection.ConnectionsClient;
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo;
import com.google.android.gms.nearby.connection.DiscoveryOptions;
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback;
import com.google.android.gms.nearby.connection.Payload;
import com.google.android.gms.nearby.connection.PayloadCallback;
import com.google.android.gms.nearby.connection.PayloadTransferUpdate;
import com.google.android.gms.nearby.connection.Strategy;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Manages all Google Nearby Connections API operations:
 * - Advertising (making this device discoverable)
 * - Discovery (finding nearby devices)
 * - Connecting to a discovered device
 * - Sending a file (metadata bytes + file payload)
 * - Receiving a file (storing temp file → starting FileTransferService)
 *
 * Strategy: P2P_CLUSTER — supports many-to-many connections.
 * Both devices advertise AND discover simultaneously in Receive mode.
 */
public class NearbyConnectionsManager {

    private static final String TAG = "NearbyMgr";
    private static final String SERVICE_ID = "com.example.dropdroid";

    private final Context appContext;
    private final ConnectionsClient connectionsClient;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // Listener callbacks (set by MainActivity)
    private TransferEventBus.ConnectionListener connectionListener;
    private TransferEventBus.TransferListener transferListener;
    private TransferEventBus.ChatListener chatListener;

    // ── Local device identity ─────────────────────────────────────────────────
    private String localDeviceName = Build.MODEL;

    // ── Chat-mode invitation tracking ─────────────────────────────────────────
    /** True when the Chat tab is active — incoming connections show invitation dialog. */
    private boolean chatModeActive = false;
    /** Endpoint IDs for connections that WE initiated (auto-accept on both sides). */
    private final Set<String> outgoingConnectionRequests = new HashSet<>();

    // ── Discovery state ──────────────────────────────────────────────────────
    private final Map<String, DeviceInfo> discoveredDevices = new HashMap<>();
    private final Map<String, DeviceInfo> connectedDevices  = new HashMap<>();
    private boolean isAdvertising  = false;
    private boolean isDiscovering  = false;

    // ── Send-side tracking: payloadId → metadata/PFD ────────────────────────
    private final Map<Long, FileMetadata>       sendingFiles = new HashMap<>();
    private final Map<Long, ParcelFileDescriptor> openPfds   = new HashMap<>();

    // ── Receive-side tracking ────────────────────────────────────────────────
    // endpointId → pending metadata (received as bytes payload before file payload arrives)
    private final Map<String, FileMetadata> pendingReceiveMeta     = new HashMap<>();
    // payloadId → Payload object (held until STATUS_SUCCESS)
    private final Map<Long, Payload>        pendingReceivePayloads = new HashMap<>();

    // ────────────────────────────────────────────────────────────────────────

    public NearbyConnectionsManager(Context context) {
        this.appContext = context.getApplicationContext();
        this.connectionsClient = Nearby.getConnectionsClient(context);
    }

    public void setConnectionListener(TransferEventBus.ConnectionListener l) { connectionListener = l; }
    public void setTransferListener(TransferEventBus.TransferListener l)     { transferListener = l; }
    public void setChatListener(TransferEventBus.ChatListener l)             { chatListener = l; }

    /** Set the name this device advertises to others (call before startAdvertising). */
    public void setLocalDeviceName(String name) { localDeviceName = name; }

    /** Enable/disable invitation-dialog mode for the Chat tab. */
    public void setChatMode(boolean active) { chatModeActive = active; }

    // ── Public API ───────────────────────────────────────────────────────────

    /** Start advertising so nearby devices can discover us. */
    public void startAdvertising() {
        if (isAdvertising) return;
        String name = localDeviceName;
        AdvertisingOptions opts = new AdvertisingOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startAdvertising(name, SERVICE_ID, connectionLifecycleCallback, opts)
                .addOnSuccessListener(v -> {
                    isAdvertising = true;
                    Log.d(TAG, "Advertising as: " + name);
                })
                .addOnFailureListener(e -> Log.e(TAG, "Advertising failed: " + e.getMessage()));
    }

    /** Start discovering nearby devices that are advertising. */
    public void startDiscovery() {
        if (isDiscovering) return;
        DiscoveryOptions opts = new DiscoveryOptions.Builder()
                .setStrategy(Strategy.P2P_CLUSTER).build();
        connectionsClient.startDiscovery(SERVICE_ID, endpointDiscoveryCallback, opts)
                .addOnSuccessListener(v -> {
                    isDiscovering = true;
                    Log.d(TAG, "Discovery started");
                })
                .addOnFailureListener(e -> Log.e(TAG, "Discovery failed: " + e.getMessage()));
    }

    public void stopAdvertising() {
        if (!isAdvertising) return;
        connectionsClient.stopAdvertising();
        isAdvertising = false;
        Log.d(TAG, "Stopped advertising");
    }

    public void stopDiscovery() {
        if (!isDiscovering) return;
        connectionsClient.stopDiscovery();
        isDiscovering = false;
        discoveredDevices.clear();
        Log.d(TAG, "Stopped discovery");
    }

    /** Stop all connections, advertising, and discovery. Call in onDestroy. */
    public void stopAll() {
        connectionsClient.stopAllEndpoints();
        connectionsClient.stopAdvertising();
        connectionsClient.stopDiscovery();
        isAdvertising = false;
        isDiscovering = false;
        discoveredDevices.clear();
        connectedDevices.clear();
        for (ParcelFileDescriptor pfd : openPfds.values()) {
            try { pfd.close(); } catch (IOException ignored) {}
        }
        openPfds.clear();
        sendingFiles.clear();
    }

    /**
     * Request a P2P connection to a discovered endpoint.
     * On success, {@link TransferEventBus.ConnectionListener#onConnectionEstablished} fires.
     */
    public void connectToDevice(String endpointId) {
        Log.d(TAG, "Requesting connection to: " + endpointId);
        outgoingConnectionRequests.add(endpointId);
        connectionsClient.requestConnection(localDeviceName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Connect request failed: " + e.getMessage());
                    notifyMain(() -> {
                        if (connectionListener != null)
                            connectionListener.onConnectionFailed(e.getMessage());
                    });
                });
    }

    /**
     * Send a file to a connected endpoint.
     * Sends file metadata as a bytes payload first, then the file itself as a file payload.
     * Both payloads arrive in-order on the receiver side.
     *
     * @param endpointId target endpoint (must be connected)
     * @param fileUri    content URI of the file to send
     * @param metadata   pre-extracted file metadata
     */
    public void sendFile(String endpointId, Uri fileUri, FileMetadata metadata) {
        // Step 1: send metadata so receiver knows file name/type before data arrives
        Payload metaPayload = Payload.fromBytes(metadata.toBytes());
        connectionsClient.sendPayload(endpointId, metaPayload)
                .addOnFailureListener(e -> Log.w(TAG, "Meta payload failed: " + e.getMessage()));
        Log.d(TAG, "Sent metadata: " + metadata);

        // Step 2: send the file
        try {
            ParcelFileDescriptor pfd = appContext.getContentResolver().openFileDescriptor(fileUri, "r");
            if (pfd == null) {
                notifyMain(() -> {
                    if (transferListener != null)
                        transferListener.onTransferFailed(metadata.getFileName(), "Cannot open file");
                });
                return;
            }
            Payload filePayload = Payload.fromFile(pfd);
            long payloadId = filePayload.getId();

            // Keep PFD open until Nearby signals success/failure — closing it early cancels the transfer
            openPfds.put(payloadId, pfd);
            sendingFiles.put(payloadId, metadata);

            connectionsClient.sendPayload(endpointId, filePayload)
                    .addOnFailureListener(e -> {
                        closePfd(payloadId);
                        sendingFiles.remove(payloadId);
                        notifyMain(() -> {
                            if (transferListener != null)
                                transferListener.onTransferFailed(metadata.getFileName(), e.getMessage());
                        });
                    });
            Log.d(TAG, "Sending file payload id=" + payloadId + " size=" + metadata.getFileSize());
        } catch (IOException e) {
            Log.e(TAG, "Failed to open URI for sending", e);
            notifyMain(() -> {
                if (transferListener != null)
                    transferListener.onTransferFailed(metadata.getFileName(), e.getMessage());
            });
        }
    }

    /**
     * Send a text chat message to a connected endpoint.
     * Wire format: "CHAT|senderName|timestamp|text"
     */
    public void sendChatMessage(String endpointId, String senderName, String text) {
        long timestamp = System.currentTimeMillis();
        String wire = "CHAT|" + senderName + "|" + timestamp + "|" + text;
        Payload payload = Payload.fromBytes(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        connectionsClient.sendPayload(endpointId, payload)
                .addOnFailureListener(e -> Log.w(TAG, "Chat message failed: " + e.getMessage()));
    }

    /**
     * Send a file within a chat session. Same as sendFile but keeps the chat connection alive.
     */
    public void sendFileInChat(String endpointId, Uri fileUri, FileMetadata metadata) {
        // Send metadata (already prefixed with FILE| by metadata.toBytes())
        Payload metaPayload = Payload.fromBytes(metadata.toBytes());
        connectionsClient.sendPayload(endpointId, metaPayload)
                .addOnFailureListener(e -> Log.w(TAG, "Chat file meta failed: " + e.getMessage()));

        try {
            ParcelFileDescriptor pfd = appContext.getContentResolver().openFileDescriptor(fileUri, "r");
            if (pfd == null) {
                notifyMain(() -> {
                    if (transferListener != null)
                        transferListener.onTransferFailed(metadata.getFileName(), "Cannot open file");
                });
                return;
            }
            Payload filePayload = Payload.fromFile(pfd);
            long payloadId = filePayload.getId();
            openPfds.put(payloadId, pfd);
            sendingFiles.put(payloadId, metadata);

            connectionsClient.sendPayload(endpointId, filePayload)
                    .addOnFailureListener(e -> {
                        closePfd(payloadId);
                        sendingFiles.remove(payloadId);
                        notifyMain(() -> {
                            if (transferListener != null)
                                transferListener.onTransferFailed(metadata.getFileName(), e.getMessage());
                        });
                    });
            Log.d(TAG, "Sending chat file payload id=" + payloadId);
        } catch (IOException e) {
            Log.e(TAG, "Failed to open URI for chat file send", e);
            notifyMain(() -> {
                if (transferListener != null)
                    transferListener.onTransferFailed(metadata.getFileName(), e.getMessage());
            });
        }
    }

    /** Get a connected device by endpoint ID. */
    public DeviceInfo getConnectedDevice(String endpointId) {
        return connectedDevices.get(endpointId);
    }

    /** Accept a pending chat invitation (call from the Yes button in the invitation dialog). */
    public void acceptChatInvitation(String endpointId) {
        connectionsClient.acceptConnection(endpointId, payloadCallback)
                .addOnFailureListener(e -> Log.e(TAG, "Accept invitation failed: " + e.getMessage()));
    }

    /** Reject a pending chat invitation (call from the No button). */
    public void rejectChatInvitation(String endpointId) {
        connectionsClient.rejectConnection(endpointId)
                .addOnFailureListener(e -> Log.e(TAG, "Reject invitation failed: " + e.getMessage()));
    }

    // ── Discovery callbacks ──────────────────────────────────────────────────

    private final EndpointDiscoveryCallback endpointDiscoveryCallback = new EndpointDiscoveryCallback() {
        @Override
        public void onEndpointFound(String endpointId, DiscoveredEndpointInfo info) {
            Log.d(TAG, "Endpoint found: " + endpointId + " (" + info.getEndpointName() + ")");
            if (info.getEndpointName().equals(localDeviceName)) {
                Log.d(TAG, "Ignoring self-discovery: " + endpointId);
                return;
            }
            discoveredDevices.put(endpointId, new DeviceInfo(endpointId, info.getEndpointName()));
            notifyDevicesUpdated();
        }

        @Override
        public void onEndpointLost(String endpointId) {
            Log.d(TAG, "Endpoint lost: " + endpointId);
            discoveredDevices.remove(endpointId);
            notifyDevicesUpdated();
        }
    };

    private void notifyDevicesUpdated() {
        List<DeviceInfo> snapshot = new ArrayList<>(discoveredDevices.values());
        notifyMain(() -> {
            if (connectionListener != null) connectionListener.onDevicesUpdated(snapshot);
        });
    }

    // ── Connection lifecycle callbacks ───────────────────────────────────────

    private final ConnectionLifecycleCallback connectionLifecycleCallback = new ConnectionLifecycleCallback() {
        @Override
        public void onConnectionInitiated(String endpointId, ConnectionInfo info) {
            Log.d(TAG, "Connection initiated by: " + endpointId + " (" + info.getEndpointName() + ")");
            boolean weInitiated = outgoingConnectionRequests.remove(endpointId);
            if (chatModeActive && !weInitiated) {
                // Incoming chat invitation — let the UI prompt the user
                String remoteName = info.getEndpointName();
                notifyMain(() -> {
                    if (connectionListener != null)
                        connectionListener.onChatInvitationReceived(endpointId, remoteName);
                });
            } else {
                // File transfer mode or we initiated — auto-accept
                connectionsClient.acceptConnection(endpointId, payloadCallback);
            }
        }

        @Override
        public void onConnectionResult(String endpointId, ConnectionResolution result) {
            if (result.getStatus().isSuccess()) {
                DeviceInfo device = discoveredDevices.containsKey(endpointId)
                        ? discoveredDevices.get(endpointId)
                        : new DeviceInfo(endpointId, "Unknown Device");
                device.setConnected(true);
                connectedDevices.put(endpointId, device);
                Log.d(TAG, "Connected: " + endpointId);
                DeviceInfo finalDevice = device;
                notifyMain(() -> {
                    if (connectionListener != null) connectionListener.onConnectionEstablished(finalDevice);
                });
            } else {
                String msg = result.getStatus().getStatusMessage();
                Log.e(TAG, "Connection failed: " + msg);
                notifyMain(() -> {
                    if (connectionListener != null) connectionListener.onConnectionFailed(msg);
                });
            }
        }

        @Override
        public void onDisconnected(String endpointId) {
            Log.d(TAG, "Disconnected: " + endpointId);
            DeviceInfo device = connectedDevices.remove(endpointId);
            if (device != null) device.setConnected(false);
            notifyMain(() -> {
                if (connectionListener != null) connectionListener.onDisconnected(endpointId);
            });
        }
    };

    // ── Payload callbacks ────────────────────────────────────────────────────

    private final PayloadCallback payloadCallback = new PayloadCallback() {
        @Override
        public void onPayloadReceived(String endpointId, Payload payload) {
            if (payload.getType() == Payload.Type.BYTES) {
                byte[] bytes = payload.asBytes();
                if (bytes != null) {
                    String raw = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);

                    if (raw.startsWith("CHAT|")) {
                        // Chat text message: "CHAT|senderName|timestamp|text"
                        String[] parts = raw.split("\\|", 4);
                        if (parts.length == 4) {
                            String senderName = parts[1];
                            long parsedTime;
                            try { parsedTime = Long.parseLong(parts[2]); }
                            catch (NumberFormatException e) { parsedTime = System.currentTimeMillis(); }
                            final long timestamp = parsedTime;
                            String text = parts[3];
                            notifyMain(() -> {
                                if (chatListener != null)
                                    chatListener.onChatMessageReceived(endpointId, senderName, text, timestamp);
                            });
                        }
                    } else {
                        // File metadata: "FILE|fileName|mimeType|fileSize" or legacy "fileName|mimeType|fileSize"
                        FileMetadata meta = FileMetadata.fromBytes(bytes);
                        pendingReceiveMeta.put(endpointId, meta);
                        Log.d(TAG, "Received metadata: " + meta);

                        DeviceInfo sender = connectedDevices.get(endpointId);
                        String senderName = sender != null ? sender.getDeviceName() : endpointId;
                        notifyMain(() -> {
                            if (transferListener != null)
                                transferListener.onIncomingFile(meta, senderName);
                        });
                    }
                }

            } else if (payload.getType() == Payload.Type.FILE) {
                // Hold the Payload reference — asJavaFile() is valid only after SUCCESS
                pendingReceivePayloads.put(payload.getId(), payload);
                Log.d(TAG, "Receiving file payload id=" + payload.getId());
            }
        }

        @Override
        public void onPayloadTransferUpdate(String endpointId, PayloadTransferUpdate update) {
            long payloadId = update.getPayloadId();
            int  status    = update.getStatus();

            // ── Sending side ─────────────────────────────────────────────────
            if (sendingFiles.containsKey(payloadId)) {
                FileMetadata meta = sendingFiles.get(payloadId);

                if (status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                    TransferProgress p = buildProgress(meta.getFileName(), meta.getFileSize(), true);
                    p.setBytesTransferred(update.getBytesTransferred());
                    p.setStatus(TransferProgress.Status.IN_PROGRESS);
                    notifyMain(() -> {
                        if (transferListener != null) transferListener.onTransferProgressUpdated(p);
                    });

                } else if (status == PayloadTransferUpdate.Status.SUCCESS) {
                    closePfd(payloadId);
                    sendingFiles.remove(payloadId);
                    TransferProgress p = buildProgress(meta.getFileName(), meta.getFileSize(), true);
                    p.setBytesTransferred(meta.getFileSize());
                    p.setStatus(TransferProgress.Status.DONE);
                    notifyMain(() -> {
                        if (transferListener != null) transferListener.onTransferCompleted(p);
                    });

                } else { // FAILURE or CANCELED
                    closePfd(payloadId);
                    sendingFiles.remove(payloadId);
                    notifyMain(() -> {
                        if (transferListener != null)
                            transferListener.onTransferFailed(meta.getFileName(), "Send failed");
                    });
                }
                return;
            }

            // ── Receiving side ───────────────────────────────────────────────
            if (pendingReceivePayloads.containsKey(payloadId)) {
                FileMetadata meta     = pendingReceiveMeta.get(endpointId);
                String       fileName = meta != null ? meta.getFileName() : "file";
                long         fileSize = meta != null ? meta.getFileSize() : 0;

                if (status == PayloadTransferUpdate.Status.IN_PROGRESS) {
                    TransferProgress p = buildProgress(fileName, fileSize, false);
                    p.setBytesTransferred(update.getBytesTransferred());
                    p.setStatus(TransferProgress.Status.IN_PROGRESS);
                    notifyMain(() -> {
                        if (transferListener != null) transferListener.onTransferProgressUpdated(p);
                    });

                } else if (status == PayloadTransferUpdate.Status.SUCCESS) {
                    Payload received = pendingReceivePayloads.remove(payloadId);
                    pendingReceiveMeta.remove(endpointId);

                    // Get the temp file Nearby wrote for us and hand off to FileTransferService
                    java.io.File tempFile = (received.asFile() != null)
                            ? received.asFile().asJavaFile() : null;
                    if (tempFile != null && meta != null) {
                        Intent intent = new Intent(appContext, FileTransferService.class);
                        intent.putExtra(FileTransferService.EXTRA_FILE_METADATA, meta);
                        intent.putExtra(FileTransferService.EXTRA_TEMP_FILE_PATH, tempFile.getAbsolutePath());
                        ContextCompat.startForegroundService(appContext, intent);
                        Log.d(TAG, "Started FileTransferService for: " + meta.getFileName());
                    }

                    TransferProgress p = buildProgress(fileName, fileSize, false);
                    p.setBytesTransferred(fileSize);
                    p.setStatus(TransferProgress.Status.DONE);
                    notifyMain(() -> {
                        if (transferListener != null) transferListener.onTransferCompleted(p);
                    });

                } else { // FAILURE or CANCELED
                    pendingReceivePayloads.remove(payloadId);
                    notifyMain(() -> {
                        if (transferListener != null)
                            transferListener.onTransferFailed(fileName, "Receive failed");
                    });
                }
            }
        }
    };

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void closePfd(long payloadId) {
        ParcelFileDescriptor pfd = openPfds.remove(payloadId);
        if (pfd != null) {
            try { pfd.close(); } catch (IOException ignored) {}
        }
    }

    private TransferProgress buildProgress(String fileName, long totalBytes, boolean sending) {
        return new TransferProgress(fileName, totalBytes, sending);
    }

    /** Post a runnable to the main (UI) thread. Nearby callbacks already run on main, but
     *  explicit posting guards against future refactoring that moves callbacks off-thread. */
    private void notifyMain(Runnable r) {
        if (Looper.myLooper() == Looper.getMainLooper()) {
            r.run();
        } else {
            mainHandler.post(r);
        }
    }
}
