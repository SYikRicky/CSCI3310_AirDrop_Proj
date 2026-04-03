package com.example.csci3310_airdrop_proj.ui.fragment;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatAdapter;

/**
 * Chat conversation screen.
 * Shows messages in a RecyclerView with text input and file attach button.
 * Communicates with MainActivity for sending messages and files via Nearby Connections.
 */
public class ChatRoomFragment extends Fragment {

    public static final String TAG = "ChatRoomFrag";
    public static final String ARG_ENDPOINT_ID = "endpointId";
    public static final String ARG_DEVICE_NAME = "deviceName";

    private String endpointId;
    private String deviceName;

    private ChatAdapter adapter;
    private RecyclerView rvMessages;
    private EditText etMessage;
    private TextView tvConnectionStatus;
    private TextView tvDisconnectedBanner;
    private View inputBar;

    /** File picker for attaching files in chat */
    private final ActivityResultLauncher<Intent> filePickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == Activity.RESULT_OK
                                && result.getData() != null) {
                            Uri uri = result.getData().getData();
                            if (uri != null) {
                                requireActivity().getContentResolver()
                                        .takePersistableUriPermission(
                                                uri, Intent.FLAG_GRANT_READ_URI_PERMISSION);
                                FileMetadata metadata = extractMetadata(uri);
                                ((MainActivity) requireActivity())
                                        .onChatSendFile(endpointId, uri, metadata);
                            }
                        }
                    });

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater,
                             @Nullable ViewGroup container,
                             @Nullable Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_chatroom, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View view, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(view, savedInstanceState);

        Bundle args = getArguments();
        if (args != null) {
            endpointId = args.getString(ARG_ENDPOINT_ID);
            deviceName = args.getString(ARG_DEVICE_NAME);
        }

        TextView tvDeviceName = view.findViewById(R.id.tv_device_name);
        tvConnectionStatus = view.findViewById(R.id.tv_connection_status);
        tvDisconnectedBanner = view.findViewById(R.id.tv_disconnected_banner);
        inputBar = view.findViewById(R.id.input_bar);
        etMessage = view.findViewById(R.id.et_message);
        ImageButton btnSend = view.findViewById(R.id.btn_send);
        ImageButton btnAttach = view.findViewById(R.id.btn_attach);
        ImageButton btnLocation = view.findViewById(R.id.btn_location);

        tvDeviceName.setText(deviceName != null ? deviceName : "Unknown Device");

        // Set up message RecyclerView
        rvMessages = view.findViewById(R.id.rv_messages);
        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);

        adapter = new ChatAdapter();
        rvMessages.setAdapter(adapter);

        // Send text message
        btnSend.setOnClickListener(v -> {
            String text = etMessage.getText().toString().trim();
            if (!text.isEmpty() && endpointId != null) {
                ((MainActivity) requireActivity()).onChatSendText(endpointId, text);
                etMessage.setText("");
            }
        });

        // Attach file
        btnAttach.setOnClickListener(v -> openFilePicker());

        // Share GPS location
        btnLocation.setOnClickListener(v -> {
            if (endpointId != null) {
                ((MainActivity) requireActivity()).onChatSendLocation(endpointId);
            }
        });
    }

    // ── Public API called by MainActivity ─────────────────────────────────────

    /** Add a message to the chat and scroll to bottom. */
    public void addMessage(ChatMessage message) {
        if (adapter == null) return;
        adapter.addMessage(message);
        rvMessages.scrollToPosition(adapter.getItemCount() - 1);
    }

    /** Show the connected status. */
    public void showConnected() {
        if (tvConnectionStatus != null) {
            tvConnectionStatus.setText(R.string.chat_connected);
        }
    }

    /** Show the disconnected state — banner visible, input disabled. */
    public void showDisconnected() {
        if (tvDisconnectedBanner != null) {
            tvDisconnectedBanner.setVisibility(View.VISIBLE);
        }
        if (tvConnectionStatus != null) {
            tvConnectionStatus.setText(R.string.chat_disconnected);
        }
        if (inputBar != null) {
            inputBar.setAlpha(0.5f);
            etMessage.setEnabled(false);
        }
    }

    /**
     * Called when a received file has been saved to the device.
     * Updates the matching message bubble to show an "Open File" button.
     */
    public void onFileSaved(Uri savedUri, String fileName, String mimeType) {
        if (adapter != null) adapter.updateMessageUri(fileName, savedUri);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void openFilePicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, false);
        filePickerLauncher.launch(intent);
    }

    private FileMetadata extractMetadata(Uri uri) {
        String fileName = "file";
        long fileSize = 0;
        String mimeType = requireActivity().getContentResolver().getType(uri);
        if (mimeType == null) mimeType = "*/*";

        try (Cursor cursor = requireActivity().getContentResolver().query(
                uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int nameIdx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                int sizeIdx = cursor.getColumnIndex(OpenableColumns.SIZE);
                if (nameIdx >= 0) fileName = cursor.getString(nameIdx);
                if (sizeIdx >= 0 && !cursor.isNull(sizeIdx)) fileSize = cursor.getLong(sizeIdx);
            }
        } catch (Exception e) {
            String path = uri.getLastPathSegment();
            if (path != null) fileName = path;
        }

        return new FileMetadata(fileName, mimeType, fileSize);
    }
}
