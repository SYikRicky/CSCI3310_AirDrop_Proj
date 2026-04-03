package com.example.csci3310_airdrop_proj.storage;

import android.content.Context;
import android.content.SharedPreferences;

import com.example.csci3310_airdrop_proj.model.ChatMessage;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Persists per-peer chat history using SharedPreferences + JSON.
 * Keyed by device name (persistent across sessions, unlike ephemeral endpointId).
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
            history = history.subList(history.size() - MAX_MESSAGES, history.size());
        }
        writeHistory(peerDeviceName, history);
        addPeer(peerDeviceName);
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
                String senderName = obj.getString("senderName");
                String text       = obj.getString("text");
                long   timestamp  = obj.getLong("timestamp");
                boolean outgoing  = obj.getBoolean("outgoing");
                if (type == ChatMessage.Type.LOCATION) {
                    double lat = obj.optDouble("latitude", 0);
                    double lng = obj.optDouble("longitude", 0);
                    messages.add(ChatMessage.createLocation(senderName, lat, lng, timestamp, outgoing));
                } else {
                    messages.add(new ChatMessage(type, senderName, text, timestamp, outgoing));
                }
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
