package com.example.csci3310_airdrop_proj.model;

import android.net.Uri;

/**
 * A chat message that carries a file transfer — image, voice clip, or
 * arbitrary attachment. {@link #getText()} returns the filename, so the
 * default bubble label reads correctly in adapters that don't treat files
 * specially.
 *
 * {@link #savedUri} is populated only after the file contents have been
 * written to local storage (see {@code NearbyConnectionsManager.IncomingPayloadSink}).
 * Until then it is {@code null} and the bubble shows the emoji/name label
 * without a preview or play button.
 */
public final class FileMessage extends ChatMessage {

    private final FileMetadata meta;
    private Uri    savedUri;
    private int    transferProgress; // 0..100

    public FileMessage(String senderName, long timestamp, boolean outgoing,
                       FileMetadata meta) {
        super(senderName, timestamp, outgoing);
        this.meta = meta;
    }

    public FileMetadata getFileMetadata() { return meta; }

    public Uri  getSavedUri()              { return savedUri; }
    public void setSavedUri(Uri savedUri)  { this.savedUri = savedUri; }

    public int  getTransferProgress()      { return transferProgress; }
    public void setTransferProgress(int p) { this.transferProgress = p; }

    @Override public Type   getType() { return Type.FILE; }
    @Override public String getText() {
        return meta != null ? meta.getFileName() : "";
    }
}
