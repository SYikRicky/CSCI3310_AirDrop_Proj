package com.example.csci3310_airdrop_proj.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.DeviceInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * RecyclerView adapter for the device discovery list.
 * Demonstrates the ViewHolder pattern from CSCI3310 course material.
 */
public class DeviceAdapter extends RecyclerView.Adapter<DeviceAdapter.DeviceViewHolder> {

    /** Callback interface for device selection events. */
    public interface OnDeviceClickListener {
        void onDeviceClicked(DeviceInfo device);
    }

    private final List<DeviceInfo>     devices  = new ArrayList<>();
    private       OnDeviceClickListener listener;

    public void setOnDeviceClickListener(OnDeviceClickListener listener) {
        this.listener = listener;
    }

    /**
     * Replace the current device list and refresh the RecyclerView.
     * Called by DeviceDiscoveryFragment when ConnectionListener.onDevicesUpdated fires.
     */
    public void updateDevices(List<DeviceInfo> newDevices) {
        devices.clear();
        if (newDevices != null) devices.addAll(newDevices);
        notifyDataSetChanged();
    }

    // ── RecyclerView.Adapter overrides ────────────────────────────────────────

    @NonNull
    @Override
    public DeviceViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View itemView = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_device, parent, false);
        return new DeviceViewHolder(itemView);
    }

    @Override
    public void onBindViewHolder(@NonNull DeviceViewHolder holder, int position) {
        holder.bind(devices.get(position), listener);
    }

    @Override
    public int getItemCount() { return devices.size(); }

    // ── ViewHolder ────────────────────────────────────────────────────────────

    static class DeviceViewHolder extends RecyclerView.ViewHolder {

        private final TextView tvName;
        private final TextView tvStatus;

        DeviceViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName   = itemView.findViewById(R.id.tv_device_name);
            tvStatus = itemView.findViewById(R.id.tv_device_status);
        }

        void bind(DeviceInfo device, OnDeviceClickListener listener) {
            tvName.setText(device.getDeviceName());
            tvStatus.setText(device.isConnected() ? "Connected — sending…" : "Tap to send");
            itemView.setAlpha(device.isConnected() ? 0.6f : 1.0f);
            itemView.setOnClickListener(v -> {
                if (listener != null && !device.isConnected()) {
                    listener.onDeviceClicked(device);
                }
            });
        }
    }
}
