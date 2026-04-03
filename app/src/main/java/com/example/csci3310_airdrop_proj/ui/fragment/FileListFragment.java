package com.example.csci3310_airdrop_proj.ui.fragment;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.SharedFile;
import com.example.csci3310_airdrop_proj.ui.adapter.FileListAdapter;
import com.google.android.material.floatingactionbutton.FloatingActionButton;
import com.google.firebase.firestore.ListenerRegistration;

import java.util.List;

/**
 * The main shared drive screen.
 * Shows all files uploaded to Firebase in a live RecyclerView.
 * Firestore's snapshot listener automatically pushes updates — no manual refresh needed.
 *
 * Demonstrates:
 *  - Fragment lifecycle (onViewCreated / onDestroyView)
 *  - RecyclerView + ViewHolder via FileListAdapter
 *  - Real-time Firestore listener (removed in onDestroyView to prevent leaks)
 *  - Fragment → Activity communication via requireActivity() casting
 */
public class FileListFragment extends Fragment {

    public static final String TAG = "FileListFrag";

    private FileListAdapter      adapter;
    private TextView             tvEmpty;
    private ListenerRegistration firestoreListener;  // Must be removed in onDestroyView

    // ── Fragment lifecycle ────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_file_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvEmpty = view.findViewById(R.id.tv_empty_files);

        // Set up RecyclerView
        RecyclerView rv = view.findViewById(R.id.rv_files);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        adapter = new FileListAdapter();
        adapter.setOnFileActionListener(new FileListAdapter.OnFileActionListener() {
            @Override
            public void onDownloadClicked(SharedFile file) {
                ((MainActivity) requireActivity()).downloadFile(file);
            }

            @Override
            public void onDeleteClicked(SharedFile file) {
                confirmDelete(file);
            }
        });
        rv.setAdapter(adapter);

        // FAB → trigger file upload
        FloatingActionButton fab = view.findViewById(R.id.fab_upload);
        fab.setOnClickListener(v -> ((MainActivity) requireActivity()).openFilePicker());

        // Start listening to Firestore — real-time updates
        firestoreListener = ((MainActivity) requireActivity())
                .getRepository()
                .listenToFiles(new com.example.csci3310_airdrop_proj.repository.SharedDriveRepository.FilesListener() {
                    @Override
                    public void onFilesChanged(List<SharedFile> files) {
                        adapter.updateFiles(files);
                        tvEmpty.setVisibility(files.isEmpty() ? View.VISIBLE : View.GONE);
                    }

                    @Override
                    public void onError(Exception e) {
                        tvEmpty.setVisibility(View.VISIBLE);
                        tvEmpty.setText(R.string.drive_load_error);
                    }
                });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Critical: remove Firestore listener to prevent memory/resource leak
        if (firestoreListener != null) {
            firestoreListener.remove();
            firestoreListener = null;
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void confirmDelete(SharedFile file) {
        new AlertDialog.Builder(requireContext())
                .setTitle("Delete File")
                .setMessage("Remove \"" + file.getFileName() + "\" from the shared drive?")
                .setPositiveButton("Delete", (d, w) ->
                        ((MainActivity) requireActivity()).deleteFile(file))
                .setNegativeButton("Cancel", null)
                .show();
    }
}
