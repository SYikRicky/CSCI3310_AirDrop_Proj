package com.example.csci3310_airdrop_proj;

import android.Manifest;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.csci3310_airdrop_proj.model.SharedFile;
import com.example.csci3310_airdrop_proj.repository.SharedDriveRepository;
import com.example.csci3310_airdrop_proj.service.FileTransferService;
import com.example.csci3310_airdrop_proj.ui.fragment.FileListFragment;
import com.example.csci3310_airdrop_proj.ui.fragment.SendModeFragment;

import java.util.ArrayList;
import java.util.List;

/**
 * MainActivity — single Activity host.
 *
 * Responsibilities:
 *  1. Creates and exposes SharedDriveRepository (singleton for the app's lifetime).
 *  2. Hosts two Fragments: FileListFragment (drive view) and SendModeFragment (upload).
 *  3. Handles runtime permission requests (storage, notifications).
 *  4. Orchestrates upload: delegates to SharedDriveRepository, forwards
 *     progress/success/failure callbacks to SendModeFragment.
 *  5. Starts FileTransferService for Firebase downloads (keeps download alive
 *     even if user navigates away from the app).
 *  6. Handles incoming ACTION_SEND Intents (cross-app share → upload flow).
 *
 * Demonstrates: Activity lifecycle, Fragment management, Intents (explicit +
 *               implicit + ACTION_SEND), runtime permissions, Foreground Service.
 */
public class MainActivity extends AppCompatActivity {

    private SharedDriveRepository repository;
    private SendModeFragment      sendModeFragment;

