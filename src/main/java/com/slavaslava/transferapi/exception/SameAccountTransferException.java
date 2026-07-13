package com.slavaslava.transferapi.exception;

public class SameAccountTransferException extends RuntimeException {

    public SameAccountTransferException(Long accountId) {
        super("Cannot transfer to the same account: " + accountId);
    }
}
