package com.example.csci3310_airdrop_proj.model;

import android.os.Parcel;
import android.os.Parcelable;

import java.nio.charset.StandardCharsets;

/**
 * Metadata about a file being transferred.
 * Implements Parcelable for passing via Intents (e.g. to FileTransferService).
 * Also provides toBytes()/fromBytes() for sending over Nearby Connections as a bytes payload.
 */
public class FileMetadata implements Parcelable {

    private final String fileName;
    private final String mimeType;
    private final long fileSize;

    public FileMetadata(String fileName, String mimeType, long fileSize) {
        this.fileName = fileName;
        this.mimeType = mimeType != null ? mimeType : "*/*";
        this.fileSize = fileSize;
    }

    public String getFileName() { return fileName; }
    public String getMimeType() { return mimeType; }
    public long getFileSize() { return fileSize; }

    // ─── Wire format: "fileName|mimeType|fileSize" ────────────────────────────

    /**
     * Serialize to a UTF-8 byte array for sending as a Nearby Connections bytes payload.
     * Format: "fileName|mimeType|fileSize"
     */
    public byte[] toBytes() {
        String encoded = fileName + "|" + mimeType + "|" + fileSize;
        return encoded.getBytes(StandardCharsets.UTF_8);
    }

    /**
     * Deserialize from a byte array received as a Nearby Connections bytes payload.
     */
    public static FileMetadata fromBytes(byte[] bytes) {
        if (bytes == null) return new FileMetadata("file", "*/*", 0);
        String s = new String(bytes, StandardCharsets.UTF_8);
        String[] parts = s.split("\\|", 3);
        if (parts.length == 3) {
            try {
                return new FileMetadata(parts[0], parts[1], Long.parseLong(parts[2]));
            } catch (NumberFormatException ignored) {
            }
        }
        return new FileMetadata(s, "*/*", 0);
    }

    // ─── Parcelable ───────────────────────────────────────────────────────────

    protected FileMetadata(Parcel in) {
        fileName = in.readString();
        mimeType = in.readString();
        fileSize = in.readLong();
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(fileName);
        dest.writeString(mimeType);
        dest.writeLong(fileSize);
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<FileMetadata> CREATOR = new Creator<FileMetadata>() {
        @Override
        public FileMetadata createFromParcel(Parcel in) { return new FileMetadata(in); }
        @Override
        public FileMetadata[] newArray(int size) { return new FileMetadata[size]; }
    };

    @Override
    public String toString() {
        return "FileMetadata{name='" + fileName + "', mime='" + mimeType + "', size=" + fileSize + "}";
    }
}
