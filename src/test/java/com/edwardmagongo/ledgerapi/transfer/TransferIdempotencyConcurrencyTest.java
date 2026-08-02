package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.account.Account;
import com.edwardmagongo.ledgerapi.account.AccountRepository;
import com.edwardmagongo.ledgerapi.account.Currency;
import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.common.ApiException;
import com.edwardmagongo.ledgerapi.common.idempotency.IdempotencyKeyRepository;
import com.edwardmagongo.ledgerapi.common.idempotency.IdempotentOutcome;
import com.edwardmagongo.ledgerapi.support.AbstractIntegrationTest;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import com.edwardmagongo.ledgerapi.transaction.TransactionService;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves that an idempotency key collapses a burst of identical retries into exactly one movement.
 *
 * <p>Like {@code TransferConcurrencyTest}, this class is deliberately NOT {@code @Transactional}:
 * one outer transaction would remove the row contention and the claim would never be contended.
 */
class TransferIdempotencyConcurrencyTest extends AbstractIntegrationTest {

    private static final int THREADS = 20;

    @Autowired TransferService transferService;
    @Autowired TransactionService transactionService;
    @Autowired IdempotencyKeyRepository idempotencyKeyRepository;
    @Autowired AccountRepository accountRepository;
    @Autowired UserRepository userRepository;
    @Autowired TransactionRepository transactionRepository;

    private User alice;
    private User bob;

    @BeforeEach
    void setUp() {
        idempotencyKeyRepository.deleteAll();
        transactionRepository.deleteAll();
        accountRepository.deleteAll();
        userRepository.deleteAll();
        alice = userRepository.save(new User("alice@example.com", "hash"));
        bob = userRepository.save(new User("bob@example.com", "hash"));
    }

    private Account fundedAccount(User owner, String opening) {
        Account account = accountRepository.save(new Account(owner, Currency.GBP));
        BigDecimal amount = new BigDecimal(opening);
        if (amount.signum() > 0) {
            transactionService.deposit(owner.getId(), account.getId(), new AmountRequest(amount));
        }
        return account;
    }

    private BigDecimal balanceOf(Account account) {
        return accountRepository.findById(account.getId()).orElseThrow().getBalance();
    }

    private <T> List<Future<T>> runConcurrently(Callable<T> task) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CountDownLatch startGate = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                futures.add(pool.submit(() -> {
                    startGate.await();
                    return task.call();
                }));
            }
            startGate.countDown();
            pool.shutdown();
            assertThat(pool.awaitTermination(60, TimeUnit.SECONDS))
                    .as("all threads should finish within 60s")
                    .isTrue();
            return futures;
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twentySimultaneousRetriesOfOneKeyMoveTheMoneyExactlyOnce() throws Exception {
        Account source = fundedAccount(alice, "1000.00");
        Account destination = fundedAccount(bob, "0.00");
        TransferRequest request =
                new TransferRequest(source.getId(), destination.getId(), new BigDecimal("10.00"));

        List<Future<Object>> futures = runConcurrently(() -> {
            try {
                return transferService.transfer(alice.getId(), "burst-key", request);
            } catch (ApiException ex) {
                // A duplicate that arrives while the winner is still committing is told the key is
                // in flight; that is a correct outcome, not a failure.
                return ex;
            }
        });

        int executed = 0;
        int replayed = 0;
        int rejected = 0;
        for (Future<Object> future : futures) {
            Object result = future.get();
            if (result instanceof IdempotentOutcome.Executed<?>) {
                executed++;
            } else if (result instanceof IdempotentOutcome.Replayed<?>) {
                replayed++;
            } else {
                rejected++;
            }
        }

        assertThat(executed).as("exactly one thread may perform the transfer").isEqualTo(1);
        assertThat(replayed + rejected)
                .as("every other thread must replay or be told the key is in flight")
                .isEqualTo(THREADS - 1);

        assertThat(balanceOf(source))
                .as("money must move exactly once despite %d simultaneous attempts", THREADS)
                .isEqualByComparingTo(new BigDecimal("990.00"));
        assertThat(balanceOf(destination)).isEqualByComparingTo(new BigDecimal("10.00"));

        assertThat(transactionRepository.findByAccountId(destination.getId()))
                .as("exactly one transfer-in leg")
                .hasSize(1);
        assertThat(idempotencyKeyRepository.count()).isEqualTo(1);
    }

    @Test
    void everyReplayedBodyIsIdenticalToTheOneThatExecuted() throws Exception {
        Account source = fundedAccount(alice, "1000.00");
        Account destination = fundedAccount(bob, "0.00");
        TransferRequest request =
                new TransferRequest(source.getId(), destination.getId(), new BigDecimal("25.00"));

        // Establish the stored response first, then hammer it with pure replays.
        IdempotentOutcome<TransferResponse> first =
                transferService.transfer(alice.getId(), "replay-key", request);
        assertThat(first).isInstanceOf(IdempotentOutcome.Executed.class);

        List<Future<IdempotentOutcome<TransferResponse>>> futures = runConcurrently(
                () -> transferService.transfer(alice.getId(), "replay-key", request));

        for (Future<IdempotentOutcome<TransferResponse>> future : futures) {
            assertThat(future.get()).isInstanceOf(IdempotentOutcome.Replayed.class);
        }

        assertThat(balanceOf(source)).isEqualByComparingTo(new BigDecimal("975.00"));
        assertThat(transactionRepository.findByAccountId(destination.getId())).hasSize(1);
    }
}
