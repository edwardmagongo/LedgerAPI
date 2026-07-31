package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.account.Account;
import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.account.Currency;
import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.common.InsufficientFundsException;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.transaction.Transaction;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import com.edwardmagongo.ledgerapi.transaction.TransactionService;
import com.edwardmagongo.ledgerapi.transaction.TransactionType;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that concurrent transfers cannot corrupt a balance.
 *
 * <p>Note what is deliberately absent: this class is NOT {@code @Transactional}. A transactional
 * test would wrap every operation in one outer transaction, so the worker threads would never
 * actually contend for rows and the test would pass regardless of whether the locking works.
 */
class TransferConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 20;

    @Autowired TransferService transferService;
    @Autowired TransactionService transactionService;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionRepository transactionRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(new User("alice@example.com", "hash"));
        bob = userRepository.save(new User("bob@example.com", "hash"));
    }

    private Account fundedAccount(User owner, String openingBalance) {
        Account account = accountRepository.save(new Account(owner, Currency.GBP));
        BigDecimal opening = new BigDecimal(openingBalance);
        // Guarded: calling the service directly bypasses Bean Validation (no @Valid on a direct
        // call), so a zero deposit would reach the database and violate CHECK (amount > 0).
        if (opening.signum() > 0) {
            transactionService.deposit(owner.getId(), account.getId(), new AmountRequest(opening));
        }
        return account;
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    /**
     * Recomputes the balance from the transaction log alone and asserts it matches the stored
     * balance column. Catches the class of bug where the balance is right but the audit trail
     * is not, or vice versa.
     */
    private void assertLedgerReconciles(Account account) {
        BigDecimal fromLog = transactionRepository.findByAccountId(account.getId()).stream()
                .map(this::signedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        assertThat(fromLog)
                .as("transaction log for account %s must reconcile with its stored balance", account.getId())
                .isEqualByComparingTo(balanceOf(account));
    }

    private BigDecimal signedAmount(Transaction transaction) {
        return transaction.getType() == TransactionType.DEPOSIT
                || transaction.getType() == TransactionType.TRANSFER_IN
                ? transaction.getAmount()
                : transaction.getAmount().negate();
    }

    /** Runs {@code task} on {@code THREADS} threads released simultaneously; returns each result. */
    private <T> List<Future<T>> runConcurrently(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new java.util.ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                    .as("all transfer threads should finish within 60s")
                    .isTrue();
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void concurrentTransfersOutOfOneAccountProduceTheExactExpectedBalance() throws Exception {
        Account source = fundedAccount(alice, "1000.00");
        Account destination = fundedAccount(bob, "0.00");

        List<Future<Object>> futures = runConcurrently(() -> transferService.transfer(
                alice.getId(),
                new TransferRequest(source.getId(), destination.getId(), new BigDecimal("10.00"))));

        for (Future<Object> future : futures) {
            future.get(); // rethrows if any transfer failed unexpectedly
        }

        assertThat(balanceOf(source)).isEqualByComparingTo(new BigDecimal("800.00"));
        assertThat(balanceOf(destination)).isEqualByComparingTo(new BigDecimal("200.00"));

        assertThat(transactionRepository.findByAccountId(source.getId()))
                .as("one deposit plus %d transfer-out legs", THREADS)
                .hasSize(THREADS + 1);
        assertThat(transactionRepository.findByAccountId(destination.getId()))
                .as("%d transfer-in legs (the destination opened at zero, so no deposit row)", THREADS)
                .hasSize(THREADS);

        assertLedgerReconciles(source);
        assertLedgerReconciles(destination);
    }

    @Test
    void concurrentTransfersIntoOneAccountFromManySourcesProduceTheExactExpectedBalance() throws Exception {
        Account shared = fundedAccount(bob, "0.00");
        List<Account> sources = new java.util.ArrayList<>();
        for (int i = 0; i < THREADS; i++) {
            sources.add(fundedAccount(alice, "50.00"));
        }
        AtomicInteger index = new AtomicInteger();

        List<Future<Object>> futures = runConcurrently(() -> {
            Account source = sources.get(index.getAndIncrement());
            return transferService.transfer(alice.getId(),
                    new TransferRequest(source.getId(), shared.getId(), new BigDecimal("50.00")));
        });

        for (Future<Object> future : futures) {
            future.get();
        }

        assertThat(balanceOf(shared)).isEqualByComparingTo(new BigDecimal("1000.00"));
        sources.forEach(source -> assertThat(balanceOf(source)).isEqualByComparingTo(BigDecimal.ZERO));
        assertLedgerReconciles(shared);
    }

    @Test
    void concurrentBidirectionalTransfersDoNotDeadlockAndNetToZero() throws Exception {
        Account left = fundedAccount(alice, "500.00");
        Account right = fundedAccount(bob, "500.00");
        AtomicInteger counter = new AtomicInteger();

        List<Future<Object>> futures = runConcurrently(() -> {
            boolean leftToRight = counter.getAndIncrement() % 2 == 0;
            UUID from = leftToRight ? left.getId() : right.getId();
            UUID to = leftToRight ? right.getId() : left.getId();
            UUID actingUser = leftToRight ? alice.getId() : bob.getId();
            return transferService.transfer(actingUser, new TransferRequest(from, to, new BigDecimal("5.00")));
        });

        for (Future<Object> future : futures) {
            future.get(); // a Postgres deadlock that escaped the retry would surface here
        }

        assertThat(balanceOf(left)).isEqualByComparingTo(new BigDecimal("500.00"));
        assertThat(balanceOf(right)).isEqualByComparingTo(new BigDecimal("500.00"));
        assertLedgerReconciles(left);
        assertLedgerReconciles(right);
    }

    @Test
    void concurrentOverdraftAttemptsNeverDriveTheBalanceNegative() throws Exception {
        // Only 5 of the 20 transfers can legitimately succeed.
        Account source = fundedAccount(alice, "50.00");
        Account destination = fundedAccount(bob, "0.00");

        List<Future<Boolean>> futures = runConcurrently(() -> {
            try {
                transferService.transfer(alice.getId(),
                        new TransferRequest(source.getId(), destination.getId(), new BigDecimal("10.00")));
                return true;
            } catch (InsufficientFundsException ex) {
                return false;
            }
        });

        int succeeded = 0;
        for (Future<Boolean> future : futures) {
            if (future.get()) {
                succeeded++;
            }
        }

        assertThat(succeeded).as("exactly five 10.00 transfers fit in a 50.00 balance").isEqualTo(5);
        assertThat(balanceOf(source)).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(balanceOf(destination)).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(balanceOf(source).signum()).as("balance must never go negative").isNotNegative();
        assertLedgerReconciles(source);
        assertLedgerReconciles(destination);
    }

    @Test
    void concurrentDepositsOnOneAccountAreAllApplied() throws Exception {
        Account account = fundedAccount(alice, "0.00");

        List<Future<Object>> futures = runConcurrently(() -> transactionService.deposit(
                alice.getId(), account.getId(), new AmountRequest(new BigDecimal("7.50"))));

        for (Future<Object> future : futures) {
            future.get();
        }

        assertThat(balanceOf(account)).isEqualByComparingTo(new BigDecimal("150.00"));
        assertLedgerReconciles(account);
    }
}
