package com.example.csci3310_airdrop_proj.playback;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.util.Log;

/**
 * Owns the single active {@link MediaPlayer} used for chat voice message
 * playback.
 *
 * The pre-refactor ChatAdapter created a new {@code MediaPlayer} inside each
 * {@code ViewHolder.bind()} call, attached lifecycle listeners, and then
 * dropped the reference. If the user scrolled before a clip finished, the
 * player kept playing and was never released — a slow leak, but a real one.
 *
 * This class enforces the UX invariant "only one voice clip plays at a time"
 * and guarantees cleanup:
 *  - {@link #play(Context, Uri)} stops any previous clip before starting a new one.
 *  - {@link #release()} is called by the fragment's {@code onDestroyView()}.
 *  - Completion / error listeners release the player automatically.
 */
public final class VoicePlaybackController {

    private static final String TAG = "VoicePlayback";

    private MediaPlayer current;

    /** Start playing {@code uri}. Stops any currently-playing clip first. */
    public void play(Context context, Uri uri) {
        stopCurrent();
        try {
            MediaPlayer player = new MediaPlayer();
            current = player;
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build());
            player.setDataSource(context, uri);
            player.setOnPreparedListener(MediaPlayer::start);
            player.setOnCompletionListener(mp -> releasePlayer(mp));
            player.setOnErrorListener((mp, what, extra) -> {
                Log.w(TAG, "MediaPlayer error what=" + what + " extra=" + extra);
                releasePlayer(mp);
                return true;
            });
            player.prepareAsync();
        } catch (Exception e) {
            Log.e(TAG, "Failed to start playback", e);
            stopCurrent();
        }
    }

    /** Release the active player (if any). Safe to call repeatedly. */
    public void release() {
        stopCurrent();
    }

    private void stopCurrent() {
        MediaPlayer p = current;
        current = null;
        releasePlayer(p);
    }

    private void releasePlayer(MediaPlayer p) {
        if (p == null) return;
        if (p == current) current = null;
        try {
            if (p.isPlaying()) p.stop();
        } catch (IllegalStateException ignored) {
            // player already in an invalid state — release is still safe
        }
        try {
            p.release();
        } catch (Exception e) {
            Log.w(TAG, "release() threw", e);
        }
    }
}
