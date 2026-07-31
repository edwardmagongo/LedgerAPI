package com.edwardmagongo.ledgerapi.account.dto;

import com.edwardmagongo.ledgerapi.account.Account;
import com.edwardmagongo.ledgerapi.account.AccountStatus;
import com.edwardmagongo.ledgerapi.account.Currency;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record AccountResponse(
        UUID id,
        Currency currency,
        BigDecimal balance,
        AccountStatus status,
        Instant createdAt) {

    public static AccountResponse from(Account account) {
        return new AccountResponse(
                account.getId(),
                account.getCurrency(),
                account.getBalance(),
                account.getStatus(),
                account.getCreatedAt());
    }
}
