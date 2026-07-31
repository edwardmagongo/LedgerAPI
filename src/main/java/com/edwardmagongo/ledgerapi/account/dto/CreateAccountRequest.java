package com.edwardmagongo.ledgerapi.account.dto;

import com.edwardmagongo.ledgerapi.account.Currency;
import jakarta.validation.constraints.NotNull;

public record CreateAccountRequest(@NotNull Currency currency) {
}
