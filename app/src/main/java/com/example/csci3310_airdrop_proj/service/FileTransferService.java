package com.example.csci3310_airdrop_proj.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.storage.FileStorageManager;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground Service that saves a received file from a Nearby Connections temp location
 * into the device's Downloads folder.
 *
 * Started by NearbyConnectionsManager when a FILE payload transfer completes successfully.
 * Extends LifecycleService (from lifecycle-service dep) so it is lifecycle-aware.
 *
 * Android 14+ requirement: must call startForeground() within 5 s of onStartCommand()
 * and must declare foregroundServiceType="dataSync" in the manifest.
 */
public class FileTransferService extends LifecycleService {

    private static final String TAG       = "FileTransferService";
    private static final String CHANNEL_ID = "dropdroid_transfer";
    private static final int    NOTIF_ID   = 1001;

    /** Intent extras passed by NearbyConnectionsManager */
    public static final String EXTRA_FILE_METADATA   = "extra_file_metadata";
    public static final String EXTRA_TEMP_FILE_PATH  = "extra_temp_file_path";

    /** Callback notified on the main thread when a file has been saved successfully. */
    public interface OnFileSavedCallback {
        void onFileSaved(Uri savedUri, String fileName, String mimeType);
    }

    private static OnFileSavedCallback sCallback;

    public static void setCallback(OnFileSavedCallback callback) {
        sCallback = callback;
    }

    private ExecutorService     executor;
    private FileStorageManager  storageManager;
    private NotificationManager notifManager;
    private final Handler       mainHandler = new Handler(Looper.getMainLooper());

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    public void onCreate() {
        super.onCreate();
        executor       = Executors.newSingleThreadExecutor();
        storageManager = new FileStorageManager(this);
        notifManager   = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        createNotificationChannel();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        super.onStartCommand(intent, flags, startId);

        // ── Must call startForeground immediately (within 5 s on API 34+) ──
        Notification notification = buildNotification("Preparing to save file…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // API 34+: must provide the foreground service type
            startForeground(NOTIF_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notification);
        }

        if (intent == null) {
            Log.w(TAG, "null intent — stopping self");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        // ── Extract extras ────────────────────────────────────────────────────
        FileMetadata meta;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            meta = intent.getParcelableExtra(EXTRA_FILE_METADATA, FileMetadata.class);
        } else {
            //noinspection deprecation
            meta = intent.getParcelableExtra(EXTRA_FILE_METADATA);
        }
        String tempPath = intent.getStringExtra(EXTRA_TEMP_FILE_PATH);

        if (meta == null || tempPath == null) {
            Log.e(TAG, "Missing metadata or temp path — stopping");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final FileMetadata finalMeta = meta;
        final String       finalPath = tempPath;
        final int          finalId   = startId;

        // ── Do the I/O on a background thread ────────────────────────────────
        executor.submit(() -> {
            File tempFile = new File(finalPath);
            if (!tempFile.exists()) {
                Log.e(TAG, "Temp file missing: " + finalPath);
                updateNotification("Save failed — file not found");
                stopSelf(finalId);
                return;
            }

            updateNotification("Saving: " + finalMeta.getFileName() + "…");

            try (FileInputStream fis = new FileInputStream(tempFile)) {
                Uri savedUri = storageManager.saveFile(finalMeta.getFileName(), finalMeta.getMimeType(), fis);
                Log.d(TAG, "Saved: " + finalMeta.getFileName());
                updateNotification("Saved: " + finalMeta.getFileName());
                if (sCallback != null) {
                    String mime = finalMeta.getMimeType();
                    String name = finalMeta.getFileName();
                    mainHandler.post(() -> {
                        if (sCallback != null) sCallback.onFileSaved(savedUri, name, mime);
                    });
                }
            } catch (IOException e) {
                Log.e(TAG, "Save failed", e);
                updateNotification("Save failed: " + e.getMessage());
            } finally {
                // Remove the Nearby temp file to free cache space
                if (tempFile.exists()) {
                    //noinspection ResultOfMethodCallIgnored
                    tempFile.delete();
                }
            }

            // Brief pause so user can read the success notification, then stop
            try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
            stopSelf(finalId);
        });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel channel = new NotificationChannel(
                    CHANNEL_ID,
                    "DroidDrop Transfers",
                    NotificationManager.IMPORTANCE_LOW);
            channel.setDescription("Shows file receive progress");
            channel.setShowBadge(false);
            notifManager.createNotificationChannel(channel);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DroidDrop")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download_done)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        if (notifManager != null) {
            notifManager.notify(NOTIF_ID, buildNotification(text));
        }
    }
}
