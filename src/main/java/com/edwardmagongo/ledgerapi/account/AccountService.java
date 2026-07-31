package com.edwardmagongo.ledgerapi.account;

import com.edwardmagongo.ledgerapi.account.dto.AccountResponse;
import com.edwardmagongo.ledgerapi.account.dto.CreateAccountRequest;
import com.edwardmagongo.ledgerapi.auth.User;
import com.edwardmagongo.ledgerapi.auth.UserRepository;
import com.edwardmagongo.ledgerapi.common.AccountClosedException;
import com.edwardmagongo.ledgerapi.common.AccountNotEmptyException;
import com.edwardmagongo.ledgerapi.common.AccountNotFoundException;
import com.edwardmagongo.ledgerapi.common.AccountNotOwnedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

@Service
public class AccountService {

    private final AccountRepository accountRepository;
    private final UserRepository userRepository;

    public AccountService(AccountRepository accountRepository, UserRepository userRepository) {
        this.accountRepository = accountRepository;
        this.userRepository = userRepository;
    }

    @Transactional
    public AccountResponse create(UUID userId, CreateAccountRequest request) {
        User owner = userRepository.findById(userId).orElseThrow(AccountNotOwnedException::new);
        return AccountResponse.from(accountRepository.save(new Account(owner, request.currency())));
    }

    @Transactional(readOnly = true)
    public List<AccountResponse> list(UUID userId) {
        return accountRepository.findByOwnerIdOrderByCreatedAtDesc(userId).stream()
                .map(AccountResponse::from)
                .toList();
    }

    @Transactional(readOnly = true)
    public AccountResponse get(UUID userId, UUID accountId) {
        return AccountResponse.from(requireOwned(userId, accountId));
    }

    @Transactional
    public void close(UUID userId, UUID accountId) {
        Account account = requireOwned(userId, accountId);
        if (account.getBalance().compareTo(BigDecimal.ZERO) != 0) {
            throw new AccountNotEmptyException(accountId);
        }
        account.close();
    }

    /** Loads an account, failing if it does not exist or is not owned by the given user. */
    @Transactional(readOnly = true)
    public Account requireOwned(UUID userId, UUID accountId) {
        Account account = accountRepository.findById(accountId)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.getOwner().getId().equals(userId)) {
            throw new AccountNotOwnedException();
        }
        return account;
    }

    /** As {@link #requireOwned}, and additionally rejects closed accounts. */
    @Transactional(readOnly = true)
    public Account requireOwnedAndActive(UUID userId, UUID accountId) {
        Account account = requireOwned(userId, accountId);
        if (!account.isActive()) {
            throw new AccountClosedException(accountId);
        }
        return account;
    }
}
