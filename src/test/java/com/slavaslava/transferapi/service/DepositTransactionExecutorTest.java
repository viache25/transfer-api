package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Deposit;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.DepositRepository;
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
class DepositTransactionExecutorTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DepositRepository depositRepository;

    private DepositTransactionExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new DepositTransactionExecutor(accountRepository, depositRepository);
    }

    @Test
    void executeThrowsWhenAccountMissing() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.execute(1L, new DepositRequest(new BigDecimal("10.00")), "key-1"))
                .isInstanceOf(AccountNotFoundException.class);
        verify(depositRepository, never()).save(any());
    }

    @Test
    void executeCreditsAccountAndPersistsADepositRecord() {
        Account account = account(1L, "100.00");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        Account result = executor.execute(1L, new DepositRequest(new BigDecimal("25.00")), "key-1");

        assertThat(result.getBalance()).isEqualByComparingTo("125.00");

        ArgumentCaptor<Deposit> captor = ArgumentCaptor.forClass(Deposit.class);
        verify(depositRepository).save(captor.capture());
        assertThat(captor.getValue().getAccountId()).isEqualTo(1L);
        assertThat(captor.getValue().getAmount()).isEqualByComparingTo("25.00");
        assertThat(captor.getValue().getIdempotencyKey()).isEqualTo("key-1");
    }

    @Test
    void executeWithoutIdempotencyThrowsWhenAccountMissing() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> executor.executeWithoutIdempotency(1L, new DepositRequest(new BigDecimal("10.00"))))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void executeWithoutIdempotencyCreditsAccountAndSkipsDepositRecord() {
        Account account = account(1L, "100.00");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        Account result = executor.executeWithoutIdempotency(1L, new DepositRequest(new BigDecimal("25.00")));

        assertThat(result.getBalance()).isEqualByComparingTo("125.00");
        verify(depositRepository, never()).save(any());
    }

    private Account account(Long id, String balance) {
        Account account = new Account("owner-" + id, new BigDecimal(balance), "EUR");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }
}
