package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import android.app.Dialog;
import android.content.Context;
import android.net.Uri;
import android.view.Window;
import android.widget.ImageView;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatViewHolder;

/**
 * Renders a {@link FileMessage} whose MIME type begins with {@code image/}:
 * shows a thumbnail preview and opens a fullscreen dialog on tap.
 *
 * Must be registered before {@link FileRenderer} in the registry — otherwise
 * the generic file renderer would claim it first.
 */
public final class ImageRenderer implements MessageRenderer {

    private static final String IMAGE_LABEL_EMOJI = "🖼️ "; // 🖼️

    @Override
    public boolean canRender(ChatMessage msg) {
        if (!(msg instanceof FileMessage)) return false;
        FileMetadata meta = ((FileMessage) msg).getFileMetadata();
        if (meta == null || meta.getMimeType() == null) return false;
        return meta.getMimeType().startsWith("image/");
    }

    @Override
    public void render(ChatViewHolder holder, ChatMessage msg) {
        FileMessage fm = (FileMessage) msg;

        holder.setBodyText(IMAGE_LABEL_EMOJI + fm.getText());

        Uri uri = fm.getSavedUri();
        if (uri != null) {
            holder.showImage(uri, v -> showFullscreen(holder.getContext(), uri));
        } else {
            // File bytes haven't been saved yet (rare race condition).
            holder.hideImage();
        }
        holder.hidePlayButton();
        holder.hideOpenFileButton();
        holder.hideMapButton();
    }

    private void showFullscreen(Context context, Uri uri) {
        Dialog dialog = new Dialog(context, android.R.style.Theme_Black_NoTitleBar_Fullscreen);
        dialog.requestWindowFeature(Window.FEATURE_NO_TITLE);
        dialog.setContentView(R.layout.dialog_image_preview);
        ImageView iv = dialog.findViewById(R.id.iv_fullscreen);
        iv.setImageURI(uri);
        iv.setOnClickListener(v -> dialog.dismiss());
        dialog.show();
    }
}
