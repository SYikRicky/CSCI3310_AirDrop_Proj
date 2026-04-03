package com.example.csci3310_airdrop_proj.ui.adapter;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * RecyclerView adapter for chat messages.
 * Two view types: sent (right-aligned, primary color) and received (left-aligned, surface color).
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private static final int VIEW_TYPE_SENT = 0;
    private static final int VIEW_TYPE_RECEIVED = 1;

    private final List<ChatMessage> messages = new ArrayList<>();
    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    public void addMessage(ChatMessage message) {
        messages.add(message);
        notifyItemInserted(messages.size() - 1);
    }

    /**
     * Finds the most recent received FILE message with the given filename (no URI yet)
     * and attaches the saved URI, triggering a rebind to show the Open button.
     */
    public void updateMessageUri(String fileName, Uri uri) {
        for (int i = messages.size() - 1; i >= 0; i--) {
            ChatMessage msg = messages.get(i);
            if (msg.getType() == ChatMessage.Type.FILE
                    && !msg.isOutgoing()
                    && fileName.equals(msg.getText())
                    && msg.getSavedUri() == null) {
                msg.setSavedUri(uri);
                notifyItemChanged(i);
                return;
            }
        }
    }

    @Override
    public int getItemViewType(int position) {
        return messages.get(position).isOutgoing() ? VIEW_TYPE_SENT : VIEW_TYPE_RECEIVED;
    }

    @NonNull
    @Override
    public ChatViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        int layoutId = viewType == VIEW_TYPE_SENT
                ? R.layout.item_chat_message_sent
                : R.layout.item_chat_message_received;
        View view = LayoutInflater.from(parent.getContext()).inflate(layoutId, parent, false);
        return new ChatViewHolder(view, viewType);
    }

    @Override
    public void onBindViewHolder(@NonNull ChatViewHolder holder, int position) {
        holder.bind(messages.get(position));
    }

    @Override
    public int getItemCount() { return messages.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class ChatViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvMessage;
        private final TextView tvTimestamp;
        private final TextView tvSender;    // only in received layout
        private final View     btnOpenFile; // only in received layout
        private final View     btnOpenMap;  // in both layouts
        private final int      viewType;

        ChatViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            this.viewType = viewType;
            tvMessage   = itemView.findViewById(R.id.tv_message);
            tvTimestamp  = itemView.findViewById(R.id.tv_timestamp);
            tvSender    = itemView.findViewById(R.id.tv_sender);
            btnOpenFile = itemView.findViewById(R.id.btn_open_file);
            btnOpenMap  = itemView.findViewById(R.id.btn_open_map);
        }

        void bind(ChatMessage msg) {
            String displayText;
            if (msg.getType() == ChatMessage.Type.FILE) {
                String fileName = msg.getText();
                if (msg.getFileMetadata() != null) {
                    long size = msg.getFileMetadata().getFileSize();
                    displayText = "\uD83D\uDCCE " + fileName + " (" + formatSize(size) + ")";
                } else {
                    displayText = "\uD83D\uDCCE " + fileName;
                }
            } else if (msg.getType() == ChatMessage.Type.LOCATION) {
                displayText = "\uD83D\uDCCD " + itemView.getContext().getString(R.string.location_shared)
                        + "\n" + String.format(Locale.US, "%.6f, %.6f",
                        msg.getLatitude(), msg.getLongitude());
            } else {
                displayText = msg.getText();
            }

            tvMessage.setText(displayText);
            tvTimestamp.setText(TIME_FORMAT.format(new Date(msg.getTimestamp())));

            if (tvSender != null) {
                tvSender.setText(msg.getSenderName());
            }

            // Show "Open File" button on received FILE messages once the file is saved
            if (btnOpenFile != null) {
                Uri savedUri = msg.getSavedUri();
                if (msg.getType() == ChatMessage.Type.FILE && savedUri != null) {
                    btnOpenFile.setVisibility(View.VISIBLE);
                    String mimeType = msg.getFileMetadata() != null
                            ? msg.getFileMetadata().getMimeType() : "*/*";
                    btnOpenFile.setOnClickListener(v -> openFile(savedUri, mimeType));
                } else {
                    btnOpenFile.setVisibility(View.GONE);
                }
            }

            // Show "Open in Maps" button for LOCATION messages
            if (btnOpenMap != null) {
                if (msg.getType() == ChatMessage.Type.LOCATION) {
                    btnOpenMap.setVisibility(View.VISIBLE);
                    btnOpenMap.setOnClickListener(v -> openInMaps(msg.getLatitude(), msg.getLongitude()));
                } else {
                    btnOpenMap.setVisibility(View.GONE);
                }
            }
        }

        private void openFile(Uri uri, String mimeType) {
            Uri uriToOpen = uri;
            if ("file".equals(uri.getScheme())) {
                uriToOpen = FileProvider.getUriForFile(
                        itemView.getContext(),
                        itemView.getContext().getPackageName() + ".fileprovider",
                        new File(uri.getPath()));
            }
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uriToOpen, mimeType != null ? mimeType : "*/*");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            try {
                itemView.getContext().startActivity(
                        Intent.createChooser(intent,
                                itemView.getContext().getString(R.string.btn_open_file)));
            } catch (ActivityNotFoundException e) {
                Toast.makeText(itemView.getContext(),
                        R.string.no_app_for_file, Toast.LENGTH_SHORT).show();
            }
        }

        private void openInMaps(double lat, double lng) {
            Intent intent = new Intent(itemView.getContext(),
                    com.example.csci3310_airdrop_proj.ui.MapActivity.class);
            intent.putExtra(com.example.csci3310_airdrop_proj.ui.MapActivity.EXTRA_LAT, lat);
            intent.putExtra(com.example.csci3310_airdrop_proj.ui.MapActivity.EXTRA_LNG, lng);
            itemView.getContext().startActivity(intent);
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
