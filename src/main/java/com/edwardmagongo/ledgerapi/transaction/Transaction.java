package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.account.Account;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transactions")
public class Transaction {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_id", nullable = false)
    private Account account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private TransactionType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "balance_after", nullable = false, precision = 19, scale = 4)
    private BigDecimal balanceAfter;

    @Column(name = "transfer_id")
    private UUID transferId;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected Transaction() {
        // required by JPA
    }

    public Transaction(Account account, TransactionType type, BigDecimal amount,
                       BigDecimal balanceAfter, UUID transferId) {
        this.id = UUID.randomUUID();
        this.account = account;
        this.type = type;
        this.amount = amount;
        this.balanceAfter = balanceAfter;
        this.transferId = transferId;
        this.createdAt = Instant.now();
    }

    public UUID getId() { return id; }
    public Account getAccount() { return account; }
    public TransactionType getType() { return type; }
    public BigDecimal getAmount() { return amount; }
    public BigDecimal getBalanceAfter() { return balanceAfter; }
    public UUID getTransferId() { return transferId; }
    public Instant getCreatedAt() { return createdAt; }
}
