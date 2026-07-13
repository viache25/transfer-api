package com.slavaslava.transferapi.dto;

import com.slavaslava.transferapi.domain.Transfer;

import java.math.BigDecimal;
import java.time.Instant;

public record TransferResponse(
        Long id,
        Long fromAccountId,
        Long toAccountId,
        BigDecimal amount,
        String status,
        String idempotencyKey,
        Instant createdAt
) {
    public static TransferResponse from(Transfer transfer) {
        return new TransferResponse(
                transfer.getId(),
                transfer.getFromAccountId(),
                transfer.getToAccountId(),
                transfer.getAmount(),
                transfer.getStatus().name(),
                transfer.getIdempotencyKey(),
                transfer.getCreatedAt()
        );
    }
}
