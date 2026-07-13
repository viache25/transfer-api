package com.slavaslava.transferapi.service;

import com.slavaslava.transferapi.domain.Account;
import com.slavaslava.transferapi.dto.AccountResponse;
import com.slavaslava.transferapi.dto.CreateAccountRequest;
import com.slavaslava.transferapi.dto.DepositRequest;
import com.slavaslava.transferapi.exception.AccountNotFoundException;
import com.slavaslava.transferapi.repository.AccountRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AccountServiceTest {

    @Mock
    private AccountRepository accountRepository;

    private AccountService accountService;

    @BeforeEach
    void setUp() {
        accountService = new AccountService(accountRepository);
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
    void depositCreditsExistingAccount() {
        Account account = new Account("Alice", new BigDecimal("100.00"), "EUR");
        when(accountRepository.findById(1L)).thenReturn(Optional.of(account));

        AccountResponse response = accountService.deposit(1L, new DepositRequest(new BigDecimal("25.00")));

        assertThat(response.balance()).isEqualByComparingTo("125.00");
    }

    @Test
    void depositThrowsWhenAccountNotFound() {
        when(accountRepository.findById(1L)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> accountService.deposit(1L, new DepositRequest(new BigDecimal("25.00"))))
                .isInstanceOf(AccountNotFoundException.class);
    }
}
