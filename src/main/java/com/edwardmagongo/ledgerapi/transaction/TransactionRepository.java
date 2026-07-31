package com.edwardmagongo.ledgerapi.transaction;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TransactionRepository extends JpaRepository<Transaction, UUID> {
    List<Transaction> findByTransferId(UUID transferId);
    List<Transaction> findByAccountId(UUID accountId);
}
