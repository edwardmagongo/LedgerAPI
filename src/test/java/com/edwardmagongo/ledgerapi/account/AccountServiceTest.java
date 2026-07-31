package com.edwardmagongo.ledgerapi.account;

import com.edwardmagongo.ledgerapi.account.dto.AccountResponse;
import com.edwardmagongo.ledgerapi.account.dto.CreateAccountRequest;
import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.common.AccountClosedException;
import com.edwardmagongo.ledgerapi.common.AccountNotEmptyException;
import com.edwardmagongo.ledgerapi.common.AccountNotFoundException;
import com.edwardmagongo.ledgerapi.common.AccountNotOwnedException;
import com.edwardmagongo.ledgerapi.common.InsufficientFundsException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock AccountRepository accountRepository;
    @Mock UserRepository userRepository;
    @InjectMocks AccountService accountService;

    private final User owner = new User("alice@example.com", "hash");
    private final User stranger = new User("bob@example.com", "hash");

    @Test
    void createStartsAtZeroBalanceAndActive() {
        when(userRepository.findById(owner.getId())).thenReturn(Optional.of(owner));
        when(accountRepository.save(any(Account.class))).thenAnswer(inv -> inv.getArgument(0));

        AccountResponse response = accountService.create(owner.getId(), new CreateAccountRequest(Currency.GBP));

        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.status()).isEqualTo(AccountStatus.ACTIVE);
        assertThat(response.currency()).isEqualTo(Currency.GBP);
    }

    @Test
    void requireOwnedRejectsAccountBelongingToAnotherUser() {
        Account account = new Account(owner, Currency.GBP);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.requireOwned(stranger.getId(), account.getId()))
                .isInstanceOf(AccountNotOwnedException.class);
    }

    @Test
    void requireOwnedRejectsMissingAccount() {
        UUID missing = UUID.randomUUID();
        when(accountRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.requireOwned(owner.getId(), missing))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void requireOwnedAndActiveRejectsClosedAccount() {
        Account account = new Account(owner, Currency.GBP);
        account.close();
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.requireOwnedAndActive(owner.getId(), account.getId()))
                .isInstanceOf(AccountClosedException.class);
    }

    @Test
    void closeRejectsAccountWithNonZeroBalance() {
        Account account = new Account(owner, Currency.GBP);
        account.credit(new BigDecimal("0.01"));
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.close(owner.getId(), account.getId()))
                .isInstanceOf(AccountNotEmptyException.class);
        assertThat(account.getStatus()).isEqualTo(AccountStatus.ACTIVE);
    }

    @Test
    void closeMarksEmptyAccountClosed() {
        Account account = new Account(owner, Currency.GBP);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        accountService.close(owner.getId(), account.getId());

        assertThat(account.getStatus()).isEqualTo(AccountStatus.CLOSED);
    }

    @Test
    void debitRejectsAmountGreaterThanBalance() {
        Account account = new Account(owner, Currency.GBP);
        account.credit(new BigDecimal("100.00"));

        assertThatThrownBy(() -> account.debit(new BigDecimal("100.01")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void debitAllowsExactBalance() {
        Account account = new Account(owner, Currency.GBP);
        account.credit(new BigDecimal("100.00"));

        account.debit(new BigDecimal("100.00"));

        assertThat(account.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
    }

    @Test
    void requireActiveDestinationAllowsAnotherUsersAccount() {
        Account account = new Account(stranger, Currency.GBP);
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThat(accountService.requireActiveDestination(account.getId())).isSameAs(account);
    }

    @Test
    void requireActiveDestinationRejectsClosedAccount() {
        Account account = new Account(stranger, Currency.GBP);
        account.close();
        when(accountRepository.findById(account.getId())).thenReturn(Optional.of(account));

        assertThatThrownBy(() -> accountService.requireActiveDestination(account.getId()))
                .isInstanceOf(AccountClosedException.class);
    }

    @Test
    void requireActiveDestinationRejectsMissingAccount() {
        UUID missing = UUID.randomUUID();
        when(accountRepository.findById(missing)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.requireActiveDestination(missing))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
