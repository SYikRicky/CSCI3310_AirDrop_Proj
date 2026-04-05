package com.example.csci3310_airdrop_proj.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;

/**
 * Upload screen.
 * User picks a file (via ACTION_OPEN_DOCUMENT implicit Intent),
 * previews the selection, then taps "Upload to Drive" to push it to Firebase Storage.
 *
 * Progress is shown via a ProgressBar updated by MainActivity callbacks.
 * On success, navigates back to FileListFragment (which auto-refreshes via Firestore listener).
 *
 * Demonstrates: Implicit Intent, ActivityResultLauncher, Fragment → Activity communication.
 */
public class SendModeFragment extends Fragment {

    private TextView    tvSelectedFile;
    private Button      btnUpload;
    private ProgressBar progressBar;
    private TextView    tvStatus;

    private Uri    selectedUri;
    private String selectedFileName;
    private String selectedMimeType;
    private long   selectedFileSize;

    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                requireActivity().getContentResolver()
                                        .takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                extractAndSetFile(uri);
                            }
                        }
                    });

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_send_mode, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvSelectedFile = view.findViewById(R.id.tv_selected_file);
        btnUpload      = view.findViewById(R.id.btn_find_devices);  // reused view id, label changed via string
        progressBar    = view.findViewById(R.id.progress_upload);
        tvStatus       = view.findViewById(R.id.tv_upload_status);
        Button btnPick = view.findViewById(R.id.btn_pick_file);

        btnUpload.setText(R.string.btn_upload);
        btnUpload.setEnabled(false);
        progressBar.setVisibility(View.GONE);
        tvStatus.setVisibility(View.GONE);

        btnPick.setOnClickListener(v -> openFilePicker());
        btnUpload.setOnClickListener(v -> startUpload());
    }

    // ── Public API called by MainActivity ─────────────────────────────────────

    /** Called from MainActivity when launched via ACTION_SEND (cross-app share). */
    public void setPreselectedFile(Uri uri, String fileName, String mimeType, long size) {
        selectedUri      = uri;
        selectedFileName = fileName;
        selectedMimeType = mimeType;
        selectedFileSize = size;
        if (tvSelectedFile != null) {
            tvSelectedFile.setText(fileName);
            btnUpload.setEnabled(true);
        }
    }

    /** Progress update (0–100) called by MainActivity during upload. */
    public void onUploadProgress(int percent) {
        if (progressBar == null) return;
        progressBar.setVisibility(View.VISIBLE);
        tvStatus.setVisibility(View.VISIBLE);
        progressBar.setProgress(percent);
        if (percent >= 100) {
            tvStatus.setText(R.string.saving_metadata); // Storage done, writing to Firestore
        } else {
            tvStatus.setText(getString(R.string.uploading) + " " + percent + "%");
        }
        btnUpload.setEnabled(false);
    }

    /** Called when upload completes successfully. Navigation is handled by MainActivity. */
    public void onUploadSuccess() {
        if (progressBar != null) progressBar.setProgress(100);
        if (tvStatus != null) {
            tvStatus.setVisibility(View.VISIBLE);
            tvStatus.setText(R.string.upload_success);
        }
    }

    /** Called when upload fails. */
    public void onUploadFailure(String error) {
        if (tvStatus == null) return;
        progressBar.setVisibility(View.GONE);
        tvStatus.setVisibility(View.VISIBLE);
        tvStatus.setText(getString(R.string.upload_failed) + ": " + error);
        btnUpload.setEnabled(selectedUri != null);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        filePickerLauncher.launch(intent);
    }

    private void extractAndSetFile(Uri uri) {
        selectedUri      = uri;
        selectedMimeType = requireActivity().getContentResolver().getType(uri);
        if (selectedMimeType == null) selectedMimeType = "*/*";
        selectedFileName = "file";
        selectedFileSize = 0;

        try (Cursor cursor = requireActivity().getContentResolver()
                .query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) selectedFileName = cursor.getString(nameIdx);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) selectedFileSize = cursor.getLong(sizeIdx);
            }
        } catch (Exception ignored) {
            String seg = uri.getLastPathSegment();
            if (seg != null) selectedFileName = seg;
        }

        tvSelectedFile.setText(selectedFileName);
        btnUpload.setEnabled(true);
        tvStatus.setVisibility(View.GONE);
        progressBar.setVisibility(View.GONE);
    }

    private void startUpload() {
        if (selectedUri == null) return;
        ((MainActivity) requireActivity()).uploadFile(
                selectedUri, selectedFileName, selectedMimeType, selectedFileSize);
    }
}
