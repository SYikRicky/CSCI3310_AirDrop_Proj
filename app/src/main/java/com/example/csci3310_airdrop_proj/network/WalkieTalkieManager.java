package com.example.csci3310_airdrop_proj.network;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioRecord;
import android.media.AudioTrack;
import android.media.MediaRecorder;
import android.net.wifi.WifiManager;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Manages walkie-talkie over local Wi-Fi using UDP broadcast.
 *
 * Protocol (binary):
 *   [type:1][nameLen:1][name:nameLen][payload...]
 *   type 0x01 = PRESENCE  (heartbeat — no payload)
 *   type 0x02 = AUDIO     (payload = PCM 16-bit 8 kHz mono)
 *   type 0x03 = LEAVE     (no payload)
 */
public class WalkieTalkieManager {

    private static final String TAG  = "WalkieTalkieMgr";
    static final int    PORT         = 5005;
    private static final byte TYPE_PRESENCE = 0x01;
    private static final byte TYPE_AUDIO    = 0x02;
    private static final byte TYPE_LEAVE    = 0x03;

    // Audio config — 8 kHz mono PCM-16, 80 ms chunks
    private static final int SAMPLE_RATE  = 8000;
    private static final int CHUNK_FRAMES = 640;           // 80 ms × 8000 Hz
    private static final int CHUNK_BYTES  = CHUNK_FRAMES * 2; // 16-bit = 2 bytes/frame

    public interface Listener {
        void onUserJoined(String name);
        void onUserLeft(String name);
        void onSpeakingChanged(String name);   // null = nobody speaking
        void onUserCountChanged(int count);
    }

    private final Context context;
    private final String  userName;
    private Listener listener;
    private final Handler  mainHandler        = new Handler(Looper.getMainLooper());
    private final Runnable clearSpeakingTask  = () -> {
        if (listener != null) listener.onSpeakingChanged(null);
    };

    private DatagramSocket socket;
    private InetAddress    broadcastAddr;
    private WifiManager.MulticastLock multicastLock;

    private volatile boolean running   = false;
    private volatile boolean recording = false;

    private AudioRecord audioRecord;
    private AudioTrack  audioTrack;

    private final ScheduledExecutorService scheduler =
            Executors.newSingleThreadScheduledExecutor();
    private ScheduledFuture<?> heartbeatFuture;
    private ScheduledFuture<?> cleanupFuture;

    // userName → last-seen epoch ms
    private final ConcurrentHashMap<String, Long> activeUsers = new ConcurrentHashMap<>();

    // ── Constructor ───────────────────────────────────────────────────────────

    public WalkieTalkieManager(Context context, String userName) {
        this.context  = context.getApplicationContext();
        this.userName = userName;
    }

    public void setListener(Listener l) { this.listener = l; }

    // ── Public API ────────────────────────────────────────────────────────────

    /** Open the socket, init audio playback, start heartbeat. Throws on error. */
    public void start() throws Exception {
        broadcastAddr = computeBroadcastAddress();

        // Acquire multicast lock so WiFi power-save doesn't drop broadcasts
        WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
        multicastLock = wifi.createMulticastLock(TAG);
        multicastLock.setReferenceCounted(false);
        multicastLock.acquire();

        socket = new DatagramSocket(null);
        socket.setReuseAddress(true);
        socket.setBroadcast(true);
        socket.bind(new InetSocketAddress(PORT));

        initAudioTrack();
        running = true;

        // Receive thread
        Thread rx = new Thread(this::receiveLoop, "wt-receive");
        rx.setDaemon(true);
        rx.start();

        // Heartbeat every 3 s
        heartbeatFuture = scheduler.scheduleAtFixedRate(
                this::sendPresence, 0, 3, TimeUnit.SECONDS);

        // Stale-user cleanup every 10 s
        cleanupFuture = scheduler.scheduleAtFixedRate(
                this::cleanupStale, 10, 10, TimeUnit.SECONDS);
    }

    /** Stop everything and release resources. */
    public void stop() {
        running   = false;
        recording = false;

        if (heartbeatFuture != null) heartbeatFuture.cancel(false);
        if (cleanupFuture   != null) cleanupFuture.cancel(false);

        sendLeave();

        if (socket != null && !socket.isClosed()) socket.close();

        if (audioRecord != null) {
            try { audioRecord.stop(); } catch (Exception ignored) {}
            audioRecord.release();
            audioRecord = null;
        }
        if (audioTrack != null) {
            try { audioTrack.stop(); } catch (Exception ignored) {}
            audioTrack.release();
            audioTrack = null;
        }
        if (multicastLock != null && multicastLock.isHeld()) {
            multicastLock.release();
        }
        mainHandler.removeCallbacks(clearSpeakingTask);
    }

    /** Start recording and broadcasting audio (PTT pressed). */
    public void startTalking() {
        if (recording || !running) return;
        recording = true;
        Thread tx = new Thread(this::recordLoop, "wt-record");
        tx.setDaemon(true);
        tx.start();
    }

    /** Stop recording (PTT released). */
    public void stopTalking() {
        recording = false;
    }

    public boolean isRunning() { return running; }

    public int getUserCount() { return activeUsers.size(); }

    public List<String> getUsers() {
        return new ArrayList<>(activeUsers.keySet());
    }

    // ── Audio ─────────────────────────────────────────────────────────────────

    private void initAudioTrack() {
        int minBuf = AudioTrack.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, CHUNK_BYTES * 4);

