package com.example.csci3310_airdrop_proj.network.protocol;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Routes incoming BYTES payloads to the first {@link PayloadCodec} that can
 * decode them, and delivers the result to a {@link PayloadSink}.
 *
 * Codecs are tried in registration order, so register more specific codecs
 * first (e.g. {@code CHATFILE|} before {@code FILE|} — both start with "F"
 * only after the prefix, but keeping strict order removes any ambiguity).
 *
 * Bytes that no codec claims are reported via
 * {@link PayloadSink#onUnknownPayload(String, byte[])}. This is an important
 * behavioural change from the old if/else chain, which silently parsed
 * unknown bytes as a FileMetadata — and produced junk values when the bytes
 * weren't actually metadata.
 */
public final class PayloadDispatcher {

    private final List<PayloadCodec> codecs;
    private final PayloadSink sink;

    public PayloadDispatcher(PayloadSink sink, PayloadCodec... codecs) {
        this.sink = sink;
        this.codecs = Collections.unmodifiableList(Arrays.asList(codecs.clone()));
    }

    /** Route {@code bytes} to the first matching codec. No-op if bytes is null or empty. */
    public void dispatch(String endpointId, byte[] bytes) {
        if (bytes == null || bytes.length == 0) return;
        for (PayloadCodec codec : codecs) {
            if (codec.tryDecode(endpointId, bytes, sink)) return;
        }
        sink.onUnknownPayload(endpointId, bytes);
    }

    /** Number of codecs registered. Test-visible. */
    int codecCount() {
        return codecs.size();
    }
}
