package com.example.csci3310_airdrop_proj.network.protocol;

/**
 * A Strategy for decoding one kind of Nearby Connections BYTES payload.
 *
 * Each implementation claims a specific wire format (e.g. "CHAT|..." text
 * messages, "CHATFILE|..." inline files). The {@link PayloadDispatcher} walks
 * a list of codecs until one returns {@code true} from
 * {@link #tryDecode(String, byte[], PayloadSink)} — this is a Chain of
 * Responsibility on the receive side.
 *
 * Encoding is codec-specific and typically exposed as static methods on the
 * implementing class, because each wire format takes different arguments.
 */
public interface PayloadCodec {

    /**
     * Attempt to decode {@code bytes}. If this codec recognises the format,
     * parse it, invoke the appropriate method on {@code sink}, and return
     * {@code true}. Otherwise return {@code false} without touching the sink.
     *
     * Implementations should do the prefix check first — it must be cheap
     * because the dispatcher may call this method many times before finding
     * the right codec.
     *
     * @param endpointId the Nearby endpoint that sent the bytes (opaque string)
     * @param bytes      raw payload bytes; never null, never empty
     * @param sink       where to deliver the parsed event
     * @return {@code true} if this codec consumed the payload
     */
    boolean tryDecode(String endpointId, byte[] bytes, PayloadSink sink);
}
