package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Transfer;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.dto.TransferCreationResult;
import com.slavaslava.transferapi.dto.TransferResponse;
import com.slavaslava.transferapi.exception.IdempotencyKeyReuseException;
import com.slavaslava.transferapi.repository.TransferRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private static final int MAX_LOCK_ATTEMPTS = 3;

    private final TransferRepository transferRepository;
    private final TransferTransactionExecutor transactionExecutor;
    private final TransferReplayCache replayCache;
    private final Counter createdCounter;
    private final Counter replayedCounter;
    private final Counter lockRetriesCounter;
    private final Counter keyReuseRejectedCounter;

    public TransferService(TransferRepository transferRepository, TransferTransactionExecutor transactionExecutor,
                            TransferReplayCache replayCache, MeterRegistry meterRegistry) {
        this.transferRepository = transferRepository;
        this.transactionExecutor = transactionExecutor;
        this.replayCache = replayCache;
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
        TransferResponse cached = replayCache.find(idempotencyKey).orElse(null);
        if (cached != null) {
            return new TransferCreationResult(replay(cached, request), true);
        }
        Transfer existing = transferRepository.findByIdempotencyKey(idempotencyKey).orElse(null);
        if (existing != null) {
            return new TransferCreationResult(replayAndCache(existing, request), true);
        }

        int failedAttempts = 0;
        while (true) {
            try {
                Transfer created = transactionExecutor.execute(request, idempotencyKey);
                createdCounter.increment();
                TransferResponse response = TransferResponse.from(created);
                // the executor's transaction has committed by now, so the cache never holds an uncommitted transfer
                replayCache.put(idempotencyKey, response);
                return new TransferCreationResult(response, false);
            } catch (ConcurrencyFailureException e) {
                // an optimistic-lock conflict (@Version changed) or a lock failure such as a deadlock
                // victim: either way the attempt's transaction rolled back completely, so retry it
                if (++failedAttempts >= MAX_LOCK_ATTEMPTS) {
                    throw e;
                }
                lockRetriesCounter.increment();
            } catch (DataIntegrityViolationException e) {
                // another request raced us with the same idempotency key and inserted first;
                // if no such transfer exists the violation had a different cause — rethrow it
                Transfer winner = transferRepository.findByIdempotencyKey(idempotencyKey).orElseThrow(() -> e);
                return new TransferCreationResult(replayAndCache(winner, request), true);
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
    private TransferResponse replayAndCache(Transfer existing, CreateTransferRequest request) {
        TransferResponse response = replay(TransferResponse.from(existing), request);
        replayCache.put(existing.getIdempotencyKey(), response);
        return response;
    }

    private TransferResponse replay(TransferResponse existing, CreateTransferRequest request) {
        boolean samePayload = existing.fromAccountId().equals(request.fromAccountId())
                && existing.toAccountId().equals(request.toAccountId())
                && existing.amount().compareTo(request.amount()) == 0;
        if (!samePayload) {
            keyReuseRejectedCounter.increment();
            throw new IdempotencyKeyReuseException(existing.idempotencyKey());
        }
        replayedCounter.increment();
        return existing;
    }
}
