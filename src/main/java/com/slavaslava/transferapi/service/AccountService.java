package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Deposit;
import com.slavaslava.transferapi.dto.AccountResponse;
import com.slavaslava.transferapi.dto.CreateAccountRequest;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.exception.IdempotencyKeyReuseException;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.DepositRepository;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class AccountService {

    private static final int MAX_LOCK_ATTEMPTS = 3;

    private final AccountRepository accountRepository;
    private final DepositRepository depositRepository;
    private final DepositTransactionExecutor depositTransactionExecutor;

    public AccountService(AccountRepository accountRepository, DepositRepository depositRepository,
                           DepositTransactionExecutor depositTransactionExecutor) {
        this.accountRepository = accountRepository;
        this.depositRepository = depositRepository;
        this.depositTransactionExecutor = depositTransactionExecutor;
    }

    @Transactional
    public AccountResponse createAccount(CreateAccountRequest request) {
        BigDecimal initialBalance = request.initialBalance() != null ? request.initialBalance() : BigDecimal.ZERO;
        Account account = new Account(request.owner(), initialBalance, request.currency());
        return AccountResponse.from(accountRepository.save(account));
    }

    @Transactional(readOnly = true)
    public AccountResponse getAccount(Long id) {
        return AccountResponse.from(findAccountOrThrow(id));
    }

    /** The newest {@code limit} accounts, newest first (used by the terminal UI's account list). */
    @Transactional(readOnly = true)
    public List<AccountResponse> listNewestAccounts(int limit) {
        return accountRepository.findAll(PageRequest.of(0, limit, Sort.by(Sort.Direction.DESC, "id")))
                .map(AccountResponse::from)
                .getContent();
    }

    // deliberately not @Transactional: each retry attempt must run in its own transaction
    // so a failed optimistic lock reloads fresh account state instead of reusing a stale version
    public AccountResponse deposit(Long id, DepositRequest request, String idempotencyKey) {
        if (idempotencyKey == null) {
            return AccountResponse.from(depositTransactionExecutor.executeWithoutIdempotency(id, request));
        }

        Deposit existing = depositRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return replay(existing, id, request);
        }

        int failedAttempts = 0;
        while (true) {
            try {
                Account account = depositTransactionExecutor.execute(id, request, idempotencyKey);
                return AccountResponse.from(account);
            } catch (ConcurrencyFailureException e) {
                // optimistic-lock conflict or lock failure (e.g. deadlock victim): the attempt rolled back, retry
                if (++failedAttempts >= MAX_LOCK_ATTEMPTS) {
                    throw e;
                }
            } catch (DataIntegrityViolationException e) {
                // another request raced us with the same idempotency key and inserted first;
                // if no such deposit exists the violation had a different cause - rethrow it
                Deposit winner = depositRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
                return replay(winner, id, request);
            }
        }
    }

    // a key may only be replayed for the exact same request; reuse with a different
    // payload is a client bug and must fail loudly instead of crediting again
    private AccountResponse replay(Deposit existing, Long accountId, DepositRequest request) {
        boolean samePayload = existing.getAccountId().equals(accountId)
                && existing.getAmount().compareTo(request.amount()) == 0;
        if (!samePayload) {
            throw new IdempotencyKeyReuseException(existing.getIdempotencyKey());
        }
        return AccountResponse.from(findAccountOrThrow(accountId));
    }

    private Account findAccountOrThrow(Long id) {
        return accountRepository.findById(id).orElseThrow(() -> new AccountNotFoundException(id));
    }
}
