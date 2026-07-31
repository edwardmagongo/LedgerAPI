package com.edwardmagongo.ledgerapi.transfer;

import com.edwardmagongo.ledgerapi.account.Account;
import com.edwardmagongo.ledgerapi.account.AccountService;
import com.edwardmagongo.ledgerapi.account.Currency;
import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.common.AccountClosedException;
import com.edwardmagongo.ledgerapi.common.CurrencyMismatchException;
import com.edwardmagongo.ledgerapi.common.InsufficientFundsException;
import com.edwardmagongo.ledgerapi.common.SelfTransferException;
import com.edwardmagongo.ledgerapi.transaction.Transaction;
import com.edwardmagongo.ledgerapi.transaction.TransactionRepository;
import com.edwardmagongo.ledgerapi.transaction.TransactionType;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferRequest;
import com.edwardmagongo.ledgerapi.transfer.dto.TransferResponse;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferExecutorTest {

    @Mock AccountService accountService;
    @Mock TransactionRepository transactionRepository;
    @InjectMocks TransferExecutor executor;

    private final User alice = new User("alice@example.com", "hash");
    private final User bob = new User("bob@example.com", "hash");

    private Account funded(User owner, Currency currency, String balance) {
        Account account = new Account(owner, currency);
        account.credit(new BigDecimal(balance));
        return account;
    }

    private TransferRequest request(Account from, Account to, String amount) {
        return new TransferRequest(from.getId(), to.getId(), new BigDecimal(amount));
    }

    @Test
    void movesMoneyAndWritesBothLegsWithSharedTransferId() {
        Account source = funded(alice, Currency.GBP, "100.00");
        Account destination = funded(bob, Currency.GBP, "20.00");
        when(accountService.requireOwnedAndActive(alice.getId(), source.getId())).thenReturn(source);
        when(accountService.requireActiveDestination(destination.getId())).thenReturn(destination);
        when(transactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        TransferResponse response = executor.executeOnce(alice.getId(), request(source, destination, "30.00"));

        assertThat(source.getBalance()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(destination.getBalance()).isEqualByComparingTo(new BigDecimal("50.00"));
        assertThat(response.fromBalanceAfter()).isEqualByComparingTo(new BigDecimal("70.00"));
        assertThat(response.toBalanceAfter()).isEqualByComparingTo(new BigDecimal("50.00"));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Transaction>> captor = ArgumentCaptor.forClass(List.class);
        verify(transactionRepository).saveAll(captor.capture());
        List<Transaction> legs = captor.getValue();

        assertThat(legs).hasSize(2);
        assertThat(legs).allSatisfy(leg -> assertThat(leg.getTransferId()).isEqualTo(response.transferId()));
        assertThat(legs).anySatisfy(leg -> {
            assertThat(leg.getType()).isEqualTo(TransactionType.TRANSFER_OUT);
            assertThat(leg.getAccount()).isSameAs(source);
            assertThat(leg.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("70.00"));
        });
        assertThat(legs).anySatisfy(leg -> {
            assertThat(leg.getType()).isEqualTo(TransactionType.TRANSFER_IN);
            assertThat(leg.getAccount()).isSameAs(destination);
            assertThat(leg.getBalanceAfter()).isEqualByComparingTo(new BigDecimal("50.00"));
        });
    }

    @Test
    void rejectsSelfTransferBeforeLoadingAnyAccount() {
        UUID sameAccount = UUID.randomUUID();

        assertThatThrownBy(() -> executor.executeOnce(alice.getId(),
                new TransferRequest(sameAccount, sameAccount, new BigDecimal("10.00"))))
                .isInstanceOf(SelfTransferException.class);

        verify(accountService, never()).requireOwnedAndActive(any(), any());
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void rejectsInsufficientFundsWithoutMutatingEitherAccount() {
        Account source = funded(alice, Currency.GBP, "100.00");
        Account destination = funded(bob, Currency.GBP, "20.00");
        when(accountService.requireOwnedAndActive(alice.getId(), source.getId())).thenReturn(source);
        when(accountService.requireActiveDestination(destination.getId())).thenReturn(destination);

        assertThatThrownBy(() -> executor.executeOnce(alice.getId(), request(source, destination, "100.01")))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(source.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        assertThat(destination.getBalance()).isEqualByComparingTo(new BigDecimal("20.00"));
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void allowsTransferOfTheEntireBalance() {
        Account source = funded(alice, Currency.GBP, "100.00");
        Account destination = funded(bob, Currency.GBP, "0.00");
        when(accountService.requireOwnedAndActive(alice.getId(), source.getId())).thenReturn(source);
        when(accountService.requireActiveDestination(destination.getId())).thenReturn(destination);
        when(transactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        executor.executeOnce(alice.getId(), request(source, destination, "100.00"));

        assertThat(source.getBalance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(destination.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void rejectsCurrencyMismatch() {
        Account source = funded(alice, Currency.GBP, "100.00");
        Account destination = funded(bob, Currency.USD, "0.00");
        when(accountService.requireOwnedAndActive(alice.getId(), source.getId())).thenReturn(source);
        when(accountService.requireActiveDestination(destination.getId())).thenReturn(destination);

        assertThatThrownBy(() -> executor.executeOnce(alice.getId(), request(source, destination, "10.00")))
                .isInstanceOf(CurrencyMismatchException.class);

        assertThat(source.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
        verify(transactionRepository, never()).saveAll(any());
    }

    @Test
    void propagatesClosedDestination() {
        Account source = funded(alice, Currency.GBP, "100.00");
        UUID closedId = UUID.randomUUID();
        when(accountService.requireOwnedAndActive(alice.getId(), source.getId())).thenReturn(source);
        when(accountService.requireActiveDestination(closedId)).thenThrow(new AccountClosedException(closedId));

        assertThatThrownBy(() -> executor.executeOnce(alice.getId(),
                new TransferRequest(source.getId(), closedId, new BigDecimal("10.00"))))
                .isInstanceOf(AccountClosedException.class);

        assertThat(source.getBalance()).isEqualByComparingTo(new BigDecimal("100.00"));
    }

    @Test
    void transfersToAnotherUsersAccountAreAllowed() {
        Account source = funded(alice, Currency.GBP, "100.00");
        Account bobsAccount = funded(bob, Currency.GBP, "0.00");
        when(accountService.requireOwnedAndActive(alice.getId(), source.getId())).thenReturn(source);
        when(accountService.requireActiveDestination(bobsAccount.getId())).thenReturn(bobsAccount);
        when(transactionRepository.saveAll(any())).thenAnswer(inv -> inv.getArgument(0));

        executor.executeOnce(alice.getId(), request(source, bobsAccount, "25.00"));

        assertThat(bobsAccount.getBalance()).isEqualByComparingTo(new BigDecimal("25.00"));
    }
}
