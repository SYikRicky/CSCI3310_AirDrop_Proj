package com.example.csci3310_airdrop_proj.network;

import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.TransferProgress;
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

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Manages all Google Nearby Connections API operations:
 * - Advertising (making this device discoverable)
 * - Discovery (finding nearby devices)
 * - Connecting to a discovered device
 * - Sending a file (metadata bytes + file payload)
 * - Receiving a file (copying Nearby temp file → app cache → FileProvider URI)
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

    // ── Send-side tracking: payloadId → metadata/PFD/temp-files ─────────────
    private final Map<Long, FileMetadata>        sendingFiles   = new HashMap<>();
    private final Map<Long, ParcelFileDescriptor> openPfds      = new HashMap<>();
    private final Map<Long, File>                 sendTempFiles  = new HashMap<>();

    // ── Receive-side tracking ────────────────────────────────────────────────
    // endpointId → pending metadata (received as bytes payload before file payload arrives)
    private final Map<String, FileMetadata> pendingReceiveMeta     = new HashMap<>();
    // payloadId → Payload object (held until STATUS_SUCCESS)
    private final Map<Long, Payload>        pendingReceivePayloads = new HashMap<>();

    // ── Chat file receive callback ────────────────────────────────────────────
    /**
     * Called on the main thread after a received chat file has been saved
     * to the app's cache directory and a FileProvider URI created.
     */
    public interface ChatFileReceivedCallback {
        void onChatFileReceived(Uri uri, FileMetadata meta);
    }
    private static ChatFileReceivedCallback sChatFileCallback;
    public static void setChatFileCallback(ChatFileReceivedCallback cb) { sChatFileCallback = cb; }

    /** Background executor for copying received files to cache. */
    private final ExecutorService fileIoExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "nearby-file-io");
        t.setDaemon(true);
        return t;
    });

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
     * If we are already connected to this endpoint, fires
     * {@link TransferEventBus.ConnectionListener#onConnectionEstablished} immediately
     * (avoids STATUS_ALREADY_CONNECTED_TO_ENDPOINT / error 8003).
     */
    public void connectToDevice(String endpointId) {
        // Guard: already connected — reuse the live connection instead of requesting again.
        if (connectedDevices.containsKey(endpointId)) {
            Log.d(TAG, "Already connected to " + endpointId + " — reusing");
            DeviceInfo existing = connectedDevices.get(endpointId);
            notifyMain(() -> {
                if (connectionListener != null) connectionListener.onConnectionEstablished(existing);
            });
            return;
        }

        Log.d(TAG, "Requesting connection to: " + endpointId);
        outgoingConnectionRequests.add(endpointId);
        connectionsClient.requestConnection(localDeviceName, endpointId, connectionLifecycleCallback)
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Connect request failed: " + e.getMessage());
                    // Fallback: if Nearby says already connected (race condition), reuse it.
                    String msg = e.getMessage() != null ? e.getMessage() : "";
                    if (msg.contains("STATUS_ALREADY_CONNECTED_TO_ENDPOINT")
                            || msg.contains("8003")) {
                        DeviceInfo existing = connectedDevices.get(endpointId);
                        if (existing != null) {
                            Log.d(TAG, "Race: already connected to " + endpointId + " — reusing");
                            notifyMain(() -> {
                                if (connectionListener != null)
                                    connectionListener.onConnectionEstablished(existing);
                            });
                            return;
                        }
                    }
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
     * Send a GPS location to a connected endpoint.
     * Wire format: "LOCATION|senderName|timestamp|lat|lng"
     */
    public void sendLocationMessage(String endpointId, String senderName,
                                    double latitude, double longitude) {
        long timestamp = System.currentTimeMillis();
        String wire = "LOCATION|" + senderName + "|" + timestamp + "|"
                + latitude + "|" + longitude;
        Payload payload = Payload.fromBytes(wire.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        connectionsClient.sendPayload(endpointId, payload)
                .addOnFailureListener(e -> Log.w(TAG, "Location message failed: " + e.getMessage()));
    }

    /**
     * Send a file within a chat session.
     *
     * FILE payloads ({@code Payload.fromFile}) are broken on API 36 / play-services-nearby 18.7.0
     * (receiver's {@code asJavaFile()} returns null). Instead we read the entire file into memory
     * and send it as a single BYTES payload with wire format:
     * <pre>
     *   "CHATFILE|senderName|fileName|mimeType|fileSize|"  +  raw file bytes
     * </pre>
     * BYTES payloads are proven to work (all CHAT| and LOCATION| messages use them).
     * Max size per BYTES payload is ~32 MB; for chat images/voice this is plenty.
     */
    public void sendFileInChat(String endpointId, Uri fileUri, FileMetadata metadata) {
        fileIoExecutor.execute(() -> {
            try {
                // Read file content into byte array
                byte[] fileBytes;
                try (InputStream in = appContext.getContentResolver().openInputStream(fileUri)) {
                    if (in == null) throw new IOException("openInputStream returned null");
                    java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
                    byte[] buf = new byte[64 * 1024];
                    int read;
                    while ((read = in.read(buf)) != -1) baos.write(buf, 0, read);
                    fileBytes = baos.toByteArray();
                }

                // Build header: "CHATFILE|senderName|fileName|mimeType|fileSize|"
                String header = "CHATFILE|" + localDeviceName + "|"
                        + metadata.getFileName() + "|"
                        + metadata.getMimeType() + "|"
                        + fileBytes.length + "|";
                byte[] headerBytes = header.getBytes(java.nio.charset.StandardCharsets.UTF_8);

                // Combine header + raw file bytes into a single payload
                byte[] combined = new byte[headerBytes.length + fileBytes.length];
                System.arraycopy(headerBytes, 0, combined, 0, headerBytes.length);
                System.arraycopy(fileBytes, 0, combined, headerBytes.length, fileBytes.length);

                Payload payload = Payload.fromBytes(combined);
                connectionsClient.sendPayload(endpointId, payload)
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "sendFileInChat payload failed: " + e.getMessage());
                            notifyMain(() -> {
                                if (transferListener != null)
                                    transferListener.onTransferFailed(
                                            metadata.getFileName(), e.getMessage());
                            });
                        });
                Log.d(TAG, "Sent chat file as BYTES: " + metadata.getFileName()
                        + " (" + fileBytes.length + " bytes, header=" + headerBytes.length + ")");

            } catch (Exception e) {
                Log.e(TAG, "sendFileInChat failed for " + metadata.getFileName(), e);
                notifyMain(() -> {
                    if (transferListener != null)
                        transferListener.onTransferFailed(metadata.getFileName(), e.getMessage());
                });
            }
        });
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
                DeviceInfo device = discoveredDevices.getOrDefault(
                        endpointId, new DeviceInfo(endpointId, "Unknown Device"));
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
                if (bytes == null) return;

                // ── CHATFILE| — inline file sent as bytes (images, voice, etc.) ──
                // Wire format: "CHATFILE|senderName|fileName|mimeType|fileSize|" + raw bytes
                // We check the first 9 bytes to avoid converting the entire (possibly large)
                // byte array to a String.
                if (bytes.length > 9 && startsWith(bytes, "CHATFILE|")) {
                    handleChatFileBytes(endpointId, bytes);
                    return;
                }

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
                } else if (raw.startsWith("LOCATION|")) {
                    // GPS location: "LOCATION|senderName|timestamp|lat|lng"
                    String[] parts = raw.split("\\|", 5);
                    if (parts.length == 5) {
                        String senderName = parts[1];
                        long parsedTime;
                        try { parsedTime = Long.parseLong(parts[2]); }
                        catch (NumberFormatException e) { parsedTime = System.currentTimeMillis(); }
                        final long timestamp = parsedTime;
                        try {
                            final double lat = Double.parseDouble(parts[3]);
                            final double lng = Double.parseDouble(parts[4]);
                            notifyMain(() -> {
                                if (chatListener != null)
                                    chatListener.onLocationMessageReceived(
                                            endpointId, senderName, lat, lng, timestamp);
                            });
                        } catch (NumberFormatException e) {
                            Log.w(TAG, "Invalid location payload: " + raw);
                        }
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
                    File tmp = sendTempFiles.remove(payloadId);
                    if (tmp != null) tmp.delete();
                    TransferProgress p = buildProgress(meta.getFileName(), meta.getFileSize(), true);
                    p.setBytesTransferred(meta.getFileSize());
                    p.setStatus(TransferProgress.Status.DONE);
                    notifyMain(() -> {
                        if (transferListener != null) transferListener.onTransferCompleted(p);
                    });

                } else { // FAILURE or CANCELED
                    closePfd(payloadId);
                    sendingFiles.remove(payloadId);
                    File tmp = sendTempFiles.remove(payloadId);
                    if (tmp != null) tmp.delete();
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

                    // ── Save received file and update the chat message ─────────
                    // Nearby stores the temp file in the app's cache directory.
                    // We move it to our own subfolder so we control its lifetime,
                    // then notify the UI so it can show the Play / preview button.
                    File tempFile = null;
                    try {
                        if (received != null && received.asFile() != null) {
                            tempFile = received.asFile().asJavaFile();
                        }
                    } catch (Exception e) {
                        Log.e(TAG, "asJavaFile() failed", e);
                    }

                    if (tempFile != null && tempFile.exists() && meta != null) {
                        final FileMetadata finalMeta = meta;
                        final File srcFile = tempFile;

                        fileIoExecutor.execute(() -> {
                            try {
                                // Move (rename) into our cache dir; fall back to copy
                                File cacheDir = new File(appContext.getCacheDir(), "chat_received");
                                //noinspection ResultOfMethodCallIgnored
                                cacheDir.mkdirs();
                                File dest = uniqueFile(cacheDir, finalMeta.getFileName());

                                if (!srcFile.renameTo(dest)) {
                                    copyFile(srcFile, dest);
                                    //noinspection ResultOfMethodCallIgnored
                                    srcFile.delete();
                                }

                                // Use Uri.fromFile — the file is in our own app storage,
                                // and we only use it within our own ImageView / MediaPlayer.
                                Uri fileUri = Uri.fromFile(dest);
                                Log.d(TAG, "Chat file ready: " + fileUri);
                                notifyMain(() -> {
                                    if (sChatFileCallback != null)
                                        sChatFileCallback.onChatFileReceived(fileUri, finalMeta);
                                });
                            } catch (Exception e) {
                                Log.e(TAG, "Failed to save received file: "
                                        + finalMeta.getFileName(), e);
                                // Don't delete srcFile on failure — keep the data
                            }
                        });
                    } else {
                        Log.w(TAG, "Received payload but no temp file: "
                                + "received=" + received + " tempFile=" + tempFile
                                + " meta=" + meta);
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

    // ── CHATFILE handler ────────────────────────────────────────────────────

    /**
     * Handle a "CHATFILE|senderName|fileName|mimeType|fileSize|" + raw-bytes payload.
     * Parses the text header (everything before the 5th pipe), extracts the raw file
     * bytes, saves them to {@code chat_received/} on a background thread, then
     * notifies both the TransferListener (so the chat bubble appears) and the
     * ChatFileReceivedCallback (so the bubble gets its savedUri for preview/play).
     */
    private void handleChatFileBytes(String endpointId, byte[] bytes) {
        // Find the 5th pipe — everything before it is the text header,
        // everything after is raw file data.
        int pipeCount = 0;
        int headerEnd = -1;
        for (int i = 0; i < bytes.length; i++) {
            if (bytes[i] == '|') {
                pipeCount++;
                if (pipeCount == 5) {
                    headerEnd = i + 1; // first byte of file data
                    break;
                }
            }
        }
        if (headerEnd < 0) {
            Log.w(TAG, "Malformed CHATFILE payload — could not find 5 pipes");
            return;
        }

        String header = new String(bytes, 0, headerEnd, java.nio.charset.StandardCharsets.UTF_8);
        // header = "CHATFILE|senderName|fileName|mimeType|fileSize|"
        String[] parts = header.split("\\|", 6); // [CHATFILE, sender, fileName, mime, size, ""]
        if (parts.length < 5) {
            Log.w(TAG, "Malformed CHATFILE header: " + header);
            return;
        }

        final String senderName = parts[1];
        final String fileName   = parts[2];
        final String mimeType   = parts[3];
        long parsedSize;
        try { parsedSize = Long.parseLong(parts[4]); }
        catch (NumberFormatException e) { parsedSize = bytes.length - headerEnd; }
        final long fileSize = parsedSize;

        final int dataOffset = headerEnd;
        final int dataLength = bytes.length - headerEnd;

        Log.d(TAG, "CHATFILE received: " + fileName + " (" + dataLength + " bytes)"
                + " mime=" + mimeType + " from=" + senderName);

        final FileMetadata meta = new FileMetadata(fileName, mimeType, fileSize);

        // 1) Notify UI about the incoming file (creates the chat bubble with emoji label)
        notifyMain(() -> {
            if (transferListener != null) transferListener.onIncomingFile(meta, senderName);
        });

        // 2) Save file bytes to disk in background, then notify UI with the savedUri
        fileIoExecutor.execute(() -> {
            try {
                File cacheDir = new File(appContext.getCacheDir(), "chat_received");
                //noinspection ResultOfMethodCallIgnored
                cacheDir.mkdirs();
                File dest = uniqueFile(cacheDir, fileName);

                try (OutputStream out = new FileOutputStream(dest)) {
                    out.write(bytes, dataOffset, dataLength);
                }

                Uri fileUri = Uri.fromFile(dest);
                Log.d(TAG, "CHATFILE saved: " + fileUri + " (" + dest.length() + " bytes)");

                notifyMain(() -> {
                    if (sChatFileCallback != null)
                        sChatFileCallback.onChatFileReceived(fileUri, meta);
                });
            } catch (Exception e) {
                Log.e(TAG, "Failed to save CHATFILE: " + fileName, e);
            }
        });
    }

    /** Fast prefix check on raw bytes without converting the whole array to String. */
    private static boolean startsWith(byte[] data, String prefix) {
        byte[] prefixBytes = prefix.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        if (data.length < prefixBytes.length) return false;
        for (int i = 0; i < prefixBytes.length; i++) {
            if (data[i] != prefixBytes[i]) return false;
        }
        return true;
    }

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

    private static void copyFile(File src, File dst) throws IOException {
        try (InputStream in  = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[64 * 1024];
            int read;
            while ((read = in.read(buf)) != -1) out.write(buf, 0, read);
        }
    }

    /** Return a File with the given name in dir, appending _1, _2… if it already exists. */
    private static File uniqueFile(File dir, String name) {
        File f = new File(dir, name);
        if (!f.exists()) return f;
        int dot = name.lastIndexOf('.');
        String stem = dot > 0 ? name.substring(0, dot) : name;
        String ext  = dot > 0 ? name.substring(dot)    : "";
        int i = 1;
        while (f.exists()) {
            f = new File(dir, stem + "_" + i + ext);
            i++;
        }
        return f;
    }
}
