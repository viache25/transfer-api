package com.slavaslava.transferapi.dto;

import com.slavaslava.transferapi.domain.Account;

import java.math.BigDecimal;

public record AccountResponse(
        Long id,
        String owner,
        BigDecimal balance,
        String currency
) {
    public static AccountResponse from(Account account) {
        return new AccountResponse(account.getId(), account.getOwner(), account.getBalance(), account.getCurrency());
    }
}
