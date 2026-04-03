package com.example.csci3310_airdrop_proj.storage;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * Handles saving received files to the device's Downloads folder.
 *
 * - API 29+ (Android 10+): uses MediaStore.Downloads (Scoped Storage)
 * - API 24–28:             writes directly to the public Downloads folder
 *
 * The IS_PENDING flag is used on API 29+ to "atomically" publish the file:
 * the file is invisible to other apps until IS_PENDING is cleared to 0.
 */
public class FileStorageManager {

    private static final String TAG = "FileStorageMgr";
    private static final int    BUFFER_SIZE = 64 * 1024; // 64 KB

    private final Context context;

    public FileStorageManager(Context context) {
        this.context = context.getApplicationContext();
    }

    /**
     * Save data from {@code inputStream} into the Downloads folder with the given name and MIME type.
     *
     * @return URI pointing to the saved file
     * @throws IOException if the write fails
     */
    public Uri saveFile(String fileName, String mimeType, InputStream inputStream) throws IOException {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            return saveViaMediaStore(fileName, mimeType, inputStream);
        } else {
            return saveDirectly(fileName, inputStream);
        }
    }

    // ── API 29+: MediaStore ──────────────────────────────────────────────────

    private Uri saveViaMediaStore(String fileName, String mimeType, InputStream data) throws IOException {
        ContentResolver resolver = context.getContentResolver();

        ContentValues values = new ContentValues();
        values.put(MediaStore.Downloads.DISPLAY_NAME, fileName);
        values.put(MediaStore.Downloads.MIME_TYPE, mimeType != null ? mimeType : "*/*");
        values.put(MediaStore.Downloads.IS_PENDING, 1); // mark as in-progress

        Uri uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values);
        if (uri == null) {
            throw new IOException("MediaStore.insert returned null for file: " + fileName);
        }

        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null) throw new IOException("openOutputStream returned null for: " + uri);
            copyStream(data, out);
        } catch (IOException e) {
            // Roll back the pending entry so it doesn't appear as a broken file
            resolver.delete(uri, null, null);
            throw e;
        }

        // Clear IS_PENDING → file becomes visible in Downloads app and other apps
        values.clear();
        values.put(MediaStore.Downloads.IS_PENDING, 0);
        resolver.update(uri, values, null, null);

        Log.d(TAG, "Saved via MediaStore: " + uri);
        return uri;
    }

    // ── API 24–28: direct file write ─────────────────────────────────────────

    private Uri saveDirectly(String fileName, InputStream data) throws IOException {
        File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
        if (!dir.exists() && !dir.mkdirs()) {
            throw new IOException("Cannot create Downloads directory: " + dir);
        }

        File file = resolveUniqueFile(dir, fileName);

        try (FileOutputStream fos = new FileOutputStream(file)) {
            copyStream(data, fos);
        }

        Log.d(TAG, "Saved directly: " + file.getAbsolutePath());
        return Uri.fromFile(file);
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** If a file with the given name already exists, appends (1), (2), … until unique. */
    private File resolveUniqueFile(File dir, String fileName) {
        File file = new File(dir, fileName);
        if (!file.exists()) return file;

        String base = getBaseName(fileName);
        String ext  = getExtension(fileName);
        int    i    = 1;
        while (file.exists()) {
            file = new File(dir, base + " (" + i + ")" + ext);
            i++;
        }
        return file;
    }

    private void copyStream(InputStream in, OutputStream out) throws IOException {
        byte[] buf = new byte[BUFFER_SIZE];
        int read;
        while ((read = in.read(buf)) != -1) {
            out.write(buf, 0, read);
        }
        out.flush();
    }

    private String getBaseName(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(0, dot) : fileName;
    }

    private String getExtension(String fileName) {
        int dot = fileName.lastIndexOf('.');
        return dot > 0 ? fileName.substring(dot) : "";
    }
}
