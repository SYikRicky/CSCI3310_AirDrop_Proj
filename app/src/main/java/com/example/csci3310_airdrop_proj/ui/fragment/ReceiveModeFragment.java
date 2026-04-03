package com.example.csci3310_airdrop_proj.ui.fragment;

import android.app.DownloadManager;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;

import java.io.File;

/**
 * Receive mode screen.
 * The user toggles this device into "discoverable + discoverer" mode.
 * Demonstrates: Fragment → Activity communication via casting requireActivity().
 */
public class ReceiveModeFragment extends Fragment {

    private Button       btnToggle;
    private TextView     tvStatus;
    private LinearLayout layoutFileActions;
    private Button       btnOpenFile;
    private Button       btnOpenDownloads;

    private boolean isReceiving = false;
    private Uri     savedFileUri;
    private String  savedMimeType;

    // ── Fragment lifecycle ─────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_receive_mode, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnToggle         = view.findViewById(R.id.btn_toggle_receive);
        tvStatus          = view.findViewById(R.id.tv_receive_status);
        layoutFileActions = view.findViewById(R.id.layout_file_actions);
        btnOpenFile       = view.findViewById(R.id.btn_open_file);
        btnOpenDownloads  = view.findViewById(R.id.btn_open_downloads);

        btnToggle.setOnClickListener(v -> toggleReceiving());
        btnOpenFile.setOnClickListener(v -> openFile());
        btnOpenDownloads.setOnClickListener(v -> openDownloads());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Stop receiving when fragment is removed from back stack
        if (isReceiving) {
            ((MainActivity) requireActivity()).stopReceiving();
        }
    }

    // ── Public API called by MainActivity ─────────────────────────────────────

    /** Update the status label (e.g. "Receiving: photo.jpg 45%"). */
    public void setStatusText(String text) {
        if (tvStatus != null) tvStatus.setText(text);
    }

    /**
     * Called after a file has been saved to Downloads.
     * Shows "Open File" and "Downloads" buttons.
     */
    public void showFileReceived(Uri uri, String fileName, String mimeType) {
        if (tvStatus != null) tvStatus.setText("Saved: " + fileName);
        savedFileUri  = uri;
        savedMimeType = mimeType;
        if (layoutFileActions != null) layoutFileActions.setVisibility(View.VISIBLE);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void toggleReceiving() {
        isReceiving = !isReceiving;
        // Hide action buttons when toggling receive mode
        if (layoutFileActions != null) layoutFileActions.setVisibility(View.GONE);
        savedFileUri  = null;
        savedMimeType = null;
        if (isReceiving) {
            btnToggle.setText(R.string.stop_receiving);
            tvStatus.setText(R.string.ready_to_receive);
            ((MainActivity) requireActivity()).startReceiving();
        } else {
            btnToggle.setText(R.string.start_receiving);
            tvStatus.setText(R.string.not_receiving);
            ((MainActivity) requireActivity()).stopReceiving();
        }
    }

    private void openFile() {
        if (savedFileUri == null) return;

        Uri uriToOpen = savedFileUri;
        // file:// URIs require FileProvider on API 24+ to share with other apps
        if ("file".equals(savedFileUri.getScheme())) {
            uriToOpen = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    new File(savedFileUri.getPath()));
        }

        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uriToOpen, savedMimeType != null ? savedMimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);

        try {
            startActivity(Intent.createChooser(intent, getString(R.string.btn_open_file)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), R.string.no_app_for_file, Toast.LENGTH_SHORT).show();
        }
    }

    private void openDownloads() {
        try {
            startActivity(new Intent(DownloadManager.ACTION_VIEW_DOWNLOADS));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(requireContext(), "Downloads app not available", Toast.LENGTH_SHORT).show();
        }
    }
}
