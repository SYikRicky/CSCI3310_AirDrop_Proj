package com.example.csci3310_airdrop_proj.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists per-peer chat history using SharedPreferences + JSON.
 * Keyed by device name (persistent across sessions, unlike ephemeral endpointId).
 *
 * Serialises all message fields including FileMetadata (mimeType, fileName, fileSize)
 * and savedUri so that image thumbnails, voice Play buttons, and emoji labels survive
 * across chat-room reopens and app restarts.
 */
public class ChatHistoryManager {

    private static final String PREFS_NAME   = "chat_history";
    private static final String KEY_PEERS    = "peers";
    private static final int    MAX_MESSAGES = 200; // per peer

    private final SharedPreferences prefs;

    public ChatHistoryManager(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    /** Append a message for the given peer device name and persist. */
    public void saveMessage(String peerDeviceName, ChatMessage msg) {
        List<ChatMessage> history = getHistory(peerDeviceName);
        history.add(msg);
        if (history.size() > MAX_MESSAGES) {
            history = new ArrayList<>(history.subList(history.size() - MAX_MESSAGES, history.size()));
        }
        writeHistory(peerDeviceName, history);
        addPeer(peerDeviceName);
    }

    /**
     * Update the savedUri for the most recent received FILE message whose text
     * (filename) matches {@code fileName} and that has no savedUri yet.
     * Called when a file transfer completes and the file has been cached locally.
     */
    public void updateFileUri(String peerDeviceName, String fileName, Uri uri) {
        List<ChatMessage> history = getHistory(peerDeviceName);
        boolean changed = false;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg.getType() == ChatMessage.Type.FILE
                    && !msg.isOutgoing()
                    && fileName.equals(msg.getText())
                    && msg.getSavedUri() == null) {
                msg.setSavedUri(uri);
                changed = true;
                break;
            }
        }
        if (changed) writeHistory(peerDeviceName, history);
    }

    /** Load the full message history for a peer. Returns empty list if none. */
    public List<ChatMessage> getHistory(String peerDeviceName) {
        String json = prefs.getString("history_" + peerDeviceName, null);
        List<ChatMessage> messages = new ArrayList<>();
        if (json == null) return messages;
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                ChatMessage.Type type = ChatMessage.Type.valueOf(obj.getString("type"));
                String  senderName = obj.getString("senderName");
                String  text       = obj.getString("text");
                long    timestamp  = obj.getLong("timestamp");
                boolean outgoing   = obj.getBoolean("outgoing");

                ChatMessage msg;
                if (type == ChatMessage.Type.LOCATION) {
                    double lat = obj.optDouble("latitude", 0);
                    double lng = obj.optDouble("longitude", 0);
                    msg = ChatMessage.createLocation(senderName, lat, lng, timestamp, outgoing);
                } else {
                    msg = new ChatMessage(type, senderName, text, timestamp, outgoing);
                }

                // Restore FileMetadata for FILE messages (voice/image emoji labels)
                if (type == ChatMessage.Type.FILE && obj.has("fm_name")) {
                    msg.setFileMetadata(new FileMetadata(
                            obj.getString("fm_name"),
                            obj.optString("fm_mime", "*/*"),
                            obj.optLong("fm_size", 0)));
                }

                // Restore savedUri for FILE messages (play / preview buttons)
                if (type == ChatMessage.Type.FILE && obj.has("savedUri")) {
                    msg.setSavedUri(Uri.parse(obj.getString("savedUri")));
                }

                messages.add(msg);
            }
        } catch (Exception ignored) { /* corrupted prefs — return partial */ }
        return messages;
    }

    /** Return all peer device names that have stored history, newest first. */
    public List<String> getAllPeers() {
        Set<String> set = prefs.getStringSet(KEY_PEERS, new HashSet<>());
        return new ArrayList<>(set);
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    private void writeHistory(String peerDeviceName, List<ChatMessage> messages) {
        try {
            JSONArray arr = new JSONArray();
            for (ChatMessage msg : messages) {
                JSONObject obj = new JSONObject();
                obj.put("type",       msg.getType().name());
                obj.put("senderName", msg.getSenderName());
                obj.put("text",       msg.getText());
                obj.put("timestamp",  msg.getTimestamp());
                obj.put("outgoing",   msg.isOutgoing());

                if (msg.getType() == ChatMessage.Type.LOCATION) {
                    obj.put("latitude",  msg.getLatitude());
                    obj.put("longitude", msg.getLongitude());
                }

                // Persist FileMetadata so mimeType is available after reload
                // (needed for voice/image emoji labels and Play/preview buttons)
                if (msg.getType() == ChatMessage.Type.FILE) {
                    FileMetadata fm = msg.getFileMetadata();
                    if (fm != null) {
                        obj.put("fm_name", fm.getFileName());
                        obj.put("fm_mime", fm.getMimeType());
                        obj.put("fm_size", fm.getFileSize());
                    }
                    if (msg.getSavedUri() != null) {
                        obj.put("savedUri", msg.getSavedUri().toString());
                    }
                }

                arr.put(obj);
            }
            prefs.edit().putString("history_" + peerDeviceName, arr.toString()).apply();
        } catch (Exception ignored) {}
    }

    private void addPeer(String peerDeviceName) {
        Set<String> peers = new HashSet<>(prefs.getStringSet(KEY_PEERS, new HashSet<>()));
        peers.add(peerDeviceName);
        prefs.edit().putStringSet(KEY_PEERS, peers).apply();
    }
}
