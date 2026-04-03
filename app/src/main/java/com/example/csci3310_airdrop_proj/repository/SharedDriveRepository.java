package com.example.csci3310_airdrop_proj.repository;

import android.content.ContentResolver;
import android.net.Uri;
import android.os.Build;
import android.util.Log;

import com.example.csci3310_airdrop_proj.model.SharedFile;
import com.google.firebase.firestore.FirebaseFirestore;
import com.google.firebase.firestore.ListenerRegistration;
import com.google.firebase.firestore.Query;
import com.google.firebase.storage.FirebaseStorage;
import com.google.firebase.storage.StorageReference;
import com.google.firebase.storage.UploadTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Single source of truth for all Firebase operations.
 *
 * Firebase structure:
 *  Firestore:
 *    Collection "shared_files"  — one document per uploaded file
 *  Storage:
 *    Bucket path "files/<uuid>_<fileName>"  — the actual file bytes
 *
 * Key design decisions:
 *  - Upload is two-phase: Storage upload first, then Firestore metadata write.
 *    This ensures downloadUrl is available before the record is visible.
 *  - Firestore snapshot listener fires on every change (add/delete) in real-time.
 *    No polling needed — Firestore pushes updates automatically.
 *  - ListenerRegistration is returned from listenToFiles() so the caller
 *    (FileListFragment) can remove the listener in onDestroyView() to avoid leaks.
 */
public class SharedDriveRepository {

    private static final String TAG            = "SharedDriveRepo";
    private static final String COLLECTION     = "shared_files";
    private static final String STORAGE_PREFIX = "files/";

    private final FirebaseFirestore db;
    private final FirebaseStorage   storage;

    // ── Callback interfaces ───────────────────────────────────────────────────

    public interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(SharedFile file);
        void onFailure(Exception e);
    }

    public interface FilesListener {
        void onFilesChanged(List<SharedFile> files);
        void onError(Exception e);
    }

    public interface DeleteCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    // ─────────────────────────────────────────────────────────────────────────

    public SharedDriveRepository() {
        db      = FirebaseFirestore.getInstance();
        storage = FirebaseStorage.getInstance();
    }

    // ── Upload ────────────────────────────────────────────────────────────────

    /**
     * Upload a file to Firebase Storage, then write its metadata to Firestore.
     *
     * @param fileUri  Content URI returned by the file picker
     * @param fileName Display name of the file
     * @param mimeType MIME type (e.g. "image/jpeg")
     * @param fileSize File size in bytes
     * @param resolver ContentResolver to open the URI as an InputStream
     * @param callback Progress/success/failure callbacks (called on main thread by Firebase)
     */
    public void uploadFile(Uri fileUri, String fileName, String mimeType,
                           long fileSize, ContentResolver resolver, UploadCallback callback) {

        // Unique storage path prevents collisions if two users upload same filename
        String storagePath = STORAGE_PREFIX + UUID.randomUUID().toString() + "_" + fileName;
        StorageReference ref = storage.getReference().child(storagePath);

        UploadTask uploadTask = ref.putFile(fileUri);

        uploadTask.addOnProgressListener(snapshot -> {
            long total = snapshot.getTotalByteCount();
            long done  = snapshot.getBytesTransferred();
            int  pct   = total > 0 ? (int) (done * 100L / total) : 0;
            callback.onProgress(pct);
        });

        uploadTask.addOnSuccessListener(snapshot ->
            // Step 2: get the public download URL from Storage
            ref.getDownloadUrl().addOnSuccessListener(downloadUri -> {
                String url = downloadUri.toString();

                // Step 3: write metadata to Firestore
                Map<String, Object> doc = new HashMap<>();
                doc.put("fileName",    fileName);
                doc.put("mimeType",    mimeType != null ? mimeType : "*/*");
                doc.put("fileSize",    fileSize);
                doc.put("uploadedBy",  Build.MODEL);
                doc.put("downloadUrl", url);
                doc.put("storagePath", storagePath);
                doc.put("uploadedAt",  com.google.firebase.firestore.FieldValue.serverTimestamp());

                db.collection(COLLECTION)
                        .add(doc)
                        .addOnSuccessListener(docRef -> {
                            SharedFile file = new SharedFile(fileName, mimeType, fileSize,
                                    Build.MODEL, url, storagePath);
                            Log.d(TAG, "Uploaded: " + fileName + " → " + docRef.getId());
                            callback.onSuccess(file);
                        })
                        .addOnFailureListener(e -> {
                            Log.e(TAG, "Firestore write failed", e);
                            callback.onFailure(e);
                        });

            }).addOnFailureListener(e -> {
                Log.e(TAG, "getDownloadUrl failed", e);
                callback.onFailure(e);
            })
        );

        uploadTask.addOnFailureListener(e -> {
            Log.e(TAG, "Storage upload failed", e);
            callback.onFailure(e);
        });
    }

    // ── Real-time file list ───────────────────────────────────────────────────

    /**
     * Subscribe to real-time updates of the shared file list.
     * Firestore pushes a new snapshot whenever any document is added, modified, or deleted.
     *
     * Call {@link ListenerRegistration#remove()} in Fragment.onDestroyView() to unsubscribe.
     *
     * @return A ListenerRegistration the caller must remove to avoid memory leaks.
     */
    public ListenerRegistration listenToFiles(FilesListener listener) {
        return db.collection(COLLECTION)
                .orderBy("uploadedAt", Query.Direction.DESCENDING)
                .addSnapshotListener((snapshots, error) -> {
                    if (error != null) {
                        Log.e(TAG, "Firestore listen error", error);
                        listener.onError(error);
                        return;
                    }
                    if (snapshots != null) {
                        List<SharedFile> files = snapshots.toObjects(SharedFile.class);
                        Log.d(TAG, "Snapshot: " + files.size() + " files");
                        listener.onFilesChanged(files);
                    }
                });
    }

    // ── Delete ────────────────────────────────────────────────────────────────

    /**
     * Delete a file from both Firebase Storage and Firestore.
     * Both deletions are attempted; Firestore document is removed even if Storage fails.
     */
    public void deleteFile(SharedFile file, DeleteCallback callback) {
        // Delete from Storage first
        StorageReference ref = storage.getReference().child(file.getStoragePath());
        ref.delete()
                .addOnCompleteListener(storageTask -> {
                    if (!storageTask.isSuccessful()) {
                        Log.w(TAG, "Storage delete failed (continuing)", storageTask.getException());
                    }
                    // Always delete Firestore document regardless of Storage result
                    db.collection(COLLECTION)
                            .document(file.getFileId())
                            .delete()
                            .addOnSuccessListener(v -> {
                                Log.d(TAG, "Deleted: " + file.getFileName());
                                callback.onSuccess();
                            })
                            .addOnFailureListener(e -> {
                                Log.e(TAG, "Firestore delete failed", e);
                                callback.onFailure(e);
                            });
                });
    }
}
