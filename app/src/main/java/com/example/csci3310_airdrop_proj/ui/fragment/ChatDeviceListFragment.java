package com.example.csci3310_airdrop_proj.ui.fragment;

import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
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
 * Shows nearby devices for the Chat tab.
 * Tapping a device opens a chatroom (instead of sending a file).
 * Reuses DeviceAdapter with a different click handler.
 */
public class ChatDeviceListFragment extends Fragment {

    public static final String TAG = "ChatDeviceListFrag";

    private DeviceAdapter adapter;
    private TextView tvEmpty;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chat_device_list, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        tvEmpty = view.findViewById(R.id.tv_empty_devices);

        RecyclerView recyclerView = view.findViewById(R.id.rv_devices);
        recyclerView.setLayoutManager(new LinearLayoutManager(requireContext()));

        adapter = new DeviceAdapter();
        adapter.setOnDeviceClickListener(device ->
                ((MainActivity) requireActivity()).onChatDeviceSelected(device));

        recyclerView.setAdapter(adapter);
    }

    /** Refresh the device list. Called by MainActivity when onDevicesUpdated fires. */
    public void updateDeviceList(List<DeviceInfo> devices) {
        if (adapter == null) return;
        adapter.updateDevices(devices);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        }
    }
}
