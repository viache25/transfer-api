package com.slavaslava.transferapi.web.ui;

import com.slavaslava.transferapi.dto.CreateTransferRequest;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;
import java.util.UUID;

/** The transfer form of the terminal page; the same rules as {@code POST /transfers} plus the key. */
public record TransferForm(
        @NotNull Long fromAccountId,
        @NotNull Long toAccountId,
        @NotNull @Positive @Digits(integer = 17, fraction = 2) BigDecimal amount,
        @NotBlank @Size(max = 255) String idempotencyKey
) {
    /** An empty form with a fresh key: like a real terminal, the page generates one key per transfer. */
    static TransferForm withNewKey() {
        return new TransferForm(null, null, null, UUID.randomUUID().toString());
    }

    CreateTransferRequest toRequest() {
        return new CreateTransferRequest(fromAccountId, toAccountId, amount);
    }
}
