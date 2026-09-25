package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.domain.Deposit;
import com.slavaslava.transferapi.dto.AccountResponse;
import com.slavaslava.transferapi.dto.CreateAccountRequest;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.exception.IdempotencyKeyReuseException;
import com.slavaslava.transferapi.repository.AccountRepository;
import com.slavaslava.transferapi.repository.DepositRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    @Mock
    private DepositRepository depositRepository;

    @Mock
    private DepositTransactionExecutor depositTransactionExecutor;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository, depositRepository, depositTransactionExecutor);
    }

    @Test
    void createAccountDefaultsMissingInitialBalanceToZero() {
        when(accountRepository.save(any(Account.class))).thenAnswer(invocation -> invocation.getArgument(0));

        AccountResponse response = accountService.createAccount(new CreateAccountRequest("Alice", null, "EUR"));

        assertThat(response.balance()).isEqualByComparingTo(BigDecimal.ZERO);
        assertThat(response.owner()).isEqualTo("Alice");
        assertThat(response.currency()).isEqualTo("EUR");
    }

    @Test
    void createAccountUsesProvidedInitialBalance() {
        ArgumentCaptor<Account> captor = ArgumentCaptor.forClass(Account.class);
        when(accountRepository.save(captor.capture())).thenAnswer(invocation -> invocation.getArgument(0));

        accountService.createAccount(new CreateAccountRequest("Bob", new BigDecimal("250.00"), "USD"));

        assertThat(captor.getValue().getBalance()).isEqualByComparingTo("250.00");
    }

    @Test
    void getAccountThrowsWhenNotFound() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.getAccount(1L))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void depositWithoutIdempotencyKeyCreditsOnceAndSkipsReplayBookkeeping() {
        Account account = account(1L, "125.00");
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositTransactionExecutor.executeWithoutIdempotency(1L, request)).thenReturn(account);

        AccountResponse response = accountService.deposit(1L, request, null);

        assertThat(response.balance()).isEqualByComparingTo("125.00");
        verify(depositRepository, never()).findByIdempotencyKey(any());
    }

    @Test
    void depositWithoutIdempotencyKeyPropagatesAccountNotFound() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositTransactionExecutor.executeWithoutIdempotency(1L, request))
                .thenThrow(new AccountNotFoundException(1L));

        assertThatThrownBy(() -> accountService.deposit(1L, request, null))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void returnsStoredResultWithoutExecutingWhenIdempotencyKeyAlreadySeen() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(deposit(1L, "25.00")));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account(1L, "125.00")));

        AccountResponse response = accountService.deposit(1L, request, "key-1");

        assertThat(response.balance()).isEqualByComparingTo("125.00");
        verify(depositTransactionExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void rejectsKeyReuseWithDifferentPayload() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.of(deposit(1L, "99.00")));

        assertThatThrownBy(() -> accountService.deposit(1L, request, "key-1"))
                .isInstanceOf(IdempotencyKeyReuseException.class);
        verify(depositTransactionExecutor, never()).execute(any(), any(), any());
    }

    @Test
    void executesOnceWhenIdempotencyKeyIsNew() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(depositTransactionExecutor.execute(1L, request, "key-1")).thenReturn(account(1L, "125.00"));

        AccountResponse response = accountService.deposit(1L, request, "key-1");

        assertThat(response.balance()).isEqualByComparingTo("125.00");
        verify(depositTransactionExecutor, times(1)).execute(1L, request, "key-1");
    }

    @Test
    void retriesOnOptimisticLockFailureAndSucceedsBeforeExhaustingAttempts() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(depositTransactionExecutor.execute(1L, request, "key-1"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, 1L))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, 1L))
                .thenReturn(account(1L, "125.00"));

        AccountResponse response = accountService.deposit(1L, request, "key-1");

        assertThat(response.balance()).isEqualByComparingTo("125.00");
        verify(depositTransactionExecutor, times(3)).execute(1L, request, "key-1");
    }

    @Test
    void givesUpAfterExhaustingOptimisticLockRetries() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositRepository.findByIdempotencyKey("key-1")).thenReturn(Optional.empty());
        when(depositTransactionExecutor.execute(1L, request, "key-1"))
                .thenThrow(new ObjectOptimisticLockingFailureException(Account.class, 1L));

        assertThatThrownBy(() -> accountService.deposit(1L, request, "key-1"))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);
        verify(depositTransactionExecutor, times(3)).execute(1L, request, "key-1");
    }

    @Test
    void returnsRacingResultWhenConcurrentInsertWithSameKeyWinsFirst() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.of(deposit(1L, "25.00")));
        when(depositTransactionExecutor.execute(1L, request, "key-1"))
                .thenThrow(new DataIntegrityViolationException("duplicate idempotency key"));
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account(1L, "125.00")));

        AccountResponse response = accountService.deposit(1L, request, "key-1");

        assertThat(response.balance()).isEqualByComparingTo("125.00");
    }

    @Test
    void rethrowsWhenDataIntegrityViolationIsNotAnIdempotencyKeyRace() {
        DepositRequest request = new DepositRequest(new BigDecimal("25.00"));
        when(depositRepository.findByIdempotencyKey("key-1"))
                .thenReturn(Optional.empty())
                .thenReturn(Optional.empty());
        when(depositTransactionExecutor.execute(1L, request, "key-1"))
                .thenThrow(new DataIntegrityViolationException("some other constraint"));

        assertThatThrownBy(() -> accountService.deposit(1L, request, "key-1"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    private Account account(Long id, String balance) {
        Account account = new Account("owner-" + id, new BigDecimal(balance), "EUR");
        ReflectionTestUtils.setField(account, "id", id);
        return account;
    }

    private Deposit deposit(Long accountId, String amount) {
        return new Deposit(accountId, new BigDecimal(amount), "key-1");
    }
}
