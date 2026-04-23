package com.example.csci3310_airdrop_proj.ui.fragment;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.OpenableColumns;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.example.csci3310_airdrop_proj.MainActivity;
import com.example.csci3310_airdrop_proj.R;
import com.example.csci3310_airdrop_proj.model.ChatMessage;
import com.example.csci3310_airdrop_proj.model.FileMetadata;
import com.example.csci3310_airdrop_proj.playback.VoicePlaybackController;
import com.example.csci3310_airdrop_proj.ui.adapter.ChatAdapter;

import java.io.File;

/**
 * Chat conversation screen.
 * Shows messages in a RecyclerView with text input, file attach, location, and voice buttons.
 * Voice messages are recorded with MediaRecorder and sent as audio/mp4 files via Nearby Connections.
 */
public class ChatRoomFragment extends Fragment {

    public static final String TAG = "ChatRoomFrag";
    public static final String ARG_ENDPOINT_ID = "endpointId";
    public static final String ARG_DEVICE_NAME = "deviceName";

    private String endpointId;
    private String deviceName;

    private ChatAdapter             adapter;
    private VoicePlaybackController voicePlayback;
    private RecyclerView rvMessages;
    private EditText etMessage;
    private TextView tvConnectionStatus;
    private TextView tvDisconnectedBanner;
    private View inputBar;

    // ── Voice recording ──────────────────────────────────��────────────────────
    private MediaRecorder mediaRecorder;
    private File          voiceTempFile;
    private boolean       isRecording = false;

    // ── Permission launcher ───────────────────────────────────────────────────
    private final ActivityResultLauncher<String> micPermissionLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.RequestPermission(),
                    granted -> {
                        if (granted) {
                            Toast.makeText(requireContext(),
                                    R.string.chat_hold_to_record, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(requireContext(),
                                    R.string.chat_mic_permission_required, Toast.LENGTH_LONG).show();
                        }
                    });

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

    // ── Fragment lifecycle ────────────────────────────────────────────────────

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
        tvConnectionStatus   = view.findViewById(R.id.tv_connection_status);
        tvDisconnectedBanner = view.findViewById(R.id.tv_disconnected_banner);
        inputBar             = view.findViewById(R.id.input_bar);
        etMessage            = view.findViewById(R.id.et_message);
        ImageButton btnSend     = view.findViewById(R.id.btn_send);
        ImageButton btnAttach   = view.findViewById(R.id.btn_attach);
        ImageButton btnLocation = view.findViewById(R.id.btn_location);
        ImageButton btnVoice    = view.findViewById(R.id.btn_voice);

        tvDeviceName.setText(deviceName != null ? deviceName : "Unknown Device");

        // Set up message RecyclerView
        rvMessages = view.findViewById(R.id.rv_messages);
        LinearLayoutManager lm = new LinearLayoutManager(requireContext());
        lm.setStackFromEnd(true);
        rvMessages.setLayoutManager(lm);

        voicePlayback = new VoicePlaybackController();
        adapter = new ChatAdapter(voicePlayback);
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

        // Voice message — hold to record, release to send
        btnVoice.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    onVoiceButtonDown();
                    return true;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    onVoiceButtonUp(event.getAction() == MotionEvent.ACTION_CANCEL);
                    return true;
            }
            return false;
        });
    }

    @Override
    public void onDestroyView() {
        super.onDestroyView();
        // Release recorder if fragment is destroyed mid-recording (e.g. back-press)
        releaseRecorder();
        // Release any active voice-message MediaPlayer — the adapter's
        // view holders will be garbage-collected, but the player lives past them.
        if (voicePlayback != null) {
            voicePlayback.release();
            voicePlayback = null;
        }
    }

    // ── Voice recording ───────────────────────────────────────────────────────

    private void onVoiceButtonDown() {
        if (endpointId == null) return;

        // Check permission first; if not granted, ask and bail
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO);
            return;
        }

        try {
            voiceTempFile = new File(requireContext().getCacheDir(),
                    "voice_" + System.currentTimeMillis() + ".mp4");

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                mediaRecorder = new MediaRecorder(requireContext());
            } else {
                //noinspection deprecation
                mediaRecorder = new MediaRecorder();
            }
            mediaRecorder.setAudioSource(MediaRecorder.AudioSource.MIC);
            mediaRecorder.setOutputFormat(MediaRecorder.OutputFormat.MPEG_4);
            mediaRecorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
            mediaRecorder.setAudioSamplingRate(44100);
            mediaRecorder.setAudioEncodingBitRate(96000);
            mediaRecorder.setOutputFile(voiceTempFile.getAbsolutePath());
            mediaRecorder.prepare();
            mediaRecorder.start();
            isRecording = true;

            // Visual feedback while recording
            etMessage.setHint(R.string.chat_recording);
            etMessage.setEnabled(false);
        } catch (Exception e) {
            releaseRecorder();
            Toast.makeText(requireContext(),
                    "Could not start recording: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    private void onVoiceButtonUp(boolean cancelled) {
        if (!isRecording) return;

        try {
            mediaRecorder.stop();
        } catch (Exception ignored) {
            // stop() throws if no audio was captured (finger released instantly)
            cancelled = true;
        }
        releaseRecorder();

        // Restore hint
        etMessage.setHint(R.string.chat_hint);
        etMessage.setEnabled(true);

        if (cancelled || voiceTempFile == null || !voiceTempFile.exists()
                || voiceTempFile.length() == 0) {
            // Nothing recorded or user cancelled — discard silently
            if (voiceTempFile != null) voiceTempFile.delete();
            voiceTempFile = null;
            return;
        }

        // Share the temp file via FileProvider and send through Nearby
        try {
            Uri fileUri = FileProvider.getUriForFile(
                    requireContext(),
                    requireContext().getPackageName() + ".fileprovider",
                    voiceTempFile);
            FileMetadata metadata = new FileMetadata(
                    voiceTempFile.getName(), "audio/mp4", voiceTempFile.length());
            ((MainActivity) requireActivity()).onChatSendFile(endpointId, fileUri, metadata);
        } catch (Exception e) {
            Toast.makeText(requireContext(),
                    "Could not send voice message: " + e.getMessage(), Toast.LENGTH_SHORT).show();
        }
        voiceTempFile = null;
    }

    private void releaseRecorder() {
        isRecording = false;
        if (mediaRecorder != null) {
            try { mediaRecorder.release(); } catch (Exception ignored) {}
            mediaRecorder = null;
        }
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
     * Updates the matching message bubble to show the Play / Open button.
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
        long   fileSize = 0;
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
