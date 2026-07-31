package com.edwardmagongo.ledgerapi.transaction;

import com.edwardmagongo.ledgerapi.account.Account;
import com.edwardmagongo.ledgerapi.account.AccountService;
import com.edwardmagongo.ledgerapi.account.Currency;
import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.common.InsufficientFundsException;
import com.edwardmagongo.ledgerapi.transaction.dto.AmountRequest;
import com.edwardmagongo.ledgerapi.transaction.dto.TransactionResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransactionExecutorTest {

    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks TransactionExecutor executor;

    private final User owner = new User("alice@example.com", "hash");

    @Test
    void depositIncreasesBalanceAndRecordsBalanceAfter() {
        Account account = new Account(owner, Currency.GBP);
        account.credit(new BigDecimal("50.00"));
        when(accountService.requireOwnedAndActive(owner.getId(), account.getId())).thenReturn(account);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response = executor.deposit(owner.getId(), account.getId(),
                new AmountRequest(new BigDecimal("25.50")));

        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("75.50"));
        assertThat(response.type()).isEqualTo(TransactionType.DEPOSIT);
        assertThat(response.balanceAfter()).isEqualByComparingTo(new BigDecimal("75.50"));
        assertThat(response.transferId()).isNull();
    }

    @Test
    void withdrawDecreasesBalance() {
        Account account = new Account(owner, Currency.GBP);
        account.credit(new BigDecimal("50.00"));
        when(accountService.requireOwnedAndActive(owner.getId(), account.getId())).thenReturn(account);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        TransactionResponse response = executor.withdraw(owner.getId(), account.getId(),
                new AmountRequest(new BigDecimal("20.00")));

        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("30.00"));
        assertThat(response.type()).isEqualTo(TransactionType.WITHDRAWAL);
    }

    @Test
    void withdrawBeyondBalanceThrowsAndWritesNoTransaction() {
        Account account = new Account(owner, Currency.GBP);
        account.credit(new BigDecimal("50.00"));
        when(accountService.requireOwnedAndActive(owner.getId(), account.getId())).thenReturn(account);

        assertThatThrownBy(() -> executor.withdraw(owner.getId(), account.getId(),
                new AmountRequest(new BigDecimal("50.01"))))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
        verify(transactionRepository, never()).save(any());
    }

    @Test
    void depositRecordsAmountExactlyAsSubmitted() {
        Account account = new Account(owner, Currency.GBP);
        when(accountService.requireOwnedAndActive(owner.getId(), account.getId())).thenReturn(account);
        when(transactionRepository.save(any(Transaction.class))).thenAnswer(inv -> inv.getArgument(0));

        executor.deposit(owner.getId(), account.getId(), new AmountRequest(new BigDecimal("0.01")));

        ArgumentCaptor<Transaction> saved = ArgumentCaptor.forClass(Transaction.class);
        verify(transactionRepository).save(saved.capture());
        assertThat(saved.getValue().getAmount()).isEqualByComparingTo(new BigDecimal("0.01"));
        assertThat(saved.getValue().getTransferId()).isNull();
    }

    @Test
    void executorDelegatesOwnershipAndActiveChecksToAccountService() {
        UUID accountId = UUID.randomUUID();
        when(accountService.requireOwnedAndActive(owner.getId(), accountId))
                .thenThrow(new com.edwardmagongo.ledgerapi.common.AccountNotOwnedException());

        assertThatThrownBy(() -> executor.deposit(owner.getId(), accountId,
                new AmountRequest(new BigDecimal("1.00"))))
                .isInstanceOf(com.edwardmagongo.ledgerapi.common.AccountNotOwnedException.class);

        verify(transactionRepository, never()).save(any());
    }
}
