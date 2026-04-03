package com.example.csci3310_airdrop_proj.model;

import android.os.Parcel;
import android.os.Parcelable;

/**
 * Snapshot of a file transfer's current progress.
 * Implements Parcelable for passing via LocalBroadcast Intents.
 */
public class TransferProgress implements Parcelable {

    public enum Status { PENDING, IN_PROGRESS, DONE, FAILED }

    private final String fileName;
    private long bytesTransferred;
    private final long totalBytes;
    private Status status;
    /** True if this device is sending, false if receiving. */
    private final boolean isSending;

    public TransferProgress(String fileName, long totalBytes, boolean isSending) {
        this.fileName = fileName;
        this.totalBytes = totalBytes;
        this.bytesTransferred = 0;
        this.status = Status.PENDING;
        this.isSending = isSending;
    }

    // ─── Getters / setters ────────────────────────────────────────────────────

    public String getFileName() { return fileName; }
    public long getBytesTransferred() { return bytesTransferred; }
    public long getTotalBytes() { return totalBytes; }
    public Status getStatus() { return status; }
    public boolean isSending() { return isSending; }

    public void setBytesTransferred(long bytes) { this.bytesTransferred = bytes; }
    public void setStatus(Status status) { this.status = status; }

    /** Returns 0–100 progress percentage. */
    public int getProgressPercent() {
        if (totalBytes <= 0) return 0;
        return (int) Math.min(100L, bytesTransferred * 100L / totalBytes);
    }

    // ─── Parcelable ───────────────────────────────────────────────────────────

    protected TransferProgress(Parcel in) {
        fileName = in.readString();
        bytesTransferred = in.readLong();
        totalBytes = in.readLong();
        status = Status.values()[in.readInt()];
        isSending = in.readByte() != 0;
    }

    @Override
    public void writeToParcel(Parcel dest, int flags) {
        dest.writeString(fileName);
        dest.writeLong(bytesTransferred);
        dest.writeLong(totalBytes);
        dest.writeInt(status.ordinal());
        dest.writeByte((byte) (isSending ? 1 : 0));
    }

    @Override
    public int describeContents() { return 0; }

    public static final Creator<TransferProgress> CREATOR = new Creator<TransferProgress>() {
        @Override
        public TransferProgress createFromParcel(Parcel in) { return new TransferProgress(in); }
        @Override
        public TransferProgress[] newArray(int size) { return new TransferProgress[size]; }
    };
}
