package com.example.csci3310_airdrop_proj.ui.adapter;

import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.R;
import com.google.android.material.button.MaterialButton;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * RecyclerView adapter for past-conversation peer names in the Chat device list.
 * Shows a "Connect" button next to peers that are currently online (discovered nearby).
 */
public class HistoryPeerAdapter extends RecyclerView.Adapter<HistoryPeerAdapter.ViewHolder> {

    public interface OnPeerClickListener {
        void onPeerClicked(String peerName);
    }

    public interface OnReconnectClickListener {
        void onReconnectClicked(String peerName);
    }

    private final List<String> peers = new ArrayList<>();
    private final Set<String> onlinePeerNames = new HashSet<>();
    private OnPeerClickListener listener;
    private OnReconnectClickListener reconnectListener;

    public void setOnPeerClickListener(OnPeerClickListener l) { listener = l; }
    public void setOnReconnectClickListener(OnReconnectClickListener l) { reconnectListener = l; }

    public void updatePeers(List<String> newPeers) {
        peers.clear();
        if (newPeers != null) peers.addAll(newPeers);
        notifyDataSetChanged();
    }

    public void updateOnlinePeers(Set<String> onlineNames) {
        onlinePeerNames.clear();
        if (onlineNames != null) onlinePeerNames.addAll(onlineNames);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(parent.getContext())
                .inflate(R.layout.item_history_peer, parent, false);
        return new ViewHolder(v);
    }

    @Override
    public void onBindViewHolder(@NonNull ViewHolder holder, int position) {
        String name = peers.get(position);
        holder.tvName.setText(name);
        holder.itemView.setOnClickListener(v -> {
            if (listener != null) listener.onPeerClicked(name);
        });

        boolean isOnline = onlinePeerNames.contains(name);
        holder.btnReconnect.setVisibility(isOnline ? View.VISIBLE : View.GONE);
        if (isOnline) {
            holder.btnReconnect.setOnClickListener(v -> {
                if (reconnectListener != null) reconnectListener.onReconnectClicked(name);
            });
        }
    }

    @Override
    public int getItemCount() { return peers.size(); }

    static class ViewHolder extends RecyclerView.ViewHolder {
        final TextView tvName;
        final MaterialButton btnReconnect;
        ViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_peer_name);
            btnReconnect = itemView.findViewById(R.id.btn_reconnect);
        }
    }
}
