package com.example.csci3310_airdrop_proj.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;

/**
 * Receive mode screen.
 * The user toggles this device into "discoverable + discoverer" mode.
 * Demonstrates: Fragment → Activity communication via casting requireActivity().
 */
public class ReceiveModeFragment extends Fragment {

    private Button   btnToggle;
    private TextView tvStatus;
    private boolean  isReceiving = false;

    // ── Fragment lifecycle ─────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_receive_mode, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        btnToggle = view.findViewById(R.id.btn_toggle_receive);
        tvStatus  = view.findViewById(R.id.tv_receive_status);

        btnToggle.setOnClickListener(v -> toggleReceiving());
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Stop receiving when fragment is removed from back stack
        if (isReceiving) {
            ((MainActivity) requireActivity()).stopReceiving();
        }
    }

    // ── Public API called by MainActivity ─────────────────────────────────────

    /** Update the status label (e.g. "Receiving: photo.jpg 45%"). */
    public void setStatusText(String text) {
        if (tvStatus != null) tvStatus.setText(text);
    }

    // ── Private ───────────────────────────────────────────────────────────────

    private void toggleReceiving() {
        isReceiving = !isReceiving;
        if (isReceiving) {
            btnToggle.setText(R.string.stop_receiving);
            tvStatus.setText(R.string.ready_to_receive);
            ((MainActivity) requireActivity()).startReceiving();
        } else {
            btnToggle.setText(R.string.start_receiving);
            tvStatus.setText(R.string.not_receiving);
            ((MainActivity) requireActivity()).stopReceiving();
        }
    }
}
