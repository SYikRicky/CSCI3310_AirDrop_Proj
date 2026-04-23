package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatViewHolder;

import java.io.File;
import java.util.Locale;

/**
 * Renders a generic {@link FileMessage} — one that isn't an image or voice
 * clip. Shows the filename with a paperclip emoji and a size hint, plus an
 * Open button on the received side that hands the file off to another app
 * via {@link Intent#ACTION_VIEW}.
 *
 * Registered after {@link ImageRenderer} and {@link VoiceRenderer} so those
 * take precedence for their specific MIME types.
 */
public final class FileRenderer implements MessageRenderer {

    private static final String FILE_LABEL_EMOJI = "📎 ";

    @Override
    public boolean canRender(ChatMessage msg) {
        return msg instanceof FileMessage;
    }

    @Override
    public void render(ChatViewHolder holder, ChatMessage msg) {
        FileMessage fm = (FileMessage) msg;
        FileMetadata meta = fm.getFileMetadata();
        long size = meta != null ? meta.getFileSize() : 0;

        holder.setBodyText(FILE_LABEL_EMOJI + fm.getText() + " (" + formatSize(size) + ")");

        Uri saved = fm.getSavedUri();
        if (saved != null) {
            String mime = meta != null ? meta.getMimeType() : "*/*";
            holder.showOpenFileButton(v -> openFile(holder.getContext(), saved, mime));
        } else {
            holder.hideOpenFileButton();
        }
        holder.hideImage();
        holder.hidePlayButton();
        holder.hideMapButton();
    }

    private static void openFile(Context context, Uri uri, String mimeType) {
        Uri uriToOpen = uri;
        if ("file".equals(uri.getScheme())) {
            uriToOpen = FileProvider.getUriForFile(
                    context,
                    context.getPackageName() + ".fileprovider",
                    new File(uri.getPath()));
        }
        Intent intent = new Intent(Intent.ACTION_VIEW);
        intent.setDataAndType(uriToOpen, mimeType != null ? mimeType : "*/*");
        intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try {
            context.startActivity(Intent.createChooser(intent,
                    context.getString(R.string.btn_open_file)));
        } catch (ActivityNotFoundException e) {
            Toast.makeText(context, R.string.no_app_for_file, Toast.LENGTH_SHORT).show();
        }
    }

    private static String formatSize(long bytes) {
        if (bytes < 1024)            return bytes + " B";
        if (bytes < 1024 * 1024)     return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
    }
}
