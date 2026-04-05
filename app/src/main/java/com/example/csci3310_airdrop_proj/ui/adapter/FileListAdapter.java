package com.example.csci3310_airdrop_proj.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.SharedFile;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the shared drive file list.
 *
 * Demonstrates the ViewHolder pattern — tvName, tvMeta, btnDownload, btnDelete
 * are all found once in the constructor and reused for every bound item,
 * avoiding repeated expensive findViewById() calls.
 */
public class FileListAdapter extends RecyclerView.Adapter<FileListAdapter.FileViewHolder> {

    public interface OnFileActionListener {
        void onPreviewClicked(SharedFile file);
        void onDownloadClicked(SharedFile file);
        void onDeleteClicked(SharedFile file);
    }

    private final List<SharedFile>      files    = new ArrayList<>();
    private       OnFileActionListener  listener;
    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());

    public void setOnFileActionListener(OnFileActionListener l) { this.listener = l; }

    /** Replace the file list and refresh RecyclerView. Called on every Firestore snapshot. */
    public void updateFiles(List<SharedFile> newFiles) {
        files.clear();
        if (newFiles != null) files.addAll(newFiles);
        notifyDataSetChanged();
    }

    // ── RecyclerView.Adapter overrides ────────────────────────────────────────

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_shared_file, parent, false);
        return new FileViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        holder.bind(files.get(position), listener);
    }

    @Override
    public int getItemCount() { return files.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class FileViewHolder extends RecyclerView.ViewHolder {

        private final TextView    tvFileName;
        private final TextView    tvMeta;
        private final ImageButton btnDownload;
        private final ImageButton btnDelete;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            tvFileName  = itemView.findViewById(R.id.tv_file_name);
            tvMeta      = itemView.findViewById(R.id.tv_file_meta);
            btnDownload = itemView.findViewById(R.id.btn_download);
            btnDelete   = itemView.findViewById(R.id.btn_delete);
        }

        void bind(SharedFile file, OnFileActionListener listener) {
            tvFileName.setText(file.getFileName());

            // Build metadata: "1.4 MB  ·  Pixel 7  ·  03 Apr 2026, 14:30"
            String date = file.getUploadedAt() != null
                    ? DATE_FMT.format(new Date(file.getUploadedAt().toDate().getTime()))
                    : "uploading…";
            tvMeta.setText(file.getFileSizeFormatted() + "  ·  " + file.getUploadedBy() + "  ·  " + date);

            // Tap the card itself to preview the file
            itemView.setOnClickListener(v -> {
                if (listener != null) listener.onPreviewClicked(file);
            });
            btnDownload.setOnClickListener(v -> {
                if (listener != null) listener.onDownloadClicked(file);
            });
            btnDelete.setOnClickListener(v -> {
                if (listener != null) listener.onDeleteClicked(file);
            });
        }
    }
}
