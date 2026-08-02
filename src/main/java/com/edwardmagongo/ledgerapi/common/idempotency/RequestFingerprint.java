package com.edwardmagongo.ledgerapi.common.idempotency;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Digests the semantically meaningful fields of a request, so that reusing an idempotency key for a
 * genuinely different operation can be detected and rejected rather than silently replayed.
 *
 * <p>The acting user is deliberately excluded: it is already part of the
 * {@code (user_id, idempotency_key)} unique constraint.
 */
public final class RequestFingerprint {

    private static final char FIELD_DELIMITER = '|';

    private RequestFingerprint() {
    }

    public static String of(IdempotentOperation operation, String... fields) {
        StringBuilder canonical = new StringBuilder(operation.name());
        for (String field : fields) {
            // The delimiter is what stops ("ab","c") and ("a","bc") hashing to the same value.
            canonical.append(FIELD_DELIMITER).append(field);
        }
        return sha256Hex(canonical.toString());
    }

    /**
     * Canonicalises a money amount so that 10, 10.0 and 10.00 — the same request written three
     * ways — produce one fingerprint. {@code toPlainString} is required because
     * {@code stripTrailingZeros} renders values like 100.00 in scientific notation (1E+2).
     */
    public static String amount(BigDecimal amount) {
        return amount.stripTrailingZeros().toPlainString();
    }

    private static String sha256Hex(String input) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("every JVM is required to provide SHA-256", ex);
        }
    }
}
