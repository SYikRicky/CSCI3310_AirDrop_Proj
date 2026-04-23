package com.example.csci3310_airdrop_proj.repository;

import android.content.ContentResolver;
import android.net.Uri;

import com.example.csci3310_airdrop_proj.model.SharedFile;

/**
 * Abstraction over the shared-drive backend.
 *
 * The production implementation, {@link FirebaseSharedDriveRepository}, talks
 * to Firebase Cloud Storage and Firestore. Tests can substitute a fake
 * implementation without bringing up the Firebase emulator. The UI layer
 * (Fragments, Activity) references only this interface.
 *
 * Callback interfaces are nested so existing call sites of the shape
 * {@code SharedDriveRepository.UploadCallback} keep compiling unchanged.
 */
public interface SharedDriveRepository {

    /** Cancellable subscription token. Callers must invoke {@link #remove()} to stop. */
    interface Registration {
        void remove();
    }

    interface UploadCallback {
        void onProgress(int percent);
        void onSuccess(SharedFile file);
        void onFailure(Exception e);
    }

    interface FilesListener {
        void onFilesChanged(java.util.List<SharedFile> files);
        void onError(Exception e);
    }

    interface DeleteCallback {
        void onSuccess();
        void onFailure(Exception e);
    }

    /** Upload a file and, on success, write its metadata record. */
    void uploadFile(Uri fileUri, String fileName, String mimeType,
                    long fileSize, ContentResolver resolver, UploadCallback callback);

    /**
     * Subscribe to real-time updates of the shared file list. Callers must
     * hold the returned {@link Registration} and remove it when the
     * subscriber goes away (e.g. {@code Fragment.onDestroyView()}).
     */
    Registration listenToFiles(FilesListener listener);

    /** Delete a file from the backend (both bytes and metadata record). */
    void deleteFile(SharedFile file, DeleteCallback callback);
}
