package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Transfer;
import com.slavaslava.transferapi.domain.TransferStatus;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.exception.CurrencyMismatchException;
import com.slavaslava.transferapi.exception.SameAccountTransferException;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferTransactionExecutor {

    private final AccountRepository accountRepository;
    private final TransferRepository transferRepository;

    public TransferTransactionExecutor(AccountRepository accountRepository, TransferRepository transferRepository) {
        this.accountRepository = accountRepository;
        this.transferRepository = transferRepository;
    }

    // must stay public: Spring's proxy-based @Transactional silently no-ops on non-public methods
    @Transactional
    public Transfer execute(CreateTransferRequest request, String idempotencyKey) {
        if (request.fromAccountId().equals(request.toAccountId())) {
            throw new SameAccountTransferException(request.fromAccountId());
        }

        Account from = accountRepository.findById(request.fromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        Account to = accountRepository.findById(request.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

        if (!from.getCurrency().equals(to.getCurrency())) {
            throw new CurrencyMismatchException(from.getCurrency(), to.getCurrency());
        }

        from.debit(request.amount());
        to.credit(request.amount());

        Transfer transfer = new Transfer(from.getId(), to.getId(), request.amount(), TransferStatus.COMPLETED,
                idempotencyKey);
        return transferRepository.save(transfer);
    }
}
