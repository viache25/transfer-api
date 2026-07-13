package com.slavaslava.transferapi.exception;

public class CurrencyMismatchException extends RuntimeException {

    public CurrencyMismatchException(String fromCurrency, String toCurrency) {
        super("Currency mismatch: source account is " + fromCurrency + ", destination account is " + toCurrency);
    }
}
