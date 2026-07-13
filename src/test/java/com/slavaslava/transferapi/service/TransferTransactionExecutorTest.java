package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Transfer;
import com.slavaslava.transferapi.domain.TransferStatus;
import com.slavaslava.transferapi.dto.CreateTransferRequest;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.exception.CurrencyMismatchException;
import com.slavaslava.transferapi.exception.InsufficientFundsException;
import com.slavaslava.transferapi.exception.SameAccountTransferException;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.TransferRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TransferTransactionExecutorTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private TransferRepository transferRepository;

    private TransferTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new TransferTransactionExecutor(accountRepository, transferRepository);
    }

    @Test
    void rejectsTransferToTheSameAccount() {
        CreateTransferRequest request = new CreateTransferRequest(1L, 1L, new BigDecimal("10.00"));

        assertThatThrownBy(() -> executor.execute(request, "key-1"))
                .isInstanceOf(SameAccountTransferException.class);
    }

    @Test
    void throwsWhenFromAccountMissing() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, new BigDecimal("10.00"));

        assertThatThrownBy(() -> executor.execute(request, "key-1"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void throwsWhenToAccountMissing() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account(1L, "100.00", "EUR")));
        when(accountRepository.findById(2L)).thenReturn(Optional.empty());
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, new BigDecimal("10.00"));

        assertThatThrownBy(() -> executor.execute(request, "key-1"))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void rejectsCurrencyMismatch() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account(1L, "100.00", "EUR")));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(account(2L, "0.00", "USD")));
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, new BigDecimal("10.00"));

        assertThatThrownBy(() -> executor.execute(request, "key-1"))
                .isInstanceOf(CurrencyMismatchException.class);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void rejectsInsufficientFunds() {
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account(1L, "5.00", "EUR")));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(account(2L, "0.00", "EUR")));
        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, new BigDecimal("10.00"));

        assertThatThrownBy(() -> executor.execute(request, "key-1"))
                .isInstanceOf(InsufficientFundsException.class);
        verify(transferRepository, never()).save(any());
    }

    @Test
    void movesFundsAndPersistsACompletedTransfer() {
        Account from = account(1L, "100.00", "EUR");
        Account to = account(2L, "20.00", "EUR");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(from));
        when(accountRepository.findById(2L)).thenReturn(Optional.of(to));
        when(transferRepository.save(any(Transfer.class))).thenAnswer(invocation -> invocation.getArgument(0));

        CreateTransferRequest request = new CreateTransferRequest(1L, 2L, new BigDecimal("30.00"));
        Transfer result = executor.execute(request, "key-1");

        assertThat(from.getBalance()).isEqualByComparingTo("70.00");
        assertThat(to.getBalance()).isEqualByComparingTo("50.00");
        assertThat(result.getStatus()).isEqualTo(TransferStatus.COMPLETED);
        assertThat(result.getIdempotencyKey()).isEqualTo("key-1");

        ArgumentCaptor<Transfer> captor = ArgumentCaptor.forClass(Transfer.class);
        verify(transferRepository).save(captor.capture());
        assertThat(captor.getValue().getFromAccountId()).isEqualTo(1L);
        assertThat(captor.getValue().getToAccountId()).isEqualTo(2L);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("30.00");
    }

    private Account account(Long id, String balance, String currency) {
        Account account = new Account("owner-" + id, new BigDecimal(balance), currency);
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
