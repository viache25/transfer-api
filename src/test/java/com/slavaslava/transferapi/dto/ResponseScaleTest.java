package com.slavaslava.transferapi.dto;

import com.slavaslava.transferapi.domain.Deposit;
import com.slavaslava.transferapi.domain.Transfer;
import com.slavaslava.transferapi.domain.TransferStatus;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;

class ResponseScaleTest {

    @Test
    void accountResponseAlwaysHasTwoDecimals() {
        assertThat(new AccountResponse(1L, "Alice", BigDecimal.ZERO, "EUR").balance().toPlainString())
                .isEqualTo("0.00");
        assertThat(new AccountResponse(1L, "Alice", new BigDecimal("5"), "EUR").balance().toPlainString())
                .isEqualTo("5.00");
    }

    @Test
    void transferResponseAlwaysHasTwoDecimals() {
        TransferResponse response = TransferResponse.from(
                new Transfer(1L, 2L, new BigDecimal("30"), TransferStatus.COMPLETED, "key"));

        assertThat(response.amount().toPlainString()).isEqualTo("30.00");
    }

    @Test
    void nullAmountIsLeftAlone() {
        assertThat(new TransferResponse(1L, 1L, 2L, null, "COMPLETED", "k", null).amount()).isNull();
    }

    @Test
    void createdAtIsTruncatedToMicrosecondsSoItSurvivesTheDatabaseRoundTrip() {
        Transfer transfer = new Transfer(1L, 2L, BigDecimal.ONE, TransferStatus.COMPLETED, "key");
        Deposit deposit = new Deposit(1L, BigDecimal.ONE, "key");

        assertThat(transfer.getCreatedAt()).isEqualTo(transfer.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
        assertThat(deposit.getCreatedAt()).isEqualTo(deposit.getCreatedAt().truncatedTo(ChronoUnit.MICROS));
    }
}
