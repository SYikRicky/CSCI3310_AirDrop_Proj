package com.example.csci3310_airdrop_proj.storage;

import android.net.Uri;

import com.example.csci3310_airdrop_proj.model.ChatMessage;

import java.util.List;

/**
 * Persistent store for per-peer chat history.
 *
 * The production implementation, {@link SharedPrefsChatHistoryRepository},
 * serialises messages to {@link android.content.SharedPreferences} as JSON.
 * Tests can substitute an in-memory implementation without bringing up an
 * Android Context.
 */
public interface ChatHistoryRepository {

    /** Append a message for the given peer and persist. */
    void saveMessage(String peerDeviceName, ChatMessage msg);

    /**
     * Update the savedUri for the most recent received FILE message whose
     * filename matches, and that does not already have a saved URI.
     * Called after a received file has been written to cache.
     */
    void updateFileUri(String peerDeviceName, String fileName, Uri uri);

    /** Load the full history for a peer. Returns empty list if none. */
    List<ChatMessage> getHistory(String peerDeviceName);

    /** Names of all peers that have stored history. */
    List<String> getAllPeers();
}
