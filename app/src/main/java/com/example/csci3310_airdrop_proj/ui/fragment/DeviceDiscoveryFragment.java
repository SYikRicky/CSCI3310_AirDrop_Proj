package com.example.csci3310_airdrop_proj.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.DeviceInfo;
import com.example.csci3310_airdrop_proj.ui.adapter.DeviceAdapter;

import java.util.List;

/**
 * Device discovery screen.
 * Shows a live RecyclerView of nearby devices found by NearbyConnectionsManager.
 * Tapping a device initiates a P2P connection and triggers file transfer.
 *
 * Demonstrates: RecyclerView + ViewHolder pattern, Fragment + Adapter interaction.
 */
public class DeviceDiscoveryFragment extends Fragment {

    public static final String TAG = "DeviceDiscoveryFrag";

    private DeviceAdapter adapter;
    private TextView      tvEmpty;
    private ProgressBar   progressBar;
    private TextView      tvTransferStatus;

    // ── Fragment lifecycle ─────────────────────────────────────────────────────

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_device_discovery, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvEmpty          = view.findViewById(R.id.tv_empty_devices);
        progressBar      = view.findViewById(R.id.progress_transfer);
        tvTransferStatus = view.findViewById(R.id.tv_transfer_status);

        progressBar.setVisibility(View.GONE);
        tvTransferStatus.setVisibility(View.GONE);

        // Set up RecyclerView with LinearLayoutManager (vertical list)
        RecyclerView recyclerView = view.findViewById(R.id.rv_devices);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new DeviceAdapter();
        adapter.setOnDeviceClickListener(device ->
                ((MainActivity) requireActivity()).onDeviceSelectedForSend(device));

        recyclerView.setAdapter(adapter);
    }

    // ── Public API called by MainActivity ─────────────────────────────────────

    /**
     * Refresh the device list.
     * Called whenever ConnectionListener.onDevicesUpdated fires.
     */
    public void updateDeviceList(List<DeviceInfo> devices) {
        if (adapter == null) return;
        adapter.updateDevices(devices);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }

    /**
     * Show/update the transfer progress bar.
     * @param percent  0–100
     * @param label    human-readable status string
     */
    public void showTransferProgress(int percent, String label) {
        if (progressBar == null) return;
        progressBar.setVisibility(View.VISIBLE);
        tvTransferStatus.setVisibility(View.VISIBLE);
        progressBar.setProgress(percent);
        tvTransferStatus.setText(label);
    }

    /** Called when the send completes successfully. */
    public void showTransferComplete(String fileName) {
        showTransferProgress(100, getString(R.string.transfer_complete) + ": " + fileName);
    }

    /** Called when the send fails. */
    public void showTransferFailed(String error) {
        if (progressBar == null) return;
        progressBar.setVisibility(View.GONE);
        tvTransferStatus.setVisibility(View.VISIBLE);
        tvTransferStatus.setText(getString(R.string.transfer_failed) + ": " + error);
    }
}
