package com.example.csci3310_airdrop_proj.ui.adapter.renderer;

import android.content.Context;
import android.content.Intent;

import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.LocationMessage;
import com.example.csci3310_airdrop_proj.ui.MapActivity;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatViewHolder;

/**
 * Renders a {@link LocationMessage}: shows coordinates with a pin emoji and
 * offers an Open Map button that launches {@link MapActivity}.
 */
public final class LocationRenderer implements MessageRenderer {

    private static final String LOCATION_LABEL_EMOJI = "📍 ";

    @Override
    public boolean canRender(ChatMessage msg) {
        return msg instanceof LocationMessage;
    }

    @Override
    public void render(ChatViewHolder holder, ChatMessage msg) {
        LocationMessage lm = (LocationMessage) msg;

        String shared = holder.getContext().getString(R.string.location_shared);
        holder.setBodyText(LOCATION_LABEL_EMOJI + shared + "\n" + msg.getText());

        holder.showMapButton(v -> openInMap(holder.getContext(),
                lm.getLatitude(), lm.getLongitude()));
        holder.hideImage();
        holder.hidePlayButton();
        holder.hideOpenFileButton();
    }

    private static void openInMap(Context context, double lat, double lng) {
        Intent intent = new Intent(context, MapActivity.class);
        intent.putExtra(MapActivity.EXTRA_LAT, lat);
        intent.putExtra(MapActivity.EXTRA_LNG, lng);
        context.startActivity(intent);
    }
}
