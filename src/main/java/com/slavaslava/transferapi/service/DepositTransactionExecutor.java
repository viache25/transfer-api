package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Deposit;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.DepositRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DepositTransactionExecutor {

    private final AccountRepository accountRepository;
    private final DepositRepository depositRepository;

    public DepositTransactionExecutor(AccountRepository accountRepository, DepositRepository depositRepository) {
        this.accountRepository = accountRepository;
        this.depositRepository = depositRepository;
    }

    // must stay public: Spring's proxy-based @Transactional silently no-ops on non-public methods
    @Transactional
    public Account execute(Long accountId, DepositRequest request, String idempotencyKey) {
        Account account = findAccountOrThrow(accountId);
        account.credit(request.amount());
        depositRepository.save(new Deposit(accountId, request.amount(), idempotencyKey));
        return account;
    }

    // no idempotency key supplied: credit only, single attempt, no deposits row (nothing to replay)
    @Transactional
    public Account executeWithoutIdempotency(Long accountId, DepositRequest request) {
        Account account = findAccountOrThrow(accountId);
        account.credit(request.amount());
        return account;
    }

    private Account findAccountOrThrow(Long accountId) {
        return accountRepository.findById(accountId).orElseThrow(() -> new AccountNotFoundException(accountId));
    }
}
