package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Transfer;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.dto.TransferCreationResult;
import com.slavaslava.transferapi.dto.TransferResponse;
import com.slavaslava.transferapi.exception.IdempotencyKeyReuseException;
import com.slavaslava.transferapi.repository.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private static final int MAX_LOCK_ATTEMPTS = 3;

    private final TransferRepository transferRepository;
    private final TransferTransactionExecutor transactionExecutor;
    private final Counter createdCounter;
    private final Counter replayedCounter;
    private final Counter lockRetriesCounter;
    private final Counter keyReuseRejectedCounter;

    public TransferService(TransferRepository transferRepository, TransferTransactionExecutor transactionExecutor,
                            MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.transactionExecutor = transactionExecutor;
        // ".created" is a reserved OpenMetrics suffix that Micrometer's Prometheus naming convention
        // strips (it's meant for internal series-creation timestamps), which would silently collapse
        // this into the meaningless "transfers_total" on /actuator/prometheus; ".count" avoids that
        this.createdCounter = meterRegistry.counter("transfers.created.count");
        this.replayedCounter = meterRegistry.counter("transfers.replayed");
        this.lockRetriesCounter = meterRegistry.counter("transfers.lock_retries");
        this.keyReuseRejectedCounter = meterRegistry.counter("transfers.key_reuse_rejected");
    }

    // deliberately not @Transactional: each retry attempt must run in its own transaction
    // so a failed optimistic lock reloads fresh account versions instead of reusing stale ones
    public TransferCreationResult createTransfer(CreateTransferRequest request, String idempotencyKey) {
        Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return new TransferCreationResult(replay(existing, request), true);
        }

        int failedAttempts = 0;
        while (true) {
            try {
                Transfer created = transactionExecutor.execute(request, idempotencyKey);
                createdCounter.increment();
                return new TransferCreationResult(TransferResponse.from(created), false);
            } catch (OptimisticLockingFailureException e) {
                if (++failedAttempts >= MAX_LOCK_ATTEMPTS) {
                    throw e;
                }
                lockRetriesCounter.increment();
            } catch (DataIntegrityViolationException e) {
                // another request raced us with the same idempotency key and inserted first;
                // if no such transfer exists the violation had a different cause — rethrow it
                Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
                return new TransferCreationResult(replay(winner, request), true);
            }
        }
    }

    @Transactional(readOnly = true)
    public Page<TransferResponse> listTransfers(Long accountId, Pageable pageable) {
        return transferRepository.findByFromAccountIdOrToAccountId(accountId, accountId, pageable)
                .map(TransferResponse::from);
    }

    // a key may only be replayed for the exact same request; reuse with a different
    // payload is a client bug and must fail loudly instead of returning someone else's result
    private TransferResponse replay(Transfer existing, CreateTransferRequest request) {
        boolean samePayload = existing.getFromAccountId().equals(request.fromAccountId())
                && existing.getToAccountId().equals(request.toAccountId())
                && existing.getAmount().compareTo(request.amount()) == 0;
        if (!samePayload) {
            keyReuseRejectedCounter.increment();
            throw new IdempotencyKeyReuseException(existing.getIdempotencyKey());
        }
        replayedCounter.increment();
        return TransferResponse.from(existing);
    }
}
