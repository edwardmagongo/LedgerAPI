package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.account.Account;
import com.edwardmagongo.ledgerapi.account.AccountService;
import com.edwardmagongo.ledgerapi.common.CurrencyMismatchException;
import com.edwardmagongo.ledgerapi.common.InsufficientFundsException;
import com.edwardmagongo.ledgerapi.common.SelfTransferException;
import com.edwardmagongo.ledgerapi.transaction.Transaction;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import com.edwardmagongo.ledgerapi.transaction.TransactionType;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Performs exactly one transfer attempt inside a single transaction: either both the debit and
 * the credit commit, or neither does. Retrying is deliberately <em>not</em> done here — see
 * {@link TransferService}.
 */
@Service
public class TransferExecutor {

    private final AccountService accountService;
    private final TransactionRepository transactionRepository;

    public TransferExecutor(AccountService accountService, TransactionRepository transactionRepository) {
        this.accountService = accountService;
        this.transactionRepository = transactionRepository;
    }

    @Transactional
    public TransferResponse executeOnce(UUID userId, TransferRequest request) {
        // Guarded before loading: the same row loaded twice into one persistence context would
        // be a single entity instance, so a self-transfer would double-apply the mutation.
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new SelfTransferException();
        }

        Account source = accountService.requireOwnedAndActive(userId, request.fromAccountId());
        Account destination = accountService.requireActiveDestination(request.toAccountId());

        if (source.getCurrency() != destination.getCurrency()) {
            throw new CurrencyMismatchException();
        }

        BigDecimal amount = request.amount();
        if (source.getBalance().compareTo(amount) < 0) {
            throw new InsufficientFundsException();
        }

        // Mutate in a deterministic order so concurrent transfers in opposite directions take
        // their row locks in the same sequence. hibernate.order_updates=true additionally sorts
        // the flush-time UPDATE statements by primary key, which is what actually fixes the
        // statement order sent to Postgres.
        List<Account> ordered = List.of(source, destination).stream()
                .sorted(Comparator.comparing(Account::getId))
                .toList();
        for (Account account : ordered) {
            if (account.getId().equals(source.getId())) {
                account.debit(amount);
            } else {
                account.credit(amount);
            }
        }

        UUID transferId = UUID.randomUUID();
        transactionRepository.saveAll(List.of(
                new Transaction(source, TransactionType.TRANSFER_OUT, amount, source.getBalance(), transferId),
                new Transaction(destination, TransactionType.TRANSFER_IN, amount, destination.getBalance(), transferId)));

        return new TransferResponse(
                transferId,
                source.getId(),
                destination.getId(),
                amount,
                source.getBalance(),
                destination.getBalance(),
                Instant.now());
    }
}
