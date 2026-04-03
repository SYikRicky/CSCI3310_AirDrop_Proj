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
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.FileMetadata;

/**
 * Send mode screen.
 * Lets the user pick a file from device storage (via an implicit Intent),
 * then navigates to DeviceDiscoveryFragment to choose a target device.
 *
 * Demonstrates: Implicit Intents, ActivityResultLauncher, Fragment → Activity communication.
 */
public class SendModeFragment extends Fragment {

    private TextView tvSelectedFile;
    private Button   btnFindDevices;

    private Uri          selectedUri;
    private FileMetadata selectedMetadata;

    /** File picker — launched via ActivityResultContracts.StartActivityForResult */
    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                // Take persistable permission so we can re-read the URI later
                                requireActivity().getContentResolver()
                                        .takePersistableUriPermission(
                                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                selectedUri      = uri;
                                selectedMetadata = extractMetadata(uri);
                                tvSelectedFile.setText(selectedMetadata.getFileName());
                                btnFindDevices.setEnabled(true);
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
        btnFindDevices = view.findViewById(R.id.btn_find_devices);
        Button btnPickFile = view.findViewById(R.id.btn_pick_file);

        btnFindDevices.setEnabled(false);

        btnPickFile.setOnClickListener(v -> openFilePicker());

        btnFindDevices.setOnClickListener(v -> {
            if (selectedUri != null && selectedMetadata != null) {
                // Delegate to MainActivity which owns NearbyConnectionsManager
                ((MainActivity) requireActivity()).onFilePicked(selectedUri, selectedMetadata);
            }
        });
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /** Launch the system file picker (implicit Intent — ACTION_OPEN_DOCUMENT). */
    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        // Allow multiple MIME types via extra (optional for MVP)
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        filePickerLauncher.launch(intent);
    }

    /**
     * Query the ContentResolver to extract the file name, MIME type, and size from the URI.
     * Uses the OpenableColumns contract — standard for files opened via ACTION_OPEN_DOCUMENT.
     */
    private FileMetadata extractMetadata(Uri uri) {
        String fileName = "file";
        long   fileSize = 0;
        String mimeType = requireActivity().getContentResolver().getType(uri);
        if (mimeType == null) mimeType = "*/*";

        try (Cursor cursor = requireActivity().getContentResolver().query(
                uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) fileSize = cursor.getLong(sizeIdx);
            }
        } catch (Exception e) {
            // Fallback: use URI last path segment
            String path = uri.getLastPathSegment();
            if (path != null) fileName = path;
        }

        return new FileMetadata(fileName, mimeType, fileSize);
    }
}
