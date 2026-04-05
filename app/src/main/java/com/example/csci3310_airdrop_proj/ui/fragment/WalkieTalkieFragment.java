package com.example.csci3310_airdrop_proj.ui.fragment;

import android.Manifest;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AlertDialog;
import androidx.core.content.ContextCompat;
import androidx.fragment.app.Fragment;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.network.WalkieTalkieManager;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.chip.Chip;

import java.util.Random;

/**
 * Walkie-Talkie fragment — push-to-talk over local Wi-Fi.
 *
 * Flow:
 *  1. User navigates to tab → join dialog shown.
 *  2. On accept → request RECORD_AUDIO permission → start WalkieTalkieManager.
 *  3. Hold PTT button to broadcast voice to everyone on the same Wi-Fi.
 */
public class WalkieTalkieFragment extends Fragment
        implements WalkieTalkieManager.Listener {

    public static final String TAG = "WalkieTalkieFrag";

    private WalkieTalkieManager manager;
    private String localName;

    private Chip           chipUserCount;
    private TextView       tvSpeaking;
    private MaterialButton btnPtt;
    private TextView       tvPttHint;
    private MaterialButton btnLeave;

    private boolean joined = false;

    // ── Permission launcher ──────────────────────────────────────────────────

    private final ActivityResultLauncher<String> audioPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            startChannel();
                        } else {
                            Toast.makeText(requireContext(),
                                    R.string.wt_mic_permission_required,
                                    Toast.LENGTH_LONG).show();
                        }
                    });

    // ── Fragment lifecycle ───────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_walkie_talkie, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        chipUserCount = view.findViewById(R.id.chip_user_count);
        tvSpeaking    = view.findViewById(R.id.tv_speaking);
        btnPtt        = view.findViewById(R.id.btn_ptt);
        tvPttHint     = view.findViewById(R.id.tv_ptt_hint);
        btnLeave      = view.findViewById(R.id.btn_leave);

        localName = loadOrCreateName();
        updateUserCount(0);

        btnLeave.setOnClickListener(v -> leaveChannel());

        // Hold-to-talk touch listener
        btnPtt.setOnTouchListener((v, event) -> {
            if (manager == null) return false;
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    manager.startTalking();
                    btnPtt.setText(R.string.wt_talking);
                    tvPttHint.setText(R.string.wt_release_to_stop);
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    manager.stopTalking();
                    btnPtt.setText(R.string.wt_hold_to_talk);
                    tvPttHint.setText(R.string.wt_hold_to_talk_hint);
                    return true;
            }
            return false;
        });

        // Show join dialog immediately
        showJoinDialog();
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        joined = false;
    }

    // ── Join / Leave ──────────────────────────────────────────────────────────

    private void showJoinDialog() {
        String msg = getString(R.string.wt_join_message, localName);
        new AlertDialog.Builder(requireContext())
                .setTitle(R.string.wt_join_title)
                .setMessage(msg)
                .setCancelable(false)
                .setPositiveButton(R.string.wt_join, (d, w) -> requestMicAndJoin())
                .setNegativeButton(R.string.wt_cancel, (d, w) -> {
                    // Stay on the page but in idle state — user can navigate away
                })
                .show();
    }

    private void requestMicAndJoin() {
        if (ContextCompat.checkSelfPermission(requireContext(),
                Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            startChannel();
        } else {
            audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
        }
    }

    private void startChannel() {
        try {
            manager = new WalkieTalkieManager(requireContext(), localName);
            manager.setListener(this);
            manager.start();
            joined = true;
            btnPtt.setEnabled(true);
            btnLeave.setVisibility(View.VISIBLE);
            updateUserCount(0);
            Toast.makeText(requireContext(),
                    getString(R.string.wt_joined, localName), Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    getString(R.string.wt_join_failed, e.getMessage()),
                    Toast.LENGTH_LONG).show();
        }
    }

    private void leaveChannel() {
        if (manager != null) {
            manager.stop();
            manager = null;
        }
        joined = false;
        btnPtt.setEnabled(false);
        btnLeave.setVisibility(View.GONE);
        tvSpeaking.setVisibility(View.INVISIBLE);
        updateUserCount(0);
        Toast.makeText(requireContext(), R.string.wt_left_channel, Toast.LENGTH_SHORT).show();
    }

    // ── WalkieTalkieManager.Listener ─────────────────────────────────────────

    @Override
    public void onUserJoined(String name) {
        if (tvSpeaking == null) return;
        updateUserCount(manager != null ? manager.getUserCount() : 0);
    }

    @Override
    public void onUserLeft(String name) {
        if (tvSpeaking == null) return;
        updateUserCount(manager != null ? manager.getUserCount() : 0);
    }

    @Override
    public void onSpeakingChanged(String name) {
        if (tvSpeaking == null) return;
        if (name == null) {
            tvSpeaking.setVisibility(View.INVISIBLE);
        } else {
            tvSpeaking.setText(getString(R.string.wt_speaking, name));
            tvSpeaking.setVisibility(View.VISIBLE);
        }
    }

    @Override
    public void onUserCountChanged(int count) {
        updateUserCount(count);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void updateUserCount(int count) {
        if (chipUserCount == null) return;
        if (!joined) {
            chipUserCount.setText(R.string.wt_not_joined);
            return;
        }
        // +1 to include ourselves
        int total = count + 1;
        chipUserCount.setText(getResources().getQuantityString(
                R.plurals.wt_users_in_channel, total, total));
    }

    /** Load persistent name from SharedPreferences, generate one if absent. */
    private String loadOrCreateName() {
        SharedPreferences prefs =
                requireContext().getSharedPreferences("app_prefs", android.content.Context.MODE_PRIVATE);
        String name = prefs.getString("device_name", null);
        if (name == null) {
            name = generateName();
            prefs.edit().putString("device_name", name).apply();
        }
        return name;
    }

    private String generateName() {
        String[] adj  = {"Swift", "Brave", "Bright", "Cool", "Quick", "Bold", "Sharp", "Calm"};
        String[] noun = {"Fox", "Lion", "Bear", "Wolf", "Hawk", "Owl", "Deer", "Lynx"};
        Random r = new Random();
        return adj[r.nextInt(adj.length)] + noun[r.nextInt(noun.length)];
    }
}
