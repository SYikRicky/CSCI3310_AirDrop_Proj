package com.example.csci3310_airdrop_proj.ui.adapter;

import android.app.Dialog;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.ImageView;
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
 * Supports text, file, location, and voice (audio/mp4) messages.
 * Two view types: sent (right-aligned) and received (left-aligned).
 */
public class ChatAdapter extends RecyclerView.Adapter<ChatAdapter.ChatViewHolder> {

    private static final int VIEW_TYPE_SENT     = 0;
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
     * and attaches the saved URI, triggering a rebind to show the Open / Play button.
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

        private final TextView  tvMessage;
        private final TextView  tvTimestamp;
        private final TextView  tvSender;       // only in received layout
        private final View      btnOpenFile;    // only in received layout — non-audio, non-image files
        private final View      btnOpenMap;     // both layouts — LOCATION messages
        private final View      btnPlayVoice;   // both layouts — audio/mp4 voice messages
        private final ImageView ivImagePreview; // both layouts — inline image thumbnail
        private final int       viewType;

        ChatViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            this.viewType   = viewType;
            tvMessage       = itemView.findViewById(R.id.tv_message);
            tvTimestamp     = itemView.findViewById(R.id.tv_timestamp);
            tvSender        = itemView.findViewById(R.id.tv_sender);
            btnOpenFile     = itemView.findViewById(R.id.btn_open_file);
            btnOpenMap      = itemView.findViewById(R.id.btn_open_map);
            btnPlayVoice    = itemView.findViewById(R.id.btn_play_voice);
            ivImagePreview  = itemView.findViewById(R.id.iv_image_preview);
        }

        void bind(ChatMessage msg) {
            String mimeType = msg.getFileMetadata() != null
                    ? msg.getFileMetadata().getMimeType() : null;
            boolean isVoice = msg.getType() == ChatMessage.Type.FILE
                    && mimeType != null && mimeType.startsWith("audio/");
            boolean isImage = msg.getType() == ChatMessage.Type.FILE
                    && mimeType != null && mimeType.startsWith("image/");

            // ── Display text ─────────────────────────────────────────────────
            String displayText;
            if (isVoice) {
                displayText = itemView.getContext().getString(R.string.chat_voice_message);
            } else if (isImage) {
                // Show only the filename; thumbnail appears below
                displayText = "\uD83D\uDDBC\uFE0F " + msg.getText();
            } else if (msg.getType() == ChatMessage.Type.FILE) {
                long size = msg.getFileMetadata() != null
                        ? msg.getFileMetadata().getFileSize() : 0;
                displayText = "\uD83D\uDCCE " + msg.getText() + " (" + formatSize(size) + ")";
            } else if (msg.getType() == ChatMessage.Type.LOCATION) {
                displayText = "\uD83D\uDCCD "
                        + itemView.getContext().getString(R.string.location_shared)
                        + "\n" + String.format(Locale.US, "%.6f, %.6f",
                            msg.getLatitude(), msg.getLongitude());
            } else {
                displayText = msg.getText();
            }

            tvMessage.setText(displayText);
            tvTimestamp.setText(TIME_FORMAT.format(new Date(msg.getTimestamp())));
            if (tvSender != null) tvSender.setText(msg.getSenderName());

            // ── Inline image thumbnail (both layouts) ────────────────────────
            if (ivImagePreview != null) {
                Uri imgUri = msg.getSavedUri();
                if (isImage && imgUri != null) {
                    ivImagePreview.setVisibility(View.VISIBLE);
                    ivImagePreview.setImageURI(imgUri);
                    ivImagePreview.setOnClickListener(v -> showFullscreenImage(imgUri));
                } else {
                    ivImagePreview.setVisibility(View.GONE);
                    ivImagePreview.setImageURI(null);
                }
            }

            // ── Voice play button (both layouts) ─────────────────────────────
            if (btnPlayVoice != null) {
                Uri playUri = msg.getSavedUri();
                if (isVoice && playUri != null) {
                    btnPlayVoice.setVisibility(View.VISIBLE);
                    btnPlayVoice.setOnClickListener(v -> playAudio(playUri));
                } else {
                    btnPlayVoice.setVisibility(View.GONE);
                }
            }

            // ── Open File button (received layout only, non-audio, non-image files) ──
            if (btnOpenFile != null) {
                Uri savedUri = msg.getSavedUri();
                if (!isVoice && !isImage && msg.getType() == ChatMessage.Type.FILE && savedUri != null) {
                    btnOpenFile.setVisibility(View.VISIBLE);
                    btnOpenFile.setOnClickListener(v -> openFile(savedUri, mimeType));
                } else {
                    btnOpenFile.setVisibility(View.GONE);
                }
            }

            // ── Open in Maps button (both layouts, LOCATION only) ─────────────
            if (btnOpenMap != null) {
                if (msg.getType() == ChatMessage.Type.LOCATION) {
                    btnOpenMap.setVisibility(View.VISIBLE);
                    btnOpenMap.setOnClickListener(
                            v -> openInMaps(msg.getLatitude(), msg.getLongitude()));
                } else {
                    btnOpenMap.setVisibility(View.GONE);
                }
            }
        }

        // ── Playback ──────────────────────────────────────────────────────────

        private void playAudio(Uri uri) {
            try {
                MediaPlayer player = new MediaPlayer();
                player.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build());
                player.setDataSource(itemView.getContext(), uri);
                player.setOnPreparedListener(MediaPlayer::start);
                player.setOnCompletionListener(MediaPlayer::release);
                player.setOnErrorListener((mp, what, extra) -> {
                    mp.release();
                    Toast.makeText(itemView.getContext(),
                            "Cannot play voice message", Toast.LENGTH_SHORT).show();
                    return true;
                });
                player.prepareAsync();
            } catch (Exception e) {
                Toast.makeText(itemView.getContext(),
                        "Cannot play voice message", Toast.LENGTH_SHORT).show();
            }
        }

        // ── Image preview ─────────────────────────────────────────────────────

        private void showFullscreenImage(Uri uri) {
            Dialog dialog = new Dialog(itemView.getContext(),
                    android.R.style.Theme_Black_NoTitleBar_Fullscreen);
            dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
            dialog.setContentView(R.layout.dialog_image_preview);
            ImageView iv = dialog.findViewById(R.id.iv_fullscreen);
            iv.setImageURI(uri);
            iv.setOnClickListener(v -> dialog.dismiss());
            dialog.show();
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