    // ── File picker launcher (used by FileListFragment FAB via openFilePicker()) ──
    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                getContentResolver().takePersistableUriPermission(
                                        uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                // Resolve metadata here so navigateToUpload can pre-fill the fragment
                                String fileName = resolveFileName(uri);
                                String mimeType = resolveMimeType(uri);
                                long   fileSize = resolveFileSize(uri);
                                navigateToUpload(uri, fileName, mimeType, fileSize);
                            }
                        }
                    });

    // ── Permission launcher ───────────────────────────────────────────────────
    private final ActivityResultLauncher<String[]> permissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestMultiplePermissions(),
                    results -> {
                        if (results.containsValue(Boolean.FALSE)) {
                            Toast.makeText(this, R.string.permission_required, Toast.LENGTH_LONG).show();
                        }
                    });

    // ── Lifecycle ─────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        repository = new SharedDriveRepository();
        requestRequiredPermissions();

        if (savedInstanceState == null) {
            // Check if launched by an ACTION_SEND intent from another app
            if (Intent.ACTION_SEND.equals(getIntent().getAction())) {
                handleShareIntent(getIntent());
            } else {
                showFileList();
            }
        }
    }

    // ── Navigation ────────────────────────────────────────────────────────────

    private void showFileList() {
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, new FileListFragment(), FileListFragment.TAG)
                .commit();
    }

    private void navigateToUpload(Uri uri, String fileName, String mimeType, long size) {
        sendModeFragment = new SendModeFragment();
        getSupportFragmentManager()
                .beginTransaction()
                .replace(R.id.fragment_container, sendModeFragment, "upload")
                .addToBackStack("upload")
                .commit();

        // Pre-fill the fragment after its view is created
        if (uri != null && fileName != null) {
            final Uri    _uri      = uri;
            final String _fileName = fileName;
            final String _mimeType = mimeType;
            final long   _size     = size;
            // Post to the main looper so onViewCreated() has run before we touch views
            getSupportFragmentManager().executePendingTransactions();
            if (sendModeFragment.getView() != null) {
                sendModeFragment.setPreselectedFile(_uri, _fileName, _mimeType, _size);
            } else {
                new android.os.Handler(android.os.Looper.getMainLooper()).post(() ->
                        sendModeFragment.setPreselectedFile(_uri, _fileName, _mimeType, _size));
            }
        }
    }

    // ── Public API for Fragments ──────────────────────────────────────────────

    /** Returns the app-wide repository. Called by FileListFragment. */
    public SharedDriveRepository getRepository() { return repository; }

    /** Called by FileListFragment FAB — triggers system file picker. */
    public void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    /**
     * Called by SendModeFragment when user taps "Upload to Drive".
     * Delegates to SharedDriveRepository; forwards progress to SendModeFragment.
     */
    public void uploadFile(Uri uri, String fileName, String mimeType, long fileSize) {
        repository.uploadFile(uri, fileName, mimeType, fileSize,
                getContentResolver(),
                new SharedDriveRepository.UploadCallback() {
                    @Override
                    public void onProgress(int percent) {
                        runOnUiThread(() -> {
                            if (sendModeFragment != null && sendModeFragment.isAdded()) {
                                sendModeFragment.onUploadProgress(percent);
                            }
                        });
                    }

                    @Override
                    public void onSuccess(SharedFile file) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Uploaded: " + file.getFileName(), Toast.LENGTH_SHORT).show();
                            sendModeFragment = null;
                            // Navigate directly to FileListFragment — popBackStack was unreliable
                            getSupportFragmentManager()
                                    .beginTransaction()
                                    .replace(R.id.fragment_container,
                                            new FileListFragment(), FileListFragment.TAG)
                                    .commit();
                        });
                    }

                    @Override
                    public void onFailure(Exception e) {
                        runOnUiThread(() -> {
                            Toast.makeText(MainActivity.this,
                                    "Upload failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
                            if (sendModeFragment != null && sendModeFragment.isAdded()) {
                                sendModeFragment.onUploadFailure(e.getMessage());
                            }
                        });
                    }
                });
    }

    /**
     * Called by FileListFragment when user taps the download button on a file.
     * Starts FileTransferService — keeps the download alive even if app is backgrounded.
     */
    public void downloadFile(SharedFile file) {
        Intent intent = new Intent(this, FileTransferService.class);
        intent.putExtra(FileTransferService.EXTRA_DOWNLOAD_URL, file.getDownloadUrl());
        intent.putExtra(FileTransferService.EXTRA_FILE_NAME,    file.getFileName());
        intent.putExtra(FileTransferService.EXTRA_MIME_TYPE,    file.getMimeType());
        ContextCompat.startForegroundService(this, intent);
        Toast.makeText(this, "Downloading: " + file.getFileName(), Toast.LENGTH_SHORT).show();
    }

    /**
     * Called by FileListFragment after the user confirms deletion.
     */
    public void deleteFile(SharedFile file) {
        repository.deleteFile(file, new SharedDriveRepository.DeleteCallback() {
            @Override
            public void onSuccess() {
                Toast.makeText(MainActivity.this,
                        "Deleted: " + file.getFileName(), Toast.LENGTH_SHORT).show();
                // FileListFragment Firestore listener refreshes automatically
            }

            @Override
            public void onFailure(Exception e) {
                Toast.makeText(MainActivity.this,
                        "Delete failed: " + e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        });
    }

    // ── Cross-app share handling ──────────────────────────────────────────────

    /**
     * Handles files shared from other apps (Gallery, Files, etc.) via ACTION_SEND.
     * Navigates directly to the upload screen with the file pre-selected.
     */
    private void handleShareIntent(Intent intent) {
        Uri uri = intent.getParcelableExtra(Intent.EXTRA_STREAM);
        if (uri == null) { showFileList(); return; }

        String mimeType = intent.getType();
        String fileName = resolveFileName(uri);
        showFileList(); // push drive as back-stack base
        navigateToUpload(uri, fileName, mimeType, resolveFileSize(uri));
    }

    private String resolveFileName(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {}
        String seg = uri.getLastPathSegment();
        return seg != null ? seg : "shared_file";
    }

    private String resolveMimeType(Uri uri) {
        String type = getContentResolver().getType(uri);
        return type != null ? type : "*/*";
    }

    private long resolveFileSize(Uri uri) {
        try (android.database.Cursor c = getContentResolver().query(
                uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(android.provider.OpenableColumns.SIZE);
                if (i >= 0 && !c.isNull(i)) return c.getLong(i);
            }
        } catch (Exception ignored) {}
        return 0;
    }

    // ── Permissions ───────────────────────────────────────────────────────────

    private void requestRequiredPermissions() {
        List<String> needed = new ArrayList<>();
        int sdk = Build.VERSION.SDK_INT;

        if (sdk >= Build.VERSION_CODES.TIRAMISU) {
            addIfMissing(needed, Manifest.permission.READ_MEDIA_IMAGES);
            addIfMissing(needed, Manifest.permission.READ_MEDIA_VIDEO);
            addIfMissing(needed, Manifest.permission.POST_NOTIFICATIONS);
        } else {
            addIfMissing(needed, Manifest.permission.READ_EXTERNAL_STORAGE);
        }

        if (!needed.isEmpty()) permissionLauncher.launch(needed.toArray(new String[0]));
    }

    private void addIfMissing(List<String> list, String perm) {
        if (ContextCompat.checkSelfPermission(this, perm) != PackageManager.PERMISSION_GRANTED) {
            list.add(perm);
        }
    }
}