        audioTrack = new AudioTrack.Builder()
                .setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build())
                .setAudioFormat(new AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(SAMPLE_RATE)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build())
                .setBufferSizeInBytes(bufSize)
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build();

        // Route to loudspeaker like a real walkie-talkie
        AudioManager am = (AudioManager) context.getSystemService(Context.AUDIO_SERVICE);
        am.setSpeakerphoneOn(true);
        am.setMode(AudioManager.MODE_IN_COMMUNICATION);

        audioTrack.play();
    }

    private void recordLoop() {
        int minBuf = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufSize = Math.max(minBuf, CHUNK_BYTES * 2);

        AudioRecord rec = new AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION,
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT, bufSize);
        audioRecord = rec;
        rec.startRecording();

        byte[] buf = new byte[CHUNK_BYTES];
        while (recording) {
            int read = rec.read(buf, 0, CHUNK_BYTES);
            if (read > 0) sendAudio(buf, read);
        }

        try { rec.stop(); } catch (Exception ignored) {}
        rec.release();
        if (audioRecord == rec) audioRecord = null;
    }

    private void playAudio(byte[] data, int offset, int len) {
        AudioTrack track = audioTrack;
        if (track != null && track.getPlayState() == AudioTrack.PLAYSTATE_PLAYING) {
            track.write(data, offset, len);
        }
    }

    // ── Network receive ───────────────────────────────────────────────────────

    private void receiveLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket pkt = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                socket.receive(pkt);
                onPacket(pkt.getData(), pkt.getLength());
            } catch (IOException e) {
                if (running) Log.w(TAG, "receive error: " + e.getMessage());
            }
        }
    }

    private void onPacket(byte[] data, int len) {
        if (len < 2) return;
        byte type    = data[0];
        int  nameLen = data[1] & 0xFF;
        if (len < 2 + nameLen) return;

        String sender = new String(data, 2, nameLen);
        if (sender.equals(userName)) return; // ignore own packets

        boolean isNew = !activeUsers.containsKey(sender);
        activeUsers.put(sender, System.currentTimeMillis());

        if (isNew) {
            notify(() -> {
                if (listener != null) listener.onUserJoined(sender);
                if (listener != null) listener.onUserCountChanged(activeUsers.size());
            });
        }

        if (type == TYPE_AUDIO) {
            int audioOff = 2 + nameLen;
            int audioLen = len - audioOff;
            if (audioLen > 0) {
                playAudio(data, audioOff, audioLen);
                notify(() -> {
                    if (listener != null) listener.onSpeakingChanged(sender);
                });
                // Clear speaking indicator after 300 ms of silence
                mainHandler.removeCallbacks(clearSpeakingTask);
                mainHandler.postDelayed(clearSpeakingTask, 300);
            }
        } else if (type == TYPE_LEAVE) {
            activeUsers.remove(sender);
            notify(() -> {
                if (listener != null) listener.onUserLeft(sender);
                if (listener != null) listener.onUserCountChanged(activeUsers.size());
            });
        }
    }

    // ── Network send ──────────────────────────────────────────────────────────

    private void sendPresence() { sendPacket(TYPE_PRESENCE, null, 0); }
    private void sendLeave()    { sendPacket(TYPE_LEAVE, null, 0); }

    private void sendAudio(byte[] audio, int len) {
        sendPacket(TYPE_AUDIO, audio, len);
    }

    private void sendPacket(byte type, byte[] payload, int payloadLen) {
        if (socket == null || socket.isClosed() || broadcastAddr == null) return;
        try {
            byte[] name     = userName.getBytes();
            int    totalLen = 2 + name.length + payloadLen;
            byte[] data     = new byte[totalLen];
            data[0] = type;
            data[1] = (byte) name.length;
            System.arraycopy(name, 0, data, 2, name.length);
            if (payload != null && payloadLen > 0) {
                System.arraycopy(payload, 0, data, 2 + name.length, payloadLen);
            }
            socket.send(new DatagramPacket(data, totalLen, broadcastAddr, PORT));
        } catch (IOException e) {
            Log.w(TAG, "send error: " + e.getMessage());
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void cleanupStale() {
        long now = System.currentTimeMillis();
        List<String> removed = new ArrayList<>();
        for (String user : activeUsers.keySet()) {
            Long last = activeUsers.get(user);
            if (last != null && now - last > 10_000) {
                activeUsers.remove(user);
                removed.add(user);
            }
        }
        if (!removed.isEmpty()) {
            notify(() -> {
                if (listener != null) {
                    for (String u : removed) listener.onUserLeft(u);
                    listener.onUserCountChanged(activeUsers.size());
                }
            });
        }
    }

    private InetAddress computeBroadcastAddress() {
        try {
            WifiManager wifi = (WifiManager) context.getSystemService(Context.WIFI_SERVICE);
            int ip   = wifi.getDhcpInfo().ipAddress;
            int mask = wifi.getDhcpInfo().netmask;
            // subnet broadcast = (ip & mask) | ~mask, little-endian bytes from getDhcpInfo
            int broadcast = (ip & mask) | ~mask;
            byte[] bytes = {
                    (byte) ( broadcast        & 0xFF),
                    (byte) ((broadcast >>  8) & 0xFF),
                    (byte) ((broadcast >> 16) & 0xFF),
                    (byte) ((broadcast >> 24) & 0xFF)
            };
            return InetAddress.getByAddress(bytes);
        } catch (Exception e) {
            try { return InetAddress.getByName("255.255.255.255"); }
            catch (Exception ex) { return null; }
        }
    }

    private void notify(Runnable r) { mainHandler.post(r); }
}
