package com.example.csci3310_airdrop_proj.model;

import com.google.firebase.Timestamp;
import com.google.firebase.firestore.DocumentId;
import com.google.firebase.firestore.ServerTimestamp;

/**
 * Represents a file entry in the shared Firestore collection.
 *
 * Firestore document structure (collection: "shared_files"):
 * {
 *   "fileName":    "photo.jpg",
 *   "mimeType":    "image/jpeg",
 *   "fileSize":    204800,
 *   "uploadedBy":  "Pixel 7",
 *   "downloadUrl": "https://firebasestorage.googleapis.com/...",
 *   "storagePath": "files/uuid_photo.jpg",
 *   "uploadedAt":  <Firestore Server Timestamp>
 * }
 *
 * The @DocumentId annotation tells Firestore to populate 'fileId'
 * automatically from the document's own ID — no manual mapping needed.
 */
public class SharedFile {

    @DocumentId
    private String fileId;

    private String    fileName;
    private String    mimeType;
    private long      fileSize;
    private String    uploadedBy;
    private String    downloadUrl;
    private String    storagePath;   // Firebase Storage path — needed for deletion

    @ServerTimestamp
    private Timestamp uploadedAt;

    /** Required no-arg constructor for Firestore deserialization. */
    public SharedFile() {}

    public SharedFile(String fileName, String mimeType, long fileSize,
                      String uploadedBy, String downloadUrl, String storagePath) {
        this.fileName    = fileName;
        this.mimeType    = mimeType;
        this.fileSize    = fileSize;
        this.uploadedBy  = uploadedBy;
        this.downloadUrl = downloadUrl;
        this.storagePath = storagePath;
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String    getFileId()      { return fileId; }
    public String    getFileName()    { return fileName; }
    public String    getMimeType()    { return mimeType; }
    public long      getFileSize()    { return fileSize; }
    public String    getUploadedBy()  { return uploadedBy; }
    public String    getDownloadUrl() { return downloadUrl; }
    public String    getStoragePath() { return storagePath; }
    public Timestamp getUploadedAt()  { return uploadedAt; }

    /** Human-readable file size (e.g. "1.4 MB"). */
    public String getFileSizeFormatted() {
        if (fileSize < 1024)                  return fileSize + " B";
        if (fileSize < 1024 * 1024)           return String.format("%.1f KB", fileSize / 1024.0);
        if (fileSize < 1024 * 1024 * 1024)    return String.format("%.1f MB", fileSize / (1024.0 * 1024));
        return String.format("%.1f GB", fileSize / (1024.0 * 1024 * 1024));
    }
}
