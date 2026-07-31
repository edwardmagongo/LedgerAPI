package com.edwardmagongo.ledgerapi.transaction.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import java.math.BigDecimal;

public record AmountRequest(
        @NotNull
        @DecimalMin(value = "0.00", inclusive = false, message = "amount must be greater than zero")
        @Digits(integer = 15, fraction = 2, message = "amount must have at most 2 decimal places")
        BigDecimal amount) {
}
