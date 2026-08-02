package com.edwardmagongo.ledgerapi.common.idempotency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RequestFingerprintTest {

    @Test
    void producesA64CharLowercaseHexDigest() {
        String fingerprint = RequestFingerprint.of(IdempotentOperation.TRANSFER, "a", "b");

        assertThat(fingerprint).hasSize(64).matches("[0-9a-f]{64}");
    }

    @Test
    void isStableForTheSameInputs() {
        String first = RequestFingerprint.of(IdempotentOperation.DEPOSIT, "acct", "10");
        String second = RequestFingerprint.of(IdempotentOperation.DEPOSIT, "acct", "10");

        assertThat(first).isEqualTo(second);
    }

    @Test
    void differsWhenTheOperationDiffers() {
        assertThat(RequestFingerprint.of(IdempotentOperation.DEPOSIT, "acct", "10"))
                .isNotEqualTo(RequestFingerprint.of(IdempotentOperation.WITHDRAWAL, "acct", "10"));
    }

    @Test
    void differsWhenAnyFieldDiffers() {
        String base = RequestFingerprint.of(IdempotentOperation.TRANSFER, "from", "to", "10");

        assertThat(RequestFingerprint.of(IdempotentOperation.TRANSFER, "from", "other", "10"))
                .isNotEqualTo(base);
        assertThat(RequestFingerprint.of(IdempotentOperation.TRANSFER, "from", "to", "20"))
                .isNotEqualTo(base);
    }

    @Test
    void fieldBoundariesCannotBeForged() {
        // Without a delimiter, ("ab","c") and ("a","bc") would hash identically.
        assertThat(RequestFingerprint.of(IdempotentOperation.TRANSFER, "ab", "c"))
                .isNotEqualTo(RequestFingerprint.of(IdempotentOperation.TRANSFER, "a", "bc"));
    }

    @Test
    void amountIgnoresTrailingZeroDifferences() {
        assertThat(RequestFingerprint.amount(new BigDecimal("10.00")))
                .isEqualTo(RequestFingerprint.amount(new BigDecimal("10.0")))
                .isEqualTo(RequestFingerprint.amount(new BigDecimal("10")));
    }

    @Test
    void amountNeverUsesScientificNotation() {
        // stripTrailingZeros() alone renders this as 1E+2.
        assertThat(RequestFingerprint.amount(new BigDecimal("100.00"))).isEqualTo("100");
    }

    @Test
    void amountKeepsSignificantDecimals() {
        assertThat(RequestFingerprint.amount(new BigDecimal("10.25"))).isEqualTo("10.25");
    }

    @Test
    void equalAmountsWrittenDifferentlyFingerprintIdentically() {
        UUID from = UUID.randomUUID();
        UUID to = UUID.randomUUID();

        String a = RequestFingerprint.of(IdempotentOperation.TRANSFER,
                from.toString(), to.toString(), RequestFingerprint.amount(new BigDecimal("10.00")));
        String b = RequestFingerprint.of(IdempotentOperation.TRANSFER,
                from.toString(), to.toString(), RequestFingerprint.amount(new BigDecimal("10")));

        assertThat(a).isEqualTo(b);
    }
}
