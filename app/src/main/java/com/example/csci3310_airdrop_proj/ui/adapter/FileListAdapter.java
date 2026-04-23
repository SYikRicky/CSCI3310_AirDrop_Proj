package com.example.csci3310_airdrop_proj.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageButton;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.SharedFile;
import com.example.csci3310_airdrop_proj.service.UploadQueue;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for the Shared Drive.
 *
 * Shows two kinds of rows:
 *  - Pending uploads (from {@link UploadQueue}) — rendered at the top with a
 *    progress bar. One row per in-flight upload; disappears when the upload
 *    succeeds (Firestore takes over) or auto-dismisses ~5 s after failure.
 *  - Completed files (from Firestore snapshot) — the long-lived rows with
 *    preview / download / delete actions.
 */
public class FileListAdapter extends RecyclerView.Adapter<RecyclerView.ViewHolder> {

    private static final int VIEW_TYPE_PENDING   = 0;
    private static final int VIEW_TYPE_COMPLETED = 1;

    public interface OnFileActionListener {
        void onPreviewClicked(SharedFile file);
        void onDownloadClicked(SharedFile file);
        void onDeleteClicked(SharedFile file);
    }

    private final List<UploadQueue.PendingUpload> pending   = new ArrayList<>();
    private final List<SharedFile>                completed = new ArrayList<>();
    private       OnFileActionListener            listener;

    private static final SimpleDateFormat DATE_FMT =
            new SimpleDateFormat("dd MMM yyyy, HH:mm", Locale.getDefault());

    public void setOnFileActionListener(OnFileActionListener l) { this.listener = l; }

    /** Replace the Firestore-backed file list. */
    public void updateFiles(List<SharedFile> newFiles) {
        completed.clear();
        if (newFiles != null) completed.addAll(newFiles);
        notifyDataSetChanged();
    }

    /** Replace the pending uploads list (called from UploadQueue listener). */
    public void updatePending(List<UploadQueue.PendingUpload> newPending) {
        pending.clear();
        if (newPending != null) pending.addAll(newPending);
        notifyDataSetChanged();
    }

    // ── RecyclerView.Adapter overrides ────────────────────────────────────────

    @Override
    public int getItemViewType(int position) {
        return position < pending.size() ? VIEW_TYPE_PENDING : VIEW_TYPE_COMPLETED;
    }

    @Override
    public int getItemCount() {
        return pending.size() + completed.size();
    }

    @NonNull
    @Override
    public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        LayoutInflater inflater = LayoutInflater.from(parent.getContext());
        if (viewType == VIEW_TYPE_PENDING) {
            View v = inflater.inflate(R.layout.item_pending_upload, parent, false);
            return new PendingViewHolder(v);
        }
        View v = inflater.inflate(R.layout.item_shared_file, parent, false);
        return new FileViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull RecyclerView.ViewHolder holder, int position) {
        if (holder instanceof PendingViewHolder) {
            ((PendingViewHolder) holder).bind(pending.get(position));
        } else {
            int completedIndex = position - pending.size();
            ((FileViewHolder) holder).bind(completed.get(completedIndex), listener);
        }
    }

    // ── Completed-file ViewHolder ─────────────────────────────────────────────

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

            String date = file.getUploadedAt() != null
                    ? DATE_FMT.format(new Date(file.getUploadedAt().toDate().getTime()))
                    : "uploading…";
            tvMeta.setText(file.getFileSizeFormatted() + "  ·  "
                    + file.getUploadedBy() + "  ·  " + date);

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

    // ── Pending-upload ViewHolder ─────────────────────────────────────────────

    static class PendingViewHolder extends RecyclerView.ViewHolder {

        private final TextView    tvName;
        private final TextView    tvMeta;
        private final ProgressBar pbProgress;

        PendingViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName     = itemView.findViewById(R.id.tv_pending_name);
            tvMeta     = itemView.findViewById(R.id.tv_pending_meta);
            pbProgress = itemView.findViewById(R.id.pb_pending);
        }

        void bind(UploadQueue.PendingUpload upload) {
            tvName.setText(upload.fileName);
            if (upload.failed) {
                String err = upload.errorMessage != null ? upload.errorMessage : "error";
                tvMeta.setText("Upload failed · " + err);
                pbProgress.setProgress(0);
            } else {
                tvMeta.setText("Uploading… " + upload.progress + "%  ·  "
                        + upload.getFileSizeFormatted());
                pbProgress.setProgress(upload.progress);
            }
            // No click actions on pending rows.
            itemView.setOnClickListener(null);
        }
    }
}
