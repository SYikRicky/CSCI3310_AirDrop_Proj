package com.example.csci3310_airdrop_proj.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;

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
        private final TextView tvSender; // only in received layout
        private final int viewType;

        ChatViewHolder(@NonNull View itemView, int viewType) {
            super(itemView);
            this.viewType = viewType;
            tvMessage = itemView.findViewById(R.id.tv_message);
            tvTimestamp = itemView.findViewById(R.id.tv_timestamp);
            tvSender = itemView.findViewById(R.id.tv_sender); // null for sent layout
        }

        void bind(ChatMessage msg) {
            String displayText;
            if (msg.getType() == ChatMessage.Type.FILE) {
                String fileName = msg.getText();
                if (msg.getFileMetadata() != null) {
                    long size = msg.getFileMetadata().getFileSize();
                    displayText = "📎 " + fileName + " (" + formatSize(size) + ")";
                } else {
                    displayText = "📎 " + fileName;
                }
            } else {
                displayText = msg.getText();
            }

            tvMessage.setText(displayText);
            tvTimestamp.setText(TIME_FORMAT.format(new Date(msg.getTimestamp())));

            if (tvSender != null) {
                tvSender.setText(msg.getSenderName());
            }
        }

        private String formatSize(long bytes) {
            if (bytes < 1024) return bytes + " B";
            if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
            return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        }
    }
}
