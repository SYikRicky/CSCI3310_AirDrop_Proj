package com.example.csci3310_airdrop_proj.storage;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.model.LocationMessage;
import com.example.csci3310_airdrop_proj.model.TextMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * {@link SharedPreferences} + JSON-backed implementation of
 * {@link ChatHistoryRepository}.
 *
 * Keyed by device name (persistent across sessions, unlike ephemeral
 * endpointId). Serialises all message fields including FileMetadata
 * (mimeType, fileName, fileSize) and savedUri so that image thumbnails,
 * voice Play buttons, and emoji labels survive across chat-room reopens
 * and app restarts.
 */
public class SharedPrefsChatHistoryRepository implements ChatHistoryRepository {

    private static final String PREFS_NAME   = "chat_history";
    private static final String KEY_PEERS    = "peers";
    private static final int    MAX_MESSAGES = 200; // per peer

    private final SharedPreferences prefs;

    public SharedPrefsChatHistoryRepository(Context context) {
        prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    @Override
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
    @Override
    public void updateFileUri(String peerDeviceName, String fileName, Uri uri) {
        List<ChatMessage> history = getHistory(peerDeviceName);
        boolean changed = false;
        for (int i = history.size() - 1; i >= 0; i--) {
            ChatMessage msg = history.get(i);
            if (msg instanceof FileMessage && !msg.isOutgoing()
                    && fileName.equals(msg.getText())) {
                FileMessage fm = (FileMessage) msg;
                if (fm.getSavedUri() == null) {
                    fm.setSavedUri(uri);
                    changed = true;
                    break;
                }
            }
        }
        if (changed) writeHistory(peerDeviceName, history);
    }

    @Override
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
                switch (type) {
                    case LOCATION: {
                        double lat = obj.optDouble("latitude",  0);
                        double lng = obj.optDouble("longitude", 0);
                        msg = new LocationMessage(senderName, timestamp, outgoing, lat, lng);
                        break;
                    }
                    case FILE: {
                        FileMetadata fm = new FileMetadata(
                                obj.optString("fm_name", text),
                                obj.optString("fm_mime", "*/*"),
                                obj.optLong  ("fm_size",  0));
                        FileMessage fileMsg = new FileMessage(senderName, timestamp, outgoing, fm);
                        if (obj.has("savedUri")) {
                            fileMsg.setSavedUri(Uri.parse(obj.getString("savedUri")));
                        }
                        msg = fileMsg;
                        break;
                    }
                    case TEXT:
                    default:
                        msg = new TextMessage(senderName, text, timestamp, outgoing);
                        break;
                }

                messages.add(msg);
            }
        } catch (Exception ignored) { /* corrupted prefs — return partial */ }
        return messages;
    }

    @Override
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

                if (msg instanceof LocationMessage) {
                    LocationMessage lm = (LocationMessage) msg;
                    obj.put("latitude",  lm.getLatitude());
                    obj.put("longitude", lm.getLongitude());
                } else if (msg instanceof FileMessage) {
                    // Persist FileMetadata so mimeType is available after reload
                    // (needed for voice/image emoji labels and Play/preview buttons)
                    FileMessage fm = (FileMessage) msg;
                    FileMetadata meta = fm.getFileMetadata();
                    if (meta != null) {
                        obj.put("fm_name", meta.getFileName());
                        obj.put("fm_mime", meta.getMimeType());
                        obj.put("fm_size", meta.getFileSize());
                    }
                    if (fm.getSavedUri() != null) {
                        obj.put("savedUri", fm.getSavedUri().toString());
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
