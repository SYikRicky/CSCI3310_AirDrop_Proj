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
import com.example.csci3310_airdrop_proj.ui.adapter.HistoryPeerAdapter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Shows nearby devices for the Chat tab plus past conversations from local history.
 * Tapping a live device sends a chat invitation; tapping a history entry opens
 * the conversation in read-only mode. A "Connect" button appears on history entries
 * whose peer is currently online (in the live device list).
 */
public class ChatDeviceListFragment extends Fragment {

    public static final String TAG = "ChatDeviceListFrag";

    private DeviceAdapter liveAdapter;
    private HistoryPeerAdapter historyAdapter;
    private TextView tvEmpty;
    private View tvHistoryHeader;
    private RecyclerView rvHistory;

    /** Maps device name → DeviceInfo for currently discovered devices. */
    private final Map<String, DeviceInfo> liveDevicesByName = new HashMap<>();

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

        // Show this device's name
        TextView tvMyName = view.findViewById(R.id.tv_my_device_name);
        tvMyName.setText(((MainActivity) requireActivity()).getLocalDeviceName());

        tvEmpty = view.findViewById(R.id.tv_empty_devices);

        // Live devices RecyclerView
        RecyclerView rvDevices = view.findViewById(R.id.rv_devices);
        rvDevices.setLayoutManager(new LinearLayoutManager(requireContext()));
        liveAdapter = new DeviceAdapter();
        liveAdapter.setOnDeviceClickListener(device ->
                ((MainActivity) requireActivity()).onChatDeviceSelected(device));
        rvDevices.setAdapter(liveAdapter);

        // History RecyclerView
        tvHistoryHeader = view.findViewById(R.id.tv_history_header);
        rvHistory = view.findViewById(R.id.rv_history);
        rvHistory.setLayoutManager(new LinearLayoutManager(requireContext()));
        historyAdapter = new HistoryPeerAdapter();
        historyAdapter.setOnPeerClickListener(peerName ->
                ((MainActivity) requireActivity()).onHistoryDeviceSelected(peerName));
        historyAdapter.setOnReconnectClickListener(peerName -> {
            DeviceInfo device = liveDevicesByName.get(peerName);
            if (device != null) {
                ((MainActivity) requireActivity()).onChatDeviceSelected(device);
            }
        });
        rvHistory.setAdapter(historyAdapter);

        // Load existing history on first show
        updatePastConversations(((MainActivity) requireActivity()).getPastConversationPeers());
    }

    /** Refresh the live device list. Called by MainActivity when onDevicesUpdated fires. */
    public void updateDeviceList(List<DeviceInfo> devices) {
        if (liveAdapter == null) return;
        liveAdapter.updateDevices(devices);
        if (tvEmpty != null) {
            tvEmpty.setVisibility(devices.isEmpty() ? View.VISIBLE : View.GONE);
        }

        // Rebuild the name→DeviceInfo map and notify history adapter of online peers
        liveDevicesByName.clear();
        Set<String> onlineNames = new HashSet<>();
        for (DeviceInfo d : devices) {
            liveDevicesByName.put(d.getDeviceName(), d);
            onlineNames.add(d.getDeviceName());
        }
        if (historyAdapter != null) {
            historyAdapter.updateOnlinePeers(onlineNames);
        }
    }

    /** Refresh the history peer list. Called by MainActivity when a message is saved. */
    public void updatePastConversations(List<String> peers) {
        if (historyAdapter == null) return;
        historyAdapter.updatePeers(peers);
        boolean hasPeers = peers != null && !peers.isEmpty();
        if (tvHistoryHeader != null) tvHistoryHeader.setVisibility(hasPeers ? View.VISIBLE : View.GONE);
        if (rvHistory != null)       rvHistory.setVisibility(hasPeers ? View.VISIBLE : View.GONE);
    }
}
