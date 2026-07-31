package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.account.Account;
import com.edwardmagongo.ledgerapi.account.AccountService;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transaction.dto.TransactionResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class TransactionExecutor {

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;

    public TransactionExecutor(AccountService accountService, TransactionRepository transactionRepository) {
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransactionResponse deposit(UUID userId, UUID accountId, AmountRequest request) {
        Account account = accountService.requireOwnedAndActive(userId, accountId);
        account.credit(request.amount());
        return TransactionResponse.from(transactionRepository.save(
                new Transaction(account, TransactionType.DEPOSIT, request.amount(), account.getBalance(), null)));
    }

    @Transactional
    public TransactionResponse withdraw(UUID userId, UUID accountId, AmountRequest request) {
        Account account = accountService.requireOwnedAndActive(userId, accountId);
        account.debit(request.amount());
        return TransactionResponse.from(transactionRepository.save(
                new Transaction(account, TransactionType.WITHDRAWAL, request.amount(), account.getBalance(), null)));
    }
}
