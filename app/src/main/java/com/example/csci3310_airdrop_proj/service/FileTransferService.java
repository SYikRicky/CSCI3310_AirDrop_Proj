package com.example.csci3310_airdrop_proj.service;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import androidx.core.app.NotificationCompat;
import androidx.lifecycle.LifecycleService;

import com.example.csci3310_airdrop_proj.storage.FileStorageManager;
import com.google.firebase.storage.FileDownloadTask;
import com.google.firebase.storage.FirebaseStorage;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Foreground Service that runs Firebase Storage downloads in the background.
 *
 * Why a Foreground Service?
 *   Large file downloads take time. If the user navigates away from the app,
 *   Android can kill background processes. A Foreground Service with a visible
 *   notification keeps the process alive for the duration of the download.
 *
 * Flow:
 *   1. MainActivity calls ContextCompat.startForegroundService(intent) with
 *      EXTRA_DOWNLOAD_URL and EXTRA_FILE_NAME.
 *   2. Service calls startForeground() immediately (within 5 s — API 34 requirement).
 *   3. Firebase Storage downloads the file to a temp location.
 *   4. FileStorageManager copies it to the public Downloads folder.
 *   5. Service updates the notification and stops itself.
 *
 * Demonstrates: Foreground Service, NotificationChannel, ExecutorService,
 *               LifecycleService, Android 14 foregroundServiceType requirement.
 */
public class FileTransferService extends LifecycleService {

    private static final String TAG        = "FileTransferService";
    private static final String CHANNEL_ID = "dropdroid_transfer";
    private static final int    NOTIF_ID   = 2001;

    public static final String EXTRA_DOWNLOAD_URL = "extra_download_url";
    public static final String EXTRA_FILE_NAME    = "extra_file_name";
    public static final String EXTRA_MIME_TYPE    = "extra_mime_type";

    private ExecutorService    executor;
    private FileStorageManager storageManager;
    private NotificationManager notifManager;

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

        // ── Must call startForeground within 5 s on API 34+ ──────────────────
        Notification notif = buildNotification("Starting download…");
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(NOTIF_ID, notif);
        }

        if (intent == null) { stopSelf(startId); return START_NOT_STICKY; }

        String downloadUrl = intent.getStringExtra(EXTRA_DOWNLOAD_URL);
        String fileName    = intent.getStringExtra(EXTRA_FILE_NAME);
        String mimeType    = intent.getStringExtra(EXTRA_MIME_TYPE);

        if (downloadUrl == null || fileName == null) {
            Log.e(TAG, "Missing URL or file name — stopping");
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final String finalUrl      = downloadUrl;
        final String finalName     = fileName;
        final String finalMime     = mimeType != null ? mimeType : "*/*";
        final int    finalStartId  = startId;

        // ── Download via Firebase Storage SDK ─────────────────────────────────
        updateNotification("Downloading: " + finalName + "…");

        // Create a temp file for Firebase to write into
        File tempFile;
        try {
            tempFile = File.createTempFile("dropdrive_", "_" + finalName, getCacheDir());
        } catch (IOException e) {
            Log.e(TAG, "Cannot create temp file", e);
            stopSelf(startId);
            return START_NOT_STICKY;
        }

        final File finalTemp = tempFile;

        FirebaseStorage.getInstance()
                .getReferenceFromUrl(finalUrl)
                .getFile(finalTemp)
                .addOnProgressListener(snapshot -> {
                    long total = snapshot.getTotalByteCount();
                    long done  = snapshot.getBytesTransferred();
                    int  pct   = total > 0 ? (int) (done * 100L / total) : 0;
                    updateNotification("Downloading: " + finalName + " (" + pct + "%)");
                })
                .addOnSuccessListener(snapshot -> {
                    // Firebase wrote to tempFile — now copy to Downloads on background thread
                    executor.submit(() -> {
                        try (FileInputStream fis = new FileInputStream(finalTemp)) {
                            Uri savedUri = storageManager.saveFile(finalName, finalMime, fis);
                            Log.d(TAG, "Saved: " + savedUri);
                            updateNotification("Saved: " + finalName);
                        } catch (IOException e) {
                            Log.e(TAG, "Copy to Downloads failed", e);
                            updateNotification("Save failed: " + e.getMessage());
                        } finally {
                            //noinspection ResultOfMethodCallIgnored
                            finalTemp.delete();
                        }
                        try { Thread.sleep(2500); } catch (InterruptedException ignored) {}
                        stopSelf(finalStartId);
                    });
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Firebase download failed", e);
                    updateNotification("Download failed: " + e.getMessage());
                    //noinspection ResultOfMethodCallIgnored
                    finalTemp.delete();
                    stopSelf(finalStartId);
                });

        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        super.onDestroy();
        if (executor != null && !executor.isShutdown()) executor.shutdownNow();
    }

    // ── Notification helpers ──────────────────────────────────────────────────

    private void createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID, "DroidDrive Transfers", NotificationManager.IMPORTANCE_LOW);
            ch.setDescription("File upload/download progress");
            ch.setShowBadge(false);
            notifManager.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification(String text) {
        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("DroidDrive")
                .setContentText(text)
                .setSmallIcon(android.R.drawable.stat_sys_download)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();
    }

    private void updateNotification(String text) {
        if (notifManager != null) notifManager.notify(NOTIF_ID, buildNotification(text));
    }
}
