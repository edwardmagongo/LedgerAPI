package com.edwardmagongo.ledgerapi.transaction.dto;

import com.edwardmagongo.ledgerapi.transaction.Transaction;
import com.edwardmagongo.ledgerapi.transaction.TransactionType;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record TransactionResponse(
        UUID id,
        UUID accountId,
        TransactionType type,
        BigDecimal amount,
        BigDecimal balanceAfter,
        UUID transferId,
        Instant createdAt) {

    public static TransactionResponse from(Transaction transaction) {
        return new TransactionResponse(
                transaction.getId(),
                transaction.getAccount().getId(),
                transaction.getType(),
                transaction.getAmount(),
                transaction.getBalanceAfter(),
                transaction.getTransferId(),
                transaction.getCreatedAt());
    }
}
