package com.slavaslava.transferapi.domain;

import com.slavaslava.transferapi.exception.InsufficientFundsException;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AccountTest {

    @Test
    void debitReducesBalanceWhenFundsAreSufficient() {
        Account account = new Account("Alice", new BigDecimal("100.00"), "EUR");

        account.debit(new BigDecimal("40.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("60.00");
    }

    @Test
    void debitThrowsAndLeavesBalanceUnchangedWhenFundsAreInsufficient() {
        Account account = new Account("Alice", new BigDecimal("50.00"), "EUR");

        assertThatThrownBy(() -> account.debit(new BigDecimal("50.01")))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(account.getBalance()).isEqualByComparingTo("50.00");
    }

    @Test
    void creditIncreasesBalance() {
        Account account = new Account("Bob", new BigDecimal("10.00"), "EUR");

        account.credit(new BigDecimal("5.00"));

        assertThat(account.getBalance()).isEqualByComparingTo("15.00");
    }
}
