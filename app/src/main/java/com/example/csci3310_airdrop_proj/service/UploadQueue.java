package com.example.csci3310_airdrop_proj.service;

import android.os.Handler;
import android.os.Looper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Process-wide tracker of in-flight uploads to the Shared Drive.
 *
 * {@link FileUploadService} writes to this queue (add / progress / success /
 * failure). The Drive {@code FileListFragment} reads from it via
 * {@link Listener} so a "pending" row can sit at the top of the file list
 * until Firestore's snapshot listener publishes the real document.
 *
 * State is held in-memory in a singleton. If the process is killed, the queue
 * is lost — but Firebase's {@code UploadTask} keeps its own robustness, and
 * any upload it completed before the kill still shows up via Firestore on
 * next launch.
 *
 * Thread-safety: all mutations run on whichever thread calls them, but
 * listeners are always invoked on the main thread.
 */
public final class UploadQueue {

    // ── Singleton ────────────────────────────────────────────────────────────

    private static final UploadQueue INSTANCE = new UploadQueue();
    public static UploadQueue get() { return INSTANCE; }
    private UploadQueue() {}

    // ── Model ────────────────────────────────────────────────────────────────

    /**
     * One row in the queue. Fields mutate during upload; the queue fires
     * {@link Listener#onQueueChanged(List)} after every change so observers
     * can redraw. Treat snapshots as immutable — never mutate the instances
     * a listener receives.
     */
    public static final class PendingUpload {
        public final String id;
        public final String fileName;
        public final String mimeType;
        public final long   fileSize;
        public int          progress;   // 0..100
        public boolean      failed;
        public String       errorMessage;

        PendingUpload(String id, String fileName, String mimeType, long fileSize) {
            this.id = id;
            this.fileName = fileName;
            this.mimeType = mimeType;
            this.fileSize = fileSize;
        }

        /** Human-readable size ("1.4 MB", "820 KB", "312 B"). */
        public String getFileSizeFormatted() {
            if (fileSize < 1024) return fileSize + " B";
            if (fileSize < 1024 * 1024) {
                return String.format(Locale.US, "%.1f KB", fileSize / 1024.0);
            }
            return String.format(Locale.US, "%.1f MB", fileSize / (1024.0 * 1024));
        }
    }

    public interface Listener {
        void onQueueChanged(List<PendingUpload> uploads);
    }

    // ── State ────────────────────────────────────────────────────────────────

    /** Insertion-ordered so newer uploads show first. */
    private final Map<String, PendingUpload> inflight = new LinkedHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private final Handler mainHandler = new Handler(Looper.getMainLooper());

    // ── Producer API (called from the service) ───────────────────────────────

    /** Register a new upload. Returns the generated id used for subsequent updates. */
    public synchronized String enqueue(String fileName, String mimeType, long fileSize) {
        String id = UUID.randomUUID().toString();
        inflight.put(id, new PendingUpload(id, fileName, mimeType, fileSize));
        fireChanged();
        return id;
    }

    public synchronized void updateProgress(String id, int percent) {
        PendingUpload u = inflight.get(id);
        if (u == null) return;
        u.progress = Math.max(0, Math.min(100, percent));
        fireChanged();
    }

    public synchronized void markSuccess(String id) {
        if (inflight.remove(id) != null) fireChanged();
    }

    /**
     * Mark an upload as failed. The row stays visible for {@code
     * autoRemoveAfterMs} so the user sees what went wrong, then is removed
     * automatically.
     */
    public synchronized void markFailure(String id, String errorMessage) {
        PendingUpload u = inflight.get(id);
        if (u == null) return;
        u.failed = true;
        u.errorMessage = errorMessage;
        fireChanged();

        // Auto-remove failed rows after 5 s so the list doesn't grow stale.
        final String idToRemove = id;
        mainHandler.postDelayed(() -> {
            synchronized (UploadQueue.this) {
                if (inflight.remove(idToRemove) != null) fireChanged();
            }
        }, 5_000);
    }

    // ── Consumer API (called from the fragment) ──────────────────────────────

    public void addListener(Listener l) {
        listeners.add(l);
        // Prime the listener with the current state.
        List<PendingUpload> snap = snapshot();
        mainHandler.post(() -> l.onQueueChanged(snap));
    }

    public void removeListener(Listener l) {
        listeners.remove(l);
    }

    public synchronized List<PendingUpload> snapshot() {
        return new ArrayList<>(inflight.values());
    }

    // ── Internals ────────────────────────────────────────────────────────────

    private void fireChanged() {
        final List<PendingUpload> snap = snapshot();
        mainHandler.post(() -> {
            for (Listener l : listeners) l.onQueueChanged(snap);
        });
    }
}
