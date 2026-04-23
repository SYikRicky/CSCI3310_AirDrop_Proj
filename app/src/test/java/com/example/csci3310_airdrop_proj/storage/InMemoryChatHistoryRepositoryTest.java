package com.example.csci3310_airdrop_proj.storage;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.TextMessage;

import org.junit.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Demonstrates the DIP payoff: with the concrete implementation hidden
 * behind {@link ChatHistoryRepository}, tests can supply a pure-JVM fake
 * and verify caller logic without Android's SharedPreferences.
 */
public class InMemoryChatHistoryRepositoryTest {

    /** Minimal in-memory stand-in for the real repository. */
    private static final class InMemoryRepo implements ChatHistoryRepository {
        final Map<String, List<ChatMessage>> store = new HashMap<>();

        @Override public void saveMessage(String peer, ChatMessage msg) {
            store.computeIfAbsent(peer, k -> new ArrayList<>()).add(msg);
        }
        @Override public void updateFileUri(String peer, String fileName, android.net.Uri uri) {
            // not used in this test
        }
        @Override public List<ChatMessage> getHistory(String peer) {
            return store.getOrDefault(peer, new ArrayList<>());
        }
        @Override public List<String> getAllPeers() {
            return new ArrayList<>(store.keySet());
        }
    }

    @Test
    public void fakeRepositorySatisfiesInterface() {
        ChatHistoryRepository repo = new InMemoryRepo();
        repo.saveMessage("Alice", new TextMessage("me", "hi Alice", 1L, true));
        repo.saveMessage("Alice", new TextMessage("Alice", "hi back", 2L, false));
        repo.saveMessage("Bob",   new TextMessage("me", "hi Bob", 3L, true));

        List<ChatMessage> alice = repo.getHistory("Alice");
        assertEquals(2, alice.size());
        assertEquals("hi Alice", alice.get(0).getText());
        assertEquals("hi back",  alice.get(1).getText());

        List<String> peers = repo.getAllPeers();
        assertEquals(2, peers.size());
        assertTrue(peers.contains("Alice"));
        assertTrue(peers.contains("Bob"));
    }

    @Test
    public void emptyPeerReturnsEmptyList() {
        ChatHistoryRepository repo = new InMemoryRepo();
        assertTrue(repo.getHistory("nobody").isEmpty());
        assertTrue(repo.getAllPeers().isEmpty());
    }
}
