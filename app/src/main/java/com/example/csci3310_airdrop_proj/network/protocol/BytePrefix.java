package com.example.csci3310_airdrop_proj.network.protocol;

import java.nio.charset.StandardCharsets;

/**
 * Byte-level utilities used by {@link PayloadCodec} implementations.
 *
 * Payloads can be large (an inline image or voice clip sent via {@code CHATFILE}
 * carries the raw file bytes appended after the header). We do not want to
 * construct a {@code new String(bytes)} for a multi-megabyte buffer just to
 * check whether the first nine characters read {@code "CHATFILE|"}. These
 * helpers do the check on the raw byte array in O(prefix length) time.
 */
final class BytePrefix {

    private BytePrefix() {}

    /** True iff {@code data} starts with the UTF-8 encoding of {@code prefix}. */
    static boolean startsWith(byte[] data, String prefix) {
        byte[] p = prefix.getBytes(StandardCharsets.UTF_8);
        if (data.length < p.length) return false;
        for (int i = 0; i < p.length; i++) {
            if (data[i] != p[i]) return false;
        }
        return true;
    }

    /**
     * Index of the n-th occurrence (1-indexed) of {@code delimiter} in
     * {@code data}, or -1 if not found. Used to locate the header/body
     * boundary in payloads that mix UTF-8 text and binary data.
     */
    static int indexOfNth(byte[] data, byte delimiter, int n) {
        int seen = 0;
        for (int i = 0; i < data.length; i++) {
            if (data[i] == delimiter) {
                seen++;
                if (seen == n) return i;
            }
        }
        return -1;
    }
}
