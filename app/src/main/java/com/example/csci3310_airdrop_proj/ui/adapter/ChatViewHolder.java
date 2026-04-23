package com.example.csci3310_airdrop_proj.ui.adapter;

import android.content.Context;
import android.net.Uri;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/**
 * Renderer-facing view wrapper for a single chat bubble.
 *
 * Instead of exposing each {@code findViewById} field to renderers, this class
 * offers intent-level methods like {@link #showImage}, {@link #showPlayButton}.
 * Each renderer manipulates only what it cares about; the class handles the
 * widget plumbing (visibility toggles, null-layout guards).
 *
 * Layout expectations — {@code item_chat_message_sent.xml} /
 * {@code item_chat_message_received.xml} contain all the view IDs below;
 * some widgets (like {@code tv_sender}) exist only on the received layout.
 * Nulls are handled gracefully.
 */
public final class ChatViewHolder extends RecyclerView.ViewHolder {

    private static final SimpleDateFormat TIME_FORMAT =
            new SimpleDateFormat("HH:mm", Locale.getDefault());

    private final TextView  tvMessage;
    private final TextView  tvTimestamp;
    private final TextView  tvSender;       // received layout only
    private final View      btnOpenFile;    // received layout only
    private final View      btnOpenMap;
    private final View      btnPlayVoice;
    private final ImageView ivImagePreview;

    public ChatViewHolder(@NonNull View itemView) {
        super(itemView);
        tvMessage       = itemView.findViewById(R.id.tv_message);
        tvTimestamp     = itemView.findViewById(R.id.tv_timestamp);
        tvSender        = itemView.findViewById(R.id.tv_sender);
        btnOpenFile     = itemView.findViewById(R.id.btn_open_file);
        btnOpenMap      = itemView.findViewById(R.id.btn_open_map);
        btnPlayVoice    = itemView.findViewById(R.id.btn_play_voice);
        ivImagePreview  = itemView.findViewById(R.id.iv_image_preview);
    }

    public Context getContext() { return itemView.getContext(); }

    // ── Common header ────────────────────────────────────────────────────────

    public void setSenderName(String senderName) {
        if (tvSender != null) tvSender.setText(senderName);
    }

    public void setTimestamp(long timestamp) {
        tvTimestamp.setText(TIME_FORMAT.format(new Date(timestamp)));
    }

    // ── Body text ────────────────────────────────────────────────────────────

    public void setBodyText(CharSequence text) {
        tvMessage.setText(text);
    }

    // ── Inline image preview ─────────────────────────────────────────────────

    public void showImage(Uri uri, View.OnClickListener onClick) {
        if (ivImagePreview == null) return;
        ivImagePreview.setVisibility(View.VISIBLE);
        ivImagePreview.setImageURI(uri);
        ivImagePreview.setOnClickListener(onClick);
    }

    public void hideImage() {
        if (ivImagePreview == null) return;
        ivImagePreview.setVisibility(View.GONE);
        ivImagePreview.setImageURI(null);
        ivImagePreview.setOnClickListener(null);
    }

    // ── Voice play button ────────────────────────────────────────────────────

    public void showPlayButton(View.OnClickListener onClick) {
        if (btnPlayVoice == null) return;
        btnPlayVoice.setVisibility(View.VISIBLE);
        btnPlayVoice.setOnClickListener(onClick);
    }

    public void hidePlayButton() {
        if (btnPlayVoice == null) return;
        btnPlayVoice.setVisibility(View.GONE);
        btnPlayVoice.setOnClickListener(null);
    }

    // ── Open file button (received layout only) ─────────────────────────────

    public void showOpenFileButton(View.OnClickListener onClick) {
        if (btnOpenFile == null) return;
        btnOpenFile.setVisibility(View.VISIBLE);
        btnOpenFile.setOnClickListener(onClick);
    }

    public void hideOpenFileButton() {
        if (btnOpenFile == null) return;
        btnOpenFile.setVisibility(View.GONE);
        btnOpenFile.setOnClickListener(null);
    }

    // ── Map button ───────────────────────────────────────────────────────────

    public void showMapButton(View.OnClickListener onClick) {
        if (btnOpenMap == null) return;
        btnOpenMap.setVisibility(View.VISIBLE);
        btnOpenMap.setOnClickListener(onClick);
    }

    public void hideMapButton() {
        if (btnOpenMap == null) return;
        btnOpenMap.setVisibility(View.GONE);
        btnOpenMap.setOnClickListener(null);
    }

    /** Convenience: hide every extra widget. Renderers call this then show only what they need. */
    public void hideAllExtras() {
        hideImage();
        hidePlayButton();
        hideOpenFileButton();
        hideMapButton();
    }
}
